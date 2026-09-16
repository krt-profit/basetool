#!/usr/bin/env python3
"""Profit Basetool - external conformance suite.

Phase 0 of ``docs/PODMAN_MIGRATION_PLAN.md``: the regression net the container-runtime
migration is gated on. It asserts invariants against a **running host** rather than against
configuration files, because the whole class of defect this project keeps hitting is a config
that is correct on disk and not in force in the process.

It has to go green against the current Docker stack **first**. A suite that has never passed
proves nothing when it passes later, and a check that has never been red is decoration -
``check-conformance.test.sh`` is what keeps that honest by breaking every check on purpose.

What this does NOT cover, deliberately
--------------------------------------
The **deny families** - the Keycloak admin lockdown, the ``/actuator`` deny and the API vhost
allow-list table - are already asserted from a genuinely external vantage point by
``.github/workflows/edge-deny-probe.yml``, on a daily schedule. Re-implementing them here would
create a second copy that can drift from the first. This suite covers what that workflow cannot:
the client address the edge actually sees, the certificate handover, and the host-side signals
(container metrics, log streams) that no external probe can reach.

Read-only
---------
Every check is a GET or a host-side read. Nothing mutates. The one exception is opt-in and
off by default: ``--include-load`` sends a burst to trip the rate limiter, which is a load
action against production and needs a deliberate flag.

The credential rule
-------------------
The Prometheus web password is interpolated into a ``curl`` config on **stdin, inside the remote
shell**, and never printed, never placed on a command line where ``ps`` could read it, and never
returned to the caller. If a check cannot be made without reading a credential out, it is not
written.

Usage
-----
::

    # external checks only
    python scripts/check-conformance.py

    # plus the host-side checks (read-only SSH)
    python scripts/check-conformance.py --ssh root@198.51.100.10

    # include the rate-limiter burst (load against the target - deliberate)
    python scripts/check-conformance.py --ssh root@198.51.100.10 --include-load

    # machine-readable, and the check inventory
    python scripts/check-conformance.py --json
    python scripts/check-conformance.py --list

Exit codes: ``0`` all selected checks passed, ``1`` at least one failed, ``2`` bad invocation.
Skipped checks never fail the run, and the report says why each was skipped.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import datetime as dt
import json
import os
import re
import shlex
import shutil
import socket
import ssl
import subprocess
import sys
import uuid
from typing import Callable, Iterable, Sequence

# --------------------------------------------------------------------------------------------
# Defaults. These four names are public: they are in .env.example and in edge-deny-probe.yml.
# Nothing secret is defaulted here, and nothing is read out of the host .env.
# --------------------------------------------------------------------------------------------
DEFAULT_HOSTS = {
    "frontend": "profit-base.online",
    "ingest": "ingest.profit-base.online",
    "grafana": "grafana.profit-base.online",
    "api": "api.profit-base.online",
}

#: lego renews at 30 days (``--renew-days 30``); alert below 21 so a stuck renewal is visible
#: while there is still a fortnight to fix it rather than on the morning it expires.
CERT_MIN_DAYS = 21

#: The prod-profile containers the app deploy owns. ``npm`` is the retired proxy and lives in the
#: ``rollback`` profile, so it is deliberately absent.
EXPECTED_APP_CONTAINERS = (
    "edge",
    "acme",
    "keycloak",
    "backend",
    "frontend",
    "ingest",
    "db-backend",
    "db-keycloak",
    "redis",
)

#: The cAdvisor series the alert rules in monitoring/prometheus/alerts/ actually read. This list
#: is the reason PODMAN_MIGRATION_PLAN.md §3.6 is a rebuild rather than a rename: a runtime swap
#: has to reproduce these, or the alerts that read them stop having data and stop firing - which
#: looks exactly like a healthy system.
REQUIRED_CONTAINER_SERIES = (
    "container_memory_working_set_bytes",
    "container_spec_memory_limit_bytes",
    "container_threads",
    "container_threads_max",
    "container_oom_events_total",
    "container_cpu_usage_seconds_total",
)

#: Private ranges an edge must never report as a client address. If our own probe comes back
#: wearing one of these, a userland port forwarder is rewriting the source - which is the
#: 2026-07-20 outage, and the failure mode rootless Podman's ``rootlessport`` would reintroduce.
PRIVATE_PREFIXES = ("10.", "172.16.", "172.17.", "172.18.", "172.19.", "172.2", "172.30.",
                    "172.31.", "192.168.", "127.", "fd", "fe80:", "::1")

PROBE_HEADER = "X-Basetool-Conformance"

# --------------------------------------------------------------------------------------------
# Test seams. Three environment variables let check-conformance.test.sh point the external
# checks at a local fixture instead of the internet. They are unset in every real invocation,
# and the suite says so in its report when they are not - so a run that silently probed a
# fixture cannot be mistaken for a run that probed production. Same principle as deploy.test.sh
# stubbing docker and cosign on PATH.
# --------------------------------------------------------------------------------------------
HTTPS_PORT = int(os.environ.get("BASETOOL_CONFORMANCE_HTTPS_PORT", "443"))
HTTP_PORT = int(os.environ.get("BASETOOL_CONFORMANCE_HTTP_PORT", "80"))
CA_BUNDLE = os.environ.get("BASETOOL_CONFORMANCE_CA_BUNDLE") or None


def _ssl_context() -> ssl.SSLContext:
    """Build the TLS context the external checks verify certificates with.

    Returns:
        A default-verifying context, loading ``BASETOOL_CONFORMANCE_CA_BUNDLE`` as an extra
        anchor when that test seam is set. Verification is never switched off: a check that
        accepted any certificate could not report a certificate failure, which is half of what
        this suite is for.
    """
    ctx = ssl.create_default_context()
    if CA_BUNDLE:
        ctx.load_verify_locations(cafile=CA_BUNDLE)
    return ctx


# ============================================================================================
# Result plumbing
# ============================================================================================
@dataclasses.dataclass
class Result:
    """The outcome of one check.

    Attributes:
        check: the check's stable id, as ``--list`` prints it.
        requirement: the requirement or decision the check exists to defend.
        status: ``pass``, ``fail`` or ``skip``.
        detail: one line a human can act on. On a failure it says what was observed, not
            merely that something was wrong.
    """

    check: str
    requirement: str
    status: str
    detail: str

    @property
    def failed(self) -> bool:
        """Whether this result should fail the run.

        Returns:
            ``True`` only for ``fail``. A skip is not a failure - an absent SSH target or a
            runner with no IPv6 must not turn into a red that hides a real one.
        """
        return self.status == "fail"


class Skip(Exception):
    """Raised by a check that cannot run here, carrying the reason for the report."""


class CheckFailed(Exception):
    """Raised by a check that ran and found the invariant broken."""


# ============================================================================================
# Host access
# ============================================================================================
class HostRunner:
    """Runs read-only commands on the deployment host.

    Two implementations in one class so the suite is testable without a host: a real one that
    shells out to ``ssh``, and a stub that executes a local script instead. The stub is what
    ``check-conformance.test.sh`` drives, and it is the only way every host-side check can be
    shown red without breaking production to do it.

    Args:
        ssh_target: ``user@host`` for the real runner, or ``None``.
        stub: a command line, split with :func:`shlex.split`, that receives the host command
            as its last argument and prints what the host would have printed. Takes precedence
            over ``ssh_target``.
        timeout: seconds before a command is abandoned.
    """

    def __init__(self, ssh_target: str | None, stub: str | None, timeout: int = 45) -> None:
        self.ssh_target = ssh_target
        self.stub = stub
        self.timeout = timeout

    @property
    def available(self) -> bool:
        """Whether host-side checks can run at all.

        Returns:
            ``True`` when either an SSH target or a stub was configured.
        """
        return bool(self.stub or self.ssh_target)

    def run(self, command: str) -> str:
        """Execute one read-only command on the host and return its stdout.

        Args:
            command: a POSIX ``sh`` command line. It is passed through single quotes to the
                remote shell, so it must not contain a single quote of its own.

        Returns:
            The command's stdout, with trailing whitespace stripped.

        Raises:
            Skip: when no host access is configured.
            CheckFailed: when the command cannot be executed or exits non-zero.
        """
        if not self.available:
            raise Skip("no --ssh target and no --host-stub")
        if "'" in command:
            raise CheckFailed(f"internal: host command must not contain a single quote: {command}")

        if self.stub:
            # A command LINE, not a path: the stub has to be launchable on every platform the
            # self-test runs on, and Windows cannot exec a .sh directly. Splitting it means the
            # caller writes `--host-stub "bash /path/to/stub.sh"` and the seam stays general.
            argv = shlex.split(self.stub) + [command]
        else:
            ssh = shutil.which("ssh")
            if not ssh:
                raise Skip("ssh not found on PATH")
            argv = [ssh, "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
                    self.ssh_target or "", f"sh -c '{command}'"]

        try:
            proc = subprocess.run(argv, capture_output=True, text=True, timeout=self.timeout)
        except subprocess.TimeoutExpired as exc:
            raise CheckFailed(f"host command timed out after {self.timeout}s: {command}") from exc
        if proc.returncode != 0:
            err = (proc.stderr or proc.stdout).strip().splitlines()
            raise CheckFailed(f"host command failed ({proc.returncode}): {err[-1] if err else command}")
        return proc.stdout.strip()

    def ssh_client_address(self) -> str | None:
        """Report the address this machine presents to the host over SSH.

        This is how ``client-address-visible`` learns its own public address without asking a
        third-party echo service: the host already knows, because we are logged into it.

        Returns:
            The client address from ``SSH_CLIENT``/``SSH_CONNECTION``, or ``None`` when it is
            unavailable - which is normal under the stub and inside a ``sudo`` session.
        """
        try:
            out = self.run("echo ${SSH_CONNECTION:-${SSH_CLIENT:-}}")
        except (Skip, CheckFailed):
            return None
        return out.split()[0] if out.split() else None


# ============================================================================================
# HTTP / TLS helpers
# ============================================================================================
def _https_get(host: str, path: str = "/", family: int = 0, timeout: int = 20,
               headers: dict[str, str] | None = None) -> tuple[int, dict[str, str]]:
    """Issue one HTTPS GET and return the status and response headers.

    Deliberately hand-rolled on ``http.client`` rather than ``urllib``: the suite has to pin the
    address *family* (an IPv4 and an IPv6 request to the same name are two different assertions,
    per ADR-0112) and must not follow redirects, because a redirect is often the finding.

    Args:
        host: the vhost name, used for SNI, ``Host`` and certificate validation.
        path: the request path.
        family: ``socket.AF_INET``, ``socket.AF_INET6``, or ``0`` for whatever resolves first.
        timeout: socket timeout in seconds.
        headers: extra request headers.

    Returns:
        ``(status_code, headers)``.

    Raises:
        OSError: on a connection or TLS failure, so the caller can report it verbatim.
    """
    import http.client

    ctx = _ssl_context()
    sock = _connect(host, HTTPS_PORT, family, timeout)
    conn = http.client.HTTPSConnection(host, HTTPS_PORT, timeout=timeout, context=ctx)
    conn.sock = ctx.wrap_socket(sock, server_hostname=host)
    try:
        conn.request("GET", path, headers={"Host": host, **(headers or {})})
        resp = conn.getresponse()
        resp.read()
        return resp.status, {k.lower(): v for k, v in resp.getheaders()}
    finally:
        conn.close()


def _connect(host: str, port: int, family: int, timeout: int) -> socket.socket:
    """Open a TCP connection to ``host`` in the requested address family.

    Args:
        host: hostname to resolve.
        port: destination port.
        family: ``socket.AF_INET``, ``socket.AF_INET6`` or ``0`` for either.
        timeout: connect timeout in seconds.

    Returns:
        A connected socket.

    Raises:
        OSError: when the name does not resolve in that family, or nothing answers.
    """
    infos = socket.getaddrinfo(host, port, family, socket.SOCK_STREAM)
    if not infos:
        raise OSError(f"{host}:{port} does not resolve in the requested address family")
    last: Exception | None = None
    for af, socktype, proto, _canon, addr in infos:
        try:
            sock = socket.socket(af, socktype, proto)
            sock.settimeout(timeout)
            sock.connect(addr)
            return sock
        except OSError as exc:  # try the next record rather than failing on the first
            last = exc
    raise OSError(f"{host}:{port} unreachable: {last}")


def _peer_certificate(host: str, family: int = 0, timeout: int = 20) -> dict:
    """Fetch and parse the leaf certificate a vhost serves.

    Args:
        host: the vhost name, sent as SNI.
        family: address family to pin, or ``0``.
        timeout: socket timeout in seconds.

    Returns:
        A dict with ``not_after`` (``datetime``), ``sans`` (tuple of DNS names) and
        ``fingerprint`` (SHA-256 hex of the DER, which is how two vhosts are compared for
        being served the *same* certificate).

    Raises:
        OSError: on a connection or TLS failure.
    """
    import hashlib

    ctx = _ssl_context()
    sock = _connect(host, HTTPS_PORT, family, timeout)
    with ctx.wrap_socket(sock, server_hostname=host) as tls:
        cert = tls.getpeercert()
        der = tls.getpeercert(binary_form=True) or b""
    not_after = dt.datetime.strptime(cert["notAfter"], "%b %d %H:%M:%S %Y %Z").replace(
        tzinfo=dt.timezone.utc)
    sans = tuple(v for k, v in cert.get("subjectAltName", ()) if k == "DNS")
    return {"not_after": not_after, "sans": sans,
            "fingerprint": hashlib.sha256(der).hexdigest()}


def _is_private(address: str) -> bool:
    """Whether an address is one a first-hop edge must never report as a client.

    Args:
        address: an IPv4 or IPv6 address in text form.

    Returns:
        ``True`` for loopback, RFC 1918, RFC 4193 and link-local addresses.
    """
    a = address.strip().lower()
    return any(a.startswith(p) for p in PRIVATE_PREFIXES)


# ============================================================================================
# Context
# ============================================================================================
@dataclasses.dataclass
class Context:
    """Everything a check needs to run.

    Attributes:
        hosts: role -> vhost name, e.g. ``{"frontend": "profit-base.online"}``.
        runner: host access, possibly unavailable.
        include_load: whether the opt-in load check may run.
        log_window: how far back host-side log reads look, in minutes.
    """

    hosts: dict[str, str]
    runner: HostRunner
    include_load: bool
    log_window: int = 10


# ============================================================================================
# Checks - external
# ============================================================================================
def check_vhost_reachable(ctx: Context) -> str:
    """Every configured vhost answers over HTTPS.

    The weakest check in the suite and the one whose failure explains every other failure, so it
    runs first and reports each host separately rather than stopping at the first.

    Args:
        ctx: the run context.

    Returns:
        A one-line summary naming each host and the status it returned.

    Raises:
        CheckFailed: when any host does not answer.
    """
    seen, broken = [], []
    for role, host in sorted(ctx.hosts.items()):
        try:
            status, _ = _https_get(host, "/")
            seen.append(f"{host}={status}")
            if status >= 500:
                broken.append(f"{host} answered {status} - the edge is up and its upstream is not")
        except OSError as exc:
            broken.append(f"{host}: {exc}")
    if broken:
        raise CheckFailed("; ".join(broken))
    return ", ".join(seen)


def check_certificate_valid(ctx: Context) -> str:
    """Each vhost serves a certificate that covers it and is not near expiry.

    Two failures in one check on purpose: a certificate that does not cover the name it is
    served for, and one that is about to expire, are both "the handover stopped working" and
    both surface here rather than as a browser error.

    Args:
        ctx: the run context.

    Returns:
        A summary naming the soonest expiry in days.

    Raises:
        CheckFailed: on a SAN mismatch or fewer than ``CERT_MIN_DAYS`` days remaining.
    """
    now = dt.datetime.now(dt.timezone.utc)
    problems, margins = [], []
    for role, host in sorted(ctx.hosts.items()):
        try:
            cert = _peer_certificate(host)
        except OSError as exc:
            raise CheckFailed(f"{host}: {exc}") from exc
        if host not in cert["sans"]:
            problems.append(f"{host} not in SAN list {cert['sans']}")
        days = (cert["not_after"] - now).days
        margins.append((days, host))
        if days < CERT_MIN_DAYS:
            problems.append(f"{host} expires in {days}d (floor {CERT_MIN_DAYS}d)")
    if problems:
        raise CheckFailed("; ".join(problems))
    days, host = min(margins)
    return f"all {len(ctx.hosts)} vhosts covered; soonest expiry {days}d ({host})"


def check_certificate_shared(ctx: Context) -> str:
    """All vhosts are served the same multi-SAN certificate.

    ``acme`` obtains ONE certificate whose first ``-d`` names it and whose SAN list carries the
    rest, then publishes that same pair under every host directory. If two vhosts serve
    different leaves, the publish loop reached one and not the other - which is exactly the
    defect REQ-OPS-026 exists for, and it is invisible until the unreached one expires.

    Args:
        ctx: the run context.

    Returns:
        The shared fingerprint, abbreviated.

    Raises:
        CheckFailed: when more than one distinct leaf is served.
    """
    prints: dict[str, list[str]] = {}
    for role, host in sorted(ctx.hosts.items()):
        try:
            fp = _peer_certificate(host)["fingerprint"]
        except OSError as exc:
            raise CheckFailed(f"{host}: {exc}") from exc
        prints.setdefault(fp, []).append(host)
    if len(prints) != 1:
        groups = " | ".join(f"{fp[:12]}: {', '.join(hosts)}" for fp, hosts in prints.items())
        raise CheckFailed(f"{len(prints)} distinct leaf certificates served - {groups}")
    fp = next(iter(prints))
    return f"one leaf across all vhosts (sha256 {fp[:16]}...)"


def check_http_redirects(ctx: Context) -> str:
    """Port 80 redirects to HTTPS instead of serving anything.

    Args:
        ctx: the run context.

    Returns:
        A summary of the status each host returned on port 80.

    Raises:
        CheckFailed: when a host answers on 80 with something other than a redirect.
    """
    import http.client

    bad = []
    for role, host in sorted(ctx.hosts.items()):
        try:
            conn = http.client.HTTPConnection(host, HTTP_PORT, timeout=20)
            conn.request("GET", "/", headers={"Host": host})
            status = conn.getresponse().status
            conn.close()
        except OSError as exc:
            raise CheckFailed(f"{host}: {exc}") from exc
        if status not in (301, 302, 307, 308):
            bad.append(f"{host}={status}")
    if bad:
        raise CheckFailed(f"port 80 did not redirect: {', '.join(bad)}")
    return f"all {len(ctx.hosts)} vhosts redirect :80 to HTTPS"


def check_ipv6_reachable(ctx: Context) -> str:
    """Every vhost has an AAAA record and answers over IPv6.

    ADR-0112 is why this is an assertion and not a nicety: the dual-stack ingress bridge exists
    so an IPv6 client's own address reaches nginx instead of being relayed through the bridge
    gateway. A vhost that quietly loses its AAAA takes that property with it, and the symptom is
    the 2026-07-20 outage rather than an error.

    The check separates two things a naive probe conflates. **A missing AAAA record, or a
    refused connection, is the target's problem** and fails. **No usable IPv6 route from this
    machine** is the runner's problem and skips - otherwise the suite would report a red about
    the deployment every time it ran from a v4-only network.

    Args:
        ctx: the run context.

    Returns:
        A summary naming each host's IPv6 status.

    Raises:
        Skip: when this machine has no IPv6 support or no route.
        CheckFailed: when a host has no AAAA record, or has one and does not answer on it.
    """
    if not socket.has_ipv6:
        raise Skip("this machine has no IPv6 support")

    missing_aaaa, unreachable, seen = [], [], []
    for role, host in sorted(ctx.hosts.items()):
        try:
            socket.getaddrinfo(host, HTTPS_PORT, socket.AF_INET6, socket.SOCK_STREAM)
        except OSError:
            missing_aaaa.append(host)
            continue
        try:
            status, _ = _https_get(host, "/", family=socket.AF_INET6)
            seen.append(f"{host}={status}")
        except OSError as exc:
            # ENETUNREACH / EAFNOSUPPORT mean this machine cannot route v6 at all. That is not a
            # finding about the deployment, and reporting it as one would train the reader to
            # ignore this check.
            if getattr(exc, "errno", None) in (
                    getattr(__import__("errno"), "ENETUNREACH", -1),
                    getattr(__import__("errno"), "EAFNOSUPPORT", -2)):
                raise Skip(f"no usable IPv6 route from this machine ({exc})") from exc
            unreachable.append(f"{host}: {exc}")

    problems = []
    if missing_aaaa:
        problems.append(f"no AAAA record: {', '.join(missing_aaaa)}")
    if unreachable:
        problems.append("; ".join(unreachable))
    if problems:
        raise CheckFailed(" | ".join(problems))
    return ", ".join(seen)


def check_scrape_targets_up(ctx: Context) -> str:
    """Every application scrape target reports ``up``.

    This is what "the health endpoints" means in a deployment whose edge health endpoint is
    deliberately loopback-only: each module serves Prometheus metrics on its own management
    port, and ``up`` is Prometheus's verdict on whether that endpoint answered.

    It is a different signal from :func:`check_containers_running`, and the difference is the
    point. A container can be ``Up`` while its actuator is unreachable or failing - the deploy
    health gate has been fooled by exactly that shape before - so the container state and the
    scrape state are asserted separately rather than one standing in for the other.

    Args:
        ctx: the run context.

    Returns:
        A summary naming how many application targets were up.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when an expected target is down or absent entirely.
    """
    wanted = ("basetool-backend", "basetool-frontend", "basetool-ingest", "keycloak")
    # `up` is queried bare and filtered here rather than with a label matcher, because a PromQL
    # matcher needs a quote character and every quote style collides with one of the three
    # shells this command passes through.
    payload = _promql(ctx, "up")
    state: dict[str, float] = {}
    for sample in payload.get("data", {}).get("result", []):
        job = sample.get("metric", {}).get("job")
        if job in wanted:
            try:
                state[job] = float(sample["value"][1])
            except (KeyError, IndexError, ValueError):
                state[job] = 0.0

    problems = [f"{job} absent from Prometheus" for job in wanted if job not in state]
    problems += [f"{job} is up=0" for job, v in sorted(state.items()) if v < 1]
    if problems:
        raise CheckFailed("; ".join(problems))
    return f"all {len(wanted)} application scrape targets up"


# ============================================================================================
# Checks - host-side
# ============================================================================================
def check_client_address_visible(ctx: Context) -> str:
    """The edge logs the CLIENT's address, not a proxy's.

    **This is the check the whole suite exists for.** Six things in the edge configuration read
    ``$remote_addr``: the per-IP limiter (REQ-SEC-023), the ADR-0112 IPv6 ``/64`` bucket key, the
    Keycloak admin allow-list, the default-server allow-list, the API allow-list, and the access
    log that is the only record of a refused request's origin. All six are correct only while the
    edge's TCP peer is the real client.

    A userland port forwarder in front of the edge collapses every client onto one address. Under
    Docker that happened once, to IPv6 clients only, and cost the 2026-07-20 outage; under
    rootless Podman's ``rootlessport`` it would happen to every client - which is why
    ``PODMAN_MIGRATION_PLAN.md`` §3.1 rejects that configuration.

    The check issues a marked request and then reads the edge's log for it, and asserts two
    independent things:

    1. the address the edge logged for **our** request is public, not a bridge or loopback
       address, and equals the address the host sees our SSH session coming from when the two
       can be compared;
    2. the edge has seen **more than one** distinct client address recently - a collapsed edge
       sees exactly one, forever.

    Args:
        ctx: the run context.

    Returns:
        A summary naming the observed address and the distinct-address count.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when the logged address is private, or only one distinct address appears.
    """
    marker = f"conformance-{uuid.uuid4().hex[:12]}"
    frontend = ctx.hosts.get("frontend") or next(iter(sorted(ctx.hosts.values())))
    try:
        _https_get(frontend, "/healthz", headers={PROBE_HEADER: marker, "User-Agent": marker})
    except OSError as exc:
        raise CheckFailed(f"could not issue the marked probe to {frontend}: {exc}") from exc

    # docker logs, never tail: /var/log/nginx/access.log inside the container is a symlink to
    # /dev/stdout, so tail/grep/wc block on a pipe that never ends. Always bounded by --since.
    logs = ctx.runner.run(
        f"docker logs edge --since {ctx.log_window}m 2>&1 | grep -F {marker} | head -5")
    if not logs.strip():
        raise CheckFailed(
            f"the edge logged no line carrying our probe marker within {ctx.log_window}m - "
            "either the request did not reach this edge, or the access log format dropped it")

    first_field = logs.splitlines()[0].split()[0]
    if _is_private(first_field):
        raise CheckFailed(
            f"the edge logged our request from {first_field}, a private/bridge address - a "
            "userland forwarder is rewriting the source, so the per-IP limiter and every "
            "$remote_addr allow-list are keyed on one bucket (see PODMAN_MIGRATION_PLAN.md §3.1)")

    ssh_addr = ctx.runner.ssh_client_address()
    matched = ""
    if ssh_addr and ((":" in ssh_addr) == (":" in first_field)):
        if ssh_addr != first_field:
            raise CheckFailed(
                f"the edge logged {first_field} but this machine reaches the host from "
                f"{ssh_addr} - the address is being rewritten in flight")
        matched = ", and it matches our SSH source"

    distinct = ctx.runner.run(
        "docker logs edge --since 60m 2>&1 | cut -d\" \" -f1 | sort -u | wc -l")
    try:
        count = int(distinct.split()[-1])
    except (ValueError, IndexError) as exc:
        raise CheckFailed(f"could not count distinct client addresses: {distinct!r}") from exc
    if count < 2:
        raise CheckFailed(
            f"the edge saw {count} distinct client address in the last hour - an edge behind a "
            "userland forwarder sees exactly one, and the per-IP limiter then applies to "
            "everybody at once")
    return f"edge logged our probe from {first_field}{matched}; {count} distinct clients in 60m"


def check_containers_running(ctx: Context) -> str:
    """Every prod-profile container is up, and none is unhealthy.

    Args:
        ctx: the run context.

    Returns:
        A summary naming how many containers were found up.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when a container is missing, exited, restarting or unhealthy.
    """
    out = ctx.runner.run('docker ps -a --format "{{.Names}}|{{.Status}}"')
    status_by_name = {}
    for line in out.splitlines():
        if "|" in line:
            name, status = line.split("|", 1)
            status_by_name[name.strip()] = status.strip()

    problems = []
    for name in EXPECTED_APP_CONTAINERS:
        status = status_by_name.get(name)
        if status is None:
            problems.append(f"{name} is absent")
        elif not status.startswith("Up"):
            problems.append(f"{name} is {status!r}")
        elif "unhealthy" in status:
            problems.append(f"{name} is {status!r}")
    if problems:
        raise CheckFailed("; ".join(problems))
    return f"all {len(EXPECTED_APP_CONTAINERS)} prod containers up and healthy"


def _promql(ctx: Context, query: str) -> dict:
    """Run one instant PromQL query on the host and return the parsed response.

    Prometheus is not published on the host: it lives on ``net-monitoring-core`` and its web
    server is basic-auth protected over **plain http** - ``https://`` fails with curl exit 35 and
    prints nothing under ``-s``, which reads exactly like a quoting failure and is not one.

    The password is fed to curl through a config on **stdin**, so it never appears on a command
    line where ``ps`` could read it and never reaches this process.

    Args:
        ctx: the run context.
        query: the PromQL expression. Must contain no single quote.

    Returns:
        The decoded ``/api/v1/query`` response.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when Prometheus cannot be reached or does not answer ``status: success``.
    """
    secret = "/var/iri/monitoring/secrets/prometheus_web_password"
    if "'" in query:
        raise CheckFailed(f"internal: PromQL must not contain a single quote: {query}")
    cmd = (
        'addr=$(docker inspect prometheus '
        '--format "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}" '
        '| cut -d" " -f1); '
        '[ -n "$addr" ] || { echo NO_PROMETHEUS_ADDRESS >&2; exit 1; }; '
        f'printf "user = \\"grafana:%s\\"\\n" "$(cat {secret})" | '
        'curl -sS -K - -G "http://$addr:9090/api/v1/query" '
        f'--data-urlencode "query={query}"'
    )
    raw = ctx.runner.run(cmd)
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise CheckFailed(f"Prometheus did not return JSON for {query!r}: {raw[:120]!r}") from exc
    if payload.get("status") != "success":
        raise CheckFailed(f"Prometheus rejected {query!r}: {payload.get('error', payload)}")
    return payload


def check_container_metrics(ctx: Context) -> str:
    """The container metric series the alert rules read are present and populated.

    Not "cAdvisor is up" - the specific series. ``ContainerOomKilled``, ``ContainerRestartLoop``,
    ``ContainerMemoryHigh``, ``ContainerPidsHigh`` and both ``*MetricsMissing`` guards all read
    from this family, and an alert whose series has silently gone away does not fire. It looks
    identical to a healthy system, which is the whole reason this check exists.

    Args:
        ctx: the run context.

    Returns:
        A summary naming how many required series carried samples.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when a required series is absent or empty.
    """
    missing = []
    for series in REQUIRED_CONTAINER_SERIES:
        payload = _promql(ctx, f"count({series})")
        results = payload.get("data", {}).get("result", [])
        if not results:
            missing.append(series)
            continue
        try:
            value = float(results[0]["value"][1])
        except (KeyError, IndexError, ValueError):
            missing.append(series)
            continue
        if value <= 0:
            missing.append(series)
    if missing:
        raise CheckFailed(
            f"{len(missing)} of {len(REQUIRED_CONTAINER_SERIES)} required container series carry "
            f"no samples: {', '.join(missing)} - every alert reading them is silently disarmed")
    return f"all {len(REQUIRED_CONTAINER_SERIES)} required container series populated"


def check_log_streams(ctx: Context) -> str:
    """Loki is ingesting, and recently.

    A log pipeline that stopped is not visible from the application side at all: the app keeps
    writing, the dashboards keep rendering the last window, and only the absence of new lines
    says anything. Asserting recency rather than presence is the difference.

    Args:
        ctx: the run context.

    Returns:
        A summary naming the ingestion rate observed.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when Loki has ingested nothing in the recent window.
    """
    payload = _promql(ctx, "sum(rate(loki_distributor_lines_received_total[5m]))")
    results = payload.get("data", {}).get("result", [])
    if not results:
        raise CheckFailed(
            "loki_distributor_lines_received_total has no samples - either Loki is not being "
            "scraped or it has stopped receiving altogether")
    rate = float(results[0]["value"][1])
    if rate <= 0:
        raise CheckFailed(
            f"Loki ingested {rate} lines/s over the last 5m - the log pipeline has stopped, and "
            "nothing downstream will say so")
    return f"Loki ingesting {rate:.2f} lines/s"


# ============================================================================================
# Checks - load (opt-in)
# ============================================================================================
def check_rate_limit_active(ctx: Context) -> str:
    """A burst past the per-IP cap is refused with 429.

    REQ-SEC-023 sets 20 r/s with a burst of 80. This sends past that and requires the edge to
    refuse - the point being not that a limiter exists but that it is **reachable through the
    real request path**, which a configuration check cannot establish.

    Opt-in because it is load against the target, and because it deliberately consumes this
    client's own bucket for a few seconds.

    Args:
        ctx: the run context.

    Returns:
        A summary naming how many of the burst were refused.

    Raises:
        Skip: when ``--include-load`` was not given.
        CheckFailed: when nothing was refused.
    """
    if not ctx.include_load:
        raise Skip("load checks are opt-in; pass --include-load")
    host = ctx.hosts.get("frontend") or next(iter(sorted(ctx.hosts.values())))

    def one(_i: int) -> int:
        try:
            status, _ = _https_get(host, "/healthz", timeout=10,
                                   headers={PROBE_HEADER: "rate-limit-probe"})
            return status
        except OSError:
            return 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
        statuses = list(pool.map(one, range(200)))
    refused = sum(1 for s in statuses if s == 429)
    if refused == 0:
        raise CheckFailed(
            f"200 requests drew no 429 (statuses seen: {sorted(set(statuses))}) - the per-IP "
            "limiter is not in the request path, or its bucket is not keyed on this client")
    return f"{refused}/200 refused with 429"


# ============================================================================================
# Registry
# ============================================================================================
@dataclasses.dataclass(frozen=True)
class Check:
    """One registered check.

    Attributes:
        name: stable id used by ``--only`` and printed in the report.
        requirement: the requirement or decision it defends.
        needs_host: whether it requires host access.
        fn: the callable implementing it.
    """

    name: str
    requirement: str
    needs_host: bool
    fn: Callable[[Context], str]


CHECKS: tuple[Check, ...] = (
    Check("vhost-reachable", "REQ-OPS-014", False, check_vhost_reachable),
    Check("certificate-valid", "REQ-OPS-026", False, check_certificate_valid),
    Check("certificate-shared", "REQ-OPS-026 / ADR-0162", False, check_certificate_shared),
    Check("http-redirects", "REQ-SEC-023", False, check_http_redirects),
    Check("ipv6-reachable", "ADR-0112", False, check_ipv6_reachable),
    Check("client-address-visible", "REQ-SEC-023 / ADR-0112", True, check_client_address_visible),
    Check("containers-running", "REQ-OPS-003", True, check_containers_running),
    Check("scrape-targets-up", "REQ-OBS-005", True, check_scrape_targets_up),
    Check("container-metrics", "REQ-OBS-006", True, check_container_metrics),
    Check("log-streams", "REQ-OBS-005", True, check_log_streams),
    Check("rate-limit-active", "REQ-SEC-023", False, check_rate_limit_active),
)


def run_checks(ctx: Context, selected: Sequence[str] | None) -> list[Result]:
    """Run the selected checks and collect their results.

    A check that raises anything unexpected is reported as a failure carrying the exception
    text, rather than aborting the run: the suite's job is to report on every invariant it was
    asked about, and one broken check must not hide the state of the others.

    Args:
        ctx: the run context.
        selected: check names to run, or ``None`` for all of them.

    Returns:
        One :class:`Result` per check, in registry order.
    """
    results = []
    for check in CHECKS:
        if selected and check.name not in selected:
            continue
        if check.needs_host and not ctx.runner.available:
            results.append(Result(check.name, check.requirement, "skip",
                                  "needs host access (--ssh or --host-stub)"))
            continue
        try:
            detail = check.fn(ctx)
            results.append(Result(check.name, check.requirement, "pass", detail))
        except Skip as exc:
            results.append(Result(check.name, check.requirement, "skip", str(exc)))
        except CheckFailed as exc:
            results.append(Result(check.name, check.requirement, "fail", str(exc)))
        except Exception as exc:  # noqa: BLE001 - a broken check is a finding, not a crash
            results.append(Result(check.name, check.requirement, "fail",
                                  f"{type(exc).__name__}: {exc}"))
    return results


# ============================================================================================
# CLI
# ============================================================================================
def _parse_args(argv: Sequence[str]) -> argparse.Namespace:
    """Parse the command line.

    Args:
        argv: arguments without the program name.

    Returns:
        The parsed namespace.
    """
    p = argparse.ArgumentParser(
        prog="check-conformance.py",
        description="External conformance suite for the Profit Basetool deployment.",
        epilog="Every check is read-only except rate-limit-active, which needs --include-load.")
    p.add_argument("--host", action="append", default=[], metavar="ROLE=NAME",
                   help="override a vhost, e.g. --host frontend=staging.example. Repeatable.")
    p.add_argument("--ssh", metavar="USER@HOST",
                   help="enable the host-side checks over read-only SSH")
    p.add_argument("--host-stub", metavar="CMD",
                   help="run host commands through this command line instead of ssh, "
                        "e.g. \"bash tools/stub.sh\". The host command is appended as "
                        "the last argument. Used by check-conformance.test.sh.")
    p.add_argument("--include-load", action="store_true",
                   help="also run the rate-limiter burst - load against the target")
    p.add_argument("--only", action="append", default=[], metavar="CHECK",
                   help="run only this check. Repeatable.")
    p.add_argument("--log-window", type=int, default=10, metavar="MIN",
                   help="minutes of host log history the probe reads back (default: 10)")
    p.add_argument("--json", action="store_true", help="emit results as JSON")
    p.add_argument("--list", action="store_true", help="list the checks and exit")
    return p.parse_args(argv)


def _resolve_hosts(overrides: Iterable[str]) -> dict[str, str]:
    """Build the vhost map from the defaults, the environment and ``--host`` overrides.

    Precedence is defaults < ``EDGE_HOST_*`` environment < ``--host``. The environment is read
    because the same four names already live there for the compose stack; the host ``.env`` file
    itself is never opened, because it carries credentials.

    Args:
        overrides: ``ROLE=NAME`` strings from the command line.

    Returns:
        role -> vhost name.

    Raises:
        SystemExit: on a malformed override or an unknown role.
    """
    hosts = dict(DEFAULT_HOSTS)
    for role in hosts:
        env = os.environ.get(f"EDGE_HOST_{role.upper()}")
        if env:
            hosts[role] = env
    for item in overrides:
        if "=" not in item:
            raise SystemExit(f"--host expects ROLE=NAME, got {item!r}")
        role, name = item.split("=", 1)
        if role not in hosts:
            raise SystemExit(f"unknown role {role!r}; known roles: {', '.join(sorted(hosts))}")
        hosts[role] = name
    return hosts


def _report(results: Sequence[Result], as_json: bool) -> None:
    """Print the run's results.

    Args:
        results: what :func:`run_checks` returned.
        as_json: emit JSON instead of the human report.
    """
    if as_json:
        print(json.dumps([dataclasses.asdict(r) for r in results], indent=2))
        return
    symbol = {"pass": "PASS", "fail": "FAIL", "skip": "SKIP"}
    width = max((len(r.check) for r in results), default=10)
    for r in results:
        print(f"{symbol[r.status]:4}  {r.check:<{width}}  {r.requirement:<22}  {r.detail}")
    failed = sum(1 for r in results if r.failed)
    skipped = sum(1 for r in results if r.status == "skip")
    passed = len(results) - failed - skipped
    print()
    if HTTPS_PORT != 443 or HTTP_PORT != 80 or CA_BUNDLE:
        print(f"NOTE: test seams active - https:{HTTPS_PORT} http:{HTTP_PORT} "
              f"ca:{CA_BUNDLE or 'system'}. This run did NOT probe production.")
    print(f"{passed} passed, {failed} failed, {skipped} skipped")


def main(argv: Sequence[str] | None = None) -> int:
    """Entry point.

    Args:
        argv: arguments without the program name, or ``None`` to read ``sys.argv``.

    Returns:
        ``0`` when nothing failed, ``1`` when at least one check failed.
    """
    args = _parse_args(list(sys.argv[1:] if argv is None else argv))

    if args.list:
        width = max(len(c.name) for c in CHECKS)
        for c in CHECKS:
            scope = "host" if c.needs_host else "external"
            print(f"{c.name:<{width}}  {scope:<8}  {c.requirement}")
        return 0

    known = {c.name for c in CHECKS}
    unknown = [n for n in args.only if n not in known]
    if unknown:
        raise SystemExit(f"unknown check(s): {', '.join(unknown)}")

    ctx = Context(
        hosts=_resolve_hosts(args.host),
        runner=HostRunner(args.ssh, args.host_stub),
        include_load=args.include_load,
        log_window=args.log_window,
    )
    results = run_checks(ctx, args.only or None)
    _report(results, args.json)
    return 1 if any(r.failed for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())

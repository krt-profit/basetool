#!/usr/bin/env python3
"""Profit Basetool - external conformance suite.

Asserts invariants against a running host rather than against configuration files. The edge
deny families are covered by ``.github/workflows/edge-deny-probe.yml``, not here. Every check is
read-only except the opt-in ``--include-load`` rate-limiter burst. No credential is ever read
out of the host.

Usage
-----
::

    python scripts/check-conformance.py

    python scripts/check-conformance.py --ssh root@198.51.100.10

    python scripts/check-conformance.py --ssh root@198.51.100.10 --include-load

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
import io
import ipaddress
import json
import os
import re
import shlex
import urllib.parse
import shutil
import socket
import ssl
import subprocess
import sys
import uuid
from typing import Callable, Iterable, Sequence

DEFAULT_HOSTS = {
    "frontend": "profit-base.online",
    "ingest": "ingest.profit-base.online",
    "grafana": "grafana.profit-base.online",
    "api": "api.profit-base.online",
}

CERT_MIN_DAYS = 21

UNPRIVILEGED_CONTAINERS = {
    "db-backend": 70,
    "db-keycloak": 70,
    "redis": 999,
}

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

REQUIRED_CONTAINER_SERIES = (
    "basetool_container_memory_working_set_bytes",
    "basetool_container_memory_limit_bytes",
    "basetool_container_pids",
    "basetool_container_pids_max",
    "basetool_container_oom_kills_total",
    "basetool_container_cpu_usage_seconds_total",
)

NEVER_A_CLIENT = (
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("fc00::/7"),
)

PROBE_HEADER = "X-Basetool-Conformance"

HTTPS_PORT = int(os.environ.get("BASETOOL_CONFORMANCE_HTTPS_PORT", "443"))
HTTP_PORT = int(os.environ.get("BASETOOL_CONFORMANCE_HTTP_PORT", "80"))
CA_BUNDLE = os.environ.get("BASETOOL_CONFORMANCE_CA_BUNDLE") or None


def _ssl_context() -> ssl.SSLContext:
    """Build the TLS context the external checks verify certificates with.

    Returns:
        A verifying context (TLS 1.2+), with ``BASETOOL_CONFORMANCE_CA_BUNDLE`` as an extra
        anchor when set.
    """
    ctx = ssl.create_default_context()
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    if CA_BUNDLE:
        ctx.load_verify_locations(cafile=CA_BUNDLE)
    return ctx


@dataclasses.dataclass
class Result:
    """The outcome of one check.

    Attributes:
        check: the check's stable id, as ``--list`` prints it.
        requirement: the requirement or decision the check exists to defend.
        status: ``pass``, ``fail`` or ``skip``.
        detail: one actionable line; on a failure, what was observed.
    """

    check: str
    requirement: str
    status: str
    detail: str

    @property
    def failed(self) -> bool:
        """Whether this result should fail the run.

        Returns:
            ``True`` only for ``fail``; a skip is not a failure.
        """
        return self.status == "fail"


class Skip(Exception):
    """Raised by a check that cannot run here, carrying the reason for the report."""


class CheckFailed(Exception):
    """Raised by a check that ran and found the invariant broken."""


class HostRunner:
    """Runs read-only commands on the deployment host.

    Shells out to ``ssh``, or to a local stub script (as ``check-conformance.test.sh`` does).

    Args:
        ssh_target: ``user@host`` for the real runner, or ``None``.
        stub: a command line, split with :func:`shlex.split`, that receives the host command
            as its last argument; takes precedence over ``ssh_target``.
        timeout: seconds before a command is abandoned.
    """

    def __init__(self, ssh_target: str | None, stub: str | None, timeout: int = 45) -> None:
        self.ssh_target = ssh_target
        self.stub = stub
        self.timeout = timeout
        self._container_cli: str | None = None

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
            command: a shell command line, run by the remote login shell (bash).

        Returns:
            The command's stdout, with trailing whitespace stripped.

        Raises:
            Skip: when no host access is configured.
            CheckFailed: when the command cannot be executed or exits non-zero.
        """
        if not self.available:
            raise Skip("no --ssh target and no --host-stub")

        command = f"cd /; {command}"

        if self.stub:
            argv = shlex.split(self.stub) + [command]
        else:
            ssh = shutil.which("ssh")
            if not ssh:
                raise Skip("ssh not found on PATH")
            argv = [ssh, "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
                    self.ssh_target or "", command]

        try:
            proc = subprocess.run(argv, capture_output=True, text=True, timeout=self.timeout)
        except subprocess.TimeoutExpired as exc:
            raise CheckFailed(f"host command timed out after {self.timeout}s: {command}") from exc
        if proc.returncode != 0:
            err = (proc.stderr or proc.stdout).strip().splitlines()
            raise CheckFailed(f"host command failed ({proc.returncode}): {err[-1] if err else command}")
        return proc.stdout.strip()

    @property
    def container_cli(self) -> str:
        """The command prefix that reaches this host's containers, detected once and cached.

        Tries plain ``podman`` first, then each lingering user's rootless ``podman``, and takes
        the first that lists containers.

        Returns:
            ``podman``, or a ``sudo -n -u <user> XDG_RUNTIME_DIR=… podman`` prefix for a rootless
            deployment the runner does not itself own.

        Raises:
            Skip: when no host access is configured, or the host has neither runtime.
        """
        if self._container_cli is None:
            probe = (
                "if command -v podman >/dev/null 2>&1; then "
                "  if podman ps --format '{{.Names}}' 2>/dev/null | grep -q .; then echo podman; "
                "  else "
                "    found=; "
                "    for u in $(ls -1 /var/lib/systemd/linger 2>/dev/null); do "
                "      uid=$(id -u \"$u\" 2>/dev/null) || continue; "
                "      if sudo -n -u \"$u\" XDG_RUNTIME_DIR=/run/user/$uid podman ps "
                "           --format '{{.Names}}' 2>/dev/null | grep -q .; then "
                "        found=\"sudo -n -u $u XDG_RUNTIME_DIR=/run/user/$uid podman\"; break; "
                "      fi; "
                "    done; "
                "    echo \"${found:-podman}\"; "
                "  fi; "
                "else echo NONE; fi"
            )
            answer = self.run(probe).strip().splitlines()[-1].strip()
            if answer == "NONE" or not answer:
                raise Skip("this host has no podman binary")
            self._container_cli = answer
        return self._container_cli

    def container_log_cmd(self, name: str, minutes: int) -> str:
        """A command that prints one container's logs, bounded by time.

        The container's log driver decides the form: ``journalctl`` for ``journald``, ``logs``
        otherwise, both when the driver cannot be read. Stderr is never merged.

        Args:
            name: the container name.
            minutes: how far back to read.

        Returns:
            A shell command line printing the log, one line per entry, stdout only.
        """
        cli = self.container_cli

        driver = self.run(
            f"{cli} inspect {name} --format '{{{{.HostConfig.LogConfig.Type}}}}' 2>/dev/null"
        ).strip()

        journal = (
            f"journalctl CONTAINER_NAME={name} --since '{minutes} min ago' "
            "--no-pager -o cat 2>/dev/null"
        )
        podman_logs = f"{cli} logs {name} --since {minutes}m 2>/dev/null"
        if driver == "journald":
            return journal
        if driver:
            return podman_logs
        return f"{{ {podman_logs}; {journal}; }}"

    def ssh_client_address(self) -> str | None:
        """Report the address this machine presents to the host over SSH.

        Returns:
            The client address from ``SSH_CONNECTION``/``SSH_CLIENT``, or ``None`` when unavailable.
        """
        try:
            out = self.run("echo ${SSH_CONNECTION:-${SSH_CLIENT:-}}")
        except (Skip, CheckFailed):
            return None
        return out.split()[0] if out.split() else None


def _https_get(host: str, path: str = "/", family: int = 0, timeout: int = 20,
               headers: dict[str, str] | None = None) -> tuple[int, dict[str, str]]:
    """Issue one HTTPS GET and return the status and response headers.

    Pins the address family and never follows redirects.

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
        except OSError as exc:
            last = exc
    raise OSError(getattr(last, "errno", None),
                  f"{host}:{port} unreachable: {last}") from last


def _peer_certificate(host: str, family: int = 0, timeout: int = 20) -> dict:
    """Fetch and parse the leaf certificate a vhost serves.

    Args:
        host: the vhost name, sent as SNI.
        family: address family to pin, or ``0``.
        timeout: socket timeout in seconds.

    Returns:
        A dict with ``not_after`` (``datetime``), ``sans`` (tuple of DNS names) and
        ``fingerprint`` (SHA-256 hex of the DER).

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


def _san_covers(host: str, sans: Sequence[str]) -> bool:
    """Whether any DNS SAN entry covers this host name.

    A leftmost ``*`` matches exactly one label (RFC 6125 §6.4.3); comparison is case-insensitive
    and ignores a trailing dot.

    Args:
        host: the vhost name being checked.
        sans: the certificate's DNS subject-alternative names.

    Returns:
        ``True`` when at least one entry covers ``host``.
    """
    h = host.lower().rstrip(".")
    for raw in sans:
        san = raw.lower().rstrip(".")
        if san == h:
            return True
        if san.startswith("*."):
            suffix = san[1:]
            if h.endswith(suffix) and "." not in h[: -len(suffix)]:
                return True
    return False


def _parse_address(text: str) -> ipaddress._BaseAddress | None:
    """Parse one log field into an address, unwrapping the IPv4-mapped IPv6 form.

    Args:
        text: a candidate address, as it appears as the first field of an access-log line.

    Returns:
        The address, with ``::ffff:a.b.c.d`` reduced to ``a.b.c.d``, or ``None`` when the text is
        not an address.
    """
    candidate = text.strip().lower().rstrip(",")
    if not candidate:
        return None
    candidate = candidate.split("%", 1)[0]
    try:
        parsed = ipaddress.ip_address(candidate)
    except ValueError:
        return None
    mapped = getattr(parsed, "ipv4_mapped", None)
    return mapped or parsed


def _is_private(address: str) -> bool:
    """Whether an address is one a first-hop edge must never report as a client.

    The address is parsed (IPv4-mapped IPv6 unwrapped) and checked against
    :data:`NEVER_A_CLIENT`, not ``ipaddress.is_private``, so documentation ranges count as public.

    Args:
        address: an IPv4 or IPv6 address in text form, in any spelling.

    Returns:
        ``True`` for loopback, unspecified, link-local, RFC 1918 and RFC 4193 addresses, and for
        anything that is not an address.
    """
    parsed = _parse_address(address)
    if parsed is None:
        return True
    if parsed.is_loopback or parsed.is_link_local or parsed.is_unspecified:
        return True
    return any(parsed in net for net in NEVER_A_CLIENT if net.version == parsed.version)


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


def check_vhost_reachable(ctx: Context) -> str:
    """Every configured vhost answers over HTTPS with a status below 500.

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
        if not _san_covers(host, cert["sans"]):
            problems.append(f"{host} not covered by SAN list {cert['sans']}")
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

    Applies only where the ``acme`` container issues certificates (non-empty ``ACME_HOSTS``;
    REQ-OPS-026).

    Args:
        ctx: the run context.

    Returns:
        The shared fingerprint, abbreviated.

    Raises:
        Skip: when the host does not issue its own certificates, or says nothing about it.
        CheckFailed: when more than one distinct leaf is served.
    """
    if ctx.runner.available:
        try:
            managed = ctx.runner.run(
                f'{ctx.runner.container_cli} inspect acme '
                '--format "{{range .Config.Env}}{{println .}}{{end}}" '
                '| grep "^ACME_HOSTS=" | cut -d= -f2-')
        except (Skip, CheckFailed):
            managed = ""
        if not managed.strip():
            raise Skip(
                "this host issues no certificates of its own (ACME_HOSTS is empty, so the acme "
                "container idles) - a single shared leaf is not claimed here")
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

    A missing AAAA record or a failed connection fails (ADR-0112); no usable IPv6 route from this
    machine skips.

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
            if getattr(exc, "errno", None) in (
                    getattr(__import__("errno"), "ENETUNREACH", -1),
                    getattr(__import__("errno"), "EAFNOSUPPORT", -2)):
                raise Skip(f"no usable IPv6 route from this machine "
                           f"({exc.strerror or exc}) - this says nothing about the "
                           f"deployment") from exc
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

    Asserted separately from :func:`check_containers_running`, since a running container can
    still have a failing actuator.

    Args:
        ctx: the run context.

    Returns:
        A summary naming how many application targets were up.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when an expected target is down or absent entirely.
    """
    wanted = ("basetool-backend", "basetool-frontend", "basetool-ingest", "keycloak")
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


def check_client_address_visible(ctx: Context) -> str:
    """The edge logs the client's address, not a proxy's (REQ-SEC-023, ADR-0112).

    Issues a marked request and asserts that the edge logged it from a public address equal to
    our SSH source where comparable, and that the edge saw more than one distinct client
    address in the last hour.

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

    logs = ctx.runner.run(
        f"{ctx.runner.container_log_cmd('edge', ctx.log_window)} | grep -F {marker} | head -5")
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
    logged = _parse_address(first_field)
    ssh_parsed = _parse_address(ssh_addr or "")
    if ssh_parsed is not None and logged is not None and ssh_parsed.version == logged.version:
        if ssh_parsed != logged:
            raise CheckFailed(
                f"the edge logged {first_field} but this machine reaches the host from "
                f"{ssh_addr} - the address is being rewritten in flight")
        matched = ", and it matches our SSH source"

    raw_lines = ctx.runner.run(f"{ctx.runner.container_log_cmd('edge', 60)} | head -20000")
    addresses = set()
    for line in raw_lines.splitlines():
        fields = line.split()
        if not fields:
            continue
        parsed = _parse_address(fields[0])
        if parsed is not None:
            addresses.add(parsed)
    count = len(addresses)
    if count < 2:
        raise CheckFailed(
            f"the edge saw {count} distinct client address in the last hour - an edge behind a "
            "userland forwarder sees exactly one, and the per-IP limiter then applies to "
            "everybody at once")
    return f"edge logged our probe from {first_field}{matched}; {count} distinct clients in 60m"


def check_redis_requires_auth(ctx: Context) -> str:
    """An unauthenticated client must not be able to talk to Redis.

    Sends an unauthenticated ``PING``; ``-NOAUTH`` passes, ``+PONG`` fails. No credential is used.

    Args:
        ctx: the run context.

    Returns:
        A summary naming what Redis answered.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when Redis answers an unauthenticated PING, or cannot be found.
    """
    cmd = (
        f"{ctx.runner.container_cli} exec redis sh -c "
        "'env -u REDISCLI_AUTH redis-cli --no-auth-warning ping 2>&1 | head -n 1' "
        "|| echo ABSENT"
    )
    reply = ctx.runner.run(cmd).strip()

    if reply == "ABSENT":
        raise CheckFailed("no running redis container on this host")
    if reply in ("UNREACHABLE", "NO_REPLY", ""):
        raise CheckFailed(
            f"could not complete an unauthenticated probe against redis ({reply or 'no output'}). "
            "That is not a pass: it says nothing about whether Redis requires authentication.")
    if reply.upper().startswith("+PONG"):
        raise CheckFailed(
            "Redis answered +PONG to an UNAUTHENTICATED ping. The session store -- OAuth2 refresh "
            "tokens included -- is readable and writable by anything that can reach it on the "
            "internal network. The cause is almost certainly a users.acl with no `user default` "
            "line, which makes Redis reset default to nopass at load: check with "
            "`grep -c '^user default ' /var/iri/redis/users.acl`, which must answer 1.")
    if "NOAUTH" not in reply.upper():
        raise CheckFailed(
            f"Redis answered {reply!r} to an unauthenticated ping - expected -NOAUTH. That is "
            "neither the healthy answer nor the known failure, so it is worth reading before "
            "assuming either.")
    return "an unauthenticated ping is refused with -NOAUTH"


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
    out = ctx.runner.run(
        f'{ctx.runner.container_cli} ps -a --format "{{{{.Names}}}}|{{{{.Status}}}}"')
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

    The query runs inside the ``prometheus`` container via ``wget`` over plain HTTP with basic
    auth; the password is read from the container's own mounted secret and never leaves it.

    Args:
        ctx: the run context.
        query: the PromQL expression. Must contain no single quote.

    Returns:
        The decoded ``/api/v1/query`` response.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when Prometheus cannot be reached or does not answer ``status: success``.
    """
    secret = "/etc/prometheus/secrets/web_password"
    encoded = urllib.parse.quote(query, safe="")
    inner = (
        f"p=$(cat {secret}); "
        "a=$(printf grafana:%s \"$p\" | base64 -w0); "
        "wget -q -O- --header=\"Authorization: Basic $a\" "
        f"\"http://127.0.0.1:9090/api/v1/query?query={encoded}\""
    )
    cmd = f"{ctx.runner.container_cli} exec prometheus sh -c '{inner}'"
    try:
        raw = ctx.runner.run(cmd)
    except CheckFailed as exc:
        text = str(exc).lower()
        if "no such object" in text or "no such container" in text:
            raise CheckFailed(
                "no running prometheus container on this host - there is no monitoring plane "
                "here to read, which is a finding about the host rather than about the query"
            ) from exc
        raise
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise CheckFailed(f"Prometheus did not return JSON for {query!r}: {raw[:120]!r}") from exc
    if payload.get("status") != "success":
        raise CheckFailed(f"Prometheus rejected {query!r}: {payload.get('error', payload)}")
    return payload


def _translate_uid(host_uid: int, uid_map: list) -> int | None:
    """Translate a host uid into the uid a container sees, using that container's ``uid_map``.

    Each row is ``(container_start, host_start, count)``, as ``/proc/<pid>/uid_map`` prints it.

    Args:
        host_uid: the uid the host sees the process running as.
        uid_map: the parsed rows of the container's uid_map.

    Returns:
        The uid inside the container, or ``None`` when the host uid is in no mapped range.
    """
    for container_start, host_start, count in uid_map:
        if host_start <= host_uid < host_start + count:
            return container_start + (host_uid - host_start)
    return None


def check_containers_unprivileged(ctx: Context) -> str:
    """The stateful containers run as their own uid, not as root inside the container.

    Reads pid 1's host uid and ``uid_map`` from the host and translates the uid into the
    container's view.

    Args:
        ctx: the run context.

    Returns:
        A summary naming each container and the container-side uid it runs as.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when a container is absent, unreadable, or running as a uid other than
            the one in :data:`UNPRIVILEGED_CONTAINERS`.
    """
    problems, good = [], []
    for name, expected in sorted(UNPRIVILEGED_CONTAINERS.items()):
        cmd = (
            f"pid=$({ctx.runner.container_cli} inspect --format '{{{{.State.Pid}}}}' "
            + name + " 2>/dev/null); "
            'if [ -z "$pid" ] || [ "$pid" = 0 ]; then echo ABSENT; exit 0; fi; '
            "grep ^Uid: /proc/$pid/status; "
            "sed 's/^/MAP /' /proc/$pid/uid_map"
        )
        out = ctx.runner.run(cmd).strip()
        if "ABSENT" in out:
            problems.append(f"{name} is not running, so nothing can be said about its uid")
            continue

        host_uid, uid_map = None, []
        for line in out.splitlines():
            fields = line.split()
            if fields[:1] == ["Uid:"] and len(fields) >= 2 and fields[1].isdigit():
                host_uid = int(fields[1])
            elif fields[:1] == ["MAP"] and len(fields) == 4 and all(f.isdigit() for f in fields[1:]):
                uid_map.append(tuple(int(f) for f in fields[1:]))
        if host_uid is None or not uid_map:
            problems.append(
                f"{name}: could not read the uid of pid 1 ({out!r}). That is not a pass -- it "
                "says nothing about whether the process dropped its privileges")
            continue

        inside = _translate_uid(host_uid, uid_map)
        if inside is None:
            problems.append(
                f"{name}: host uid {host_uid} is outside the container's own uid_map {uid_map}, "
                "which should be impossible for its own pid 1")
        elif inside != expected:
            extra = " -- it is running as ROOT inside the container" if inside == 0 else ""
            problems.append(f"{name} runs as container uid {inside}, expected {expected}{extra}")
        else:
            good.append(f"{name}={inside}")

    if problems:
        raise CheckFailed("; ".join(problems))
    return "container-side uid of pid 1: " + ", ".join(good)


def check_containers_read_only(ctx: Context) -> str:
    """Every app container runs on a read-only root filesystem.

    Args:
        ctx: the run context.

    Returns:
        A summary naming how many containers were verified read-only.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when a container is not read-only, or when one cannot be read at all.
    """
    out = ctx.runner.run(
        f'{ctx.runner.container_cli} inspect '
        '--format "{{.Name}}|{{.HostConfig.ReadonlyRootfs}}" '
        + " ".join(EXPECTED_APP_CONTAINERS)
    )
    state: dict[str, str] = {}
    for line in out.splitlines():
        if "|" in line:
            name, value = line.split("|", 1)
            state[name.strip().lstrip("/")] = value.strip().lower()

    problems = []
    verified = 0
    for name in EXPECTED_APP_CONTAINERS:
        value = state.get(name)
        if value is None:
            problems.append(f"{name}: no container, so its root filesystem says nothing")
        elif value != "true":
            problems.append(f"{name} has a writable root filesystem")
        else:
            verified += 1
    if problems:
        raise CheckFailed("; ".join(problems))
    return f"all {verified} app containers on a read-only root filesystem"


def check_container_metrics(ctx: Context) -> str:
    """The container metric series the alert rules read are present and populated.

    Each series in :data:`REQUIRED_CONTAINER_SERIES` must carry at least one sample.

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
    """Loki has ingested lines within the last five minutes.

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


def check_trace_pipeline(ctx: Context) -> str:
    """Spans reach Alloy's OTLP receiver, when the apps are configured to emit any.

    Skips unless ``MONITORING_TRACING_ENABLED=true`` in the host ``.env`` (read by one narrow
    grep). An absent span counter counts as a failure, not as "nothing yet".

    Args:
        ctx: the run context.

    Returns:
        A summary naming the spans observed.

    Raises:
        Skip: when no host access is configured, or tracing is switched off on this host.
        CheckFailed: when tracing is on and no span has reached the receiver.
    """
    raw = ctx.runner.run(
        "grep -E '^MONITORING_TRACING_ENABLED=' /var/iri/code/.env 2>/dev/null || true")
    if not raw.strip():
        if not ctx.runner.run("test -e /var/iri/code/.env && echo yes || true").strip():
            raise Skip("no /var/iri/code/.env on this host yet -- it arrives with the restore, so "
                       "before that there is nothing to read and nothing to conclude")
        if not ctx.runner.run("test -r /var/iri/code/.env && echo yes || true").strip():
            raise Skip("/var/iri/code/.env is not readable by this SSH account (0640 "
                       "deploy:deploy) -- rerun with an account that can read it")
        raise Skip("the .env does not set MONITORING_TRACING_ENABLED; it defaults to false "
                   "(REQ-OBS, tracing is inert by default) so there are no spans to expect")
    value = raw.split("=", 1)[1].strip().strip('"').strip("'").lower()
    if value != "true":
        raise Skip(f"MONITORING_TRACING_ENABLED={value} on this host -- the apps emit no spans, "
                   "so the pipeline has nothing to carry")

    payload = _promql(ctx, "sum(otelcol_receiver_accepted_spans_total)")
    results = payload.get("data", {}).get("result", [])
    if not results:
        raise CheckFailed(
            "otelcol_receiver_accepted_spans_total has no samples at all while tracing is ON - "
            "no span has ever reached Alloy's OTLP receiver. The usual cause on a Podman host is "
            "that the app containers cannot resolve `alloy`: it is a HOST service there, so the "
            "name needs AddHost=alloy:host-gateway (generate-quadlet.py's PODMAN_HOST_ALIASES). "
            "The spans are dropped in each app's own exporter, which logs nothing")
    spans = float(results[0]["value"][1])
    if spans <= 0:
        raise CheckFailed(
            "Alloy's OTLP receiver has accepted 0 spans while tracing is ON - the apps are "
            "emitting nowhere and no alert covers this path")
    return f"Alloy has accepted {spans:.0f} spans"


def check_rate_limit_active(ctx: Context) -> str:
    """A burst past the per-IP cap is refused with 429.

    Sends 200 requests against the REQ-SEC-023 limit (20 r/s, burst 80). Opt-in, since it is
    load against the target.

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


BAKED_INTO_UNITS = {
    "IRI_IMAGE_NAMESPACE": "Image=",
    "IRI_BASETOOL_VERSION": "Image=",
    "IRI_KEYCLOAK_HOST_ALIAS": "AddHost=",
    "IRI_KEYSTORE_HOST_PATH": "Volume=",
    "IRI_BACKEND_KEYSTORE_HOST_PATH": "Volume=",
    "IRI_FRONTEND_KEYSTORE_HOST_PATH": "Volume=",
    "IRI_INGEST_KEYSTORE_HOST_PATH": "Volume=",
    "IRI_KEYCLOAK_KEYSTORE_HOST_PATH": "Volume=",
    "IRI_INTERNAL_TRUSTSTORE_HOST_PATH": "Volume=",
    "IRI_TRUSTSTORE_HOST_PATH": "Volume=",
    "IRI_REDIS_ACL_HOST_PATH": "Volume=",
    "IRI_UPSTREAM_CA_HOST_PATH": "Volume=",
    "IRI_GRAFANA_UPSTREAM_CERT_HOST_PATH": "Volume=",
}


def check_env_reaches_the_units(ctx: Context) -> str:
    """A value set in the host `.env` must actually be in the units, or be absent from both.

    Quadlet does not interpolate the ``.env``, so each :data:`BAKED_INTO_UNITS` value set there
    must appear in a unit or a systemd drop-in.

    Args:
        ctx: the run context.

    Returns:
        A summary naming each variable and where its effective value came from.

    Raises:
        Skip: when no host access is configured, or the host carries no `.env`.
        CheckFailed: when the `.env` sets a value that neither the unit nor a drop-in carries.
    """
    wanted = "|".join(sorted(BAKED_INTO_UNITS))
    env_raw = ctx.runner.run(
        f"grep -E '^({wanted})=' /var/iri/code/.env 2>/dev/null || true")
    if not env_raw.strip():
        if not ctx.runner.run("test -f /var/iri/code/.env && echo yes || true").strip():
            raise Skip("no /var/iri/code/.env on this host")
        if not ctx.runner.run("test -r /var/iri/code/.env && echo yes || true").strip():
            raise Skip("/var/iri/code/.env is not readable by this SSH account (it is 0640 "
                       "deploy:deploy) -- rerun with an account that can read it, or this check "
                       "says nothing")
        raise Skip("the .env on this host sets none of the variables the units bake in")

    env = {}
    for line in env_raw.splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()

    units = ctx.runner.run(
        "cat /etc/containers/systemd/*.container "
        "/etc/containers/systemd/*.container.d/*.conf "
        "/etc/containers/systemd/users/*/*.container "
        "/etc/containers/systemd/users/*/*.container.d/*.conf "
        "/home/*/.config/containers/systemd/*.container "
        "/home/*/.config/containers/systemd/*.container.d/*.conf 2>/dev/null || true"
    )
    if not units.strip():
        raise Skip("no Quadlet units found under /etc/containers/systemd or any home directory")

    problems, good = [], []
    for var, directive in sorted(BAKED_INTO_UNITS.items()):
        value = env.get(var, "")
        if not value:
            continue
        if value in units:
            good.append(f"{var} ({directive})")
        else:
            problems.append(
                f"{var}={value} is set in .env but appears in no unit or drop-in. Quadlet does not "
                f"interpolate, so the units carry the generator's baked default in {directive} and "
                f"this line has no effect. Add a drop-in under "
                f"~/.config/containers/systemd/<service>.container.d/ or remove the line."
            )

    if problems:
        raise CheckFailed("; ".join(problems))
    if not good:
        return "no per-host override is set, so the units' baked defaults are uncontested"
    return f"{len(good)} override(s) reach the units: {', '.join(good)}"


def check_edge_not_directly_reachable(ctx: Context) -> str:
    """The edge's own port must be reachable from nothing but the front end (ADR-0187).

    Probes ports 8080 and 8443 from the host on each global address; the edge trusts the PROXY
    header, so it must publish on loopback only.

    Args:
        ctx: the run context.

    Returns:
        A summary naming the addresses that were tried and refused.

    Raises:
        Skip: when no host access is configured, or the edge does not publish on loopback at all.
        CheckFailed: when the edge answers on an address other than loopback.
    """
    published = ctx.runner.run(
        f"{ctx.runner.container_cli} inspect edge "
        "--format '{{json .NetworkSettings.Ports}}' 2>/dev/null || echo ''"
    ).strip()
    if not published or '127.0.0.1' not in published:
        raise Skip(
            "the edge does not publish on loopback, so there is no front end in front of it "
            "(ADR-0187 not in effect on this host)"
        )

    addrs = ctx.runner.run(
        "ip -o addr show scope global | awk '{print $4}' | cut -d/ -f1"
    ).split()
    if not addrs:
        raise Skip("the host reports no global address to probe from")

    reachable = []
    for addr in addrs:
        target = f"[{addr}]" if ":" in addr else addr
        for port in ("8080", "8443"):
            code = ctx.runner.run(
                f"curl -s -o /dev/null --connect-timeout 4 --max-time 6 "
                f"http://{target}:{port}/ >/dev/null 2>&1; echo $?"
            ).strip().splitlines()[-1].strip()
            if code not in ("7", "28"):
                reachable.append(f"{target}:{port} -> connected (curl exit {code})")

    if reachable:
        raise CheckFailed(
            "the edge answers on a routable address, so the PROXY header it trusts can be forged: "
            + "; ".join(reachable)
            + ". It must publish on loopback only (ADR-0187)."
        )

    return f"refused on {len(addrs)} global address(es), on both 8080 and 8443"


HOST_EXPORTERS = {
    "node-exporter": ("node_exporter_build_info", "http://127.0.0.1:9100/metrics"),
    "alloy": ("alloy_build_info", "http://127.0.0.1:12345/metrics"),
}

COMPOSE_MONITORING = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docker-compose.monitoring.yml")


def _compose_pinned_version(service: str, path: str = COMPOSE_MONITORING) -> str | None:
    """Read the version tag of one compose service's image, without a YAML parser.

    The ``image:`` line is found textually inside the service's two-space-indented block.

    Args:
        service: the compose service name.
        path: the compose file.

    Returns:
        The tag with any leading ``v`` removed (``1.12.1``), or ``None`` when the service or its
        image line cannot be found.
    """
    try:
        lines = io.open(path, encoding="utf-8").read().splitlines()
    except OSError:
        return None
    inside = False
    for line in lines:
        if line.startswith(f"  {service}:"):
            inside = True
            continue
        if inside and line.startswith("  ") and not line.startswith("   ") and line.strip():
            break
        if inside:
            match = re.match(r"\s+image:\s*\S+?:v?([0-9][^@\s]*)", line)
            if match:
                return match.group(1)
    return None


def check_host_exporter_versions(ctx: Context) -> str:
    """The host-native node_exporter and Alloy run the versions the compose file pins.

    Both are host packages; ``docker-compose.monitoring.yml`` is the one place their version is
    chosen.

    Args:
        ctx: the run context.

    Returns:
        A summary naming each component and the version it runs.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when a component's version differs from the pin, or cannot be read at all.
    """
    problems, good = [], []
    for service, (metric, url) in sorted(HOST_EXPORTERS.items()):
        pinned = _compose_pinned_version(service)
        if not pinned:
            problems.append(f"{service}: no image pin found in docker-compose.monitoring.yml")
            continue
        line = ctx.runner.run(
            f"curl -s --max-time 5 {url} 2>/dev/null | grep '^{metric}' | head -n 1 || true")
        match = re.search(r'[{,]version="v?([^"]+)"', line)
        if not match:
            problems.append(
                f"{service}: {metric} not readable from {url} -- the component is down or not "
                "exporting its build info, so its version says nothing")
            continue
        running = match.group(1)
        if running != pinned:
            problems.append(
                f"{service} runs {running}, the compose pin is {pinned} -- update the host package "
                "(a tested maintenance) or the pin, so the configuration the release ships matches "
                "the process that reads it")
        else:
            good.append(f"{service}={running}")
    if problems:
        raise CheckFailed("; ".join(problems))
    return "host packages match the compose pins: " + ", ".join(good)


def check_security_updates_enabled(ctx: Context) -> str:
    """The host applies security updates unattended, and only security updates.

    Checks both the ``dnf-automatic.timer`` state and ``/etc/dnf/automatic.conf``.

    Args:
        ctx: the run context.

    Returns:
        A summary of the timer state and the configuration it found.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when the timer is not enabled and active, when the configuration does not say
            ``upgrade_type = security`` and ``apply_updates = yes``, or when the container runtime is
            not excluded (REQ-OPS-032).
    """
    enabled = ctx.runner.run("systemctl is-enabled dnf-automatic.timer 2>/dev/null || true").strip()
    active = ctx.runner.run("systemctl is-active dnf-automatic.timer 2>/dev/null || true").strip()
    conf = ctx.runner.run(
        "grep -E '^[[:space:]]*(upgrade_type|apply_updates|exclude)[[:space:]]*=' "
        "/etc/dnf/automatic.conf 2>/dev/null || true")
    settings: dict[str, str] = {}
    for line in conf.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            settings[key.strip()] = value.strip()

    problems = []
    if enabled != "enabled" or active != "active":
        problems.append(
            f"dnf-automatic.timer is {enabled or 'absent'}/{active or 'unknown'}, expected "
            "enabled/active -- the host receives no security updates")
    if settings.get("upgrade_type") != "security":
        problems.append(
            f"upgrade_type is {settings.get('upgrade_type') or 'unset'}, expected security -- "
            "unattended feature updates are not what REQ-OPS-032 allows")
    if settings.get("apply_updates") not in ("yes", "true", "1"):
        problems.append(
            f"apply_updates is {settings.get('apply_updates') or 'unset'} -- updates would be "
            "downloaded and never installed")
    excluded = settings.get("exclude", "").split()
    if "podman" not in excluded:
        problems.append(
            "the container runtime is not excluded -- podman and its companions must move on a "
            "tested maintenance, not unattended")
    if problems:
        raise CheckFailed("; ".join(problems))
    return f"dnf-automatic.timer {enabled}/{active}, security-only, runtime excluded"


CHECKS: tuple[Check, ...] = (
    Check("vhost-reachable", "REQ-OPS-014", False, check_vhost_reachable),
    Check("certificate-valid", "REQ-OPS-026", False, check_certificate_valid),
    Check("certificate-shared", "REQ-OPS-026 / ADR-0162", True, check_certificate_shared),
    Check("http-redirects", "REQ-SEC-023", False, check_http_redirects),
    Check("ipv6-reachable", "ADR-0112", False, check_ipv6_reachable),
    Check("client-address-visible", "REQ-SEC-023 / ADR-0112", True, check_client_address_visible),
    Check("containers-running", "REQ-OPS-003", True, check_containers_running),
    Check("redis-requires-auth", "REQ-SEC-023 / ADR-0088", True, check_redis_requires_auth),
    Check("scrape-targets-up", "REQ-OBS-005", True, check_scrape_targets_up),
    Check("container-metrics", "REQ-OBS-006", True, check_container_metrics),
    Check("log-streams", "REQ-OBS-005", True, check_log_streams),
    Check("trace-pipeline", "REQ-OBS-009 / REQ-OBS-019", True, check_trace_pipeline),
    Check("rate-limit-active", "REQ-SEC-023", False, check_rate_limit_active),
    Check("edge-not-directly-reachable", "ADR-0187 / REQ-SEC-023", True,
          check_edge_not_directly_reachable),
    Check("containers-unprivileged", "REQ-OPS-014 / ADR-0189", True,
          check_containers_unprivileged),
    Check("env-reaches-the-units", "REQ-OPS-004 / REQ-OPS-022", True,
          check_env_reaches_the_units),
    Check("containers-read-only", "REQ-OPS-014 / ADR-0190", True,
          check_containers_read_only),
    Check("host-exporter-versions", "REQ-OBS-005 / OPS-SEC-06", True,
          check_host_exporter_versions),
    Check("security-updates-enabled", "REQ-OPS-032", True,
          check_security_updates_enabled),
)


def run_checks(ctx: Context, selected: Sequence[str] | None) -> list[Result]:
    """Run the selected checks and collect their results.

    An unexpected exception is reported as a failure of that check instead of aborting the run.

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

    Precedence is defaults < ``EDGE_HOST_*`` environment < ``--host``.

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
    """List the checks, or run the selected ones and print the report.

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

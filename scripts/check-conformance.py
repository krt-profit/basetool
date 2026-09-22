#!/usr/bin/env python3
"""Profit Basetool - external conformance suite.

Phase 0 of ``docs/archive/PODMAN_MIGRATION_PLAN.md``: the regression net the container-runtime
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
import ipaddress
import json
import os
import shlex
import urllib.parse
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
#: Containers that must not be running as root inside themselves, and the uid each must be.
#: The same numbers scripts/generate-quadlet.py pins with RUN_AS and the bootstrap role owns the
#: data directories as -- see ADR-0189. Deliberately NOT derived from either at runtime: this suite
#: has to be able to disagree with them, which is the whole point of an acceptance check.
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

#: The cAdvisor series the alert rules in monitoring/prometheus/alerts/ actually read. This list
#: is the reason PODMAN_MIGRATION_PLAN.md §3.6 is a rebuild rather than a rename: a runtime swap
#: has to reproduce these, or the alerts that read them stop having data and stop firing - which
#: looks exactly like a healthy system.
#:
#: Each entry is the cAdvisor name and the name the cgroup collector publishes under, because the
#: SIGNAL is what has to survive a runtime swap, not the spelling. cAdvisor is deleted on rootless
#: Podman (its upstream issue is closed as not planned), and `scripts/cgroup-container-metrics.py`
#: reads the same numbers straight out of the kernel's cgroup files and publishes them as
#: `basetool_container_*` through node_exporter's textfile collector.
#:
#: Asked for the cAdvisor name alone, this check reported all six series missing on a host where
#: every one of them was being collected under the other name -- "every alert reading them is
#: silently disarmed" about alerts that were armed. The alert rules themselves were taught the same
#: `or` normalisation earlier (`basetool:container:present` in containers-runtime.yml); this check
#: was the half that was left behind.
REQUIRED_CONTAINER_SERIES = (
    ("container_memory_working_set_bytes", "basetool_container_memory_working_set_bytes"),
    ("container_spec_memory_limit_bytes", "basetool_container_memory_limit_bytes"),
    ("container_threads", "basetool_container_pids"),
    ("container_threads_max", "basetool_container_pids_max"),
    ("container_oom_events_total", "basetool_container_oom_kills_total"),
    ("container_cpu_usage_seconds_total", "basetool_container_cpu_usage_seconds_total"),
)

#: Ranges an edge must never report as a client address. If our own probe comes back wearing one,
#: a userland port forwarder is rewriting the source - which is the 2026-07-20 outage, and the
#: failure mode rootless Podman's ``rootlessport`` would reintroduce.
#:
#: These were TEXT PREFIXES until 2026-09-18, and the spelling was the whole problem twice over:
#: `::ffff:172.28.15.10` -- the IPv4-mapped form a single dual-stack bind produces -- began with
#: none of them and read as public, while the entry `"172.2"`, written to cover 172.20-172.29,
#: also matched `172.2.3.4`, which is ordinary public space. Networks answer both correctly.
NEVER_A_CLIENT = (
    ipaddress.ip_network("10.0.0.0/8"),        # RFC 1918
    ipaddress.ip_network("172.16.0.0/12"),     # RFC 1918 -- 172.16 through 172.31, and no further
    ipaddress.ip_network("192.168.0.0/16"),    # RFC 1918
    ipaddress.ip_network("fc00::/7"),          # RFC 4193 unique-local
)

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
    # The default context still PERMITS TLS 1.0 and 1.1 -- CodeQL flags it, and it is right to.
    # It matters more here than in a client: this suite asserts what the edge offers, and a probe
    # willing to negotiate a protocol the edge should refuse cannot report that the edge stopped
    # refusing it. The floor is the one the edge itself serves.
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
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
        #: Cached container-runtime prefix; detected once, on first use. See `container_cli`.
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
            command: a shell command line, run by the remote login shell. Both hosts run bash,
                which a couple of probes rely on.

        Returns:
            The command's stdout, with trailing whitespace stripped.

        Raises:
            Skip: when no host access is configured.
            CheckFailed: when the command cannot be executed or exits non-zero.
        """
        if not self.available:
            raise Skip("no --ssh target and no --host-stub")

        # EVERY host command runs from `/`, and that is load-bearing rather than tidy.
        #
        # `ssh root@<host>` starts in /root, which is 0550 root:root on the RHEL family. sudo keeps
        # the CALLER's working directory, so the moment a probe reaches the rootless containers --
        # `sudo -n -u <service-user> XDG_RUNTIME_DIR=... podman ps` -- sudo tries to chdir there as
        # that user and fails with
        #
        #     cannot chdir to /root: Permission denied
        #
        # The runtime-detection loop in `container_cli` swallows that (`2>/dev/null`), finds no
        # candidate, falls back to bare `podman`, and root's own podman has no containers. The
        # result is not an error: it is NINE checks reporting a perfectly healthy host as absent,
        # from the exact invocation the cutover runbook prescribes. Measured on the testing host
        # 2026-09-21 -- from /root the probe lists nothing, from / it lists every container.
        #
        # `cd /;` rather than `cd / &&` on purpose: `&&` binds looser than `|`, and a couple of
        # probes are pipelines. A semicolon cannot change how the command that follows parses.
        command = f"cd /; {command}"

        if self.stub:
            # A command LINE, not a path: the stub has to be launchable on every platform the
            # self-test runs on, and Windows cannot exec a .sh directly. Splitting it means the
            # caller writes `--host-stub "bash /path/to/stub.sh"` and the seam stays general.
            argv = shlex.split(self.stub) + [command]
        else:
            ssh = shutil.which("ssh")
            if not ssh:
                raise Skip("ssh not found on PATH")
            # The command is passed as one argument and ssh runs it through the remote login
            # shell. An earlier version wrapped it in `sh -c '...'`, which forbade single quotes
            # in every probe for no benefit -- ssh was already going to invoke a shell.
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
        """The command prefix that reaches THIS host's containers.

        The suite shelled out to a literal ``docker`` in eight places while
        ``ansible/roles/basetool_host`` installs podman, crun, netavark, aardvark-dns and passt --
        and no ``podman-docker`` shim. On a host this repository bootstraps there is no ``docker``
        binary at all, so the three checks written specifically to assert the new ADR-0189/0190
        posture were the ones that could never run against the runtime they were written for.

        It is detected, not configured, and detected by TRYING rather than by inferring. The first
        version of this looked the owning user up with
        ``ls /home/*/.config/containers/systemd/*.container``; ``/home/iri`` is ``0750`` and the
        runner is ``sysadm``, so the glob expanded to nothing, the fallback picked the current
        user, and bare ``podman`` answered ``no such object`` for eight healthy containers --
        every arm of the suite would have reported a dead stack. So each candidate is asked
        whether it can actually see containers, and the first that can is the answer.

        Measured on the testing host, 2026-09-18: all eight command shapes the suite uses
        (``ps --format``, ``inspect --format`` over ``.State.Pid``, ``.HostConfig.ReadonlyRootfs``,
        ``.NetworkSettings.Networks``, ``.NetworkSettings.Ports``, ``.Config.Env``) return
        identically under ``podman`` and ``docker``. ``logs`` does not -- see
        :meth:`container_log_cmd`.

        Returns:
            ``docker``, ``podman``, or a ``sudo -n -u <user> XDG_RUNTIME_DIR=… podman`` prefix for
            a rootless deployment the runner does not itself own.

        Raises:
            Skip: when no host access is configured, or the host has neither runtime.
        """
        if self._container_cli is None:
            probe = (
                "if command -v docker >/dev/null 2>&1 && docker ps >/dev/null 2>&1; then "
                "  echo docker; "
                "elif command -v podman >/dev/null 2>&1; then "
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
                raise Skip("this host has neither a docker nor a podman binary")
            self._container_cli = answer
        return self._container_cli

    def container_log_cmd(self, name: str, minutes: int) -> str:
        """A command that prints one container's logs, bounded by time.

        ``logs`` is the one shape that does **not** survive the runtime swap, which is why it has
        its own method instead of riding on :attr:`container_cli`. Measured on the testing host,
        2026-09-18: those Quadlet units ran with the ``journald`` log driver, and
        ``podman logs edge --since 60m`` returned **zero lines** for a container that was logging,
        while ``journalctl CONTAINER_NAME=edge`` over the same window returned 67 access-log
        lines. Swapping the binary alone would have left two checks reading an empty log and
        reporting on it.

        That measurement was then generalised into "podman's rootless default", and it is not one.
        The production host resolves ``k8s-file`` -- ``podman info`` says so and every container
        inspects to it -- where the journalctl form returns zero lines forever. Both defaults are
        real, neither is safe to assume, so the driver is now READ per container below.

        ``journalctl`` needs no ``sudo`` for this: the entries carry no ``_UID`` restriction and
        the runner (``sysadm``, in ``wheel``) read them unprivileged in the same measurement.

        Note this never merges stderr. The distinct-client-address count used ``2>&1`` and so
        counted nginx's own error log as clients -- on real data the distinct first fields include
        ``2026/09/17``, a date, from a ``[notice]`` line.

        Args:
            name: the container name.
            minutes: how far back to read.

        Returns:
            A shell command line printing the log, one line per entry, stdout only.
        """
        cli = self.container_cli
        if cli.endswith("docker"):
            return f"{cli} logs {name} --since {minutes}m 2>/dev/null"

        # ASK which driver this container has; do not assume one. `journalctl CONTAINER_NAME=` only
        # ever sees a container whose log driver is `journald`, and that is not podman's default
        # everywhere. The testing host used journald (measured 2026-09-18, which is where the
        # journalctl form came from); the PRODUCTION host uses `k8s-file` (measured 2026-09-22) and
        # has never returned a single line to that command -- `journalctl CONTAINER_NAME=edge`
        # without a time bound: zero, for the whole life of the host.
        #
        # What that cost is the reason this asks. `client-address-visible` read an empty log and
        # concluded "the request did not reach this edge", about an edge that was serving every
        # request on the machine -- the most misleading verdict the suite can produce, on the check
        # its own docstring calls "the check the whole suite exists for". The address was in fact
        # correct: `podman logs edge` showed the probe logged from the prober's real public IPv6.
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
        # Inconclusive -- and there is no safe default, because each form returns EMPTY rather than
        # an error against the other driver. Reading both is the only answer that cannot silently
        # say "nothing happened": every caller greps or counts distinct values, so a duplicate line
        # costs nothing and a missing one costs the check its meaning.
        return f"{{ {podman_logs}; {journal}; }}"

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
    # CARRY THE ERRNO. This used to raise OSError(f"..."), a single-argument OSError whose `errno`
    # is None -- and check_ipv6_reachable decides whether a failure is the DEPLOYMENT'S or THIS
    # MACHINE'S by reading exactly that attribute. The effect was that its skip path could never be
    # taken: run from a v4-only network, the suite reported `ipv6-reachable FAIL ... [Errno 101]
    # Network is unreachable` about four vhosts that were serving IPv6 correctly (measured
    # 2026-09-22, from a WSL host with no global v6 address and no route to any). A red that means
    # "the runner has no IPv6" and a red that means "the edge lost its AAAA" were indistinguishable,
    # which is the exact failure the check's own docstring exists to prevent.
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


def _san_covers(host: str, sans: Sequence[str]) -> bool:
    """Whether any DNS SAN entry covers this host name.

    Implements the wildcard rule of RFC 6125 §6.4.3 rather than comparing strings: a leftmost
    ``*`` matches exactly one label, so ``*.example.com`` covers ``api.example.com`` but neither
    ``example.com`` itself nor ``a.b.example.com``. Comparison is case-insensitive and ignores a
    trailing dot.

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
            suffix = san[1:]  # ".example.com"
            if h.endswith(suffix) and "." not in h[: -len(suffix)]:
                return True
    return False


def _parse_address(text: str) -> ipaddress._BaseAddress | None:
    """Parse one log field into an address, unwrapping the IPv4-mapped IPv6 form.

    Args:
        text: a candidate address, as it appears as the first field of an access-log line.

    Returns:
        The address, with ``::ffff:a.b.c.d`` reduced to ``a.b.c.d``, or ``None`` when the text is
        not an address at all -- which on real data includes ``2026/09/17``, the first field of
        every nginx ``[notice]`` line.
    """
    candidate = text.strip().lower().rstrip(",")
    if not candidate:
        return None
    candidate = candidate.split("%", 1)[0]  # fe80::1%eth0 -- drop the zone id
    try:
        parsed = ipaddress.ip_address(candidate)
    except ValueError:
        return None
    mapped = getattr(parsed, "ipv4_mapped", None)
    return mapped or parsed


def _is_private(address: str) -> bool:
    """Whether an address is one a first-hop edge must never report as a client.

    This compared PREFIX STRINGS until 2026-09-18, and so classified ``::ffff:172.28.15.10`` --
    the IPv4-mapped IPv6 form -- as **public**: none of ``"10."``, ``"172."…``, ``"127."``,
    ``"fd"``, ``"::1"`` is a prefix of it. That is precisely the shape a single dual-stack bind
    produces, and `check_client_address_visible`, documented as "the check the whole suite exists
    for", therefore returned PASS on the exact collapse it was written to catch.

    Parsing the address instead of its spelling removes the whole class: ``ipaddress`` knows that
    ``::ffff:10.9.0.14`` is ``10.9.0.14``, and that ``10.9.0.14`` is private, without a table of
    text prefixes that has to enumerate ``"172.2"`` to cover ``172.20``–``172.29``.

    Args:
        address: an IPv4 or IPv6 address in text form, in any spelling.

    The ranges are named explicitly rather than deferred to ``ipaddress.is_private``, which is a
    wider question than this one: it answers "is this address special-purpose in any registry",
    and so covers the RFC 5737 documentation ranges -- ``203.0.113.0/24`` and friends -- that this
    repository's own fixtures use as stand-ins for a *public* client. A check that calls
    ``203.0.113.42`` a bridge address is a false red on every test host.

    Returns:
        ``True`` for loopback, RFC 1918, RFC 4193 and link-local addresses -- and for anything
        that is not an address at all, because a first-hop edge reporting a non-address as its
        client is not a passing state either.
    """
    parsed = _parse_address(address)
    if parsed is None:
        return True
    if parsed.is_loopback or parsed.is_link_local or parsed.is_unspecified:
        return True
    return any(parsed in net for net in NEVER_A_CLIENT if net.version == parsed.version)


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

    ``acme`` obtains ONE certificate whose first ``-d`` names it and whose SAN list carries the
    rest, then publishes that same pair under every host directory. If two vhosts serve
    different leaves, the publish loop reached one and not the other - which is exactly the
    defect REQ-OPS-026 exists for, and it is invisible until the unreached one expires.

    This is a property of *this deployment issuing its own certificates*, not a universal one.
    An environment whose certificate is provided rather than issued sets ``ACME_HOSTS`` empty,
    which makes the acme container idle by design, and may legitimately serve several - the
    testing host serves a wildcard for its subdomains and a separate one for the apex. So the
    check reads that variable and skips rather than inventing a finding.

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
            # Only ACME_HOSTS is extracted, on the host. The same environment carries
            # ACME_EMAIL, and an address must not cross into this process or the report.
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


# DO NOT add an up-front "can this machine route IPv6?" probe here. One was written on
# 2026-09-22 and removed the same hour: it connected a UDP socket to a documentation address
# (2001:db8::1) and skipped the whole check on ENETUNREACH, which asks about GLOBAL routing -- while
# the self-test's fixture serves on ::1, where global routing is absent and irrelevant. Every ipv6
# scenario in the suite turned from pass/fail into skip, including the one that proves the check can
# go red at all.
#
# It also passed locally and failed in CI, which is the part worth remembering: Windows lets that
# UDP connect succeed and Linux does not, so the probe's verdict depended on the operating system
# rather than on the network.
#
# The question is answered where it is actually asked -- by the real connection attempt, whose
# errno _connect now preserves. That is the whole fix.


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

    That separation was written from the start and did not work until 2026-09-22: ``_connect``
    raised a single-argument ``OSError`` whose ``errno`` was ``None``, so the branch that reads the
    errno could never be taken and the suite reported ``FAIL ... [Errno 101] Network is
    unreachable`` about four vhosts that were serving IPv6 correctly. Preserving the errno is the
    whole fix; see the comment above this function for the up-front probe that was tried instead and
    why it must not come back.

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
                # exc.strerror, not exc: the wrapped message already carries "[Errno 101]", and
                # str(OSError(errno, msg)) prefixes it a second time.
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

    # The container log, never tail: /var/log/nginx/access.log inside the container is a symlink
    # to /dev/stdout, so tail/grep/wc block on a pipe that never ends. Always bounded by time, and
    # always read through HostRunner.container_log_cmd -- under podman's journald driver
    # `podman logs` returns nothing at all, measured.
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
    # Compare the PARSED addresses, not the spellings. `(":" in a) == (":" in b)` was a test for
    # "same family" that reads `::ffff:10.9.0.14` as IPv6 and `10.9.0.14` as IPv4, so it skipped
    # the cross-check on the one pairing where the two are the same address written two ways.
    if ssh_parsed is not None and logged is not None and ssh_parsed.version == logged.version:
        if ssh_parsed != logged:
            raise CheckFailed(
                f"the edge logged {first_field} but this machine reaches the host from "
                f"{ssh_addr} - the address is being rewritten in flight")
        matched = ", and it matches our SSH source"

    # Counted here rather than with `cut | sort -u | wc -l` on the host, and over stdout only.
    # The old pipeline merged stderr, so nginx's error log contributed distinct first fields --
    # on this deployment's real log the distinct set includes `2026/09/17`, the date that opens
    # every [notice] line. A fully collapsed edge logging exactly ONE client address still reached
    # `count >= 2` that way, and the check reported green on the failure it was added to catch.
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

    This exists because of a specific production defect, on 2026-07-10. Redis is started with
    ``--aclfile``, and once that is in play the file is the source of truth for **every** user
    including ``default`` -- a file omitting a ``default`` entry makes Redis reset it to
    ``nopass ~* &* +@all`` at load. Production shipped exactly that, leaving the session store,
    OAuth2 refresh tokens included, readable and writable with no authentication on the internal
    network.

    ``--requirepass`` did not save it and never could: measured against ``redis:8-alpine`` on
    2026-09-16, an ACL file without a ``default`` line leaves Redis open **whether or not**
    ``--requirepass`` is given, and an ACL file with one wins over it. It was removed on that
    evidence, which makes the ACL file the whole of the protection -- and makes asserting it worth
    a check rather than a runbook line nobody runs.

    The probe needs **no credential**: it opens a socket and sends ``PING``. A Redis that answers
    ``+PONG`` to that is open; one that answers ``-NOAUTH`` is doing its job. Nothing is written,
    and no password is read from anywhere.

    Args:
        ctx: the run context.

    Returns:
        A summary naming what Redis answered.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when Redis answers an unauthenticated PING, or cannot be found.
    """
    # Everything is reported on stdout with exit 0, so the outcome is read from the ANSWER rather
    # than from an exception. The first draft raised on a non-zero exit and then matched the reason
    # out of the error text -- which happily matched the marker in the command it was quoting, and
    # reported "no redis container" for what was really a read timeout.
    #
    # `head -n 1`, not `head -c N`: Redis keeps the connection open, so a byte count blocks until
    # it is reached and -NOAUTH is shorter than any sensible count. That cost one confusing run.
    #
    # **The ping is sent from inside the container now**, and that is a fix. It used to read the
    # container's IP out of `inspect` and open /dev/tcp to it FROM THE HOST -- fine on Docker, whose
    # bridge is host-visible. Under rootless Podman the container network is in a user namespace and
    # the host has no route into it: measured on the testing host 2026-09-21, this check did not
    # fail, it HUNG, and was killed by the runner's 45-second timeout. The one check whose purpose
    # is catching unauthenticated access to the session store could not run at all.
    #
    # `redis-cli` is an unauthenticated client here and -NOAUTH is a protocol-level answer, so the
    # assertion is unchanged in substance: it is still "an anonymous client is refused". What is
    # lost is the proof that the PORT is closed to the network, and nothing here proved that anyway
    # -- the network segmentation is `edge-not-directly-reachable`'s and the firewall's job.
    #
    # `env -u REDISCLI_AUTH` is not decoration. redis-cli reads that variable and would authenticate
    # itself, turning the answer into +PONG and this check into a PASS on exactly the state it
    # exists to catch. Measured: the container carries REDIS_PASSWORD but not REDISCLI_AUTH, so it
    # does not happen today -- and a check that is only correct until someone adds an environment
    # variable is not correct.
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

    Prometheus is not published on the host: it lives on ``net-monitoring-core`` and its web
    server is basic-auth protected over **plain http** - ``https://`` fails with exit 35 and
    prints nothing, which reads exactly like a quoting failure and is not one.

    **The query is asked from INSIDE the container, and that is a fix rather than a style.** This
    used to read the container's IP out of ``inspect`` and curl it FROM THE HOST, which works on
    Docker because its bridge is host-visible. Under rootless Podman the container network lives in
    a user namespace and the host has no route into it at all: measured on the testing host
    2026-09-21, ``inspect`` returned ``10.89.0.22`` and a curl to it timed out after six seconds.
    Three checks read Prometheus - ``scrape-targets-up``, ``container-metrics``, ``log-streams`` -
    and all three failed that way, at the two cutover steps that exist to catch exactly this.

    ``wget`` rather than curl because the Prometheus image carries no curl, and the query is
    percent-encoded HERE rather than handed to ``--data-urlencode``: doing it in Python keeps the
    shell fragment free of spaces and quotes, which is what makes it safe to pass through
    ``sh -c``. The password is read inside the container from its own mounted secret, so it never
    crosses the SSH boundary and never reaches this process.

    Args:
        ctx: the run context.
        query: the PromQL expression.

    Returns:
        The decoded ``/api/v1/query`` response.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when Prometheus cannot be reached or does not answer ``status: success``.

    Args:
        ctx: the run context.
        query: the PromQL expression. Must contain no single quote.

    Returns:
        The decoded ``/api/v1/query`` response.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when Prometheus cannot be reached or does not answer ``status: success``.
    """
    # The secret as the CONTAINER sees it, not as the host does. The host copy lives at
    # /var/iri/monitoring/secrets/prometheus_web_password and is mounted here.
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
        # Match on what the RUNTIME says, not on a sentinel of our own. The previous version
        # tested `"NO_PROMETHEUS_ADDRESS" in str(exc)` -- and CheckFailed's message quotes the
        # command, which itself contained `echo NO_PROMETHEUS_ADDRESS`. Every failure of this
        # helper, including a plain timeout, therefore reported "no running prometheus container".
        # That is what sent two separate investigations after a container that was running fine.
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

    Each row is ``(container_start, host_start, count)``, the triple ``/proc/<pid>/uid_map``
    prints. A container with no user namespace carries the identity map, so the same code answers
    for rootful Docker and for rootless Podman without having to know which it is looking at.

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

    This exists because of a measurement on 2026-09-16 that went the wrong way. Dropping redis's
    ``SETUID``/``SETGID`` does **not** stop it: its entrypoint tests ``has_cap setuid && has_cap
    setgid`` and, finding neither, skips the privilege drop and carries on as root. The container
    is up, answers ``PING``, and passes ``containers-running``. It then writes its append-only
    files as ``0:0``, and the correct configuration afterwards refuses to start on them. A
    hardening change that reads as a success is how that happens, and no other check here notices.

    Read from the HOST, without ``docker exec``: the container's pid, that pid's real uid, and its
    ``uid_map``. Translating the host uid back through the map gives the uid **as the container
    sees it**, which is the number that matters -- and makes the check identical on a rootful
    Docker host, where the map is the identity, and on a rootless Podman one, where the same
    container uid appears as a subuid. On Podman it therefore asserts the translation as well.

    Args:
        ctx: the run context.

    Returns:
        A summary naming each container and the container-side uid it runs as.

    Raises:
        Skip: when no host access is configured.
        CheckFailed: when a container is absent, unreadable, or running as a uid other than the
            one the units and the bootstrap role agree on.
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

    Read-only is not defence in depth here so much as a statement about what the image is allowed
    to become: a container that cannot rewrite its own installation cannot be persistently
    modified by anything that gets inside it, and the next restart is the image again.

    Measured service by service before it was required of them (section 21 of the migration plan).
    Nine of the ten third-party images write nothing outside their mounts or write only under
    /tmp; a healthy Spring Boot module writes Tomcat's work directory, its docbase and the JVM
    perf data, all three under /tmp. Podman mounts /run, /tmp and /var/tmp as tmpfs under
    ``--read-only`` and copies the image's content up into them, which is why none of them needs
    an explicit tmpfs -- and why this is expressed in the Quadlet units rather than in the compose
    file, since Docker does not do that.

    ``keycloak`` needed two passes. Plain read-only stops its start-time Quarkus re-augmentation
    dead, and the first reading of that was that it could not have a read-only root filesystem at
    all. A tmpfs over the one directory it rewrites -- with ``tmpcopyup``, so the image content is
    there -- costs 4.7M and works, because that augmentation was already being thrown away at
    every start.

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
    for names in REQUIRED_CONTAINER_SERIES:
        # `count(a) or count(b)`: the first family that has samples answers, and a host is healthy
        # if EITHER does. `or` and not a sum, because the two never coexist -- one runtime emits
        # one of them -- and a sum would hide a half-populated family behind the other.
        query = " or ".join(f"count({name})" for name in names)
        series = names[0]
        payload = _promql(ctx, query)
        results = payload.get("data", {}).get("result", [])
        if not results:
            missing.append("/".join(names))
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


def check_trace_pipeline(ctx: Context) -> str:
    """Spans reach Alloy's OTLP receiver, when the apps are configured to emit any.

    The trace path has no alert of any kind -- not on `otelcol_receiver_accepted_spans_total`, not
    on `tempo_distributor_spans_received_total` -- so a broken one reports nothing anywhere. It is
    also the path most likely to break on a runtime change, because the apps address Alloy by a
    NAME and a service that moves to the host stops answering to it. Measured on the testing host
    on 2026-09-20: both counters ABSENT rather than zero, i.e. the pipeline had never carried a
    single span, while every dashboard and every alert reported a healthy monitoring plane.

    Absent is deliberately treated as the failure and not as "nothing yet". These are counters
    Alloy creates on the first span it accepts; the apps emit on every request, so on a host that
    serves at all, absence means the spans are being dropped before they arrive -- in each app's own
    exporter, where nothing looks.

    The check reads `MONITORING_TRACING_ENABLED` from the host `.env` first and skips when tracing
    is off, because an app that is not emitting is not a fault. That is one grep for one name, in
    the same narrow shape `check_env_reaches_the_units` uses so the rest of the credential set never
    crosses the SSH boundary.

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
        # Same distinction as check_env_reaches_the_units, and one more: an ABSENT file, an
        # UNREADABLE one and one without the key all produce nothing here. Run against the
        # migration target before its first deploy, this reported ".env is not readable by this
        # SSH account" about a file that simply did not exist yet -- a true statement that points
        # at the wrong problem, and the kind that sends someone checking sudo rules for an hour.
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


#: The compose variables that `scripts/generate-quadlet.py` BAKES into the units at generation
#: time, mapped to the unit directive each one ends up in. Quadlet performs no interpolation, so a
#: `${VAR}` reaching a unit file would be written there literally -- the generator therefore
#: resolves each one to the value the promoted bundle carries.
#:
#: That is correct, and it has a consequence nothing states: setting any of these in the host
#: `.env` does NOTHING. The variable is in the file, it looks effective, and the unit ignores it.
#: Three separate hours were spent on this in one day -- IRI_KEYCLOAK_HOST_ALIAS (a container
#: timing out against its own issuer), IRI_TRUSTSTORE_HOST_PATH (PKIX failures against a private
#: CA) and IRI_BASETOOL_VERSION (harmless only because both tags happened to point at one digest).
#: Each looked like a different problem.
BAKED_INTO_UNITS = {
    "IRI_IMAGE_NAMESPACE": "Image=",
    "IRI_BASETOOL_VERSION": "Image=",
    "IRI_KEYCLOAK_HOST_ALIAS": "AddHost=",
    "IRI_KEYSTORE_HOST_PATH": "Volume=",
    "IRI_TRUSTSTORE_HOST_PATH": "Volume=",
    "IRI_REDIS_ACL_HOST_PATH": "Volume=",
    "IRI_UPSTREAM_CA_HOST_PATH": "Volume=",
}


def check_env_reaches_the_units(ctx: Context) -> str:
    """A value set in the host `.env` must actually be in the units, or be absent from both.

    Under compose, every one of these variables reached the container because compose interpolated
    the file at `up` time. Under Quadlet there is no interpolation: the generator resolves them
    once, into a promotable artifact that is the same on every host. A host that sets one of them
    in its `.env` is therefore writing a line with no effect -- and the failure that follows is
    never about a variable. It is a container that will not start, a PKIX error, or a service
    quietly running the wrong image.

    The check does not demand that the units follow the `.env`; the units are deliberately
    host-independent. It demands that the two do not **disagree silently**. A host that needs a
    different value supplies a systemd drop-in, and the drop-in is what this reads -- so the
    intended mechanism passes and the trap fails.

    Args:
        ctx: the run context.

    Returns:
        A summary naming each variable and where its effective value came from.

    Raises:
        Skip: when no host access is configured, or the host carries no `.env`.
        CheckFailed: when the `.env` sets a value that neither the unit nor a drop-in carries.
    """
    # Only the seven keys this check is about, filtered ON THE HOST -- the same shape
    # check_certificate_shared already uses. `cat /var/iri/code/.env` pulled the whole production
    # credential set across the SSH boundary into this process, and although nothing here could
    # print one (BAKED_INTO_UNITS holds image tags, a host alias and four paths, and the message
    # loop iterates only over those), the narrower read means the values never arrive at all. The
    # names are module constants, so nothing caller-supplied reaches the pattern.
    wanted = "|".join(sorted(BAKED_INTO_UNITS))
    env_raw = ctx.runner.run(
        f"grep -E '^({wanted})=' /var/iri/code/.env 2>/dev/null || true")
    if not env_raw.strip():
        if not ctx.runner.run("test -f /var/iri/code/.env && echo yes || true").strip():
            raise Skip("no /var/iri/code/.env on this host")
        # `test -r`, not `test -f`, and the distinction is the whole of this branch. The grep above
        # ends in `|| true`, so it produces empty output both when the file HAS none of these keys
        # and when the runner could not open it at all -- and .env is 0640 deploy:deploy, which an
        # ordinary login account cannot read. Measured 2026-09-20: run as `sysadm` against the
        # testing host, this check reported "sets none of the variables" about a file that sets
        # IRI_KEYCLOAK_HOST_ALIAS. A check that draws a conclusion from a file it could not open is
        # the exact failure this one exists to report about others.
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

    # The effective unit text INCLUDING drop-ins, which is what podman actually generates from.
    #
    # NOT `~`. The first version of this used it and skipped with "no Quadlet units on this host"
    # against a host that had thirty-nine of them: `~` is the home of whoever the runner executes
    # as, and on a rootless deployment the units live in the DEPLOY user's home while the runner
    # may be root or another account entirely. A check that looks in the wrong place does not fail,
    # it skips -- which is indistinguishable from a host that has nothing to check.
    #
    # Both locations, no assumption about the user: /etc for a rootful install, every home for a
    # rootless one.
    #
    # THREE locations, and the third is the one that matters on this deployment. Added 2026-09-20:
    # podman-systemd.unit(5) lists /etc/containers/systemd/users/$(UID) as a rootless search path,
    # and it is where `deploy.sh` installs and where the role writes its drop-ins -- because it is
    # the only one of the four an account other than the owner can write. Neither glob above
    # matched it, so this check skipped with "no Quadlet units found" against a host carrying 39
    # units and two drop-ins. The same shape as the `~` bug the comment above already records,
    # one directory deeper.
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
            continue  # unset in the .env: the unit's baked default is the only claim, and it stands
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
    """The edge's own port must be reachable from nothing but the front end.

    This is the invariant ADR-0187 rests on, and it is the one that turns the decision into its
    opposite when it slips. The front end hands the edge the client's address in a PROXY protocol
    header, and the edge believes it -- because the header **asserts** a source address, it does not
    prove one. So if anything else can open a connection to the edge's port, it can invent a client
    address, and walk past the per-client rate limiter (``REQ-SEC-023``) and the Keycloak admin
    allow-list, which are exactly the two controls the front end exists to preserve.

    The property that closes it is that the container publishes on **loopback only**. That is one
    line in the unit file, it is invisible in every screenshot of a working system, and nothing about
    a healthy stack would reveal its absence. Hence a check rather than a comment.

    The probe deliberately runs **from the host**, against the host's own routable addresses rather
    than against ``127.0.0.1``: loopback is where the port is *supposed* to answer. A connection that
    succeeds there proves nothing, and one that succeeds on the public address proves the invariant
    is gone.

    Args:
        ctx: the run context.

    Returns:
        A summary naming the addresses that were tried and refused.

    Raises:
        Skip: when no host access is configured, or the edge does not publish on loopback at all --
            which is the pre-ADR-0187 shape and not a failure of this check.
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

    # Every routable address the host has, v4 and v6 -- not a hardcoded one. A host gains an
    # interface and the check has to follow it, or it proves the invariant for the address somebody
    # thought of in 2026 and not for the one that was added later.
    addrs = ctx.runner.run(
        "ip -o addr show scope global | awk '{print $4}' | cut -d/ -f1"
    ).split()
    if not addrs:
        raise Skip("the host reports no global address to probe from")

    reachable = []
    for addr in addrs:
        target = f"[{addr}]" if ":" in addr else addr
        for port in ("8080", "8443"):
            # curl's EXIT CODE, not its %{http_code}. The question here is whether a TCP
            # connection can be established from a routable address -- not whether HTTP came back
            # over it. `%{http_code}` answers `000` for both "connection refused" and "connected,
            # then the server said nothing usable", and the second is exactly what :8443 does when
            # it speaks PROXY protocol at a client that does not: an edge fully reachable from the
            # internet read as refused.
            #
            #   7  could not connect        -> refused or filtered. This is the passing state.
            #   28 operation timed out      -> filtered. Also passing.
            #   anything else, 0 included   -> the connection was ESTABLISHED, which is the defect;
            #                                  52 (empty reply) and 35 (TLS error) both land here.
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

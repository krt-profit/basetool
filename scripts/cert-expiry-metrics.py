#!/usr/bin/env python3
"""Profit Basetool - expiry metrics for certificates that no listener serves.

Every TLS certificate this deployment serves is already watched: the blackbox exporter probes it
and ``probe_ssl_earliest_cert_expiry`` feeds the ``CertificateExpiringSoon`` alert. That mechanism
has one blind spot, and it is structural rather than an oversight - **a probe can only see a
certificate something is serving.**

The internal CA is the case that matters. ``/var/iri/monitoring/certs/basetool-ca.crt`` is the trust
anchor for every ``proxy_ssl_verify on`` upstream at the edge and for the ``https_internal`` probe
module itself. Nothing listens on it, so nothing probes it, so nothing would have noticed it
expiring - and the day it does, every verified upstream fails at once AND the probes that would
otherwise have warned about the leaves fail with it. Measured 2026-09-20: it was the one certificate
in the monitoring plane with no coverage at all.

The same applies to any certificate placed in that directory for a service that is currently down.
Grafana's self-signed pair is probed through ``blackbox-internal-tls-grafana``, but only while
Grafana is running; the file is readable either way.

What it emits
-------------
::

    basetool_certificate_expiry_timestamp_seconds{path,subject,issuer,self_signed}
    basetool_certificate_not_before_timestamp_seconds{path,subject,issuer,self_signed}
    basetool_certificate_files
    basetool_certificate_metrics_timestamp_seconds

``self_signed="true"`` marks a certificate whose issuer equals its subject - a root CA or a
standalone self-signed leaf. The alert rules give those a longer lead time, because re-issuing a CA
means re-issuing everything it signed, which is not a fourteen-day job.

Label cardinality is bounded by the contents of a directory an operator provisions by hand
(REQ-OBS-011) - two files on the testing host today, the internal CA and Grafana's leaf.

Usage
-----
::

    # one shot, the way the systemd timer runs it
    python3 scripts/cert-expiry-metrics.py --output /var/iri/monitoring/textfile/certificates.prom

    # see what it would write, without writing
    python3 scripts/cert-expiry-metrics.py --dry-run

    # additional directories, repeatable
    python3 scripts/cert-expiry-metrics.py --dir /var/iri/monitoring/certs --dir /etc/pki/basetool

Exit codes: ``0`` wrote (or would have written) a file, ``1`` no certificate was found or the write
failed, ``2`` bad invocation. Finding no certificate is an error rather than an empty file, for the
same reason the cgroup collector treats it that way: an empty metrics file and a mistyped directory
look identical to Prometheus, and only one of them is benign.

PKCS#12 keystores are deliberately NOT read. They need a password, this collector must never hold
one, and the leaf inside them is served by backend, frontend, ingest and Keycloak - so the probes
already cover it.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from typing import Iterable, NamedTuple

#: Where an operator provisions the certificates this deployment does not serve.
DEFAULT_DIRS = ("/var/iri/monitoring/certs",)

#: What counts as a certificate file. `.key` is deliberately absent: this collector never opens
#: private material, and a key file carries no expiry of its own anyway.
SUFFIXES = (".crt", ".pem", ".cer")

METRIC = "basetool_certificate"

#: How a distinguished name is rendered into the ``subject`` and ``issuer`` labels.
#:
#: NOT openssl's default, and that is not a preference. Measured 2026-09-20 on the same certificate:
#:
#:     OpenSSL 3.0.13 (Ubuntu 24.04)   subject=C = DE, O = DAS KARTELL, CN = ... CA
#:     OpenSSL 3.5.7  (Rocky 10.2)     subject=C=DE, O=DAS KARTELL, CN=... CA
#:
#: Same file, different label value. Prometheus identifies a series BY its labels, so an openssl
#: upgrade under a running host would silently retire every certificate series and start new ones -
#: the old set going stale exactly like a collector that stopped, which is the state
#: ``CertificateMetricsStale`` exists to report and would not, because the timestamp keeps moving.
#:
#: RFC 2253 is defined by the RFC rather than by the tool, supported since OpenSSL 1.x, and escapes
#: its own special characters. It also reverses the order to most-specific-first
#: (``CN=...,OU=...,O=...,C=DE``), which is what the RFC says and what LDAP tooling expects.
NAME_FORMAT = "RFC2253"

HELP = {
    "expiry_timestamp_seconds": (
        "gauge",
        "Unix time at which the certificate stops being valid (notAfter).",
    ),
    "not_before_timestamp_seconds": (
        "gauge",
        "Unix time at which the certificate started being valid (notBefore).",
    ),
}


class CollectorError(Exception):
    """A failure that should end the run with a message rather than a traceback."""


class Cert(NamedTuple):
    """One parsed certificate."""

    path: str
    subject: str
    issuer: str
    not_after: int
    not_before: int

    @property
    def self_signed(self) -> bool:
        """Whether the certificate signed itself.

        Returns:
            True when issuer and subject are identical, which is what a root CA and a standalone
            self-signed leaf have in common and what earns the longer alerting lead time.
        """
        return self.subject == self.issuer


def _escape(value: str) -> str:
    """Escape a Prometheus label value.

    Args:
        value: the raw label value, which for a subject or issuer contains commas and spaces and
            may contain a backslash or a quote.

    Returns:
        The value with backslash, double quote and newline escaped, per the exposition format.
    """
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _openssl(path: str, *args: str) -> str:
    """Run ``openssl x509`` against one file and return its stdout.

    openssl rather than a Python X.509 library on purpose: `cryptography` is not installed on the
    target host and this collector must not add a dependency to a box whose whole point is that it
    runs a fixed, audited set of packages. openssl is present because the platform ships it.

    Args:
        path: the certificate file.
        *args: the flags after ``-noout``.

    Returns:
        Standard output, stripped.

    Raises:
        CollectorError: when openssl is absent or the file is not a certificate.
    """
    # Callers asking for a DN get RFC 2253, never openssl's default -- see NAME_FORMAT.
    try:
        proc = subprocess.run(
            ["openssl", "x509", "-in", path, "-noout", *args],
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
    except FileNotFoundError as exc:
        raise CollectorError("openssl is not on PATH") from exc
    except subprocess.TimeoutExpired as exc:
        raise CollectorError(f"openssl timed out on {path}") from exc
    if proc.returncode != 0:
        raise CollectorError(f"{path}: not a PEM certificate ({proc.stderr.strip()})")
    return proc.stdout.strip()


def _parse_date(raw: str) -> int:
    """Turn an openssl ``notAfter=...`` line into a Unix timestamp.

    Args:
        raw: the openssl output line, e.g. ``notAfter=Dec 23 10:17:48 2028 GMT``.

    Returns:
        The instant as a Unix timestamp.

    Raises:
        CollectorError: when the line is not in the shape openssl documents.
    """
    _, _, value = raw.partition("=")
    value = value.strip()
    if not value:
        raise CollectorError(f"no date in {raw!r}")
    # openssl prints GMT and nothing else for these fields, so the parse is fixed rather than
    # locale-dependent. calendar.timegm, not mktime: the latter would apply the HOST's timezone to
    # a value that is explicitly GMT, which is a silent offset of up to a day near an expiry.
    import calendar

    try:
        parsed = time.strptime(value, "%b %d %H:%M:%S %Y %Z")
    except ValueError as exc:
        raise CollectorError(f"unparseable date {value!r}") from exc
    return calendar.timegm(parsed)


def read_cert(path: str) -> Cert:
    """Read one certificate file.

    Args:
        path: the file to read.

    Returns:
        The parsed certificate.

    Raises:
        CollectorError: when the file cannot be read or is not a certificate.
    """
    subject = _openssl(path, "-subject", "-nameopt", NAME_FORMAT).partition("=")[2].strip()
    issuer = _openssl(path, "-issuer", "-nameopt", NAME_FORMAT).partition("=")[2].strip()
    not_after = _parse_date(_openssl(path, "-enddate"))
    not_before = _parse_date(_openssl(path, "-startdate"))
    return Cert(path=path, subject=subject, issuer=issuer,
                not_after=not_after, not_before=not_before)


def discover(dirs: Iterable[str]) -> list[str]:
    """Find every certificate file under the given directories.

    Args:
        dirs: directories to walk. A directory that does not exist is skipped rather than fatal,
            because the same unit runs on hosts at different stages of provisioning.

    Returns:
        Sorted absolute paths, so the output is stable between runs and a diff of two scrapes shows
        a real change rather than a reordering.
    """
    found: list[str] = []
    for directory in dirs:
        if not os.path.isdir(directory):
            continue
        for entry in sorted(os.listdir(directory)):
            if entry.endswith(SUFFIXES):
                found.append(os.path.join(directory, entry))
    return sorted(found)


def render(certs: list[Cert], now: int) -> str:
    """Render the exposition text.

    Args:
        certs: the certificates to report.
        now: the timestamp to stamp the run with.

    Returns:
        The full exposition text, ending in a newline.
    """
    lines: list[str] = []
    for suffix in ("expiry_timestamp_seconds", "not_before_timestamp_seconds"):
        kind, help_text = HELP[suffix]
        lines.append(f"# HELP {METRIC}_{suffix} {help_text}")
        lines.append(f"# TYPE {METRIC}_{suffix} {kind}")
        for cert in certs:
            labels = (
                f'path="{_escape(cert.path)}",'
                f'subject="{_escape(cert.subject)}",'
                f'issuer="{_escape(cert.issuer)}",'
                f'self_signed="{str(cert.self_signed).lower()}"'
            )
            value = cert.not_after if suffix.startswith("expiry") else cert.not_before
            lines.append(f"{METRIC}_{suffix}{{{labels}}} {value}")
    lines.append(f"# HELP {METRIC}_files Certificate files this collector read.")
    lines.append(f"# TYPE {METRIC}_files gauge")
    lines.append(f"{METRIC}_files {len(certs)}")
    lines.append(
        f"# HELP {METRIC}_metrics_timestamp_seconds Unix time of the last successful collection."
    )
    lines.append(f"# TYPE {METRIC}_metrics_timestamp_seconds gauge")
    lines.append(f"{METRIC}_metrics_timestamp_seconds {now}")
    return "\n".join(lines) + "\n"


def write_atomically(target: str, content: str) -> None:
    """Write the exposition file so no scrape ever sees it half-written.

    node_exporter reads the whole textfile directory on every scrape, so a partially written file
    is a parse error served to Prometheus. Writing a sibling and renaming makes the swap atomic;
    the sibling goes in the same directory because ``os.replace`` is only atomic within one
    filesystem.

    Args:
        target: the final path, conventionally ending in ``.prom``.
        content: the rendered exposition text.

    Raises:
        CollectorError: when the file cannot be written.
    """
    directory = os.path.dirname(os.path.abspath(target)) or "."
    tmp = os.path.join(directory, f".{os.path.basename(target)}.{os.getpid()}.tmp")
    try:
        with open(tmp, "w", encoding="ascii", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, target)
    except OSError as exc:
        # Best-effort cleanup, and its failure is deliberately swallowed: this path is already
        # handling a failed write, and the two ways the unlink can fail are both uninteresting --
        # the sibling was never created (the `open` itself failed), or the directory is not
        # writable, which is the same fact `exc` already carries. Raising from here would replace
        # the diagnosis with a symptom, so the ORIGINAL error is re-raised on the next line and a
        # stray `.tmp` is left for `ls` to show. node_exporter ignores it: the textfile collector
        # reads `*.prom` only.
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise CollectorError(f"cannot write {target}: {exc}") from exc


def main(argv: list[str] | None = None) -> int:
    """Entry point.

    Args:
        argv: command-line arguments, defaulting to ``sys.argv[1:]``.

    Returns:
        ``0`` wrote or would have written, ``1`` nothing found or the write failed, ``2`` bad
        invocation.
    """
    parser = argparse.ArgumentParser(prog="cert-expiry-metrics.py")
    parser.add_argument("--dir", action="append", dest="dirs", default=None,
                        help=f"directory to scan, repeatable (default: {', '.join(DEFAULT_DIRS)})")
    parser.add_argument("--output", help="the .prom file to write")
    parser.add_argument("--dry-run", action="store_true",
                        help="print what would be written and write nothing")
    args = parser.parse_args(argv)

    if not args.output and not args.dry_run:
        parser.error("one of --output or --dry-run is required")

    dirs = args.dirs or list(DEFAULT_DIRS)
    paths = discover(dirs)
    if not paths:
        print(f"cert-expiry-metrics: no certificate found under {', '.join(dirs)}", file=sys.stderr)
        return 1

    certs: list[Cert] = []
    failures = 0
    for path in paths:
        try:
            certs.append(read_cert(path))
        except CollectorError as exc:
            # One unreadable file must not cost the coverage of the others: a directory holding a
            # stray text file is a configuration mistake, and a CA that goes unwatched because of
            # it would be the expensive kind.
            print(f"cert-expiry-metrics: skipping {path}: {exc}", file=sys.stderr)
            failures += 1

    if not certs:
        print("cert-expiry-metrics: no file under the scanned directories parsed as a certificate",
              file=sys.stderr)
        return 1

    content = render(certs, int(time.time()))
    if args.dry_run:
        sys.stdout.write(content)
        return 0
    try:
        write_atomically(args.output, content)
    except CollectorError as exc:
        print(f"cert-expiry-metrics: {exc}", file=sys.stderr)
        return 1
    print(f"wrote {len(certs)} certificate(s) to {args.output}"
          + (f" ({failures} skipped)" if failures else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())

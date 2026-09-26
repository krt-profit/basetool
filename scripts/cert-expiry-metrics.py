#!/usr/bin/env python3
"""Profit Basetool - expiry metrics for certificates that no listener serves.

Reads PEM certificate files (such as the internal CA) from disk, since a blackbox probe only sees
certificates a listener serves. PKCS#12 keystores are not read.

What it emits
-------------
::

    basetool_certificate_expiry_timestamp_seconds{path,subject,issuer,self_signed}
    basetool_certificate_not_before_timestamp_seconds{path,subject,issuer,self_signed}
    basetool_certificate_files
    basetool_certificate_metrics_timestamp_seconds

``self_signed="true"`` marks a certificate whose issuer equals its subject. Label cardinality is
bounded by the hand-provisioned directory contents (REQ-OBS-011).

Usage
-----
::

    python3 scripts/cert-expiry-metrics.py --output /var/iri/monitoring/textfile/certificates.prom

    python3 scripts/cert-expiry-metrics.py --dry-run

    python3 scripts/cert-expiry-metrics.py --dir /var/iri/monitoring/certs --dir /etc/pki/basetool

Exit codes: ``0`` wrote (or would have written) a file, ``1`` no certificate was found or the write
failed, ``2`` bad invocation.
"""

from __future__ import annotations

import argparse
import contextlib
import os
import subprocess
import sys
import time
from typing import Iterable, NamedTuple

DEFAULT_DIRS = ("/var/iri/monitoring/certs",)

SUFFIXES = (".crt", ".pem", ".cer")

METRIC = "basetool_certificate"

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
            True when issuer and subject are identical.
        """
        return self.subject == self.issuer


def _escape(value: str) -> str:
    """Escape a Prometheus label value.

    Args:
        value: the raw label value.

    Returns:
        The value with backslash, double quote and newline escaped, per the exposition format.
    """
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _openssl(path: str, *args: str) -> str:
    """Run ``openssl x509`` against one file and return its stdout.

    Args:
        path: the certificate file.
        *args: the flags after ``-noout``.

    Returns:
        Standard output, stripped.

    Raises:
        CollectorError: when openssl is absent or the file is not a certificate.
    """
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
        dirs: directories to scan; a missing directory is skipped.

    Returns:
        Sorted paths of files with a certificate suffix.
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
    """Write the exposition file via a sibling temporary file and an atomic rename.

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
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise CollectorError(f"cannot write {target}: {exc}") from exc


def main(argv: list[str] | None = None) -> int:
    """Scan the directories and write (or print) the certificate metrics.

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

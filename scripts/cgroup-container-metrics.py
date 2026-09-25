#!/usr/bin/env python3
"""Profit Basetool - container metrics read straight from cgroup v2.

Reads CPU, throttling, OOM-kill, memory and pids values of each rootless-Podman container (and
selected host services) from ``/sys/fs/cgroup`` and writes ``basetool_container_*`` series
(REQ-OBS-011) for node_exporter's textfile collector. Needs no container socket.

Usage
-----
::

    python3 scripts/cgroup-container-metrics.py --output /var/iri/monitoring/textfile/containers.prom

    python3 scripts/cgroup-container-metrics.py --dry-run

    python3 scripts/cgroup-container-metrics.py --cgroup-root /tmp/fake --pattern '(?P<name>[^/]+)$'

Exit codes: ``0`` wrote (or would have written) a file, ``1`` nothing matched or the write failed,
``2`` bad invocation.
"""

from __future__ import annotations

import argparse
import math
import os
import re
import sys
import time
from typing import Iterator, Sequence

DEFAULT_PATTERN = (
    r"user\.slice/user-\d+\.slice/user@\d+\.service/(?:app\.slice/)?(?P<name>[^/]+)\.service$"
)

HEALTHCHECK_UNIT = re.compile(r"^[0-9a-f]{64}-[0-9a-f]+$")

PAYLOAD_PREFIX = "libpod-payload-"

DEFAULT_CGROUP_ROOT = "/sys/fs/cgroup"

DEFAULT_HOST_SERVICES = (
    "alloy.service=alloy",
    "prometheus-node-exporter.service=node-exporter",
)

COLLECTOR_PREFIX = "basetool_container_metrics"


class CollectorError(Exception):
    """Raised when the collector cannot produce a meaningful file."""


def _read(path: str) -> str | None:
    """Read one cgroup file, tolerating a vanished or unreadable file.

    Args:
        path: absolute path to the cgroup file.

    Returns:
        The file's contents, or ``None`` when it is absent or unreadable.
    """
    try:
        with open(path, encoding="ascii") as handle:
            return handle.read()
    except (FileNotFoundError, PermissionError, OSError):
        return None


def _keyed(text: str | None) -> dict[str, str]:
    """Parse a cgroup file of ``key value`` lines.

    Args:
        text: the file contents, or ``None``.

    Returns:
        A mapping of key to raw value; empty when ``text`` is ``None``.
    """
    out: dict[str, str] = {}
    for line in (text or "").splitlines():
        parts = line.split()
        if len(parts) >= 2:
            out[parts[0]] = parts[1]
    return out


def _number(raw: str | None) -> float | None:
    """Interpret a cgroup scalar, mapping the literal ``max`` to positive infinity.

    Args:
        raw: the file's contents, or ``None``.

    Returns:
        The value as a float, ``math.inf`` for ``max``, or ``None`` when unparseable.
    """
    if raw is None:
        return None
    raw = raw.strip()
    if raw == "max":
        return math.inf
    try:
        return float(raw)
    except ValueError:
        return None


def discover(cgroup_root: str, pattern: re.Pattern[str]) -> Iterator[tuple[str, str]]:
    """Walk the cgroup tree and yield the container cgroups it holds.

    Args:
        cgroup_root: the cgroup v2 mount point, or a fake tree under test.
        pattern: a compiled regex with a ``name`` group, matched against each directory's path
            relative to ``cgroup_root`` with forward slashes.

    Yields:
        ``(name, absolute_path)`` for each matching directory, sorted by name; healthcheck units
        (:data:`HEALTHCHECK_UNIT`) are skipped.
    """
    found: list[tuple[str, str]] = []
    for dirpath, _dirnames, _files in os.walk(cgroup_root):
        rel = os.path.relpath(dirpath, cgroup_root).replace(os.sep, "/")
        if rel == ".":
            continue
        match = pattern.search(rel)
        if match and not HEALTHCHECK_UNIT.match(match.group("name")):
            found.append((match.group("name"), dirpath))
    yield from sorted(found)


def container_cgroup(unit_path: str) -> str:
    """Resolve a container unit's cgroup to the one that carries the container's limits.

    Args:
        unit_path: the ``<name>.service`` cgroup directory :func:`discover` found.

    Returns:
        The ``libpod-payload-<id>`` child (:data:`PAYLOAD_PREFIX`) that holds a process, else the
        first by name; ``unit_path`` itself when there is no payload child.
    """
    try:
        children = sorted(entry for entry in os.listdir(unit_path)
                          if entry.startswith(PAYLOAD_PREFIX)
                          and os.path.isdir(os.path.join(unit_path, entry)))
    except OSError:
        return unit_path
    payloads = [os.path.join(unit_path, child) for child in children]
    if not payloads:
        return unit_path
    for payload in payloads:
        if (_read(os.path.join(payload, "cgroup.procs")) or "").strip():
            return payload
    return payloads[0]


def discover_host_services(cgroup_root: str, specs: Sequence[str]) -> list[tuple[str, str]]:
    """Resolve ``UNIT=NAME`` host-service specs to the unit cgroups that exist on this host.

    Args:
        cgroup_root: the cgroup v2 mount point, or a fake tree under test.
        specs: ``UNIT=NAME`` strings, e.g. ``alloy.service=alloy``, looked up under ``system.slice``.

    Returns:
        ``(name, absolute_path)`` for each unit whose cgroup directory exists, in spec order.

    Raises:
        CollectorError: when a spec is not of the form ``UNIT=NAME`` with both halves non-empty.
    """
    found: list[tuple[str, str]] = []
    for spec in specs:
        unit, sep, name = spec.partition("=")
        if not sep or not unit or not name or "/" in unit:
            raise CollectorError(f"--host-service expects UNIT=NAME, got {spec!r}")
        path = os.path.join(cgroup_root, "system.slice", unit)
        if os.path.isdir(path):
            found.append((name, path))
    return found


def sample(path: str) -> dict[str, float]:
    """Read one container's cgroup files into a metric mapping.

    Args:
        path: the container's cgroup directory.

    Returns:
        Metric suffix to value, omitting anything the kernel did not publish.
    """
    out: dict[str, float] = {}

    cpu = _keyed(_read(os.path.join(path, "cpu.stat")))
    for key, suffix, scale in (
        ("usage_usec", "cpu_usage_seconds_total", 1e-6),
        ("nr_periods", "cpu_periods_total", 1.0),
        ("nr_throttled", "cpu_throttled_periods_total", 1.0),
        ("throttled_usec", "cpu_throttled_seconds_total", 1e-6),
    ):
        value = _number(cpu.get(key))
        if value is not None:
            out[suffix] = value * scale

    events = _keyed(_read(os.path.join(path, "memory.events")))
    oom_kill = _number(events.get("oom_kill"))
    if oom_kill is not None:
        out["oom_kills_total"] = oom_kill

    mem = _keyed(_read(os.path.join(path, "memory.stat")))
    anon = _number(mem.get("anon"))
    if anon is not None:
        out["memory_anon_bytes"] = anon

    file_mapped = _number(mem.get("file_mapped"))
    if file_mapped is not None:
        out["memory_mapped_file_bytes"] = file_mapped

    current = _number(_read(os.path.join(path, "memory.current")))
    inactive_file = _number(mem.get("inactive_file"))
    if current is not None:
        out["memory_usage_bytes"] = current
        if inactive_file is not None:
            out["memory_working_set_bytes"] = max(0.0, current - inactive_file)

    limit = _number(_read(os.path.join(path, "memory.max")))
    if limit is not None:
        out["memory_limit_bytes"] = limit

    pids_current = _number(_read(os.path.join(path, "pids.current")))
    if pids_current is not None:
        out["pids"] = pids_current
    pids_max = _number(_read(os.path.join(path, "pids.max")))
    if pids_max is not None:
        out["pids_max"] = pids_max

    return out


HELP = {
    "cpu_usage_seconds_total": ("counter", "Cumulative CPU time consumed, from cpu.stat usage_usec."),
    "cpu_periods_total": ("counter", "Elapsed CFS enforcement periods, from cpu.stat nr_periods."),
    "cpu_throttled_periods_total": ("counter", "CFS periods in which the cgroup was throttled."),
    "cpu_throttled_seconds_total": ("counter", "Time the cgroup spent throttled, from throttled_usec."),
    "oom_kills_total": ("counter", "Processes killed by the OOM killer, from memory.events oom_kill."),
    "memory_anon_bytes": ("gauge", "Anonymous memory in use, from memory.stat anon. The cgroup v2 analogue of RSS."),
    "memory_usage_bytes": ("gauge", "Total memory charged to the cgroup, from memory.current."),
    "memory_working_set_bytes": ("gauge", "memory.current minus reclaimable page cache, as cAdvisor computed it."),
    "memory_mapped_file_bytes": ("gauge", "Mapped file pages, from memory.stat file_mapped -- binaries and libraries."),
    "memory_limit_bytes": ("gauge", "Memory ceiling from memory.max; +Inf when unlimited."),
    "pids": ("gauge", "Processes and threads in the cgroup, from pids.current."),
    "pids_max": ("gauge", "Process ceiling from pids.max; +Inf when unlimited."),
}


def _escape(value: str) -> str:
    """Escape a Prometheus label value.

    Args:
        value: the raw label value.

    Returns:
        The value with backslashes, quotes and newlines escaped per the exposition format.
    """
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _format(value: float) -> str:
    """Render a sample value for the exposition format.

    Args:
        value: the sample.

    Returns:
        ``+Inf`` for infinity, a bare integer for an integral value, otherwise ``repr``.
    """
    if value == math.inf:
        return "+Inf"
    if value == float("-inf") or math.isnan(value):
        return "-Inf" if value == float("-inf") else "NaN"
    if float(value).is_integer() and abs(value) < 2 ** 53:
        return str(int(value))
    return repr(value)


def render(samples: Sequence[tuple[str, dict[str, float]]], now: float,
           containers: int | None = None, host_services: int = 0) -> str:
    """Render the exposition-format text node_exporter will serve.

    Metrics are grouped by name with a single ``# HELP`` / ``# TYPE`` pair each.

    Args:
        samples: ``(name, metrics)`` pairs, containers and host services alike.
        now: the collection timestamp, as a Unix time.
        containers: how many of ``samples`` are containers; ``None`` means all of them.
        host_services: how many of ``samples`` are host services.

    Returns:
        The complete file contents, ending in a newline.
    """
    lines: list[str] = []
    for suffix, (kind, help_text) in HELP.items():
        present = [(name, values[suffix]) for name, values in samples if suffix in values]
        if not present:
            continue
        metric = f"basetool_container_{suffix}"
        lines.append(f"# HELP {metric} {help_text}")
        lines.append(f"# TYPE {metric} {kind}")
        for name, value in present:
            lines.append(f'{metric}{{name="{_escape(name)}"}} {_format(value)}')

    lines.append(f"# HELP {COLLECTOR_PREFIX}_containers Containers this collector found.")
    lines.append(f"# TYPE {COLLECTOR_PREFIX}_containers gauge")
    container_count = len(samples) if containers is None else containers
    lines.append(f"{COLLECTOR_PREFIX}_containers {container_count}")
    lines.append(f"# HELP {COLLECTOR_PREFIX}_host_services "
                 "Host services (systemd system units) this collector found.")
    lines.append(f"# TYPE {COLLECTOR_PREFIX}_host_services gauge")
    lines.append(f"{COLLECTOR_PREFIX}_host_services {host_services}")
    lines.append(f"# HELP {COLLECTOR_PREFIX}_timestamp_seconds When this file was last written.")
    lines.append(f"# TYPE {COLLECTOR_PREFIX}_timestamp_seconds gauge")
    lines.append(f"{COLLECTOR_PREFIX}_timestamp_seconds {now:.3f}")
    return "\n".join(lines) + "\n"


def write_atomically(target: str, content: str) -> None:
    """Write the metrics file via a sibling temporary file and an atomic rename.

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
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise CollectorError(f"could not write {target}: {exc}") from exc


def collect(cgroup_root: str, pattern: str) -> list[tuple[str, dict[str, float]]]:
    """Discover containers and sample each one.

    Args:
        cgroup_root: the cgroup v2 mount point, or a fake tree under test.
        pattern: the regex selecting container cgroups, with a ``name`` group.

    Returns:
        ``(name, metrics)`` for every container that published at least one value.

    Raises:
        CollectorError: when the pattern is invalid, the root is missing, or nothing matched.
    """
    return _containers(cgroup_root, pattern, required=True)


def _containers(cgroup_root: str, pattern: str,
                required: bool) -> list[tuple[str, dict[str, float]]]:
    """Discover and sample the container cgroups, optionally insisting on finding one.

    Args:
        cgroup_root: the cgroup v2 mount point, or a fake tree under test.
        pattern: the regex selecting container cgroups, with a ``name`` group.
        required: raise when nothing matched, rather than returning an empty list.

    Returns:
        ``(name, metrics)`` for every container that published at least one value.

    Raises:
        CollectorError: when the pattern is invalid, the root is missing, or - with ``required`` -
            nothing matched.
    """
    if not os.path.isdir(cgroup_root):
        raise CollectorError(f"cgroup root {cgroup_root} is not a directory")
    try:
        compiled = re.compile(pattern)
    except re.error as exc:
        raise CollectorError(f"invalid --pattern: {exc}") from exc
    if "name" not in (compiled.groupindex or {}):
        raise CollectorError("--pattern must contain a (?P<name>...) group")

    samples = [(name, sample(container_cgroup(path)))
               for name, path in discover(cgroup_root, compiled)]
    samples = [(name, values) for name, values in samples if values]
    if not samples and required:
        raise CollectorError(
            f"no container cgroups matched under {cgroup_root} - an empty metrics file and a "
            "broken pattern look identical to Prometheus, so this is an error rather than a "
            "file with nothing in it")
    return samples


def collect_all(cgroup_root: str, pattern: str, host_services: Sequence[str]
                ) -> tuple[list[tuple[str, dict[str, float]]], int, int]:
    """Sample the containers and the named host services together.

    Finding no container is allowed while a host service was found.

    Args:
        cgroup_root: the cgroup v2 mount point, or a fake tree under test.
        pattern: the regex selecting container cgroups, with a ``name`` group.
        host_services: ``UNIT=NAME`` specs, see :data:`DEFAULT_HOST_SERVICES`.

    Returns:
        ``(samples, container_count, host_service_count)``; containers first, then host services.

    Raises:
        CollectorError: when the pattern or a spec is invalid, the root is missing, or neither a
            container nor a host service was found.
    """
    containers = _containers(cgroup_root, pattern, required=False)
    taken = {name for name, _ in containers}
    hosts = [(name, sample(path))
             for name, path in discover_host_services(cgroup_root, host_services)
             if name not in taken]
    hosts = [(name, values) for name, values in hosts if values]
    if not containers and not hosts:
        raise CollectorError(
            f"no container cgroups matched under {cgroup_root} and no host service was found - an "
            "empty metrics file and a broken pattern look identical to Prometheus, so this is an "
            "error rather than a file with nothing in it")
    return containers + hosts, len(containers), len(hosts)


def main(argv: Sequence[str] | None = None) -> int:
    """Collect the samples and write (or print) the metrics file.

    Args:
        argv: arguments without the program name, or ``None`` to read ``sys.argv``.

    Returns:
        ``0`` on success, ``1`` when nothing could be collected or written.
    """
    parser = argparse.ArgumentParser(
        prog="cgroup-container-metrics.py",
        description="Read container metrics from cgroup v2 for node_exporter's textfile collector.")
    parser.add_argument("--cgroup-root", default=DEFAULT_CGROUP_ROOT,
                        help=f"cgroup v2 mount point (default: {DEFAULT_CGROUP_ROOT})")
    parser.add_argument("--pattern", default=DEFAULT_PATTERN,
                        help="regex with a (?P<name>...) group, matched against each cgroup "
                             "directory's path relative to --cgroup-root")
    parser.add_argument("--host-service", action="append", metavar="UNIT=NAME",
                        help="a systemd system unit to read as well, published as NAME; repeat "
                             "for several (default: " + ", ".join(DEFAULT_HOST_SERVICES) + ")")
    parser.add_argument("--no-host-services", action="store_true",
                        help="read containers only")
    parser.add_argument("--output", metavar="PATH",
                        help="the .prom file to write; omit with --dry-run")
    parser.add_argument("--dry-run", action="store_true",
                        help="render to stdout instead of writing")
    args = parser.parse_args(list(sys.argv[1:] if argv is None else argv))

    if not args.dry_run and not args.output:
        parser.error("--output is required unless --dry-run is given")

    host_specs: Sequence[str] = ()
    if not args.no_host_services:
        host_specs = args.host_service or DEFAULT_HOST_SERVICES

    try:
        samples, containers, hosts = collect_all(args.cgroup_root, args.pattern, host_specs)
        content = render(samples, time.time(), containers=containers, host_services=hosts)
        if args.dry_run:
            sys.stdout.write(content)
        else:
            write_atomically(args.output, content)
            print(f"wrote {containers} container(s) and {hosts} host service(s) to {args.output}")
    except CollectorError as exc:
        print(f"cgroup-container-metrics: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

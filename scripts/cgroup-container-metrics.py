#!/usr/bin/env python3
"""Profit Basetool - container metrics read straight from cgroup v2.

Phase 4 of ``docs/archive/PODMAN_MIGRATION_PLAN.md``. Under rootless Podman there is no cAdvisor: its
rootless-Podman issue upstream is closed as not planned, and `prometheus-podman-exporter` publishes
a strict subset of what the alert rules read. Three signals have no exporter equivalent at all -
OOM kills, CPU throttling, and the pids ceiling.

All three are in the kernel, and always were. cAdvisor never had privileged access to anything
`/sys/fs/cgroup` does not publish; it read these files and gave them Docker's names. So this reads
them directly and writes them where node_exporter's **textfile collector** will pick them up - a
mechanism this deployment already runs (``/var/iri/monitoring/textfile``, which ``deploy.sh``
writes ``basetool_monitoring_config_applied_timestamp`` into).

It needs no container socket and no privilege beyond reading `/sys/fs/cgroup`, which is a security
gain over the GET-only Docker-socket proxy cAdvisor and Alloy reach through today.

What it replaces
----------------
===========================================  =================================================
cAdvisor series                              this collector
===========================================  =================================================
``container_oom_events_total``               ``basetool_container_oom_kills_total``
``container_cpu_cfs_periods_total``          ``basetool_container_cpu_periods_total``
``container_cpu_cfs_throttled_periods_total``  ``basetool_container_cpu_throttled_periods_total``
``container_cpu_cfs_throttled_seconds_total``  ``basetool_container_cpu_throttled_seconds_total``
``container_threads``                        ``basetool_container_pids``
``container_threads_max``                    ``basetool_container_pids_max``
``container_memory_rss``                     ``basetool_container_memory_anon_bytes``
``container_memory_working_set_bytes``       ``basetool_container_memory_working_set_bytes``
``container_memory_mapped_file``             ``basetool_container_memory_mapped_file_bytes``
``container_spec_memory_limit_bytes``        ``basetool_container_memory_limit_bytes``
``container_cpu_usage_seconds_total``        ``basetool_container_cpu_usage_seconds_total``
===========================================  =================================================

The names are deliberately **not** cAdvisor's. Two reasons: the project's own convention is that
host-emitted series are ``basetool_*`` (REQ-OBS-011), and the semantics are not identical -
``container_threads`` counts threads while ``pids.current`` counts processes-and-threads in the
cgroup. Reusing the old name would hide that difference behind a familiar label, and the alert
expressions have to be rewritten anyway because the Compose label vocabulary disappears with
Compose.

Usage
-----
::

    # one shot, the way the systemd timer runs it
    python3 scripts/cgroup-container-metrics.py --output /var/iri/monitoring/textfile/containers.prom

    # see what it would write, without writing
    python3 scripts/cgroup-container-metrics.py --dry-run

    # against a different tree and naming scheme (this is how the self-test drives it)
    python3 scripts/cgroup-container-metrics.py --cgroup-root /tmp/fake --pattern '(?P<name>[^/]+)$'

Exit codes: ``0`` wrote (or would have written) a file, ``1`` nothing matched or the write failed,
``2`` bad invocation. Finding no containers is an error rather than an empty file, because an empty
metrics file and a broken pattern look identical to Prometheus and only one of them is benign.
"""

from __future__ import annotations

import argparse
import math
import os
import re
import sys
import time
from typing import Iterator, Sequence

#: Where the rootless Quadlet containers live. A Quadlet ``foo.container`` generates a user unit
#: ``foo.service``, so the unit name in the cgroup path IS the container name - which is why this
#: needs no container runtime to resolve a label. ``app.slice`` is optional because systemd only
#: interposes it for some unit types.
DEFAULT_PATTERN = (
    r"user\.slice/user-\d+\.slice/user@\d+\.service/(?:app\.slice/)?(?P<name>[^/]+)\.service$"
)

#: Podman's healthcheck runs as a transient systemd unit named ``<64-hex container id>-<random
#: hex>.service``, in the same ``app.slice`` as the Quadlet units, so :data:`DEFAULT_PATTERN`
#: matches it. It lives for the length of one probe. Until 2026-09-22 every run the timer happened
#: to catch became a "container" of its own: the dashboard legends filled with 80-character hex
#: names, sorted ahead of the real ones, and each drew a single dot. No container name can take
#: this shape - Quadlet units are named after the compose service - so it is excluded outright,
#: whatever ``--pattern`` says.
HEALTHCHECK_UNIT = re.compile(r"^[0-9a-f]{64}-[0-9a-f]+$")

#: The child cgroup that holds the container's own processes under Quadlet's default
#: ``--cgroups=split``. The unit's cgroup ``<name>.service`` is only the parent: it holds this
#: payload and a ``runtime`` sibling for conmon, and podman writes the container's ``memory.max``,
#: ``pids.max`` and ``cpu.max`` onto the payload, never onto the unit. Measured on production
#: 2026-09-22: ``backend.service`` read ``memory.max max``, ``pids.max 97670`` and ``nr_periods 0``
#: while its payload read 2 GiB, 2048 and 6370 periods with 12 throttled.
PAYLOAD_PREFIX = "libpod-payload-"

DEFAULT_CGROUP_ROOT = "/sys/fs/cgroup"

#: The monitoring plane's two HOST services, read from their own system-unit cgroups and published
#: under the names they had as containers (``UNIT=NAME``). Added 2026-09-22.
#:
#: Under Compose, ``alloy`` and ``node-exporter`` were containers, so the container memory, OOM and
#: pids alerts watched them for free - and alloy's memory history (192 -> 256 -> 384 -> 512M) is
#: exactly what those alerts caught. Under Podman both became host services (generate-quadlet.py
#: says why), which took them out of every container cgroup this collector walks: from the cutover
#: on, nothing watched either one's memory, and nothing capped it. A systemd unit's cgroup carries
#: the same files a container's does, so reading it is the whole fix; the names are kept so every
#: existing alert, dashboard panel and inhibit rule keyed on ``name`` means what it meant before.
#: A unit that is not on this host is skipped, not an error - a Docker host has neither.
DEFAULT_HOST_SERVICES = (
    "alloy.service=alloy",
    "prometheus-node-exporter.service=node-exporter",
)

#: Emitted alongside the per-container series so the collector's own silence is visible. A
#: textfile that stops being rewritten is otherwise indistinguishable from a quiet system:
#: node_exporter keeps serving the last file it read, forever.
COLLECTOR_PREFIX = "basetool_container_metrics"


class CollectorError(Exception):
    """Raised when the collector cannot produce a meaningful file."""


# ============================================================================================
# cgroup reading
# ============================================================================================
def _read(path: str) -> str | None:
    """Read one cgroup file, tolerating the races a live cgroup tree produces.

    A container can exit between the directory walk and the read, and a delegated subtree can
    deny a file the caller is not allowed to see. Neither is worth failing the whole run for.

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

    ``memory.max`` and ``pids.max`` hold the string ``max`` when no limit is set. Infinity is the
    honest translation: a ratio against it is zero, so a headroom alert written as
    ``used / limit > 0.9`` simply never fires for an unlimited container, which is correct. A
    sentinel such as ``-1`` or ``0`` would make that same alert fire constantly or divide by zero.

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
            **relative to** ``cgroup_root``, with forward slashes on every platform.

    Yields:
        ``(name, absolute_path)`` for each matching directory, sorted by name so the output file
        is byte-stable between runs that saw the same containers. A Podman healthcheck's transient
        unit (:data:`HEALTHCHECK_UNIT`) is never yielded, even when the pattern matches it.
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

    Under ``--cgroups=split`` (see :data:`PAYLOAD_PREFIX`) that is the ``libpod-payload-<id>``
    child, and every value is read there - usage as well as limits, so a ratio never divides the
    container-plus-conmon usage by the container-only limit. It is also exactly the cgroup cAdvisor
    measured under Docker, so the numbers keep their meaning across the cutover.

    Args:
        unit_path: the ``<name>.service`` cgroup directory :func:`discover` found.

    Returns:
        The payload child when there is one. During a recreate an old and a new payload can exist
        side by side for a moment; the first one that still holds a process wins, and the first by
        name when none does. A unit with no payload child - a container started without split
        cgroups, or a plain user service - is returned unchanged.
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
        specs: ``UNIT=NAME`` strings, e.g. ``alloy.service=alloy``; the unit is looked up under
            ``system.slice``, where systemd places every system service.

    Returns:
        ``(name, absolute_path)`` for each unit whose cgroup directory exists, in spec order. A unit
        that is absent is left out silently: it is not running, or it is not a service here at all.

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

    Every value here was verified against a running container on 2026-09-16 rather than taken
    from documentation, including the two that are not obvious: ``cpu.stat`` carries
    ``nr_periods`` even when no CPU limit is set (it reads 0), and ``memory.max`` / ``pids.max``
    carry the literal string ``max`` when unlimited.

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

    # memory.events is the ONLY place an OOM kill is counted. The container may be running
    # happily right now and still carry a non-zero count from an earlier kill, which is exactly
    # what makes it a useful counter rather than a state.
    events = _keyed(_read(os.path.join(path, "memory.events")))
    oom_kill = _number(events.get("oom_kill"))
    if oom_kill is not None:
        out["oom_kills_total"] = oom_kill

    mem = _keyed(_read(os.path.join(path, "memory.stat")))
    anon = _number(mem.get("anon"))
    if anon is not None:
        out["memory_anon_bytes"] = anon

    # The third series of the dashboard's memory breakdown, which explains the gap between anon and
    # the working set: mapped executables and libraries are page cache, so they count toward the
    # working set and toward no process's RSS. The panel asked cAdvisor's
    # `container_memory_mapped_file` for it, and cAdvisor is deleted on this runtime, so the series
    # was simply absent -- indistinguishable from a container that maps nothing.
    file_mapped = _number(mem.get("file_mapped"))
    if file_mapped is not None:
        out["memory_mapped_file_bytes"] = file_mapped

    current = _number(_read(os.path.join(path, "memory.current")))
    inactive_file = _number(mem.get("inactive_file"))
    if current is not None:
        out["memory_usage_bytes"] = current
        if inactive_file is not None:
            # cAdvisor's working set is current minus reclaimable page cache. Clamped at zero
            # because the two files are read a moment apart and can disagree under churn.
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


# ============================================================================================
# Rendering
# ============================================================================================
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

    Prometheus accepts ``2.0`` and ``2`` alike, but an integral counter reads better - and stays
    shorter - without the decimal tail, and a float keeps full precision through ``repr`` rather
    than losing digits to a fixed format.

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

    Metrics are grouped by name with a single ``# HELP`` / ``# TYPE`` pair each, because
    node_exporter rejects a textfile that repeats a type declaration for the same metric - a
    per-container layout parses fine by eye and is refused at load.

    Args:
        samples: ``(name, metrics)`` pairs, containers and host services alike.
        now: the collection timestamp, as a Unix time.
        containers: how many of ``samples`` are containers; ``None`` means all of them. Kept apart
            from the host services because ContainerCgroupCollectorFoundNothing reads it as "the
            pattern still matches the container layout", which two host services must not mask.
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
    """Write the metrics file so a scrape never sees it half-written.

    node_exporter reads the whole directory on every scrape, so a partially written file is a
    parse error served to Prometheus. Writing a sibling and renaming makes the swap atomic; the
    sibling is created in the same directory because ``os.replace`` is only atomic within one
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
        try:
            os.unlink(tmp)
        except OSError:
            # Best effort. The temp file is in the same directory as the target and will be
            # overwritten by the next run; failing to unlink it must not replace the error that
            # actually matters, which is raised below.
            pass
        raise CollectorError(f"could not write {target}: {exc}") from exc


# ============================================================================================
# CLI
# ============================================================================================
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

    A host with its stack deliberately down but its host services up still gets a file - with
    ``containers 0``, which is what ContainerCgroupCollectorFoundNothing exists to report - rather
    than no file at all. Only a host where NOTHING matched is still an error.

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
    """Entry point.

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

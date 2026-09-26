#!/usr/bin/env python3
"""Enforce REQ-OPS-019: a forking healthcheck requires a zombie-reaping PID 1.

Fails when a compose service runs a BusyBox ``wget`` ``https://`` healthcheck (which forks an
``ssl_client`` per probe) without ``init: true``. The probe is the compose ``healthcheck.test``,
else the ``HEALTHCHECK`` of this repository's own Dockerfile stage.

Usage:
    python3 .github/scripts/check_pid1_reaping.py [--selftest]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]

APP_DOCKERFILE = "docker/app/Dockerfile"
IMAGE_DOCKERFILES = {
    "basetool-backend": (APP_DOCKERFILE, "runtime-backend"),
    "basetool-frontend": (APP_DOCKERFILE, "runtime-frontend"),
    "basetool-ingest": (APP_DOCKERFILE, "runtime-ingest"),
}


class ComposeLoader(yaml.SafeLoader):
    """SafeLoader that unwraps unknown tags such as Compose's ``!override`` and ``!reset``."""


def _construct_unknown(loader: ComposeLoader, _suffix: str, node):
    """Unwrap any unrecognised YAML tag to the plain value it decorates.

    :param loader: the active loader instance.
    :param _suffix: the unmatched tag suffix, ignored.
    :param node: the tagged node to construct.
    :return: the node's value as a scalar, list or dict.
    """
    if isinstance(node, yaml.SequenceNode):
        return loader.construct_sequence(node)
    if isinstance(node, yaml.MappingNode):
        return loader.construct_mapping(node)
    return loader.construct_scalar(node)


ComposeLoader.add_multi_constructor("", _construct_unknown)


def probe_forks_tls_helper(probe: str) -> bool:
    """Report whether a healthcheck command spawns a TLS helper process (``wget`` over ``https://``).

    :param probe: the healthcheck command line, already flattened to a single string.
    :return: ``True`` when the command is a ``wget`` fetch of an ``https://`` URL.
    """
    return "wget" in probe and "https://" in probe


def flatten_probe(test) -> str | None:
    """Flatten a compose ``healthcheck.test`` value into one command string.

    A leading ``CMD`` / ``CMD-SHELL`` marker is dropped.

    :param test: the raw ``healthcheck.test`` value, or ``None`` when absent.
    :return: the flattened command, or ``None`` when there is no probe or it is explicitly ``NONE``.
    """
    if test is None:
        return None
    if isinstance(test, str):
        return test
    if isinstance(test, list):
        if not test or str(test[0]).upper() == "NONE":
            return None
        parts = test[1:] if str(test[0]).upper() in {"CMD", "CMD-SHELL"} else test
        return " ".join(str(p) for p in parts)
    return None


def stage_text(dockerfile: str, stage: str) -> str | None:
    """Return the instructions of one named build stage, line continuations already joined.

    :param dockerfile: the Dockerfile's full text.
    :param stage: the stage name after ``AS`` (case-insensitive, as Docker treats it).
    :return: the text from that stage's ``FROM`` line up to the next ``FROM``, or ``None`` when no
        stage carries that name.
    """
    joined = re.sub(r"\\\r?\n\s*", " ", dockerfile)
    stages = re.split(r"(?m)^(?=FROM\s)", joined)
    for chunk in stages:
        header = re.match(r"FROM\s+\S+\s+AS\s+(\S+)", chunk, re.IGNORECASE)
        if header and header.group(1).lower() == stage.lower():
            return chunk
    return None


def dockerfile_probe(image: str) -> str | None:
    """Return the ``HEALTHCHECK`` command baked into one of this repo's own images.

    :param image: the compose ``image:`` value, which may carry a registry, namespace and tag.
    :return: the image's healthcheck command, or ``None`` for third-party or un-probed images.
    """
    for marker, (rel, stage) in IMAGE_DOCKERFILES.items():
        if marker not in image:
            continue
        chunk = stage_text((REPO_ROOT / rel).read_text(encoding="utf-8"), stage)
        if chunk is None:
            return None
        match = re.search(r"^HEALTHCHECK\b(.*)$", chunk, re.MULTILINE)
        if not match:
            return None
        cmd = re.search(r"\bCMD\b(.*)", match.group(1), re.DOTALL)
        return cmd.group(1).strip() if cmd else None
    return None


def collect_services() -> dict[str, dict]:
    """Merge every root compose file into one service view.

    Later files override earlier ones per key; anchors and ``<<`` merge keys are resolved.

    :return: service name mapped to ``{"init": bool | None, "probe": str | None, "files": [...]}``.
    """
    merged: dict[str, dict] = {}
    for path in sorted(REPO_ROOT.glob("docker-compose*.yml")):
        doc = yaml.load(path.read_text(encoding="utf-8"), Loader=ComposeLoader) or {}
        for name, svc in (doc.get("services") or {}).items():
            if not isinstance(svc, dict):
                continue
            entry = merged.setdefault(name, {"init": None, "probe": None, "image": None, "files": []})
            entry["files"].append(path.name)
            if "init" in svc:
                entry["init"] = bool(svc["init"])
            if svc.get("image"):
                entry["image"] = str(svc["image"])
            health = svc.get("healthcheck")
            if isinstance(health, dict):
                if health.get("disable") is True:
                    entry["probe"] = None
                else:
                    probe = flatten_probe(health.get("test"))
                    if probe:
                        entry["probe"] = probe
    return merged


def find_violations(services: dict[str, dict]) -> list[tuple[str, str]]:
    """Select services whose probe forks a TLS helper while PID 1 cannot reap it.

    :param services: the merged service view from :func:`collect_services`.
    :return: ``(service name, offending probe)`` pairs; empty when the invariant holds.
    """
    violations = []
    for name, entry in sorted(services.items()):
        probe = entry["probe"]
        if probe is None and entry["image"]:
            probe = dockerfile_probe(entry["image"])
        if probe and probe_forks_tls_helper(probe) and entry["init"] is not True:
            violations.append((name, probe))
    return violations


def selftest() -> int:
    """Check the detector against forking and non-forking probes and the repository's own images.

    :return: process exit code -- ``0`` when every fixture behaves as specified.
    """
    cases = [
        ("wget -q -O /dev/null --no-check-certificate https://localhost:3000/api/health", True),
        ("wget -q --no-check-certificate -O /dev/null https://localhost:18091/actuator/health", True),
        ("wget -q -O /dev/null http://localhost:9093/-/ready", False),
        ("curl -fsS https://localhost:3000/api/health", False),
        ("exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /health/ready HTTP/1.1'", False),
        ("pg_isready -U user -d db", False),
    ]
    failures = 0
    for probe, expected in cases:
        actual = probe_forks_tls_helper(probe)
        status = "ok " if actual == expected else "FAIL"
        if actual != expected:
            failures += 1
        print(f"  [{status}] forks={actual!s:<5} expected={expected!s:<5} {probe[:60]}")

    bad = {"svc": {"init": None, "probe": cases[0][0], "image": None, "files": ["x"]}}
    good = {"svc": {"init": True, "probe": cases[0][0], "image": None, "files": ["x"]}}
    if len(find_violations(bad)) != 1:
        print("  [FAIL] a forking probe without init was not reported")
        failures += 1
    if find_violations(good):
        print("  [FAIL] a forking probe WITH init was wrongly reported")
        failures += 1

    own_port = {"basetool-backend": "11261", "basetool-frontend": "18081", "basetool-ingest": "11262"}
    for marker in IMAGE_DOCKERFILES:
        probe = dockerfile_probe(f"ghcr.io/krt-profit/{marker}:stable")
        if not probe or not probe_forks_tls_helper(probe):
            print(f"  [FAIL] {marker}: image HEALTHCHECK did not resolve to a forking probe ({probe!r})")
            failures += 1
        elif f"localhost:{own_port[marker]}/" not in probe:
            print(f"  [FAIL] {marker}: resolved another stage's probe ({probe!r})")
            failures += 1
        else:
            print(f"  [ok ] {marker}: image HEALTHCHECK resolved from its own stage -> forking probe")
    if stage_text("FROM a AS runtime-backend\nHEALTHCHECK CMD x\n", "runtime-frontend") is not None:
        print("  [FAIL] stage_text returned a stage that does not exist")
        failures += 1

    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main() -> int:
    """Run the repository check (or the self-test) and report the outcome.

    :return: process exit code -- ``0`` when the invariant holds, ``1`` on any violation.
    """
    if "--selftest" in sys.argv:
        return selftest()

    services = collect_services()
    violations = find_violations(services)
    print(f"Checked {len(services)} compose service(s) for REQ-OPS-019 PID-1 reaping.")
    if not violations:
        print("OK: every service with a forking (wget-HTTPS) healthcheck runs an init as PID 1.")
        return 0

    print("\nREQ-OPS-019 violation: forking healthcheck without a zombie-reaping PID 1.\n")
    for name, probe in violations:
        print(f"  service: {name}")
        print(f"    probe: {probe}")
        print("    fix:   add `init: true` to this service (or to the compose template it inherits)")
    print(
        "\nBusyBox wget forks an `ssl_client` per https:// probe. Without an init as PID 1 it is\n"
        "never reaped, and one zombie per probe fills the service's `pids` cgroup cap until the\n"
        "kernel refuses every fork (ingest 2026-07-12, grafana 2026-07-26). See REQ-OPS-019 in\n"
        "docs/specs/deployment-delivery.md."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())

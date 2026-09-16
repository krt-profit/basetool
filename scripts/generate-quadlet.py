#!/usr/bin/env python3
"""Profit Basetool - generate Quadlet units from the Compose files, and detect drift between them.

Phase 2 of ``docs/PODMAN_MIGRATION_PLAN.md``. Twenty-two services, nineteen networks and three
volumes become roughly forty unit files. Transcribing that by hand is how a digest pin or a network
membership goes quietly wrong, and nothing would notice: a unit that starts is not a unit that is
right.

So this generates them, and the same code detects drift. ``--check`` regenerates into memory and
diffs against the tree, which is what keeps the two representations honest for as long as both
exist. After the cutover the compose file retires and these units become the source; until then the
compose file is authoritative and this tool is the bridge.

What it refuses to do
---------------------
It **fails rather than guessing**, because a plausible-looking wrong unit is worse than no unit:

- an unresolved ``${VAR}`` in a volume path (Quadlet does not expand them - see the plan's §3.1);
- a multi-line ``command:``, which cannot become a single ``Exec=`` line and has to be extracted
  into a script file first;
- a service with no recorded disposition, so adding one to compose fails the build until somebody
  says whether it is a container, a host service, or deleted.

Usage
-----
::

    python scripts/generate-quadlet.py            # write quadlet/
    python scripts/generate-quadlet.py --check    # fail on drift, write nothing
    python scripts/generate-quadlet.py --list     # what happens to each service, and why

Exit codes: ``0`` clean, ``1`` drift or a refusal, ``2`` bad invocation.
"""

from __future__ import annotations

import argparse
import difflib
import io
import os
import re
import sys
from typing import Any, Sequence

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_UNITS = os.path.join(REPO, "quadlet", "systemd")
OUT_VARS = os.path.join(REPO, "quadlet", "env.d")

COMPOSE_APP = os.path.join(REPO, "docker-compose.yml")
COMPOSE_MON = os.path.join(REPO, "docker-compose.monitoring.yml")

#: Where the per-service environment files land on the host. The compose `environment:` maps are
#: CLOSED ALLOW-LISTS -- each service sees only the variables its own map names -- and that property
#: has to survive the translation. `EnvironmentFile=` with the whole `.env` would hand every
#: container every secret, so the deployer renders one file per service from the `.vars` list this
#: tool emits.
ENV_DIR_ON_HOST = "/var/iri/code/env.d"

#: Host paths the compose files reach through a variable. Quadlet units are static, so these are
#: resolved here -- to the values the production `.env` already sets. The plan's §3.1 records the
#: alternative (render the units at bundle-build time) and why this one was taken.
PATH_VARS = {
    "IRI_KEYSTORE_HOST_PATH": "/var/iri/secrets/keystore.p12",
    "IRI_TRUSTSTORE_HOST_PATH": "/var/iri/secrets/keystore.p12",
    "IRI_REDIS_ACL_HOST_PATH": "/var/iri/redis/users.acl",
    "IRI_UPSTREAM_CA_HOST_PATH": "/var/iri/monitoring/certs/basetool-ca.crt",
}

#: Relative bind-mount sources in compose resolve against the project directory.
PROJECT_DIR_ON_HOST = "/var/iri/code"

#: What becomes of each service. Every service in either compose file must appear here, or the tool
#: fails -- adding one to compose then blocks the build until somebody decides, which is the point.
DISPOSITION: dict[str, tuple[str, str]] = {
    # --- the application stack ---------------------------------------------------------------
    "edge": ("container", ""),
    "acme": ("container", ""),
    "keycloak": ("container", ""),
    "backend": ("container", ""),
    "frontend": ("container", ""),
    "ingest": ("container", ""),
    "db-backend": ("container", ""),
    "db-keycloak": ("container", ""),
    "redis": ("container", ""),
    # --- the monitoring plane ----------------------------------------------------------------
    "prometheus": ("container", ""),
    "grafana": ("container", ""),
    "loki": ("container", ""),
    "tempo": ("container", ""),
    "alertmanager": ("container", ""),
    "blackbox-exporter": ("container", ""),
    "postgres-exporter-backend": ("container", ""),
    "postgres-exporter-keycloak": ("container", ""),
    "redis-exporter": ("container", ""),
    "node-exporter": (
        "host-service",
        "mounts /run/systemd/private for its systemd collector, which a rootless container cannot "
        "reach -- and node_systemd_unit_state is exactly the signal this migration gains, because "
        "Quadlet units ARE systemd units. It also owns the textfile directory the cgroup collector "
        "writes into. As a packaged host service it needs none of those mounts.",
    ),
    "alloy": (
        "host-service",
        "mounts /var/log and carries group_add 4/473 to read root:adm files such as auth.log. A "
        "rootless container's supplementary groups are namespace groups, not host groups, so that "
        "read stops working. Running it on the host restores it and removes the last consumer of "
        "the container socket.",
    ),
    "cadvisor": (
        "deleted",
        "its rootless-Podman support is closed as not planned upstream. Its series come back from "
        "prometheus-podman-exporter plus scripts/cgroup-container-metrics.py -- see the plan's §10.",
    ),
    "socket-proxy": (
        "deleted",
        "it exists only to hand cAdvisor and Alloy a GET-only view of the Docker socket. There is "
        "no Docker socket.",
    ),
    # --- never on a production host ------------------------------------------------------------
    "npm": ("deleted", "the retired proxy, kept in compose's `rollback` profile only."),
}

#: Compose profiles whose services are translated. `dev` and `rollback` are local-stack and
#: rollback-only and have no place in a host's unit directory.
TRANSLATED_PROFILES = {"prod"}


#: A compose interpolation. Used to refuse one in a place Quadlet cannot expand it.
VAR_RE = r"\$\{([A-Za-z_][A-Za-z0-9_]*)"


#: Compose service keys this tool knows how to translate. Anything else fails the run rather than
#: being dropped: a unit that is missing a control still starts, and nothing downstream can tell
#: the difference between "not configured" and "quietly lost in translation".
KNOWN_SERVICE_KEYS = {
    "image", "container_name", "profiles", "user", "read_only", "cap_drop", "cap_add",
    "security_opt", "tmpfs", "ports", "networks", "volumes", "environment", "command",
    "entrypoint", "healthcheck", "deploy", "ulimits", "pids_limit", "restart",
    "stop_grace_period", "depends_on", "logging", "sysctls", "group_add", "shm_size",
    "extra_hosts", "dns", "labels", "init", "working_dir", "hostname",
    "oom_score_adj",
}

#: The resource limits understood under `deploy.resources.limits`. Same rule, same reason.
KNOWN_LIMIT_KEYS = {"memory", "cpus", "pids"}


class Refusal(Exception):
    """Raised when a service cannot be translated faithfully."""


# ============================================================================================
# Helpers
# ============================================================================================
def _resolve(value: str, where: str) -> str:
    """Resolve the compose interpolations that appear in **paths**.

    Handles ``${VAR}``, ``${VAR:-default}`` and one level of nesting, against :data:`PATH_VARS`.

    Args:
        value: the raw compose string.
        where: a label naming the service and key, used in the refusal message.

    Returns:
        The resolved string.

    Raises:
        Refusal: when a variable has no recorded value. Guessing here would produce a unit that
            mounts a directory literally named ``${IRI_...}``, which starts fine and serves
            nothing.
    """
    previous = None
    while previous != value:
        previous = value
        for name, resolved in PATH_VARS.items():
            value = re.sub(r"\$\{" + name + r"(:-[^}]*)?\}", resolved, value)
    unresolved = re.findall(r"\$\{([A-Za-z_][A-Za-z0-9_]*)", value)
    if unresolved:
        raise Refusal(
            f"{where}: no recorded host path for {', '.join(sorted(set(unresolved)))}. Quadlet does "
            "not expand variables in a Volume= path, so this would mount a directory named after "
            "the variable. Add it to PATH_VARS, or render the units at bundle-build time (plan §3.1)."
        )
    return value


def _image_defaults(image: str, service: str) -> str:
    """Resolve the compose interpolations in an image reference to their own defaults.

    ``ghcr.io/${IRI_IMAGE_NAMESPACE:-krt-profit}/basetool-backend:${IRI_BASETOOL_VERSION:-stable}``
    is resolved by compose when it loads the file. Quadlet does not expand variables in ``Image=``
    either, so the literal string would reach podman and the pull would fail on a repository named
    after the variable.

    Resolving each to the default written in the compose file itself is faithful rather than
    invented: it is exactly what compose produces on a host that does not set them, which is what
    the production ``.env`` does -- it leaves ``IRI_BASETOOL_VERSION`` unset so ``stable`` applies.

    The tag is in any case not what runs. ``REQ-OPS-003`` has the deployer resolve the rolling tag
    to a **digest** and apply that, so this value is the starting point the deployer overrides, not
    the pin.

    Args:
        image: the compose image reference.
        service: the service name, for the refusal message.

    Returns:
        The reference with every interpolation replaced by its default.

    Raises:
        Refusal: for a variable with no default, which cannot be resolved without guessing.
    """
    def default_of(match: "re.Match[str]") -> str:
        name, default = match.group(1), match.group(2)
        if default is None:
            raise Refusal(
                f"{service}: its image reference interpolates ${{{name}}} with no default. Quadlet "
                "does not expand variables in Image=, and there is nothing here to fall back to. "
                "Give it a default in the compose file, or resolve it when the bundle is built."
            )
        return default

    return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}", default_of, image)


def _qualify(image: str) -> str:
    """Make a short image name fully qualified.

    Docker resolves a short name to Docker Hub implicitly. Podman does not: it consults
    ``unqualified-search-registries`` and ``short-name-aliases.conf``, so the same string can
    resolve to a different registry on a differently configured host. Podman's own quadlet
    generator warns about every one of these, and it is right to -- a digest pin does not help if
    the name in front of it resolved somewhere unexpected.

    Args:
        image: the compose image reference, possibly with a tag and a digest.

    Returns:
        The reference with an explicit registry.
    """
    # No slash at all means a bare Docker Hub name -- `redis:8-alpine`, `postgres:18-alpine`.
    # Checking the colon first would be wrong here: that colon is the TAG, not a registry port.
    if "/" not in image:
        return "docker.io/" + image
    first = image.split("/")[0]
    # With a slash, the first segment is a registry only if it looks like a host: a dot, a port,
    # or the literal localhost. Otherwise it is a Docker Hub namespace -- `grafana/loki:3.7.7`.
    if "." in first or ":" in first or first == "localhost":
        return image
    return "docker.io/" + image


def _volume(spec: str, service: str) -> str:
    """Translate one compose volume entry.

    Args:
        spec: the ``source:target[:options]`` string.
        service: the service name, for the refusal message.

    Returns:
        The ``Volume=`` value, with a named volume rewritten to its ``.volume`` unit.

    Raises:
        Refusal: when a variable in the source cannot be resolved.
    """
    spec = _resolve(spec, f"{service}.volumes")
    parts = spec.split(":")
    source = parts[0]
    if source.startswith("./"):
        source = PROJECT_DIR_ON_HOST + source[1:]
    elif not source.startswith("/"):
        # a named volume -- Quadlet references the unit, not the raw name
        source = f"{source}.volume"
    return ":".join([source] + parts[1:])


def _health(hc: dict[str, Any]) -> list[str]:
    """Translate a compose healthcheck into Quadlet ``Health*`` keys.

    A ``CMD-SHELL`` test keeps its shell, and its ``$VAR`` references are left for the **container**
    to expand rather than baked in here. That is deliberate and is an improvement on the compose
    form: compose interpolates them at file-load time from the host `.env`, which would put values
    such as the database user into a unit file on disk.

    Args:
        hc: the compose ``healthcheck`` mapping.

    Returns:
        The ``Health…=`` lines, plus ``Notify=healthy`` so systemd's readiness matches what
        ``depends_on: condition: service_healthy`` meant.
    """
    test = hc.get("test")
    if not test or test == ["NONE"]:
        return []
    if isinstance(test, str):
        cmd = test
    elif test[0] == "CMD-SHELL":
        cmd = " ".join(test[1:])
    elif test[0] == "CMD":
        cmd = " ".join(test[1:])
    else:
        cmd = " ".join(test)

    lines = [f"HealthCmd={cmd}"]
    for compose_key, quadlet_key in (
        ("interval", "HealthInterval"),
        ("timeout", "HealthTimeout"),
        ("retries", "HealthRetries"),
        ("start_period", "HealthStartPeriod"),
        ("start_interval", "HealthStartupInterval"),
    ):
        if compose_key in hc:
            lines.append(f"{quadlet_key}={hc[compose_key]}")
    # The readiness half of compose's `depends_on: condition: service_healthy`. Requires= alone
    # orders startup; this is what makes a dependent wait for HEALTHY rather than for EXISTS.
    lines.append("Notify=healthy")
    return lines


def _exec(service: str, spec: dict[str, Any]) -> list[str]:
    """Translate ``entrypoint`` and ``command``.

    Args:
        service: the service name.
        spec: the compose service mapping.

    Returns:
        The ``Entrypoint=`` / ``Exec=`` lines.

    Raises:
        Refusal: on a multi-line command, which cannot become a single ``Exec=``.
    """
    lines: list[str] = []
    entrypoint = spec.get("entrypoint")
    command = spec.get("command")

    def flatten(value: Any) -> str:
        return value if isinstance(value, str) else " ".join(str(v) for v in value)

    if command is not None:
        text = flatten(command)
        found = re.findall(VAR_RE, text)
        if found:
            raise Refusal(
                f"{service}: its `command:` interpolates {sorted(set(found))}. Compose expands that "
                "when it LOADS the file; Quadlet's Exec= becomes the container's argv and is never "
                "passed through a shell, so the literal ${...} would reach the process. Resolve it "
                "the way acme's loop was: extract the command to a script, mount it, and let the "
                "variable expand inside the container from its EnvironmentFile."
            )
        if "\n" in text.strip():
            raise Refusal(
                f"{service}: its `command:` is a multi-line shell script and Quadlet's Exec= is a "
                "single line. Extract it to a file, mount the file, and point Exec= at it -- which "
                "is what the edge already does with render-and-run.sh, and it makes the script "
                "reviewable and testable instead of a YAML block scalar."
            )
    if entrypoint is not None:
        lines.append(f"Entrypoint={flatten(entrypoint)}")
    if command is not None:
        lines.append(f"Exec={flatten(command)}")
    return lines


# ============================================================================================
# Unit rendering
# ============================================================================================
def render_network(name: str, spec: dict[str, Any]) -> str:
    """Render a ``.network`` unit.

    Args:
        name: the compose network key.
        spec: its definition.

    Returns:
        The unit file's contents.
    """
    spec = spec or {}
    real = spec.get("name", name)
    lines = [
        "# Generated by scripts/generate-quadlet.py -- do not edit. Run the generator.",
        "[Network]",
        f"NetworkName={real}",
    ]
    for entry in (spec.get("ipam", {}) or {}).get("config", []) or []:
        if "subnet" in entry:
            lines.append(f"Subnet={entry['subnet']}")
        if "gateway" in entry:
            lines.append(f"Gateway={entry['gateway']}")
    if spec.get("enable_ipv6"):
        lines.append("IPv6=true")
    if spec.get("internal"):
        lines.append("Internal=true")
    opts = spec.get("driver_opts", {}) or {}
    if opts.get("com.docker.network.bridge.enable_ip_masquerade") == "false":
        lines += [
            "",
            "# Compose disabled masquerading here so this bridge carries ingress and no egress.",
            "# netavark 2.1 has no equivalent for a managed, non-internal bridge -- its documented",
            "# options are mtu, metric, no_default_route and isolate, and masquerading is tied to",
            "# mode=managed. no_default_route is the candidate: the edge is on this one",
            "# non-internal network and five Internal=true ones, so with no default route anywhere",
            "# it has no egress. THAT IS A HYPOTHESIS ABOUT A SECURITY CONTROL -- measure it in",
            "# Phase 1 before trusting it (plan §3.5).",
            "PodmanArgs=--opt no_default_route=true",
        ]
    return "\n".join(lines) + "\n"


def render_volume(name: str) -> str:
    """Render a ``.volume`` unit.

    Args:
        name: the compose volume key.

    Returns:
        The unit file's contents.
    """
    return (
        "# Generated by scripts/generate-quadlet.py -- do not edit. Run the generator.\n"
        "[Volume]\n"
        f"VolumeName={name}\n"
    )


def render_container(service: str, spec: dict[str, Any]) -> str:
    """Render a ``.container`` unit.

    Args:
        service: the service name.
        spec: the compose service mapping.

    Returns:
        The unit file's contents.

    Raises:
        Refusal: when something cannot be translated faithfully.
    """
    unknown = set(spec) - KNOWN_SERVICE_KEYS
    if unknown:
        raise Refusal(
            f"{service}: unrecognised compose key(s) {sorted(unknown)}. Add them to "
            "KNOWN_SERVICE_KEYS once they are translated, or the unit ships without whatever they "
            "configured and nothing downstream can tell that from 'not configured'."
        )

    unit: list[str] = []
    container: list[str] = []
    service_section: list[str] = []

    depends = list(spec.get("depends_on") or [])
    if depends:
        deps = " ".join(f"{d}.service" for d in depends)
        unit += [f"After={deps}", f"Requires={deps}"]

    container.append(f"Image={_qualify(_image_defaults(spec['image'], service))}")
    container.append(f"ContainerName={service}")

    if "user" in spec:
        user = str(spec["user"])
        if ":" in user:
            uid, gid = user.split(":", 1)
            container += [f"User={uid}", f"Group={gid}"]
        else:
            container.append(f"User={user}")

    if spec.get("read_only"):
        container.append("ReadOnly=true")
    for cap in spec.get("cap_drop", []) or []:
        container.append(f"DropCapability={cap}")
    for cap in spec.get("cap_add", []) or []:
        container.append(f"AddCapability={cap}")
    if any("no-new-privileges" in str(o) for o in spec.get("security_opt", []) or []):
        container.append("NoNewPrivileges=true")
    for tmpfs in spec.get("tmpfs", []) or []:
        container.append(f"Tmpfs={tmpfs}")
    for port in spec.get("ports", []) or []:
        container.append(f"PublishPort={port}")

    networks = spec.get("networks")
    names = list(networks.keys()) if isinstance(networks, dict) else list(networks or [])
    for net in names:
        container.append(f"Network={net}.network")

    for vol in spec.get("volumes", []) or []:
        container.append(f"Volume={_volume(str(vol), service)}")

    if spec.get("environment"):
        container.append(f"EnvironmentFile={ENV_DIR_ON_HOST}/{service}.env")

    container += _exec(service, spec)
    container += _health(spec.get("healthcheck") or {})

    podman_args: list[str] = []
    limits = (spec.get("deploy") or {}).get("resources", {}).get("limits", {})
    unknown_limits = set(limits) - KNOWN_LIMIT_KEYS
    if unknown_limits:
        raise Refusal(
            f"{service}: unrecognised resource limit(s) {sorted(unknown_limits)} under "
            "deploy.resources.limits. Teach the generator, or the unit ships without them and "
            "looks complete."
        )
    if "memory" in limits:
        container.append(f"Memory={limits['memory']}")
    # The pids cap bounds a fork bomb and a worker/zombie leak. It is not decoration: the
    # 2026-07-12 native-thread-OOM accumulated one <defunct> per 30s health probe until this cap
    # stopped it, and ContainerPidsHigh measures against it. Compose spells it under
    # deploy.resources.limits, NOT as the top-level `pids_limit` key -- reading only the latter is
    # how all nineteen units lost it once.
    if "pids" in limits:
        container.append(f"PidsLimit={limits['pids']}")
    if "cpus" in limits:
        podman_args.append(f"--cpus={limits['cpus']}")
    # All nine monitoring containers carry oom_score_adj: 500 -- deliberately MORE attractive to
    # the OOM killer than the application, so that under memory pressure the kernel takes Grafana
    # before it takes the backend. Quadlet has no key for it; podman run does. A POSITIVE
    # adjustment is what an unprivileged process is allowed to set, so this survives rootless.
    if "oom_score_adj" in spec:
        podman_args.append(f"--oom-score-adj={spec['oom_score_adj']}")
    nofile = (spec.get("ulimits") or {}).get("nofile")
    if isinstance(nofile, dict):
        podman_args.append(f"--ulimit nofile={nofile['soft']}:{nofile['hard']}")
    if "pids_limit" in spec:
        container.append(f"PidsLimit={spec['pids_limit']}")
    if podman_args:
        container.append("PodmanArgs=" + " ".join(podman_args))

    if spec.get("restart"):
        service_section.append("Restart=always")
    grace = spec.get("stop_grace_period")
    if grace:
        service_section.append(f"TimeoutStopSec={str(grace).rstrip('s')}")

    out = ["# Generated by scripts/generate-quadlet.py -- do not edit. Run the generator."]
    if unit:
        out += ["[Unit]"] + unit + [""]
    out += ["[Container]"] + container + [""]
    out += ["[Service]"] + (service_section or ["Restart=always"]) + [""]
    out += ["[Install]", "WantedBy=default.target"]
    return "\n".join(out) + "\n"


def render_vars(service: str, spec: dict[str, Any]) -> str:
    """Render the per-service environment allow-list.

    The compose ``environment:`` maps are closed allow-lists: a service sees only the variables its
    own map names. That is load-bearing -- the backend's database password and Grafana's OIDC
    secret sit in the same host ``.env`` -- and `EnvironmentFile=` with the whole file would hand
    every container every secret. So the deployer renders one file per service from this list.

    Args:
        service: the service name.
        spec: the compose service mapping.

    Returns:
        One variable name per line.
    """
    env = spec.get("environment") or {}
    names = list(env.keys()) if isinstance(env, dict) else [e.split("=")[0] for e in env]
    header = (
        f"# Generated by scripts/generate-quadlet.py -- do not edit. Run the generator.\n"
        f"# The closed allow-list for {service}: the deployer renders\n"
        f"# {ENV_DIR_ON_HOST}/{service}.env from the host .env using exactly these names.\n"
    )
    return header + "\n".join(sorted(names)) + "\n"


# ============================================================================================
# Driving
# ============================================================================================
def generate() -> tuple[dict[str, str], list[str]]:
    """Build every unit and allow-list.

    Returns:
        ``(files, notes)`` where ``files`` maps a repository-relative path to its contents and
        ``notes`` records what was skipped and why.

    Raises:
        Refusal: on anything that cannot be translated faithfully, or a service with no recorded
            disposition.
    """
    files: dict[str, str] = {}
    notes: list[str] = []
    net_defs: dict[str, dict[str, Any]] = {}
    used_networks: set[str] = set()
    used_volumes: set[str] = set()

    for path in (COMPOSE_APP, COMPOSE_MON):
        doc = yaml.safe_load(io.open(path, encoding="utf-8"))
        for name, spec in (doc.get("networks") or {}).items():
            spec = spec or {}
            if not spec.get("external"):
                net_defs.setdefault(name, spec)

        for service, spec in (doc.get("services") or {}).items():
            profiles = set(spec.get("profiles") or [])
            if profiles and not (profiles & TRANSLATED_PROFILES):
                if service not in DISPOSITION:
                    notes.append(f"{service}: skipped, profile {sorted(profiles)}")
                continue
            if service not in DISPOSITION:
                raise Refusal(
                    f"{service}: no recorded disposition. Add it to DISPOSITION as 'container', "
                    "'host-service' or 'deleted' with a reason. A new service must not slip into a "
                    "host's unit directory unexamined, and it must not silently fail to."
                )
            kind, reason = DISPOSITION[service]
            if kind == "pending":
                notes.append(f"{service}: PENDING A DECISION -- {reason}")
                continue
            if kind != "container":
                notes.append(f"{service}: {kind} -- {reason}")
                continue
            files[f"quadlet/systemd/{service}.container"] = render_container(service, spec)
            if spec.get("environment"):
                files[f"quadlet/env.d/{service}.vars"] = render_vars(service, spec)

            nets = spec.get("networks")
            used_networks.update(nets.keys() if isinstance(nets, dict) else (nets or []))
            for vol in spec.get("volumes", []) or []:
                source = str(vol).split(":")[0]
                if not source.startswith(("/", "./", "$")):
                    used_volumes.add(source)

    # Networks and volumes are derived from the containers that were actually emitted, never from
    # the compose top-level blocks. Otherwise a dev-only volume and `net-docker-proxy` -- whose only
    # members were the two deleted services -- follow the stack onto a production host, and nothing
    # would ever notice a bridge that exists for nobody.
    for name in sorted(used_networks):
        if name in net_defs:
            files[f"quadlet/systemd/{name}.network"] = render_network(name, net_defs[name])
        else:
            notes.append(f"{name}: referenced but declared external; no .network unit emitted")
    for name in sorted(used_volumes):
        files[f"quadlet/systemd/{name}.volume"] = render_volume(name)

    unused = sorted(set(net_defs) - used_networks)
    if unused:
        notes.append("networks with no translated member, omitted: " + ", ".join(unused))

    return files, notes


def main(argv: Sequence[str] | None = None) -> int:
    """Entry point.

    Args:
        argv: arguments without the program name, or ``None`` to read ``sys.argv``.

    Returns:
        ``0`` clean, ``1`` on drift or a refusal.
    """
    p = argparse.ArgumentParser(prog="generate-quadlet.py")
    p.add_argument("--check", action="store_true",
                   help="fail on drift instead of writing")
    p.add_argument("--list", action="store_true",
                   help="print what happens to each service and exit")
    args = p.parse_args(list(sys.argv[1:] if argv is None else argv))

    if args.list:
        width = max(len(s) for s in DISPOSITION)
        for service, (kind, reason) in sorted(DISPOSITION.items()):
            print(f"{service:<{width}}  {kind:<12}  {reason}")
        return 0

    try:
        files, notes = generate()
    except Refusal as exc:
        print(f"generate-quadlet: refusing -- {exc}", file=sys.stderr)
        return 1

    if args.check:
        drift = []
        for rel, content in sorted(files.items()):
            path = os.path.join(REPO, rel)
            actual = io.open(path, encoding="utf-8").read() if os.path.exists(path) else ""
            if actual != content:
                drift.append(rel)
                for line in difflib.unified_diff(
                        actual.splitlines(), content.splitlines(),
                        fromfile=f"{rel} (on disk)", tofile=f"{rel} (generated)", lineterm=""):
                    print(line)
        stale = []
        for root in (OUT_UNITS, OUT_VARS):
            for name in (os.listdir(root) if os.path.isdir(root) else []):
                rel = os.path.relpath(os.path.join(root, name), REPO).replace(os.sep, "/")
                if rel not in files:
                    stale.append(rel)
        if drift or stale:
            print(f"\ngenerate-quadlet: {len(drift)} file(s) drifted, {len(stale)} stale: "
                  f"{', '.join(sorted(drift + stale))}", file=sys.stderr)
            print("Run `python scripts/generate-quadlet.py` and commit the result.", file=sys.stderr)
            return 1
        print(f"generate-quadlet: {len(files)} unit(s) match the compose files.")
        return 0

    os.makedirs(OUT_UNITS, exist_ok=True)
    os.makedirs(OUT_VARS, exist_ok=True)
    for rel, content in sorted(files.items()):
        path = os.path.join(REPO, rel)
        io.open(path, "w", encoding="utf-8", newline="\n").write(content)
    print(f"generate-quadlet: wrote {len(files)} file(s)")
    for note in notes:
        print(f"  note: {note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

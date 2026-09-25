#!/usr/bin/env python3
"""Profit Basetool - generate Quadlet units from the Compose files, and detect drift between them.

The compose files stay authoritative; the ``.container``, ``.network`` and ``.volume`` units and
the ``env.d`` templates are generated from them, and ``--check`` diffs a regeneration against the
tree. The generator refuses rather than guesses, e.g. on an unresolved ``${VAR}`` in a path, a
multi-line ``command:``, or a service with no entry in ``DISPOSITION``.

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
import ipaddress
import math
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

ENV_DIR_ON_HOST = "/var/iri/code/env.d"

FRONT_END = {
    "edge": {
        "publish": ["127.0.0.1:8080:8080", "[::1]:8080:8080",
                    "127.0.0.1:8443:8443", "[::1]:8443:8443"],
        "role_var": "basetool_host_edge_trusted_proxies",
        "pins": {
            "net-edge-ingress": "172.28.15.10",
            "net-proxy-frontend": "172.28.3.250",
            "net-proxy-keycloak": "172.28.4.250",
            "net-proxy-ingest": "172.28.7.250",
            "net-proxy-grafana": "172.28.11.250",
            "net-proxy-api": "172.28.13.250",
        },
    }
}

RUN_AS = {
    "db-backend":  {"uid": 70,  "role_path": "db-backend"},
    "db-keycloak": {"uid": 70,  "role_path": "db-keycloak"},
    "redis":       {"uid": 999, "role_path": "redis"},
}

ROLE_DEFAULTS = os.path.join(REPO, "ansible", "roles", "basetool_host", "defaults", "main.yml")

READ_ONLY: dict[str, dict[str, Any]] = {
    "prometheus": {},
    "loki": {},
    "tempo": {},
    "grafana": {"environment": {"GF_PLUGINS_PREINSTALL_DISABLED": "true"}},
    "alertmanager": {},
    "blackbox-exporter": {},
    "postgres-exporter-backend": {},
    "postgres-exporter-keycloak": {},
    "redis-exporter": {},
    "acme": {},
    "keycloak": {"tmpfs": ["/opt/keycloak/lib/quarkus:rw,tmpcopyup",
                           "/opt/keycloak/data/transaction-logs:rw",
                           "/opt/keycloak/data/tmp:rw"]},
    "backend": {},
    "frontend": {},
    "ingest": {},
}

PATH_VARS = {
    "IRI_KEYSTORE_HOST_PATH": "/var/iri/secrets/keystore.p12",
    "IRI_BACKEND_KEYSTORE_HOST_PATH": "/var/iri/secrets/tls/backend.p12",
    "IRI_FRONTEND_KEYSTORE_HOST_PATH": "/var/iri/secrets/tls/frontend.p12",
    "IRI_INGEST_KEYSTORE_HOST_PATH": "/var/iri/secrets/tls/ingest.p12",
    "IRI_KEYCLOAK_KEYSTORE_HOST_PATH": "/var/iri/secrets/tls/keycloak.p12",
    "IRI_INTERNAL_TRUSTSTORE_HOST_PATH": "/var/iri/secrets/tls/truststore.p12",
    "IRI_TRUSTSTORE_HOST_PATH": "/var/iri/secrets/tls/truststore.p12",
    "IRI_REDIS_ACL_HOST_PATH": "/var/iri/redis/users.acl",
    "IRI_UPSTREAM_CA_HOST_PATH": "/var/iri/monitoring/certs/basetool-ca.crt",
    "IRI_GRAFANA_UPSTREAM_CERT_HOST_PATH": "/var/iri/monitoring/certs/grafana.crt",
}

PROJECT_DIR_ON_HOST = "/var/iri/code"

VALUE_VARS = {
    "IRI_KEYCLOAK_HOST_ALIAS": "localhost:127.0.0.1",
}

DISPOSITION: dict[str, tuple[str, str]] = {
    "edge": ("container", ""),
    "acme": ("container", ""),
    "keycloak": ("container", ""),
    "backend": ("container", ""),
    "frontend": ("container", ""),
    "ingest": ("container", ""),
    "db-backend": ("container", ""),
    "db-keycloak": ("container", ""),
    "redis": ("container", ""),
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
    "podman-exporter": (
        "host-service",
        "a Podman-only exporter with no Compose counterpart; the role installs it as a user unit "
        "of the service user, because a system-level one would talk to the root podman.",
    ),
}

PODMAN_HOST_ALIASES = {
    "prometheus": ("node-exporter", "alloy", "podman-exporter"),
    "backend": ("alloy",),
    "frontend": ("alloy",),
    "ingest": ("alloy",),
    "keycloak": ("alloy",),
}

PODMAN_LOOPBACK_PUBLISH = {
    "loki": ("127.0.0.1:3100:3100",),
    "tempo": ("127.0.0.1:4327:4317",),
}

TRANSLATED_PROFILES = {"prod"}


VAR_RE = r"\$\{([A-Za-z_][A-Za-z0-9_]*)"

VAR_REF_RE = re.compile(r"\$\{[A-Za-z_][A-Za-z0-9_]*(?:[:-][^{}]*)?\}")


QUADLET_ONLY_ENV: dict[str, dict[str, str]] = {
    "redis": {"REDIS_PASSWORD": "${REDIS_PASSWORD:?REDIS_PASSWORD must be set in .env}"},
}


START_TIMEOUT_MARGIN_SEC = 60


STOP_TIMEOUT_MARGIN_SEC = 15


QUADLET_INTERNAL_NETWORKS = frozenset({
    "net-db-backend",
    "net-db-keycloak",
    "net-redis-backend",
    "net-redis-frontend",
    "net-redis-ingest",
})


TRANSLATED_SERVICE_KEYS = {
    "image", "profiles", "user", "read_only", "cap_drop", "cap_add",
    "security_opt", "tmpfs", "ports", "networks", "volumes", "environment", "command",
    "entrypoint", "healthcheck", "deploy", "ulimits", "pids_limit", "restart",
    "stop_grace_period", "depends_on", "oom_score_adj",
    "extra_hosts", "init",
}

IGNORED_SERVICE_KEYS = {
    "container_name": (
        "the generator derives ContainerName= from the service name, and the two are asserted "
        "equal for every translated service, so reading the key would add a second source for "
        "one value"
    ),
    "logging": (
        "compose pins json-file with max-size 10m / max-file 5 because the backend alone writes "
        "~150 MB/day and would fill /var/lib/docker/containers. Podman's effective driver here is "
        "journald, which rotates on its own budget instead -- so the SETTING is not translated and "
        "the PROBLEM moves to host configuration: journald's SystemMaxUse= and MaxRetentionSec= "
        "(31 days, REQ-OBS-010), set by the bootstrap role in 27-observability.yml, not a unit "
        "directive"
    ),
}

KNOWN_SERVICE_KEYS = TRANSLATED_SERVICE_KEYS | set(IGNORED_SERVICE_KEYS)

assert not (TRANSLATED_SERVICE_KEYS & set(IGNORED_SERVICE_KEYS)), \
    "a compose key cannot be both translated and ignored"
assert all(IGNORED_SERVICE_KEYS.values()), "every ignored key needs a stated reason"

KNOWN_LIMIT_KEYS = {"memory", "cpus", "pids"}


class Refusal(Exception):
    """Raised when a service cannot be translated faithfully."""


def _resolve(value: str, where: str) -> str:
    """Resolve the compose interpolations that appear in **paths**.

    Handles ``${VAR}``, ``${VAR:-default}`` and one level of nesting, against :data:`PATH_VARS`.

    Args:
        value: the raw compose string.
        where: a label naming the service and key, used in the refusal message.

    Returns:
        The resolved string.

    Raises:
        Refusal: when a variable has no recorded value.
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


def _resolve_value(value: str, where: str) -> str:
    """Resolve a compose interpolation that is a **value** rather than a path.

    Same contract as :func:`_resolve`, against :data:`VALUE_VARS`.

    Args:
        value: the raw compose string.
        where: a label naming the service and key, used in the refusal message.

    Returns:
        The resolved string.

    Raises:
        Refusal: when a variable has no recorded value.
    """
    previous = None
    while previous != value:
        previous = value
        for name, resolved in VALUE_VARS.items():
            value = re.sub(r"\$\{" + name + r"(:-[^}]*)?\}", resolved, value)
    unresolved = re.findall(r"\$\{([A-Za-z_][A-Za-z0-9_]*)", value)
    if unresolved:
        raise Refusal(
            f"{where}: no recorded value for {', '.join(sorted(set(unresolved)))}. Quadlet does not "
            "expand variables, so this would be written into the unit literally. Add it to "
            "VALUE_VARS with the value the promoted bundle should carry, and let a host that needs "
            "a different one override it with a systemd drop-in."
        )
    return value


def _image_defaults(image: str, service: str) -> str:
    """Resolve the compose interpolations in an image reference to their own defaults.

    Quadlet does not expand variables in ``Image=``; the deployer later pins the tag to a digest
    (REQ-OPS-003).

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
    """Make a short image name fully qualified, defaulting to ``docker.io``.

    Args:
        image: the compose image reference, possibly with a tag and a digest.

    Returns:
        The reference with an explicit registry.
    """
    if "/" not in image:
        return "docker.io/" + image
    first = image.split("/")[0]
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
        Refusal: when a variable in the source cannot be resolved, or a mount from the config
            tree is not ``:ro``.
    """
    spec = _resolve(spec, f"{service}.volumes")
    parts = spec.split(":")
    source = parts[0]
    if source.startswith("./"):
        source = PROJECT_DIR_ON_HOST + source[1:]
    elif not source.startswith("/"):
        source = f"{source}.volume"
    if source == PROJECT_DIR_ON_HOST or source.startswith(PROJECT_DIR_ON_HOST + "/"):
        options = parts[2].split(",") if len(parts) > 2 else []
        if "ro" not in options:
            raise Refusal(
                f"{service}: mounts {source} from the config tree without `:ro`. The deployer "
                "rewrites that tree on every release, and a container that can write into it can "
                "change what the next release applies. Mount it read-only."
            )
    return ":".join([source] + parts[1:])


def _seconds(value: Any, where: str) -> int:
    """Parse a compose duration into whole seconds.

    Accepts a bare number or ``1h2m3s``-style units (``us``/``ms``/``s``/``m``/``h``); fractions
    round up, since the result feeds a timeout.

    Args:
        value: the compose value -- an int, or a string such as ``30s`` or ``1m30s``.
        where: the service and key, for the refusal message.

    Returns:
        The duration in whole seconds.

    Raises:
        Refusal: on a duration this parser does not understand, rather than a silent zero.
    """
    if isinstance(value, (int, float)):
        return int(math.ceil(float(value)))
    text = str(value).strip()
    if re.fullmatch(r"\d+", text):
        return int(text)
    units = {"us": 1e-6, "ms": 1e-3, "s": 1.0, "m": 60.0, "h": 3600.0}
    parts = re.findall(r"(\d+(?:\.\d+)?)(us|ms|h|m|s)", text)
    if not parts or "".join(a + b for a, b in parts) != text:
        raise Refusal(
            f"{where}: {value!r} is not a duration this generator can read. It feeds "
            "TimeoutStartSec=, and guessing a start budget is how a unit gets killed mid-start."
        )
    return int(math.ceil(sum(float(a) * units[b] for a, b in parts)))


def _escape_percent(value: str) -> str:
    """Escape ``%`` so systemd does not read it as a specifier.

    Quadlet folds ``[Container]`` keys into ``ExecStart=`` without escaping; ``%%`` reaches the
    process as one literal ``%``.

    Args:
        value: the text as compose wrote it.

    Returns:
        The same text with every ``%`` doubled.
    """
    return value.replace("%", "%%")


def _sh_quote(word: str) -> str:
    """Quote one exec-form argv element for the shell podman runs ``HealthCmd=`` through.

    An element whose only ``$`` uses are well-formed ``${NAME...}`` references (and no backtick
    or backslash) gets double quotes, keeping the expansion live; a plain safe word stays bare;
    anything else gets single quotes.

    Args:
        word: one element of the compose exec-form list.

    Returns:
        The element, quoted so the re-joined string reproduces the original argv.
    """
    if not word:
        return "''"
    if "${" in word and not re.search(r"[`\\]", word):
        if "$" not in VAR_REF_RE.sub("", word):
            return '"' + word.replace('"', '\\"') + '"'
    if re.fullmatch(r"[A-Za-z0-9_@%+=:,./-]+", word):
        return word
    return "'" + word.replace("'", "'\\''") + "'"


def _health(
    hc: dict[str, Any], env: dict[str, Any], service: str
) -> tuple[list[str], list[str]]:
    """Translate a compose healthcheck into Quadlet ``Health*`` keys.

    Variable references are left for the container to expand, rewritten from host-side names to
    the container-side names in the service's environment map.

    Args:
        hc: the compose ``healthcheck`` mapping.
        env: the service's compose ``environment`` map, which says what the container is handed.
        service: the service name, for the refusal message.

    Returns:
        The ``[Container]`` lines (``Health…=`` plus ``Notify=healthy``) and the ``[Service]``
        lines (``TimeoutStartSec=``).

    Raises:
        Refusal: when the command names a variable the container does not receive, or a list
            ``test:`` does not start with ``NONE``, ``CMD`` or ``CMD-SHELL``.
    """
    test = hc.get("test")
    if not test or test == ["NONE"]:
        return [], []
    if isinstance(test, str):
        cmd = test
    elif test[0] == "CMD-SHELL":
        cmd = " ".join(str(w) for w in test[1:])
    elif test[0] == "CMD":
        cmd = " ".join(_sh_quote(str(w)) for w in test[1:])
    else:
        raise Refusal(
            f"{service}: its healthcheck `test:` is a list starting with {test[0]!r}, which is "
            "none of compose's three keywords (NONE, CMD, CMD-SHELL). Whether the rest is argv or "
            "a shell command decides whether it must be quoted, and guessing wrong is either a "
            "probe that never passes or a shell injection on every interval."
        )

    inside = {}
    for container_key, value in (env or {}).items():
        for host in re.findall(r"\$\{([A-Z_0-9]+)", str(value)):
            inside.setdefault(host, container_key)
    for host in sorted(set(re.findall(r"\$\{([A-Z_0-9]+)", cmd))):
        if host in (env or {}):
            continue
        if host not in inside:
            raise Refusal(
                f"{service}: the health command names ${{{host}}}, and the container is handed that "
                "value under no name at all. Compose expanded it on the host; a shell inside the "
                "container cannot. Add it to the service's environment, or write the health command "
                "in terms of a name the container receives."
            )
        cmd = re.sub(
            r"\$\{" + re.escape(host) + r"(?![A-Za-z0-9_])",
            lambda m, name=inside[host]: "${" + name,
            cmd,
        )

    lines = [f"HealthCmd={_escape_percent(cmd)}"]
    for compose_key, quadlet_key in (
        ("interval", "HealthInterval"),
        ("timeout", "HealthTimeout"),
        ("retries", "HealthRetries"),
        ("start_period", "HealthStartPeriod"),
        ("start_interval", "HealthStartupInterval"),
    ):
        if compose_key in hc:
            lines.append(f"{quadlet_key}={hc[compose_key]}")
    lines.append("Notify=healthy")

    budget = _seconds(hc.get("start_period", 0), f"{service}.healthcheck.start_period") + int(
        hc.get("retries", 3)
    ) * (
        _seconds(hc.get("interval", "30s"), f"{service}.healthcheck.interval")
        + _seconds(hc.get("timeout", "30s"), f"{service}.healthcheck.timeout")
    )
    return lines, [f"TimeoutStartSec={budget + START_TIMEOUT_MARGIN_SEC}"]


def _exec(service: str, spec: dict[str, Any]) -> list[str]:
    """Translate ``entrypoint`` and ``command``.

    Both are run through :func:`_escape_percent`.

    Args:
        service: the service name.
        spec: the compose service mapping.

    Returns:
        The ``Entrypoint=`` / ``Exec=`` lines.

    Raises:
        Refusal: on a command that interpolates a variable or spans several lines.
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
        lines.append(f"Entrypoint={_escape_percent(flatten(entrypoint))}")
    if command is not None:
        lines.append(f"Exec={_escape_percent(flatten(command))}")
    return lines


def render_network(name: str, spec: dict[str, Any]) -> str:
    """Render a ``.network`` unit.

    Args:
        name: the compose network key.
        spec: its definition.

    Returns:
        The unit file's contents.

    Raises:
        Refusal: when compose and :data:`QUADLET_INTERNAL_NETWORKS` both mark it internal.
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
    if spec.get("internal") and name in QUADLET_INTERNAL_NETWORKS:
        raise Refusal(
            f"{name}: compose already declares it internal, and QUADLET_INTERNAL_NETWORKS names it "
            "again. Remove the table entry -- two places saying one thing are two places that can "
            "come to disagree."
        )
    if spec.get("internal") or name in QUADLET_INTERNAL_NETWORKS:
        lines.append("Internal=true")
    opts = spec.get("driver_opts", {}) or {}
    if opts.get("com.docker.network.bridge.enable_ip_masquerade") == "false":
        lines += [
            "",
            "# Compose disabled masquerading here so this bridge carries ingress and no egress.",
            "# netavark has no masquerade switch for a managed bridge; no_default_route is the",
            "# equivalent: the edge is on this one non-internal network and five Internal=true",
            "# ones, so with no default route anywhere it has no egress. Measured 2026-09-16",
            "# (docs/archive/PODMAN_MIGRATION_PLAN.md section 13): a plain network reaches the",
            "# internet, this option blocks it, and the container keeps only a link-scope route.",
            "Options=no_default_route=true",
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
    container.append("LogDriver=journald")

    run_as = RUN_AS.get(service)
    if run_as is not None:
        if "user" in spec:
            raise Refusal(
                f"{service}: compose says user: {spec['user']!r} and RUN_AS says "
                f"{run_as['uid']}. Two answers to one question -- delete one of them rather "
                "than letting the generator pick."
            )
        container += [
            f"User={run_as['uid']}",
            f"Group={run_as['uid']}",
            "ReadOnly=true",
            "DropCapability=ALL",
        ]
    else:
        if "user" in spec:
            user = str(spec["user"])
            if ":" in user:
                uid, gid = user.split(":", 1)
                container += [f"User={uid}", f"Group={gid}"]
            else:
                container.append(f"User={user}")

        if spec.get("read_only") and service in READ_ONLY:
            raise Refusal(
                f"{service}: compose already sets read_only: true, and READ_ONLY names it again. "
                "Remove the table entry -- a second place to say the same thing is a first place "
                "for the two to differ."
            )
        if spec.get("read_only") or service in READ_ONLY:
            container.append("ReadOnly=true")
        for cap in spec.get("cap_drop", []) or []:
            container.append(f"DropCapability={cap}")
        for cap in spec.get("cap_add", []) or []:
            container.append(f"AddCapability={cap}")
        for entry in READ_ONLY.get(service, {}).get("tmpfs") or []:
            container.append(f"Tmpfs={entry}")
        for key, value in (READ_ONLY.get(service, {}).get("environment") or {}).items():
            container.append(f"Environment={key}={value}")
    if any("no-new-privileges" in str(o) for o in spec.get("security_opt", []) or []):
        container.append("NoNewPrivileges=true")
    for tmpfs in spec.get("tmpfs", []) or []:
        container.append(f"Tmpfs={tmpfs}")
    front = FRONT_END.get(service)
    if front:
        for port in front["publish"]:
            container.append(f"PublishPort={port}")
    else:
        for port in spec.get("ports", []) or []:
            container.append(f"PublishPort={port}")
    for port in PODMAN_LOOPBACK_PUBLISH.get(service, ()):
        container.append(f"PublishPort={port}")

    networks = spec.get("networks")
    names = list(networks.keys()) if isinstance(networks, dict) else list(networks or [])
    for net in names:
        pinned = (front or {}).get("pins", {}).get(net)
        if pinned:
            container.append(f"Network={net}.network:ip={pinned}")
        else:
            container.append(f"Network={net}.network")

    for vol in spec.get("volumes", []) or []:
        container.append(f"Volume={_volume(str(vol), service)}")

    if spec.get("environment") or QUADLET_ONLY_ENV.get(service):
        container.append(f"EnvironmentFile={ENV_DIR_ON_HOST}/{service}.env")

    container += _exec(service, spec)
    health_container, health_service = _health(
        spec.get("healthcheck") or {},
        {**(spec.get("environment") or {}), **QUADLET_ONLY_ENV.get(service, {})},
        service,
    )
    container += health_container
    service_section += health_service

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
    if "pids" in limits:
        container.append(f"PidsLimit={limits['pids']}")
    if "cpus" in limits:
        podman_args.append(f"--cpus={limits['cpus']}")
    if "oom_score_adj" in spec:
        podman_args.append(f"--oom-score-adj={spec['oom_score_adj']}")
    if spec.get("init"):
        container.append("RunInit=true")
    for entry in spec.get("extra_hosts") or []:
        resolved = _resolve_value(str(entry), f"{service}.extra_hosts")
        container.append(f"AddHost={resolved}")
    for alias in PODMAN_HOST_ALIASES.get(service, ()):
        container.append(f"AddHost={alias}:host-gateway")
    ulimits = spec.get("ulimits") or {}
    unknown_ulimits = set(ulimits) - {"nofile"}
    if unknown_ulimits:
        raise Refusal(
            f"{service}: unrecognised ulimit(s) {sorted(unknown_ulimits)}. Teach the generator, or "
            "the unit ships without them and looks complete."
        )
    nofile = ulimits.get("nofile")
    if isinstance(nofile, dict):
        container.append(f"Ulimit=nofile={nofile['soft']}:{nofile['hard']}")
    elif nofile is not None:
        container.append(f"Ulimit=nofile={nofile}:{nofile}")
    if "pids_limit" in spec:
        container.append(f"PidsLimit={spec['pids_limit']}")
    if podman_args:
        container.append("PodmanArgs=" + " ".join(podman_args))

    if spec.get("restart"):
        service_section += [
            "Restart=always",
            "RestartSec=1",
            "RestartSteps=6",
            "RestartMaxDelaySec=60",
        ]
        unit.append("StartLimitIntervalSec=0")
    grace = spec.get("stop_grace_period")
    if grace:
        stop = _seconds(grace, f"{service}.stop_grace_period")
        container.append(f"StopTimeout={stop}")
        service_section.append(f"TimeoutStopSec={stop + STOP_TIMEOUT_MARGIN_SEC}")

    for line in container:
        key, _, value = line.partition("=")
        if key in ("Exec", "Entrypoint", "HealthCmd"):
            continue
        if "%" in value:
            raise Refusal(
                f"{service}: {key}= carries a `%` ({value!r}). Quadlet folds every [Container] key "
                "into ExecStart=, where systemd reads `%` as a specifier -- `%m` becomes the "
                "machine id, `%d` is unresolvable outside a credential context. Measured on the "
                "testing host 2026-09-18: Quadlet does NOT escape it. Double it to `%%` at the "
                "place this value is built, the way _escape_percent does for Exec=."
            )

    out = ["# Generated by scripts/generate-quadlet.py -- do not edit. Run the generator."]
    if unit:
        out += ["[Unit]"] + unit + [""]
    out += ["[Container]"] + container + [""]
    out += ["[Service]"] + (service_section or ["Restart=always"]) + [""]
    out += ["[Install]", "WantedBy=default.target"]
    return "\n".join(out) + "\n"


def render_vars(service: str, spec: dict[str, Any]) -> str:
    """Render the per-service environment TEMPLATE the deployer fills in.

    Each line is the compose ``KEY=value`` verbatim, interpolated later against the host ``.env``.
    One file per service keeps each environment a closed allow-list; secrets appear only as
    ``${NAME:?...}`` references.

    Args:
        service: the service name.
        spec: the compose service mapping.

    Returns:
        The template text, one ``KEY=value`` line per environment entry.

    Raises:
        Refusal: on a value containing a newline, which no env file can carry.
    """
    env = spec.get("environment") or {}
    if isinstance(env, dict):
        items = [(k, str(v)) for k, v in env.items()]
    else:
        items = [tuple(str(e).split("=", 1)) for e in env]
    declared = {k for k, _ in items}
    for key, value in QUADLET_ONLY_ENV.get(service, {}).items():
        if key not in declared:
            items.append((key, value))

    for key, value in items:
        if "\n" in value:
            raise Refusal(
                f"{service}: the value of {key} contains a newline. An env file is one assignment "
                "per line and cannot carry it -- put the value in a file the container mounts."
            )

    header = (
        "# Generated by scripts/generate-quadlet.py -- do not edit. Run the generator.\n"
        f"# The environment template for {service}. The deployer renders\n"
        f"# {ENV_DIR_ON_HOST}/{service}.env from this, interpolating ${{...}} against the host\n"
        "# .env the way compose did. A literal passes through; a ${NAME:-default} keeps its\n"
        "# default when the host sets nothing; a ${NAME:?...} must be set or the render fails.\n"
        "#\n"
        "# NO SECRET IS IN THIS FILE. A secret appears as a reference, and its value exists only on\n"
        "# the host -- which is also why this is one file per service: the compose environment maps\n"
        "# are closed allow-lists, and one shared EnvironmentFile would hand every container every\n"
        "# secret.\n"
    )
    body = "\n".join(f"{k}={v}" for k, v in sorted(items))
    return header + body + "\n"


def _verify_run_as_against_role() -> None:
    """Refuse if the hardening tables disagree with the bootstrap role, or with each other.

    :data:`RUN_AS` uids must match ``basetool_host_container_owners`` in the role defaults.

    Raises:
        Refusal: when a service in RUN_AS has no owner entry in the role or has one with a
            different ``container_uid``; when a service is named by both RUN_AS and READ_ONLY;
            or when READ_ONLY names something that does not become a container.
    """
    doc = yaml.safe_load(io.open(ROLE_DEFAULTS, encoding="utf-8"))
    by_path: dict[str, int] = {}
    for entry in doc.get("basetool_host_container_owners") or []:
        for path in entry.get("paths") or []:
            by_path[str(path)] = int(entry["container_uid"])

    for service, run_as in sorted(RUN_AS.items()):
        path, uid = run_as["role_path"], run_as["uid"]
        if path not in by_path:
            raise Refusal(
                f"{service}: RUN_AS runs it as uid {uid}, but basetool_host_container_owners has no "
                f"entry for {path!r}. Nothing would own its data directory as that uid, so the "
                "container would start as a user with no write access to its own state."
            )
        if by_path[path] != uid:
            raise Refusal(
                f"{service}: RUN_AS says uid {uid}, the bootstrap role owns {path!r} as "
                f"{by_path[path]}. One of the two was changed without the other."
            )

    overlap = sorted(set(RUN_AS) & set(READ_ONLY))
    if overlap:
        raise Refusal(
            f"{', '.join(overlap)}: listed in RUN_AS and in READ_ONLY. RUN_AS already emits "
            "ReadOnly=true as part of its set, so the second entry is either redundant or a "
            "disagreement, and neither should be resolved by whichever table is read last."
        )

    for service in sorted(READ_ONLY):
        kind = DISPOSITION.get(service, ("absent", ""))[0]
        if kind != "container":
            raise Refusal(
                f"{service}: READ_ONLY names it, but its disposition is {kind!r}. A read-only "
                "root filesystem for something that never becomes a container is a line nobody "
                "will notice has stopped applying."
            )


def _verify_front_end(
    service_networks: dict[str, list[str]], net_defs: dict[str, dict[str, Any]]
) -> None:
    """Refuse if the front end's pinned addresses and its trusted ones are not the same set.

    Podman may present the PROXY header from the edge's address on any network it joins, so every
    joined network needs a pinned address inside its subnet, and the role's trusted list must be
    exactly those addresses (ADR-0187).

    Args:
        service_networks: for each front-end service, the networks it actually joins.
        net_defs: the compose network definitions, for the subnet each pin must fall inside.

    Raises:
        Refusal: when a joined network carries no pin, when a pin names a network the service does
            not join, when a pin falls outside that network's subnet, or when the role's trusted
            list is not exactly the set of pinned addresses.
    """
    doc = yaml.safe_load(io.open(ROLE_DEFAULTS, encoding="utf-8"))

    for service, front in sorted(FRONT_END.items()):
        pins: dict[str, str] = front.get("pins") or {}
        joined = service_networks.get(service, [])

        unpinned = [net for net in joined if net not in pins]
        if unpinned:
            raise Refusal(
                f"{service}: joins {', '.join(unpinned)} with no pinned address. Podman may present"
                " the PROXY header from its address there, and nothing would name it -- nginx then"
                " discards the header and every client address collapses to the bridge. Add the"
                " network to FRONT_END['" + service + "']['pins'] and to the role's trusted list."
            )
        stray = [net for net in pins if net not in joined]
        if stray:
            raise Refusal(
                f"{service}: pins an address on {', '.join(stray)}, which it does not join. The pin"
                " has no effect and the address it names is trusted for nothing."
            )

        for net, address in sorted(pins.items()):
            subnets = [
                entry["subnet"]
                for entry in ((net_defs.get(net) or {}).get("ipam") or {}).get("config", []) or []
                if "subnet" in entry
            ]
            if not subnets:
                raise Refusal(
                    f"{service}: pinned to {address} on {net}, which declares no subnet. netavark"
                    " cannot honour a static address on an unmanaged range."
                )
            try:
                ip = ipaddress.ip_address(address)
            except ValueError as exc:
                raise Refusal(f"{service}: {address!r} on {net} is not an address") from exc
            if not any(ip in ipaddress.ip_network(sub) for sub in subnets):
                raise Refusal(
                    f"{service}: {address} is outside {', '.join(subnets)} on {net}. netavark"
                    " refuses this when the container starts, which is a failed deploy rather than"
                    " a failed build."
                )

        role_var = front.get("role_var")
        if not role_var:
            continue
        trusted = [str(value) for value in (doc.get(role_var) or [])]
        if not trusted:
            raise Refusal(
                f"{role_var} is missing or empty in the bootstrap role. It is what becomes"
                " EDGE_TRUSTED_PROXY; without it the edge trusts nothing, its listeners stay plain"
                " and the front end's PROXY header is rejected."
            )
        if len(set(trusted)) != len(trusted):
            raise Refusal(f"{role_var} lists an address twice.")
        only_pinned = sorted(set(pins.values()) - set(trusted))
        only_trusted = sorted(set(trusted) - set(pins.values()))
        if only_pinned:
            raise Refusal(
                f"{', '.join(only_pinned)}: pinned for {service} but missing from {role_var}. When"
                " podman presents the header from one of these, nginx discards it and every client"
                " address collapses to the bridge -- silently, with a valid configuration."
            )
        if only_trusted:
            raise Refusal(
                f"{', '.join(only_trusted)}: named in {role_var} but pinned to nothing. An address"
                " nothing reserves can be handed to another container, which could then assert any"
                " client address it likes."
            )


def _verify_internal_networks(
    units: dict[str, str], net_defs: dict[str, dict[str, Any]], used: set[str]
) -> None:
    """Refuse a container that an internal network would silently cut off.

    An ``Internal=true`` network has no gateway route and no DNAT, so a container on internal
    networks only can neither publish a port nor reach the host gateway.

    Args:
        units: service name -> rendered ``.container`` text.
        net_defs: the compose network definitions.
        used: the networks some emitted container actually joins.

    Raises:
        Refusal: when a container on internal networks only publishes a port or aliases the host
            gateway, or when ``QUADLET_INTERNAL_NETWORKS`` names a network no container joins.
    """
    stale = sorted(QUADLET_INTERNAL_NETWORKS - used)
    if stale:
        raise Refusal(
            f"QUADLET_INTERNAL_NETWORKS names {', '.join(stale)}, which no translated container "
            "joins. The entry protects nothing and would read as if it did."
        )

    def internal(net: str) -> bool:
        return bool((net_defs.get(net) or {}).get("internal")) or net in QUADLET_INTERNAL_NETWORKS

    for service, text in sorted(units.items()):
        lines = text.splitlines()
        nets = [
            line.split("=", 1)[1].split(":", 1)[0].removesuffix(".network")
            for line in lines if line.startswith("Network=")
        ]
        if not nets or not all(internal(net) for net in nets):
            continue
        reaching_out = [
            line for line in lines
            if line.startswith("PublishPort=")
            or (line.startswith("AddHost=") and line.endswith(":host-gateway"))
        ]
        if reaching_out:
            raise Refusal(
                f"{service}: every network it joins is internal ({', '.join(nets)}), yet the unit "
                f"carries {', '.join(reaching_out)}. An internal network has no DNAT and no route "
                "to the host gateway, so that line would start clean and never work. Give the "
                "service a non-internal network, or drop the network from "
                "QUADLET_INTERNAL_NETWORKS."
            )


def generate() -> tuple[dict[str, str], list[str]]:
    """Build every unit and allow-list.

    Returns:
        ``(files, notes)`` where ``files`` maps a repository-relative path to its contents and
        ``notes`` records what was skipped and why.

    Raises:
        Refusal: on anything that cannot be translated faithfully, or a service with no recorded
            disposition.
    """
    _verify_run_as_against_role()

    files: dict[str, str] = {}
    notes: list[str] = []
    net_defs: dict[str, dict[str, Any]] = {}
    front_networks: dict[str, list[str]] = {}
    used_networks: set[str] = set()
    used_volumes: set[str] = set()
    rendered_units: dict[str, str] = {}

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
            rendered_units[service] = files[f"quadlet/systemd/{service}.container"]
            if spec.get("environment") or QUADLET_ONLY_ENV.get(service):
                files[f"quadlet/env.d/{service}.env.tmpl"] = render_vars(service, spec)

            nets = spec.get("networks")
            names = list(nets.keys() if isinstance(nets, dict) else (nets or []))
            used_networks.update(names)
            if service in FRONT_END:
                front_networks[service] = names
            for vol in spec.get("volumes", []) or []:
                source = str(vol).split(":")[0]
                if not source.startswith(("/", "./", "$")):
                    used_volumes.add(source)

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

    _verify_front_end(front_networks, net_defs)
    _verify_internal_networks(rendered_units, net_defs, used_networks)

    return files, notes


def main(argv: Sequence[str] | None = None) -> int:
    """Write the generated files, check them for drift, or list the service dispositions.

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

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
#: container every secret, so the deployer renders one file per service from the `.env.tmpl`
#: template this tool emits. See render_vars for why it is a template and not a list of names.
ENV_DIR_ON_HOST = "/var/iri/code/env.d"

#: Host paths the compose files reach through a variable. Quadlet units are static, so these are
#: resolved here -- to the values the production `.env` already sets. The plan's §3.1 records the
#: alternative (render the units at bundle-build time) and why this one was taken.
# ADR-0187: the edge does not publish its ports to the world any more. A host-level
# haproxy binds :80 and :443 and forwards with PROXY protocol, so the container is
# reachable ONLY from that front end -- which is precisely what makes the header
# unforgeable, since PROXY protocol asserts a source address rather than proving one.
#
# Compose keeps "80:8080" and "443:8443" because the Docker deployment still serves
# them directly; this override is what makes the Quadlet shape differ, deliberately
# and in one readable place rather than by a second copy of the compose file.
#
# The address is PINNED, and that is not neatness. Measured on Rocky 10.2: the peer
# the edge sees is the container's OWN address, and without a pin it moved from
# .2 to .3 across a recreation -- so `set_real_ip_from`, which must name a single
# address, would have been broken by the first restart. Three recreations with the
# pin produced the same peer every time. `IP=` also requires a user-defined bridge
# network, which net-edge-ingress is.
FRONT_END = {
    "edge": {
        "publish": ["127.0.0.1:8080:8080", "[::1]:8080:8080",
                    "127.0.0.1:8443:8443", "[::1]:8443:8443"],
        "network": "net-edge-ingress",
        "ip": "172.28.15.10",
    }
}

# ADR-0189: the stateful services run AS their own uid instead of dropping to it.
#
# Measured on Rocky 10.2 / Podman 5.8.2 with the real digests, data mounts and persistence
# (plan section 20). Each of these images boots as root, chowns its data directory and steps down
# with gosu; giving it the uid up front removes the root phase, and with it every capability:
#
#   postgres   five capabilities -> none.  Four were load-bearing; FOWNER never was.
#   redis      five capabilities -> none.  Only SETGID/SETUID were load-bearing.
#
# The reason this is an override rather than a `user:` in compose is the same as FRONT_END's: the
# Docker deployment still runs these containers, and changing how IT starts a live database is a
# separate change with its own deploy. Compose stays authoritative for everything else.
#
# It is emitted as a SET -- User, Group, ReadOnly, DropCapability=ALL -- because that is the
# combination that was measured. Shipping half of it would ship something nobody ran.
#
# What it costs: the root phase also REPAIRS. If a data directory's ownership is ever wrong, root
# fixes it and `User=` merely fails. That trade is deliberate, and it is safe only because the
# ownership is not a hope: the bootstrap role owns each directory as
# `basetool_host_subuid_base + container_uid - 1`, and `_verify_run_as_against_role` below refuses to
# generate anything if these numbers and the role's stop agreeing.
#
# And the reason it matters more than a tidier unit file: dropping redis's capabilities WITHOUT
# giving it a uid does not fail. Its entrypoint tests `has_cap setuid && has_cap setgid` and
# silently skips the privilege drop, so redis runs as root, answers PING, and writes AOF files as
# 0:0 that the correct configuration can no longer open. A partial capability set is the dangerous
# state here, which is why there is no way to express one.
RUN_AS = {
    "db-backend":  {"uid": 70,  "role_path": "db-backend"},
    "db-keycloak": {"uid": 70,  "role_path": "db-keycloak"},
    "redis":       {"uid": 999, "role_path": "redis"},
}

#: The bootstrap role's own view of those uids. Read at generation time, never transcribed.
ROLE_DEFAULTS = os.path.join(REPO, "ansible", "roles", "basetool_host", "defaults", "main.yml")

# ADR-0190: every remaining container gets a read-only root filesystem, except one.
#
# Measured service by service on Rocky 10.2 / Podman 5.8.2 (plan section 21), in two arms each: the
# image run WRITABLE, with `podman diff` asked what it put on its own root filesystem, and then the
# same thing `--read-only` to see whether it still comes up. Nine of the ten public images write
# NOTHING outside their mounts, or write only under /tmp -- which podman mounts as tmpfs under
# `--read-only` anyway. The application modules were measured the same way against their built
# images: a healthy Spring Boot service writes Tomcat's work directory, its docbase and the JVM
# perf data, all three under /tmp, and nothing else. Their own code contains no filesystem write
# at all.
#
# It is a Quadlet-side table and not `read_only: true` in compose for a reason that is not
# stylistic: **podman mounts /run, /tmp and /var/tmp as tmpfs under `--read-only` and Docker does
# not**. The same line in the compose file would break most of these services on the Docker host
# that still runs them. What was measured is podman's behaviour, so it is expressed where podman
# reads it.
#
# `keycloak` is here too, and it took two passes to get right. `kc.sh start` without `--optimized`
# re-augments the Quarkus application at every boot, and plain read-only stops it dead with
# `FileSystemException: /opt/keycloak/lib/quarkus/transformed-...`. The first reading of that was
# that keycloak simply cannot be read-only. It was wrong on both halves:
#
#   * podman's tmpfs takes `tmpcopyup`, so the image content IS present under the mount; and
#   * the augmentation is ALREADY thrown away -- it lands in the container's writable layer and is
#     redone at every start -- so a tmpfs has exactly the lifetime it already had.
#
# Measured with the real SPI provider JAR staged the way deploy.sh stages it: read-only plus a
# tmpfs over `lib/quarkus` alone -- 4.7M of the 172M tree -- comes up ready, and the augmentation
# compiles the provider in (generated-bytecode.jar differs by 768 bytes against an empty
# providers/, same configuration, one variable). The 476 paths podman diff reported across `lib/`
# were overlay metadata, not writes.
#
# This keeps ADR-0055 intact, which is the point: the provider JAR stays its own signed promotable
# artifact, a provider-only change still auto-applies and still recreates only keycloak, and the
# rollback is still JAR-level. Baking the provider into a custom image and running
# `start --optimized` would buy the same read-only property by dismantling all of that.
READ_ONLY: dict[str, dict[str, Any]] = {
    "prometheus": {},
    "loki": {},
    "tempo": {},
    # Grafana's background installer tries to refresh a BUNDLED plugin inside its own installation
    # directory and logs `unlinkat /usr/share/grafana/data/plugins-bundled/elasticsearch:
    # read-only file system` at every start. It serves regardless -- measured, HTTP 200 on /login --
    # but an error line per boot is exactly what the log-based alerting reads. Disabling the
    # preinstaller removes it and changes nothing this deployment uses: the datasources are
    # provisioned from files and elasticsearch is not one of them. Measured both ways.
    "grafana": {"environment": {"GF_PLUGINS_PREINSTALL_DISABLED": "true"}},
    "alertmanager": {},
    "blackbox-exporter": {},
    "postgres-exporter-backend": {},
    "postgres-exporter-keycloak": {},
    "redis-exporter": {},
    "acme": {},
    # The third entry was missing until 2026-09-17 and cost the login page its
    # styling -- silently, because Keycloak stayed HEALTHY throughout.
    #
    # Keycloak serves a static theme resource two ways. Asked with
    # `Accept-Encoding: identity` it streams the file: 200. Asked with `gzip` --
    # which every browser sends, and which Go's http.Transport adds on its own, so
    # a Go reverse proxy in front sends it too -- it serves a compressed copy from
    # a cache under /opt/keycloak/data/tmp/kc-gzip-cache. On a read-only root
    # filesystem that directory cannot be created, and the response is **404**, not
    # a fallback to the uncompressed file.
    #
    # So: `curl` without the header said 200 and a browser said 404 on the same
    # URL, which reads as a proxy fault and is not one. Measured both ways against
    # the same image, with and without this line: gzip 404 -> gzip 200, and
    # kc-gzip-cache appears.
    "keycloak": {"tmpfs": ["/opt/keycloak/lib/quarkus:rw,tmpcopyup",
                           "/opt/keycloak/data/transaction-logs:rw",
                           "/opt/keycloak/data/tmp:rw"]},
    "backend": {},
    "frontend": {},
    "ingest": {},
}

PATH_VARS = {
    "IRI_KEYSTORE_HOST_PATH": "/var/iri/secrets/keystore.p12",
    "IRI_TRUSTSTORE_HOST_PATH": "/var/iri/secrets/keystore.p12",
    "IRI_REDIS_ACL_HOST_PATH": "/var/iri/redis/users.acl",
    "IRI_UPSTREAM_CA_HOST_PATH": "/var/iri/monitoring/certs/basetool-ca.crt",
}

#: Relative bind-mount sources in compose resolve against the project directory.
PROJECT_DIR_ON_HOST = "/var/iri/code"

#: Non-path compose interpolations, resolved to the value the PROMOTED BUNDLE carries.
#:
#: `IRI_KEYCLOAK_HOST_ALIAS` exists so a non-production environment can point the public Keycloak
#: name at its own host (REQ-OPS-022). Production sets nothing and takes the no-op default, which
#: is what is baked here -- the units are one artifact for every environment, so a host-specific
#: alias cannot live in them.
#:
#: A host that needs a real alias supplies a systemd DROP-IN beside the unit, which is host
#: configuration exactly like the `.env` and belongs to Ansible:
#:
#:     ~/.config/containers/systemd/frontend.container.d/10-host-alias.conf
#:     [Container]
#:     AddHost=basetool.greluc.me:10.98.0.13
#:
#: That is written down here rather than left to be rediscovered, because the failure it prevents
#: is a container timing out against its own issuer at start-up -- which reads as a Keycloak
#: outage and is a missing line.
VALUE_VARS = {
    "IRI_KEYCLOAK_HOST_ALIAS": "localhost:127.0.0.1",
}

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
}

#: Compose profiles whose services are translated. `dev` and `rollback` are local-stack and
#: rollback-only and have no place in a host's unit directory.
TRANSLATED_PROFILES = {"prod"}


#: A compose interpolation. Used to refuse one in a place Quadlet cannot expand it.
VAR_RE = r"\$\{([A-Za-z_][A-Za-z0-9_]*)"


#: Compose service keys this tool actually reads and turns into a unit directive.
#:
#: THIS LIST IS NOT A LIST OF NAMES THE TOOL HAS SEEN. Until 2026-09-17 there was one set, called
#: "known", and the refusal below told the reader to add a key to it *once it is translated*.
#: Eleven keys had been added without that second half, so the guard against silent loss was
#: itself the thing losing them: `extra_hosts` and `init` were declared by the three JVM services
#: and dropped without a word. `init` is the zombie-reaping fix for the 2026-07-12 native-thread
#: OOM, so its silent loss re-creates a production incident the repository has a post-mortem for.
#:
#: The split is the fix. A key belongs here only when code below emits something for it, and in
#: IGNORED_SERVICE_KEYS only with a reason. A key in neither fails the run.
TRANSLATED_SERVICE_KEYS = {
    "image", "profiles", "user", "read_only", "cap_drop", "cap_add",
    "security_opt", "tmpfs", "ports", "networks", "volumes", "environment", "command",
    "entrypoint", "healthcheck", "deploy", "ulimits", "pids_limit", "restart",
    "stop_grace_period", "depends_on", "oom_score_adj",
    "extra_hosts", "init",
}

#: Compose service keys that deliberately produce no unit directive, each with the reason. A key
#: here is a decision on the record, not an omission -- which is the whole difference between this
#: and what the single "known" set used to express.
IGNORED_SERVICE_KEYS = {
    "container_name": (
        "the generator derives ContainerName= from the service name, and the two are asserted "
        "equal for every translated service, so reading the key would add a second source for "
        "one value"
    ),
    "logging": (
        "compose pins json-file with max-size 10m / max-file 5 because the backend alone writes "
        "~150 MB/day and would fill /var/lib/docker/containers. Podman's effective driver here is "
        "journald, which rotates on its own budget instead -- so the SETTING is not translated but "
        "the PROBLEM is not solved either: journald's SystemMaxUse has to carry it, and that is "
        "host configuration (Ansible), not a unit directive"
    ),
}

#: Anything in neither set fails. Keys that were previously listed and never implemented --
#: dns, group_add, hostname, labels, shm_size, sysctls, working_dir -- are deliberately absent:
#: no translated service declares one today, and if one ever does, the refusal must fire rather
#: than the control vanish. Podman 5.8.2 supports DNS=, GroupAdd=, ShmSize=, Sysctl= and
#: WorkingDir= (checked against `man 5 podman-systemd.unit` on the target host), so implementing
#: one is a small change -- which is exactly why it should be made deliberately.
KNOWN_SERVICE_KEYS = TRANSLATED_SERVICE_KEYS | set(IGNORED_SERVICE_KEYS)

assert not (TRANSLATED_SERVICE_KEYS & set(IGNORED_SERVICE_KEYS)), \
    "a compose key cannot be both translated and ignored"
assert all(IGNORED_SERVICE_KEYS.values()), "every ignored key needs a stated reason"

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


def _resolve_value(value: str, where: str) -> str:
    """Resolve a compose interpolation that is a **value** rather than a path.

    Same contract as :func:`_resolve` and a separate table, because the two answer different
    questions: ``PATH_VARS`` records where something lives on the host, ``VALUE_VARS`` records what
    the promoted bundle carries for a setting an environment may override.

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


def _health(hc: dict[str, Any], env: dict[str, Any], service: str) -> list[str]:
    """Translate a compose healthcheck into Quadlet ``Health*`` keys.

    A ``CMD-SHELL`` test keeps its shell, and its ``$VAR`` references are left for the **container**
    to expand rather than baked in here. That is deliberate and is an improvement on the compose
    form: compose interpolates them at file-load time from the host `.env`, which would put values
    such as the database user into a unit file on disk.

    It only works if the NAME is one the container receives, and that is not automatic. Compose
    writes host-side names in a health command because compose expands them on the host;
    ``db-keycloak`` asks for ``@DOLLAR@{KC_POSTGRES_USER}`` while the container is handed that value as
    ``POSTGRES_USER``. Passed through unchanged, the shell inside the container answers
    ``KC_POSTGRES_USER: parameter not set or null`` and the service never reports healthy --
    measured on the testing host, 2026-09-17, which is how this was found. ``db-backend`` survives
    only because its two names happen to match.

    So references are rewritten to the container-side name, taken from the service's own
    environment map.

    Args:
        hc: the compose ``healthcheck`` mapping.
        env: the service's compose ``environment`` map, which says what the container is handed.
        service: the service name, for the refusal message.

    Returns:
        The ``Health…=`` lines, plus ``Notify=healthy`` so systemd's readiness matches what
        ``depends_on: condition: service_healthy`` meant.

    Raises:
        Refusal: when the command names a variable the container is handed under no name at all,
            which cannot be translated and would fail only at health-check time.
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

    # Rewrite host-side names to the names the container actually receives -- see the note above.
    inside = {}
    for container_key, value in (env or {}).items():
        for host in re.findall(r"\$\{([A-Z_0-9]+)", str(value)):
            inside.setdefault(host, container_key)
    for host in sorted(set(re.findall(r"\$\{([A-Z_0-9]+)", cmd))):
        if host in (env or {}):
            continue                      # the container gets this very name
        if host not in inside:
            raise Refusal(
                f"{service}: the health command names ${{{host}}}, and the container is handed that "
                "value under no name at all. Compose expanded it on the host; a shell inside the "
                "container cannot. Add it to the service's environment, or write the health command "
                "in terms of a name the container receives."
            )
        cmd = cmd.replace("${" + host, "${" + inside[host])

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

    run_as = RUN_AS.get(service)
    if run_as is not None:
        if "user" in spec:
            raise Refusal(
                f"{service}: compose says user: {spec['user']!r} and RUN_AS says "
                f"{run_as['uid']}. Two answers to one question -- delete one of them rather "
                "than letting the generator pick."
            )
        # Emitted together because it was measured together; see RUN_AS.
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
            # The one or two paths a read-only service still has to write. Compose's own `tmpfs:`
            # is emitted below; these exist only because the root filesystem became read-only.
            container.append(f"Tmpfs={entry}")
        for key, value in (READ_ONLY.get(service, {}).get("environment") or {}).items():
            # A literal value, set because the unit is read-only -- it never belongs in the host's
            # .env, and it is not a secret, so it is written into the unit rather than routed
            # through EnvironmentFile.
            container.append(f"Environment={key}={value}")
    if any("no-new-privileges" in str(o) for o in spec.get("security_opt", []) or []):
        container.append("NoNewPrivileges=true")
    for tmpfs in spec.get("tmpfs", []) or []:
        container.append(f"Tmpfs={tmpfs}")
    front = FRONT_END.get(service)
    if front:
        # Deliberately NOT spec["ports"]: see FRONT_END. The compose value is the
        # Docker deployment's, and translating it faithfully here would publish the
        # edge to the world behind a front end that trusts a forgeable header.
        for port in front["publish"]:
            container.append(f"PublishPort={port}")
    else:
        for port in spec.get("ports", []) or []:
            container.append(f"PublishPort={port}")

    networks = spec.get("networks")
    names = list(networks.keys()) if isinstance(networks, dict) else list(networks or [])
    for net in names:
        if front and net == front["network"]:
            # Quadlet takes the address as an option on the Network= line.
            container.append(f"Network={net}.network:ip={front['ip']}")
        else:
            container.append(f"Network={net}.network")

    for vol in spec.get("volumes", []) or []:
        container.append(f"Volume={_volume(str(vol), service)}")

    if spec.get("environment"):
        container.append(f"EnvironmentFile={ENV_DIR_ON_HOST}/{service}.env")

    container += _exec(service, spec)
    container += _health(spec.get("healthcheck") or {}, spec.get("environment") or {}, service)

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
    # `init: true` runs a minimal init as PID 1 so orphaned children get reaped. This is not a
    # nicety: the JVM runs as PID 1 and does NOT reap, the health probe's BusyBox wget forks an
    # `ssl_client` helper it never waits on, and each probe left one <defunct> until the pids cap
    # was reached -- the 2026-07-12 native-thread OOM, at roughly 17h uptime. Quadlet 5.8.2 has no
    # Init= key (checked against `man 5 podman-systemd.unit` on the target host), so it goes
    # through podman's own flag.
    if spec.get("init"):
        podman_args.append("--init")
    # `extra_hosts` -> AddHost=, which Podman 5.8.2 documents with the same `hostname:ip` form
    # compose uses and allows more than once. The value is a VALUE_VARS interpolation rather than
    # a path, so it resolves against that table and refuses on anything unrecorded -- a literal
    # `${IRI_...}` in an /etc/hosts entry would be a hostname nothing ever matches.
    for entry in spec.get("extra_hosts") or []:
        resolved = _resolve_value(str(entry), f"{service}.extra_hosts")
        container.append(f"AddHost={resolved}")
    nofile = (spec.get("ulimits") or {}).get("nofile")
    if isinstance(nofile, dict):
        podman_args.append(f"--ulimit nofile={nofile['soft']}:{nofile['hard']}")
    if "pids_limit" in spec:
        container.append(f"PidsLimit={spec['pids_limit']}")
    if podman_args:
        container.append("PodmanArgs=" + " ".join(podman_args))

    if spec.get("restart"):
        # compose says `unless-stopped` (or `always`), which means: retry indefinitely, with a
        # backoff that grows and caps out. Restart=always alone does NOT mean that. systemd stops a
        # unit for good after StartLimitBurst starts inside StartLimitIntervalSec -- five starts in
        # ten seconds by default -- and with systemd's default RestartSec=100ms a container that
        # fails fast burns all five within a second. Measured on the testing VM 2026-09-16: the edge
        # reached "Start request repeated too quickly" and stayed down. A database that is merely
        # slow to come up is survivable under Compose and terminal under Quadlet without the three
        # keys below.
        service_section += [
            "Restart=always",
            # The backoff itself: 1s, doubling-ish through RestartSteps intervals, capped at 60s --
            # the shape Docker's restart policy has. RestartSteps=/RestartMaxDelaySec= need
            # systemd >= 254; the target platform ships 257.
            "RestartSec=1",
            "RestartSteps=6",
            "RestartMaxDelaySec=60",
        ]
        # And the give-up rule, stated as ONE decision rather than left to interact.
        #
        # Backoff and the start limiter quietly cancel each other out: once the interval reaches the
        # 60s cap, at most ten starts fit in a ten-minute window, so a burst of 60 is never reached
        # and the limit never fires. The unit would still CARRY a limit that reads as if it applied.
        # So the choice is made explicitly here: never give up. That is what `unless-stopped` means,
        # it is what this deployment does today, and it is defensible because this stack is
        # monitored -- a service that is down raises an alert, so a `failed` unit is not the only
        # signal anyone would get. An edge that stays down after five minutes is a total outage; one
        # that keeps retrying every 60s recovers by itself when the cause clears.
        unit.append("StartLimitIntervalSec=0")
    grace = spec.get("stop_grace_period")
    if grace:
        service_section.append(f"TimeoutStopSec={str(grace).rstrip('s')}")

    out = ["# Generated by scripts/generate-quadlet.py -- do not edit. Run the generator."]
    if unit:
        # StartLimitIntervalSec= belongs in [Unit]. systemd.service(5) does not mention it at all --
        # in [Service] it is silently ignored, which is the failure mode where the unit reads as
        # limited and is not. Checked against systemd 257's own man pages on the target host.
        out += ["[Unit]"] + unit + [""]
    out += ["[Container]"] + container + [""]
    out += ["[Service]"] + (service_section or ["Restart=always"]) + [""]
    out += ["[Install]", "WantedBy=default.target"]
    return "\n".join(out) + "\n"


def render_vars(service: str, spec: dict[str, Any]) -> str:
    """Render the per-service environment TEMPLATE the deployer fills in.

    This emitted a list of NAMES until 2026-09-17, on the theory that the deployer would look each
    one up in the host ``.env``. Measured against the compose files before the units were ever
    started, that theory covers **25 of 168** entries. The other 143 are not names to look up:

    * **58 are literal values** -- ``PGPORT: 15432``, ``PGDATA: /var/lib/postgresql/data/pgdata``,
      ``KC_DB: postgres``. They exist only in the compose file and are in no ``.env`` anywhere, so
      a name list hands the container nothing. Postgres would have started on its built-in
      defaults: a new empty cluster in the wrong directory, on the wrong port, behind a health
      check that can never pass.
    * **85 are composite or defaulted** -- ``jdbc:postgresql://db-keycloak:15433/${KC_POSTGRES_DB}``
      interpolates INTO a longer string, and ``${KC_METRICS_ENABLED:-false}`` carries a default that
      the name alone does not.

    So the artifact is a template: the compose right-hand side, verbatim, one ``KEY=value`` per
    line. The deployer renders it by interpolating against the host ``.env`` -- which is what
    compose itself did, and is why the values survive the translation.

    The closed-allow-list property that motivated the original design is unchanged, and is still
    why this is one file per service: a service sees only the variables its own compose map names,
    and one shared ``EnvironmentFile=`` would hand every container every secret. **No secret is in
    this file.** A secret appears as the same ``${NAME:?...}`` reference compose carries, and its
    value exists only on the host.

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



# ============================================================================================
# Driving
# ============================================================================================
def _verify_run_as_against_role() -> None:
    """Refuse if the hardening tables disagree with the bootstrap role, or with each other.

    ``User=70`` in a unit and a data directory owned as if the container were uid 70 are the same
    fact written in two repositories' worth of tooling. They agree today. This is what notices the
    day one of them is edited and the other is not -- at generation time, where the answer is a
    failed build, rather than at boot, where it is a database that will not start.

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
                files[f"quadlet/env.d/{service}.env.tmpl"] = render_vars(service, spec)

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

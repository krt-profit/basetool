#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Assert that the OIDC issuer the apps validate is the one Keycloak advertises.

Renders each stack with ``docker compose config`` and checks the result (ADR-0166):

* **Rule A.** A full-URL ``KC_HOSTNAME`` has the same path as ``KC_HTTP_RELATIVE_PATH``.
* **Rule B.** Every configured issuer is the one Keycloak advertises (exact for a full URL; host
  and path only for a bare hostname).
* **Rule C.** The apps in one stack agree on the issuer.
* **Rule D.** The Spring ``${KEYCLOAK_ISSUER_URI:<literal>}`` fallbacks match the production default.
* **Rule E.** Grafana's three OIDC endpoints sit on the issuer and follow a hostname override.
* **Rule F.** The identity probes in the static ``prometheus.yml`` name the deployed base.

Usage:
    python scripts/check-keycloak-issuer.py [--repo-root DIR]

Exits non-zero and prints every problem it found; prints a one-line summary per stack when clean.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

REALM = "iri"

PROD_BASE = "https://profit-base.online/auth"

FAKE_BASE = "https://basetool.example.test/auth"
FAKE_ISSUER_OVERRIDE = "https://identity.example.test/auth/realms/" + REALM

TEST_ISSUER = "http://host.docker.internal:18080/auth/realms/" + REALM
ANDROID_ISSUER = "http://127.0.0.1:18080/auth/realms/" + REALM

REQUIRED_VAR = re.compile(r"\$\{([A-Z0-9_]+):\?")
PLACEHOLDER = "placeholder-not-a-real-value"

SPRING_ISSUER = re.compile(r"issuer-uri:\s*\$\{KEYCLOAK_ISSUER_URI(?::([^}]*))?\}")

SPRING_CONFIGS = (
    "backend/src/main/resources/application-prod.yml",
    "backend/src/main/resources/application-dev.yml",
    "frontend/src/main/resources/application-prod.yml",
    "frontend/src/main/resources/application-dev.yml",
    "ingest/src/main/resources/application.yml",
    "ingest/src/main/resources/application-prod.yml",
    "ingest/src/main/resources/application-dev.yml",
)

GRAFANA_OIDC_ENDPOINTS = {
    "GF_AUTH_GENERIC_OAUTH_AUTH_URL": "auth",
    "GF_AUTH_GENERIC_OAUTH_TOKEN_URL": "token",
    "GF_AUTH_GENERIC_OAUTH_API_URL": "userinfo",
}

PROM_URL = re.compile(r"https?://[^\s\"']+")

REQUIRED_PROBE_SUFFIXES = (
    "/realms/{realm}/.well-known/openid-configuration",
    "/health",
    "/metrics",
)

BASE = "docker-compose.yml"
TEST = "docker-compose.test.yml"
BUILD = "docker-compose.build.yml"
E2E = "docker-compose.e2e.yml"
ANDROID = "docker-compose.android.yml"
LOCALTEST = "docker-compose.localtest.yml"
MONITORING = "docker-compose.monitoring.yml"
PROMETHEUS = "monitoring/prometheus/prometheus.yml"


class Scenario:
    """One rendered stack: which compose files, which profile, which environment.

    Attributes:
        name: what the scenario is called in the report.
        files: the compose files, in precedence order, exactly as that stack is started.
        profile: the compose profile to render ("prod" or "dev"); required, since every service
            carries a profile.
        env: environment overrides layered on top of the placeholder set.
        expect: the issuer every app must end up with, or a {service: issuer} mapping when the
            stack is deliberately not uniform, or None to apply only the rules.
        why: one line on what this scenario protects, printed when it fails.
        enforce_pair: False for the documented escape hatch where the issuer is overridden to
            something that deliberately differs from KC_HOSTNAME.
        require_agreement: False for a stack that is deliberately split-horizon.
        pair_services: when set, only these services are held to rule B.
    """

    def __init__(
        self,
        name,
        files,
        profile,
        env,
        expect,
        why,
        enforce_pair=True,
        require_agreement=True,
        pair_services=None,
    ):
        self.name = name
        self.files = files
        self.profile = profile
        self.env = env
        self.expect = expect
        self.why = why
        self.enforce_pair = enforce_pair
        self.require_agreement = require_agreement
        self.pair_services = pair_services


SCENARIOS = [
    Scenario(
        "prod-defaults",
        [BASE],
        "prod",
        {},
        PROD_BASE + "/realms/" + REALM,
        "what production deploys when .env sets neither variable",
    ),
    Scenario(
        "prod-hostname-override",
        [BASE],
        "prod",
        {"IRI_KEYCLOAK_HOSTNAME": FAKE_BASE},
        FAKE_BASE + "/realms/" + REALM,
        "the issuer follows the hostname -- this is what fails the moment the issuer becomes a "
        "second independent literal again",
    ),
    Scenario(
        "prod-issuer-override",
        [BASE],
        "prod",
        {"IRI_KEYCLOAK_HOSTNAME": FAKE_BASE, "IRI_KEYCLOAK_ISSUER_URI": FAKE_ISSUER_OVERRIDE},
        FAKE_ISSUER_OVERRIDE,
        "the explicit issuer still wins, for a deployment where the two genuinely differ",
        enforce_pair=False,
    ),
    Scenario(
        "test-stack",
        [BASE, TEST],
        "dev",
        {},
        None,
        "the isolated local test stack: bare hostname plus /auth",
    ),
    Scenario(
        "android-stack",
        [BASE, TEST, ANDROID],
        "dev",
        {},
        {
            "backend-dev": ANDROID_ISSUER,
            "frontend-dev": TEST_ISSUER,
            "ingest-dev": TEST_ISSUER,
        },
        "the emulator split-horizon override (the app repo's three-file stack)",
        require_agreement=False,
        pair_services=["backend-dev"],
    ),
    Scenario(
        "e2e-stack",
        [BASE, TEST, BUILD, E2E],
        "dev",
        {},
        None,
        "the E2E isolation stack -- the file whose origin-only KC_HOSTNAME broke ADR-0166's first "
        "draft",
    ),
    Scenario(
        "localtest-stack",
        [BASE, TEST, BUILD, E2E, LOCALTEST],
        "dev",
        {},
        None,
        "the local-run variant of the E2E stack",
    ),
]


def placeholder_env(root: Path, files) -> dict:
    """Builds the environment ``docker compose config`` needs in order to render at all.

    Every ``${NAME:?...}`` in the given compose files gets a placeholder.

    Args:
        root: the repository root.
        files: the compose file names to scan.

    Returns:
        A mapping of variable name to placeholder value.
    """
    env = {}
    for name in files:
        text = (root / name).read_text(encoding="utf-8")
        for var in REQUIRED_VAR.findall(text):
            env[var] = PLACEHOLDER
    return env


def render_files(root: Path, files, profile, overrides) -> dict:
    """Renders a set of compose files through compose's own interpolation.

    Args:
        root: the repository root.
        files: the compose file names, in precedence order.
        profile: the compose profile to render, or None.
        overrides: environment overrides layered on top of the placeholder set.

    Returns:
        The ``services`` mapping of the rendered configuration.

    Raises:
        RuntimeError: if ``docker compose config`` fails, with its stderr attached.
    """
    env = dict(os.environ)
    for key in ("IRI_KEYCLOAK_HOSTNAME", "IRI_KEYCLOAK_ISSUER_URI"):
        env.pop(key, None)
    env.update(placeholder_env(root, files))
    env.update(overrides)

    cmd = ["docker", "compose"]
    for name in files:
        cmd += ["-f", name]
    if profile:
        cmd += ["--profile", profile]
    cmd += ["config", "--format", "json"]

    proc = subprocess.run(cmd, cwd=str(root), env=env, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(
            "`%s` failed (exit %d):\n%s" % (" ".join(cmd), proc.returncode, proc.stderr.strip())
        )
    return json.loads(proc.stdout).get("services", {})


def render(root: Path, scenario: Scenario) -> dict:
    """Renders one scenario's stack.

    Args:
        root: the repository root.
        scenario: the stack to render.

    Returns:
        The ``services`` mapping of the rendered configuration.

    Raises:
        RuntimeError: if ``docker compose config`` fails.
    """
    return render_files(root, scenario.files, scenario.profile, scenario.env)


def collect(services: dict):
    """Pulls the identity settings out of a rendered stack.

    Args:
        services: the rendered ``services`` mapping.

    Returns:
        A tuple of (KC_HOSTNAME, KC_HTTP_RELATIVE_PATH, {service name: issuer}). The first two are
        None if no service in this stack runs Keycloak.
    """
    hostname = None
    relative_path = None
    issuers = {}
    for name, service in sorted(services.items()):
        env = service.get("environment") or {}
        if env.get("KC_HOSTNAME"):
            hostname = env["KC_HOSTNAME"]
            relative_path = env.get("KC_HTTP_RELATIVE_PATH") or "/"
        if env.get("KEYCLOAK_ISSUER_URI"):
            issuers[name] = env["KEYCLOAK_ISSUER_URI"]
    return hostname, relative_path, issuers


def norm_path(path: str) -> str:
    """Normalises a URL path for comparison: leading slash, no trailing slash, root is ``""``.

    Args:
        path: a path component, possibly empty or missing its leading slash.

    Returns:
        The normalised path, e.g. ``/auth``, or ``""`` for the root.
    """
    path = (path or "").strip()
    if path and not path.startswith("/"):
        path = "/" + path
    return path.rstrip("/")


def split_issuer(issuer: str):
    """Splits an issuer URI into the base Keycloak advertises and the realm.

    Args:
        issuer: e.g. ``https://profit-base.online/auth/realms/iri``.

    Returns:
        A tuple of (base, realm) -- e.g. (``https://profit-base.online/auth``, ``iri``) -- or
        (issuer, None) if it carries no ``/realms/`` segment at all.
    """
    marker = "/realms/"
    idx = issuer.rfind(marker)
    if idx < 0:
        return issuer, None
    return issuer[:idx], issuer[idx + len(marker) :]


def check_scenario(root: Path, scenario: Scenario, problems: list) -> str:
    """Renders one scenario and applies rules A to C to it.

    Args:
        root: the repository root.
        scenario: the stack to check.
        problems: accumulator; every failure is appended as a formatted line.

    Returns:
        A one-line summary of what was found, for the clean-run report.
    """
    try:
        services = render(root, scenario)
    except RuntimeError as exc:
        problems.append("%s: %s" % (scenario.name, exc))
        return "%s: NOT RENDERED" % scenario.name

    hostname, relative_path, issuers = collect(services)

    if not issuers:
        problems.append(
            "%s: no service sets KEYCLOAK_ISSUER_URI -- either the compose file list or the profile "
            "is wrong (every service here carries a `profiles:` key, and a render without one is "
            "empty), or the variable was renamed. Either way this scenario checked nothing."
            % scenario.name
        )
        return "%s: NO ISSUERS" % scenario.name

    distinct = sorted(set(issuers.values()))
    if scenario.require_agreement and len(distinct) > 1:
        problems.append(
            "%s: the services do not agree on the issuer -- %s. Three apps validating two issuers "
            "is the same outage as one wrong issuer."
            % (scenario.name, ", ".join("%s=%s" % (n, i) for n, i in sorted(issuers.items())))
        )

    if scenario.expect:
        expected_map = scenario.expect
        if isinstance(expected_map, str):
            expected_map = {name: expected_map for name in issuers}
        for name, issuer in sorted(issuers.items()):
            wanted = expected_map.get(name)
            if wanted is None:
                problems.append(
                    "%s: %s sets KEYCLOAK_ISSUER_URI=%s and this scenario does not account for it "
                    "-- a new service joined the stack; decide which issuer it must validate"
                    % (scenario.name, name, issuer)
                )
            elif issuer != wanted:
                problems.append(
                    "%s: %s validates %s but this scenario requires %s -- %s"
                    % (scenario.name, name, issuer, wanted, scenario.why)
                )
        for name in sorted(set(expected_map) - set(issuers)):
            problems.append(
                "%s: %s is expected to validate %s but sets no KEYCLOAK_ISSUER_URI at all"
                % (scenario.name, name, expected_map[name])
            )

    if hostname is None:
        problems.append(
            "%s: no service sets KC_HOSTNAME, so the issuer above is checked against nothing"
            % scenario.name
        )
        return "%s: NO KEYCLOAK" % scenario.name

    full_url = "://" in hostname
    host_path = norm_path(urlsplit(hostname).path) if full_url else None
    rel = norm_path(relative_path)

    if full_url and host_path != rel:
        problems.append(
            "%s: KC_HOSTNAME=%s has path %r but KC_HTTP_RELATIVE_PATH=%r. Keycloak would SERVE "
            "under %r and ADVERTISE issuer links under %r, report healthy, and the apps would die "
            "at start-up on 'The Issuer ... did not match the requested issuer' -- which reads as a "
            "backend fault (ADR-0166)."
            % (scenario.name, hostname, host_path or "/", relative_path, rel or "/", host_path or "/")
        )

    if not scenario.enforce_pair:
        return "%s: issuer %s (explicit override; pair check skipped by design)" % (
            scenario.name,
            distinct[0],
        )

    for name, issuer in sorted(issuers.items()):
        if scenario.pair_services is not None and name not in scenario.pair_services:
            continue
        base, realm = split_issuer(issuer)
        if realm is None:
            problems.append(
                "%s: %s has KEYCLOAK_ISSUER_URI=%s, which names no realm"
                % (scenario.name, name, issuer)
            )
            continue
        if full_url:
            expected = hostname.rstrip("/")
            if base.rstrip("/") != expected:
                problems.append(
                    "%s: %s validates issuer base %s, but KC_HOSTNAME=%s makes Keycloak advertise "
                    "%s" % (scenario.name, name, base, hostname, expected)
                )
        else:
            parts = urlsplit(base)
            if parts.hostname != hostname:
                problems.append(
                    "%s: %s validates issuer host %s, but KC_HOSTNAME=%s"
                    % (scenario.name, name, parts.hostname, hostname)
                )
            if norm_path(parts.path) != rel:
                problems.append(
                    "%s: %s validates issuer path %r, but KC_HTTP_RELATIVE_PATH=%r -- Keycloak "
                    "serves its realms under that prefix"
                    % (scenario.name, name, norm_path(parts.path) or "/", relative_path)
                )

    return "%s: KC_HOSTNAME=%s + %s -> %s (%d service(s))" % (
        scenario.name,
        hostname,
        relative_path,
        " and ".join(distinct),
        len(issuers),
    )


def check_spring_defaults(root: Path, problems: list) -> str:
    """Rule D -- the Spring fallback defaults must match the compose production default.

    Args:
        root: the repository root.
        problems: accumulator; every mismatch is appended.

    Returns:
        A one-line summary for the clean-run report.
    """
    expected = PROD_BASE + "/realms/" + REALM
    checked = 0
    for rel in SPRING_CONFIGS:
        path = root / rel
        if not path.exists():
            problems.append("spring-defaults: %s does not exist -- the file list is stale" % rel)
            continue
        found = SPRING_ISSUER.findall(path.read_text(encoding="utf-8"))
        if not found:
            problems.append(
                "spring-defaults: %s no longer configures issuer-uri from KEYCLOAK_ISSUER_URI -- "
                "this gate has stopped watching it" % rel
            )
            continue
        for default in found:
            if not default:
                continue
            checked += 1
            if default != expected:
                problems.append(
                    "spring-defaults: %s falls back to %s, but docker-compose.yml deploys %s. An "
                    "app started without KEYCLOAK_ISSUER_URI would validate the wrong issuer."
                    % (rel, default, expected)
                )
    return "spring-defaults: %d fallback default(s) match %s" % (checked, expected)


def check_monitoring_oidc(root: Path, problems: list) -> str:
    """Rule E -- Grafana's three OIDC endpoints sit on the issuer the apps validate.

    Checked on the production defaults and with ``IRI_KEYCLOAK_HOSTNAME`` overridden.

    Args:
        root: the repository root.
        problems: accumulator; every mismatch is appended.

    Returns:
        A one-line summary for the clean-run report.
    """
    cases = [
        ("defaults", {}, PROD_BASE),
        ("hostname-override", {"IRI_KEYCLOAK_HOSTNAME": FAKE_BASE}, FAKE_BASE),
    ]
    checked = 0
    for label, overrides, base in cases:
        try:
            services = render_files(root, [MONITORING], None, overrides)
        except RuntimeError as exc:
            problems.append("monitoring-oidc (%s): %s" % (label, exc))
            return "monitoring-oidc: NOT RENDERED"

        env = (services.get("grafana") or {}).get("environment") or {}
        if not env:
            problems.append(
                "monitoring-oidc (%s): docker-compose.monitoring.yml has no `grafana` service with "
                "an environment -- this rule has stopped watching anything" % label
            )
            return "monitoring-oidc: NO GRAFANA"

        expected_prefix = "%s/realms/%s/protocol/openid-connect" % (base, REALM)
        for key, endpoint in sorted(GRAFANA_OIDC_ENDPOINTS.items()):
            actual = env.get(key)
            if not actual:
                problems.append(
                    "monitoring-oidc (%s): %s is not set. Grafana's generic_oauth has no discovery "
                    "option, so a missing endpoint is a login that cannot complete." % (label, key)
                )
                continue
            checked += 1
            expected = "%s/%s" % (expected_prefix, endpoint)
            if actual != expected:
                problems.append(
                    "monitoring-oidc (%s): %s is %s but the apps validate issuer %s/realms/%s, so it "
                    "should be %s. Grafana would send members to a realm other than the one minting "
                    "their tokens, and the login fails with nothing wrong in any log."
                    % (label, key, actual, base, REALM, expected)
                )
    return "monitoring-oidc: %d Grafana endpoint(s) follow the issuer, defaults and override" % checked


def check_prometheus_targets(root: Path, problems: list) -> str:
    """Rule F -- the Prometheus identity probes name the deployed identity base.

    Every absolute URL at or under the identity path (segment-exact) must be on the deployed base,
    and every :data:`REQUIRED_PROBE_SUFFIXES` target must be present.

    Args:
        root: the repository root.
        problems: accumulator; every mismatch is appended.

    Returns:
        A one-line summary for the clean-run report.
    """
    path = root / PROMETHEUS
    if not path.exists():
        problems.append("prometheus-targets: %s does not exist -- the file list is stale" % PROMETHEUS)
        return "prometheus-targets: MISSING"

    identity_path = norm_path(urlsplit(PROD_BASE).path)
    text = path.read_text(encoding="utf-8")

    identity_targets = []
    for url in PROM_URL.findall(text):
        url = url.rstrip(",")
        parts = urlsplit(url)
        target_path = norm_path(parts.path)
        if target_path == identity_path or target_path.startswith(identity_path + "/"):
            identity_targets.append(url)

    if not identity_targets:
        problems.append(
            "prometheus-targets: no probe target under %r at all. The Keycloak discovery document "
            "and the two management-surface denies are supposed to be probed from outside "
            "(REQ-OBS-012); finding none means they were dropped or the base moved without this "
            "rule noticing." % identity_path
        )
        return "prometheus-targets: NONE FOUND"

    for url in sorted(set(identity_targets)):
        if not url.startswith(PROD_BASE + "/") and url != PROD_BASE:
            problems.append(
                "prometheus-targets: %s is probed, but docker-compose.yml deploys identity at %s. "
                "prometheus.yml is not interpolated, so a domain move never reaches it on its own."
                % (url, PROD_BASE)
            )

    present = set(identity_targets)
    for suffix in REQUIRED_PROBE_SUFFIXES:
        expected = PROD_BASE + suffix.format(realm=REALM)
        if expected not in present:
            problems.append(
                "prometheus-targets: %s is not probed. Either it was dropped or it was moved off the "
                "identity base -- both leave the endpoint uncovered, and an uncovered probe is "
                "indistinguishable from a passing one (REQ-OBS-012)." % expected
            )

    return "prometheus-targets: %d identity probe(s) on %s, all %d required present" % (
        len(present),
        PROD_BASE,
        len(REQUIRED_PROBE_SUFFIXES),
    )


def check_prod_base_is_real(root: Path, problems: list) -> None:
    """Pins PROD_BASE to docker-compose.yml, so this file cannot drift from the stack it checks.

    Args:
        root: the repository root.
        problems: accumulator; a mismatch is appended.
    """
    text = (root / BASE).read_text(encoding="utf-8")
    if ("KC_HOSTNAME: ${IRI_KEYCLOAK_HOSTNAME:-%s}" % PROD_BASE) not in text:
        problems.append(
            "self-check: docker-compose.yml no longer defaults KC_HOSTNAME to %s, so this script's "
            "PROD_BASE -- and every expectation built from it -- is stale. Update PROD_BASE in "
            "scripts/check-keycloak-issuer.py in the same change." % PROD_BASE
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--repo-root",
        default=str(Path(__file__).resolve().parent.parent),
        help="repository root (default: the parent of scripts/)",
    )
    parser.add_argument(
        "--only",
        default="",
        help=(
            "comma-separated check names to run instead of all of them -- any stack scenario, plus "
            "spring-defaults, monitoring-oidc and prometheus-targets. A debugging and self-test aid: "
            "each stack costs a `docker compose config` render, and the regression suite has no "
            "reason to pay for all of them per mutation. CI runs the gate with no filter."
        ),
    )
    args = parser.parse_args()
    root = Path(args.repo_root).resolve()

    extra_checks = {
        "spring-defaults": check_spring_defaults,
        "monitoring-oidc": check_monitoring_oidc,
        "prometheus-targets": check_prometheus_targets,
    }

    scenarios = SCENARIOS
    extras = list(extra_checks)
    if args.only:
        wanted = [name.strip() for name in args.only.split(",") if name.strip()]
        known = {s.name for s in SCENARIOS} | set(extra_checks)
        unknown = [name for name in wanted if name not in known]
        if unknown:
            print(
                "unknown check(s): %s (known: %s)" % (", ".join(unknown), ", ".join(sorted(known))),
                file=sys.stderr,
            )
            return 2
        scenarios = [s for s in SCENARIOS if s.name in wanted]
        extras = [name for name in extra_checks if name in wanted]

    problems: list = []
    summaries = []

    check_prod_base_is_real(root, problems)
    for scenario in scenarios:
        summaries.append(check_scenario(root, scenario, problems))
    for name in extras:
        summaries.append(extra_checks[name](root, problems))

    if problems:
        print("Keycloak issuer check FAILED (%d problem(s)):\n" % len(problems), file=sys.stderr)
        for problem in problems:
            print("  - %s" % problem, file=sys.stderr)
        print(
            "\nThe issuer the apps validate must be the one Keycloak advertises. ADR-0166 carries "
            "the measured hostname/relative-path table.",
            file=sys.stderr,
        )
        return 1

    print(
        "Keycloak issuer OK -- %d stack(s) and %d further surface(s) checked:"
        % (len(scenarios), len(extras))
    )
    for summary in summaries:
        print("  %s" % summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())

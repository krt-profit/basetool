#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
"""Assert that the OIDC issuer the apps validate is the one Keycloak advertises.

WHY THIS GATE EXISTS
--------------------
The identity base URL is a literal on roughly 36 lines across 19 files, and until now **nothing
checked that any two of them agreed**. ``.env.example`` stated the invariant as manual discipline --
"SET BOTH OR NEITHER", the two values "must agree" -- which is a comment, not a check.

The failure it guards against is measured rather than imagined; ADR-0166 records the experiment.
Keycloak's public base URL is decided by *two* settings that have to say the same thing::

    KC_HOSTNAME         KC_HTTP_RELATIVE_PATH   serves at   advertised issuer
    https://host        /auth                   /auth       https://host/realms/...    BROKEN
    https://host/auth   /auth                   /auth       https://host/auth/realms/  correct
    https://host/auth   (unset)                 /           --                         needs a
                                                                                       stripping proxy

The first row is the trap, and every part of it is quiet. Keycloak answers on ``/auth``, its
discovery document parses, and the container reports **healthy** -- while backend, frontend and
ingest each die at start-up on::

    The Issuer "https://host/realms/iri" did not match the requested issuer
    "https://host/auth/realms/iri"

which reads as a backend fault and is in fact a topology mismatch. A first draft of ADR-0166
specified exactly that combination, on the strength of the upstream reverse-proxy guide's wording;
only running the smoke stack caught it. That is a failed deploy, found at deploy time, by a human,
at the one moment nobody wants to be reading Keycloak's hostname documentation.

WHY IT CHECKS THE RENDERED STACK RATHER THAN THE FILES
------------------------------------------------------
``docker-compose.yml`` no longer carries the issuer as an independent value: it *derives* it,
``${IRI_KEYCLOAK_ISSUER_URI:-${IRI_KEYCLOAK_HOSTNAME:-...}/realms/iri}``. Re-implementing that
nested interpolation here in order to check it would be asserting a copy of the thing under test
against itself. So this gate renders the real stacks with ``docker compose config`` -- compose's own
interpolation, the same code path the deploy uses -- and checks the *result*. Same principle as
``check-edge-nginx.sh``, which renders the vhost templates and hands them to a real ``nginx -t``
rather than pattern-matching the templates.

Rendering also buys the property that matters most and is invisible in any single file: **that the
derivation actually follows**. The ``prod-hostname-override`` scenario sets ``IRI_KEYCLOAK_HOSTNAME``
alone and requires all three apps to move with it. Turn the issuer back into a second independent
literal and that scenario fails at once -- the hostname moves, the issuer does not.

The rules, in the order they are reported:

* **Rule A -- the hostname's path is the serving path.** Only when ``KC_HOSTNAME`` is a full URL.
  A *bare* hostname is not the broken row: scheme, port and context path all come from the request
  when no full URL is configured, which is what the test stack relies on and what ADR-0166 measured
  separately (``host.docker.internal`` + ``/auth`` advertises ``.../auth/realms/...`` correctly).
* **Rule B -- every configured issuer is the one Keycloak will advertise.** With a full-URL
  hostname that is exact, down to scheme and port. With a bare hostname, host and path are pinned
  and scheme/port are left to the request, because that is genuinely what Keycloak does.
* **Rule C -- the apps in one stack agree with each other.** Three services validating two
  different issuers is the same outage as validating one wrong one.
* **Rule D -- the Spring fallback defaults match the compose production default.** The
  ``application*.yml`` files carry ``${KEYCLOAK_ISSUER_URI:<literal>}``. That literal is what an app
  validates when the variable is absent, it sits nowhere near the compose file, and moving the
  domain without it leaves a build that silently trusts the retired issuer.

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

# The realm this project runs. Used only to build the EXPECTED issuer in the scenarios below; the
# rules themselves split an issuer on "/realms/" instead, so a realm rename needs no edit here.
REALM = "iri"

# The production identity base URL, as baked into docker-compose.yml. Pinned back to that file by
# check_prod_base_is_real() below, so this constant cannot drift away from the stack it checks.
PROD_BASE = "https://profit-base.online/auth"

# A throwaway domain for the override scenarios. RFC 2606 reserves .test and it resolves nowhere --
# nothing here is deployed, contacted or trusted, only rendered.
FAKE_BASE = "https://basetool.example.test/auth"
FAKE_ISSUER_OVERRIDE = "https://identity.example.test/auth/realms/" + REALM

# The two local-stack issuers, pinned so the android override's deliberate split-horizon (below) is
# asserted in both directions rather than merely tolerated.
TEST_ISSUER = "http://host.docker.internal:18080/auth/realms/" + REALM
ANDROID_ISSUER = "http://127.0.0.1:18080/auth/realms/" + REALM

# Every ${NAME:?...} in the compose files has to be set for `docker compose config` to render at
# all. The names are harvested from the files rather than listed, so a new required variable does
# not quietly break this gate. The VALUES are placeholders and must stay that way: a real credential
# in a CI log is a leaked credential (CLAUDE.md, Testing).
REQUIRED_VAR = re.compile(r"\$\{([A-Z0-9_]+):\?")
PLACEHOLDER = "placeholder-not-a-real-value"

# `issuer-uri: ${KEYCLOAK_ISSUER_URI:<default>}` in the Spring configs. The default is optional --
# ingest's prod profile deliberately has none, which makes the variable required there.
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

BASE = "docker-compose.yml"
TEST = "docker-compose.test.yml"
BUILD = "docker-compose.build.yml"
E2E = "docker-compose.e2e.yml"
ANDROID = "docker-compose.android.yml"
LOCALTEST = "docker-compose.localtest.yml"


class Scenario:
    """One rendered stack: which compose files, which profile, which environment.

    Every service in this repository carries a ``profiles:`` key, so a render without one produces
    an empty service list and a gate that passes by checking nothing. ``profile`` is therefore
    required, not optional -- and ``check_scenario`` fails loudly on an empty result rather than
    reporting success.

    Attributes:
        name: what the scenario is called in the report.
        files: the compose files, in precedence order, exactly as that stack is started.
        profile: the compose profile to render -- "prod" for the deployed services, "dev" for the
            local stacks.
        env: environment overrides layered on top of the placeholder set.
        expect: the issuer every app must end up with, or a {service: issuer} mapping when the
            stack is deliberately not uniform, or None to apply only the rules.
        why: one line on what this scenario protects, printed when it fails.
        enforce_pair: False for the documented escape hatch where the issuer is overridden to
            something that deliberately differs from KC_HOSTNAME.
        require_agreement: False for a stack that is deliberately split-horizon.
        pair_services: when set, only these services are held to rule B; the rest are documented
            casualties of a split-horizon override.
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
    # The one stack that is deliberately NOT uniform, so the exemption is spelled out rather than
    # left as a silent skip. docker-compose.android.yml pins Keycloak's advertised issuer to
    # 127.0.0.1 (what the emulator reaches through `adb reverse`) and realigns ONLY backend-dev,
    # which is the service that has to validate the app's tokens. frontend-dev and ingest-dev keep
    # the test stack's host.docker.internal and therefore stop accepting logins -- the override's
    # own header says so: "with this in effect the web frontend's own login no longer resolves the
    # issuer from inside its container". Both halves are pinned below, so the day that arrangement
    # changes this gate reports it instead of shrugging.
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

    Every ``${NAME:?...}`` in the given compose files gets a placeholder, so a render fails on a
    real problem rather than on an unset password.

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


def render(root: Path, scenario: Scenario) -> dict:
    """Renders one scenario's stack through compose's own interpolation.

    Args:
        root: the repository root.
        scenario: the stack to render.

    Returns:
        The ``services`` mapping of the rendered configuration.

    Raises:
        RuntimeError: if ``docker compose config`` fails, with its stderr attached.
    """
    env = dict(os.environ)
    # Drop any identity variables the CALLER exports, so a developer's own .env-shaped shell cannot
    # make this gate pass or fail for a reason that is not in the repository.
    for key in ("IRI_KEYCLOAK_HOSTNAME", "IRI_KEYCLOAK_ISSUER_URI"):
        env.pop(key, None)
    env.update(placeholder_env(root, scenario.files))
    env.update(scenario.env)

    cmd = ["docker", "compose"]
    for name in scenario.files:
        cmd += ["-f", name]
    if scenario.profile:
        cmd += ["--profile", scenario.profile]
    cmd += ["config", "--format", "json"]

    proc = subprocess.run(cmd, cwd=str(root), env=env, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(
            "`%s` failed (exit %d):\n%s" % (" ".join(cmd), proc.returncode, proc.stderr.strip())
        )
    return json.loads(proc.stdout).get("services", {})


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
            # Unset means Keycloak serves at the root; normalise that to "/" so the comparison
            # below has something to compare.
            relative_path = env.get("KC_HTTP_RELATIVE_PATH") or "/"
        if env.get("KEYCLOAK_ISSUER_URI"):
            issuers[name] = env["KEYCLOAK_ISSUER_URI"]
    return hostname, relative_path, issuers


def norm_path(path: str) -> str:
    """Normalises a URL path for comparison: no trailing slash, and root is the empty string.

    ``/auth``, ``/auth/`` and ``auth`` all name the same mount point to Keycloak, and ``/`` and
    ``""`` both mean the root. Comparing the raw strings would report a difference that is not one.

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

    # Rule C -- the apps in one stack have to agree with each other, unless the stack is one of the
    # documented split-horizon overrides.
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
        # Every stack listed here starts Keycloak. One that does not would mean the file list is
        # wrong, and rules A and B would then pass by having nothing to test.
        problems.append(
            "%s: no service sets KC_HOSTNAME, so the issuer above is checked against nothing"
            % scenario.name
        )
        return "%s: NO KEYCLOAK" % scenario.name

    full_url = "://" in hostname
    host_path = norm_path(urlsplit(hostname).path) if full_url else None
    rel = norm_path(relative_path)

    # Rule A -- a full-URL hostname must carry the serving path. This is ADR-0166's broken row, and
    # it applies whether or not the issuer is overridden, because it is Keycloak-internal: the
    # server would advertise root links no matter what the apps had been told to expect.
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

    # Rule B -- every issuer has to be the one this Keycloak will advertise. A split-horizon stack
    # narrows this to the services that genuinely talk to the hostname configured above.
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
            # Scheme, host, port and path are all pinned by the configured hostname.
            expected = hostname.rstrip("/")
            if base.rstrip("/") != expected:
                problems.append(
                    "%s: %s validates issuer base %s, but KC_HOSTNAME=%s makes Keycloak advertise "
                    "%s" % (scenario.name, name, base, hostname, expected)
                )
        else:
            # A bare hostname takes scheme, port AND context path from the request, so only the
            # host and the path are ours to assert (ADR-0166, measured for the test stack).
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
                continue  # No default: the variable is required there (ingest's prod profile).
            checked += 1
            if default != expected:
                problems.append(
                    "spring-defaults: %s falls back to %s, but docker-compose.yml deploys %s. An "
                    "app started without KEYCLOAK_ISSUER_URI would validate the wrong issuer."
                    % (rel, default, expected)
                )
    return "spring-defaults: %d fallback default(s) match %s" % (checked, expected)


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
            "comma-separated scenario names to run instead of all of them. A debugging and "
            "self-test aid -- each stack costs a `docker compose config` render, and the "
            "regression suite has no reason to pay for seven of them per mutation. CI runs the "
            "gate with no filter."
        ),
    )
    args = parser.parse_args()
    root = Path(args.repo_root).resolve()

    scenarios = SCENARIOS
    if args.only:
        wanted = [name.strip() for name in args.only.split(",") if name.strip()]
        known = {s.name for s in SCENARIOS}
        unknown = [name for name in wanted if name not in known]
        if unknown:
            print(
                "unknown scenario(s): %s (known: %s)"
                % (", ".join(unknown), ", ".join(sorted(known))),
                file=sys.stderr,
            )
            return 2
        scenarios = [s for s in SCENARIOS if s.name in wanted]

    problems: list = []
    summaries = []

    check_prod_base_is_real(root, problems)
    for scenario in scenarios:
        summaries.append(check_scenario(root, scenario, problems))
    summaries.append(check_spring_defaults(root, problems))

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

    print("Keycloak issuer OK -- %d stack(s) checked:" % len(scenarios))
    for summary in summaries:
        print("  %s" % summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())

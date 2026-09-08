#!/usr/bin/env python3
"""Fail when a shipped Gradle module's SBOM is not generated, committed and published.

An SBOM is what a consumer reads *instead of* unpacking the artifact, so the set
of published BOMs is read as "these are the components of this release". A module
missing from that set does not read as missing -- it reads as absent from the
product. This check makes the set an assertion instead of four hand-maintained
lists that happen to agree.

It caught the two ways the set had already drifted (v1.7.3):

* ``ingest`` had the plugin and a committed BOM, but no release workflow named
  it. The file aged in place from 2026-07-11, reaching production naming 126
  stale component versions, and was never attached to a release at all.
* ``keycloak-spi`` had no SBOM whatsoever, while ``promote.yml`` pushes its
  provider-JAR bundle to the production Keycloak alongside the app images.

Every module in ``settings.gradle.kts`` must therefore be either wired for an
SBOM end to end, or listed in ``NOT_SHIPPED`` with the reason it ships nothing.
A new module is a failure until somebody decides which it is -- that decision
being the whole point, since both drifts above were silence, not a wrong answer.

Exit codes:
  0  -> every shipped module is wired end to end.
  1  -> at least one gap; each is printed with the file that must change.

Usage:
    check_sbom_coverage.py
"""

from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

SETTINGS = REPO / "settings.gradle.kts"
PREPARE = REPO / ".github" / "workflows" / "release-prepare.yml"
PUBLISH = REPO / ".github" / "workflows" / "release-publish.yml"

# Modules that deliberately publish no SBOM, with the reason. A module lands here
# only when nothing it produces reaches a consumer; "we forgot" is not a reason,
# which is why the entry is prose rather than a bare name.
NOT_SHIPPED = {
    "test-support": (
        "test-only helper library shared by the anonymous-surface sweeps (#1804). Nothing depends "
        "on it at runtime and no image carries it, so publishing its BOM would list JUnit and "
        "Mockito as components of the delivered product"
    ),
}


def modules() -> list[str]:
    """Read the Gradle module names from ``settings.gradle.kts``.

    Parses the ``include("name")`` lines rather than scanning directories, so a
    stray folder that is not part of the build is not mistaken for a module and
    a real module cannot hide by lacking one.

    :return: the module names in declaration order.
    :raises OSError: if the settings file cannot be read.
    """
    text = SETTINGS.read_text(encoding="utf-8")
    return re.findall(r'^include\("([^"]+)"\)', text, re.MULTILINE)


def check_module(module: str, prepare: str, publish: str) -> list[str]:
    """Assert one shipped module is wired for an SBOM in all four places.

    The four are independent and each fails silently on its own: the Gradle
    plugin (no BOM is produced), the committed pair (nothing to attach), the
    regeneration line (the committed pair goes stale), and the publish lists (the
    fresh pair never leaves the repository).

    :param module: the Gradle module name, e.g. ``ingest``.
    :param prepare: full text of ``release-prepare.yml``.
    :param publish: full text of ``release-publish.yml``.
    :return: one human-readable problem per gap; empty when the module is sound.
    """
    problems: list[str] = []
    build = REPO / module / "build.gradle.kts"

    if "libs.plugins.cyclonedx.bom" not in build.read_text(encoding="utf-8"):
        problems.append(
            f"{module}: {build.relative_to(REPO)} does not apply the CycloneDX plugin "
            f"(add `alias(libs.plugins.cyclonedx.bom)` and a `cyclonedxBom` block writing "
            f"docs/{module}-bom.json + .xml)"
        )

    for suffix in ("json", "xml"):
        bom = REPO / module / "docs" / f"{module}-bom.{suffix}"
        if not bom.is_file():
            problems.append(
                f"{module}: {bom.relative_to(REPO)} is not committed "
                f"(run `./gradlew :{module}:cyclonedxBom` and commit the result)"
            )

    if f":{module}:cyclonedxBom" not in prepare:
        problems.append(
            f"{module}: release-prepare.yml never regenerates it -- its committed BOM will age in "
            f"place and ship stale component versions (add `:{module}:cyclonedxBom` to the Gradle "
            f"invocation)"
        )

    loop = re.search(r"^\s*for module in ([^;]+); do", prepare, re.MULTILINE)
    if loop is None:
        problems.append(
            f"{module}: could not find the `for module in ...` churn loop in release-prepare.yml "
            f"-- this checker needs updating alongside whatever replaced it"
        )
    elif module not in loop.group(1).split():
        problems.append(
            f"{module}: missing from release-prepare.yml's churn loop, so a regeneration that only "
            f"rotated the serial number would be committed as a change (add it to `for module in "
            f"{loop.group(1).strip()}`)"
        )

    if f"{module}/docs" not in prepare:
        problems.append(
            f"{module}: release-prepare.yml does not stage {module}/docs, so the regenerated BOM "
            f"is discarded instead of committed onto the release branch"
        )

    for suffix in ("json", "xml"):
        asset = f"{module}/docs/{module}-bom.{suffix}"
        # Twice: once as an attestation subject, once as a release asset. Attested
        # but unpublished is useless; published but unattested is what ADR-0145
        # closed.
        if publish.count(asset) < 2:
            problems.append(
                f"{module}: release-publish.yml must list {asset} both as an attestation subject "
                f"and as a release asset (found {publish.count(asset)} of 2)"
            )

    return problems


def main() -> None:
    """Check every module and exit non-zero with the full list of gaps.

    Reports all problems rather than the first, so one CI run tells the author
    everything that has to change instead of one round trip per gap.

    :raises SystemExit: always -- 0 when sound, 1 when any gap was found.
    """
    prepare = PREPARE.read_text(encoding="utf-8")
    publish = PUBLISH.read_text(encoding="utf-8")

    declared = modules()
    if not declared:
        print(f"error: no `include(\"...\")` module found in {SETTINGS.relative_to(REPO)}")
        raise SystemExit(1)

    problems: list[str] = []

    for stale in sorted(set(NOT_SHIPPED) - set(declared)):
        problems.append(
            f"{stale}: listed in NOT_SHIPPED but no longer a module -- drop the entry so the "
            f"exemption list keeps meaning something"
        )

    shipped = [m for m in declared if m not in NOT_SHIPPED]
    for module in shipped:
        problems.extend(check_module(module, prepare, publish))

    if problems:
        print("SBOM coverage gaps:\n")
        for problem in problems:
            print(f"  - {problem}")
        print(
            "\nEvery module that reaches a consumer publishes an SBOM. If this one ships nothing, "
            "add it to NOT_SHIPPED in this script with the reason."
        )
        raise SystemExit(1)

    exempt = ", ".join(sorted(NOT_SHIPPED)) or "none"
    print(f"SBOM coverage OK: {', '.join(shipped)} (not shipped: {exempt})")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Fail when a shipped Gradle module's SBOM is not generated, committed and published.

Every module in ``settings.gradle.kts`` must be wired for an SBOM end to end, listed in
``NOT_SHIPPED`` with a reason, or listed in ``SHIPPED_INSIDE`` with its carriers. Also
checks fresh BOM generation and that the release notes name every image and SBOM.

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
ROOT_BUILD = REPO / "build.gradle.kts"
CI = REPO / ".github" / "workflows" / "ci.yml"
PREPARE = REPO / ".github" / "workflows" / "release-prepare.yml"
PUBLISH = REPO / ".github" / "workflows" / "release-publish.yml"
IMAGES = REPO / ".github" / "workflows" / "release-images.yml"
NOTES = REPO / ".github" / "scripts" / "extract_release_notes.py"

NOT_SHIPPED = {
    "test-support": (
        "test-only helper library shared by the anonymous-surface sweeps (#1804). Nothing depends "
        "on it at runtime and no image carries it, so publishing its BOM would list JUnit and "
        "Mockito as components of the delivered product"
    ),
}

SHIPPED_INSIDE = {
    "logging-support": (
        "LogSafe and the PII maskers every application's logback configuration names (ADR-0205); "
        "a plain JAR inside the three boot JARs, never an artifact of its own",
        ("backend", "frontend", "ingest"),
    ),
}


def modules() -> list[str]:
    """Read the Gradle module names from the ``include("name")`` lines of ``settings.gradle.kts``.

    :return: the module names in declaration order.
    :raises OSError: if the settings file cannot be read.
    """
    text = SETTINGS.read_text(encoding="utf-8")
    return re.findall(r'^include\("([^"]+)"\)', text, re.MULTILINE)


def check_module(module: str, prepare: str, publish: str) -> list[str]:
    """Assert one shipped module is wired for an SBOM in all four places.

    The four are the Gradle plugin, the committed JSON/XML pair, the regeneration in
    ``release-prepare.yml`` and the publish lists in ``release-publish.yml``.

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
        if publish.count(asset) < 2:
            problems.append(
                f"{module}: release-publish.yml must list {asset} both as an attestation subject "
                f"and as a release asset (found {publish.count(asset)} of 2)"
            )

    return problems


def check_shipped_inside(module: str, carriers: tuple[str, ...]) -> list[str]:
    """Assert a library-only module is a runtime dependency of every module said to carry it.

    Each carrier's build script must declare ``implementation(project(":<module>"))``.

    :param module: the library module, e.g. ``logging-support``.
    :param carriers: the shipped modules whose artifacts contain it.
    :return: one human-readable problem per carrier that does not depend on it.
    """
    problems: list[str] = []
    declaration = f'implementation(project(":{module}"))'
    for carrier in carriers:
        build = REPO / carrier / "build.gradle.kts"
        if declaration not in build.read_text(encoding="utf-8"):
            problems.append(
                f"{module}: SHIPPED_INSIDE names {carrier} as a carrier, but "
                f"{build.relative_to(REPO)} does not declare `{declaration}` -- the module would "
                f"ship in no SBOM at all"
            )
    return problems


def check_fresh_generation(shipped: list[str], prepare: str, ci: str) -> list[str]:
    """Assert a released SBOM is generated fresh and verified against the resolved classpath.

    Requires both CycloneDX tasks to be untracked and finalized by ``verifyCyclonedxBom``,
    the release regeneration to pass ``--no-build-cache``, and ``ci.yml`` to run every
    shipped module's ``cyclonedxBom``.

    :param shipped: the Gradle modules that publish an SBOM, in declaration order.
    :param prepare: full text of ``release-prepare.yml``.
    :param ci: full text of ``ci.yml``.
    :return: one human-readable problem per missing piece; empty when all are in place.
    """
    problems: list[str] = []
    root = ROOT_BUILD.read_text(encoding="utf-8")
    block = re.search(
        r'plugins\.withId\("org\.cyclonedx\.bom"\) \{(.*?)\n  plugins\.withId\(', root, re.DOTALL
    )
    if block is None:
        problems.append(
            'could not find the `plugins.withId("org.cyclonedx.bom")` block in build.gradle.kts '
            "-- this checker needs updating alongside whatever replaced it"
        )
    else:
        body = block.group(1)
        if body.count("doNotTrackState(") < 2:
            problems.append(
                "build.gradle.kts: cyclonedxDirectBom and cyclonedxBom must both call "
                "`doNotTrackState(...)` -- the plugin's inputs do not see project dependencies, "
                "so a tracked task can be UP-TO-DATE or FROM-CACHE with a stale component list"
            )
        finalized = 'finalizedBy("verifyCyclonedxBom")' in body
        registered = 'register("verifyCyclonedxBom")' in body
        if not (finalized and registered):
            problems.append(
                "build.gradle.kts: `cyclonedxBom` must be finalized by a registered "
                "`verifyCyclonedxBom`, the check that the BOM lists exactly the resolved "
                "runtimeClasspath"
            )

    step = re.search(
        r"- name: Regenerate CycloneDX SBOMs\n(.*?)(?=\n\s*- name:)", prepare, re.DOTALL
    )
    if step is None or "--no-build-cache" not in step.group(1):
        problems.append(
            "release-prepare.yml: the `Regenerate CycloneDX SBOMs` step must pass "
            "`--no-build-cache` -- the job restores the Gradle caches, and a release SBOM is never "
            "served from one"
        )

    for module in shipped:
        if f":{module}:cyclonedxBom" not in ci:
            problems.append(
                f"{module}: ci.yml never runs `:{module}:cyclonedxBom`, so its "
                f"verifyCyclonedxBom check first runs at release time instead of on the PR"
            )

    return problems


def check_release_notes(shipped: list[str], images: str) -> list[str]:
    """Assert the release-notes footer announces everything the release ships.

    Compares ``SERVICE_IMAGES`` and ``SBOM_MODULES`` in ``extract_release_notes.py`` with
    the build matrix and the shipped modules, in both directions.

    :param shipped: the Gradle modules that publish an SBOM, in declaration order.
    :param images: full text of ``release-images.yml``.
    :return: one human-readable problem per gap; empty when the footer is sound.
    """
    problems: list[str] = []
    notes = NOTES.read_text(encoding="utf-8")

    def tuple_entries(name: str) -> set[str] | None:
        """Return the string members of a module-level tuple, or None if absent."""
        match = re.search(rf"^{name}: tuple\[str, \.\.\.\] = \(([^)]*)\)", notes, re.MULTILINE)
        return None if match is None else set(re.findall(r'"([^"]+)"', match.group(1)))

    matrix = re.search(r"^\s*module: \[([^\]]+)\]", images, re.MULTILINE)
    declared_images = tuple_entries("SERVICE_IMAGES")
    if matrix is None:
        problems.append(
            "could not find the `module: [...]` build matrix in release-images.yml -- this "
            "checker needs updating alongside whatever replaced it"
        )
    elif declared_images is None:
        problems.append(
            f"{NOTES.relative_to(REPO)} has no SERVICE_IMAGES tuple -- the release notes' image "
            f"list can no longer be checked against the build matrix"
        )
    else:
        built = {m.strip() for m in matrix.group(1).split(",")}
        for missing in sorted(built - declared_images):
            problems.append(
                f"{missing}: release-images.yml builds, signs and pushes it, but "
                f"{NOTES.relative_to(REPO)}'s SERVICE_IMAGES omits it -- the GitHub Release will "
                f"not mention the image at all (add it to the tuple)"
            )
        for phantom in sorted(declared_images - built):
            problems.append(
                f"{phantom}: listed in {NOTES.relative_to(REPO)}'s SERVICE_IMAGES but not in "
                f"release-images.yml's build matrix -- the notes would advertise an image tag "
                f"nobody can pull"
            )

    declared_boms = tuple_entries("SBOM_MODULES")
    if declared_boms is None:
        problems.append(
            f"{NOTES.relative_to(REPO)} has no SBOM_MODULES tuple -- the release notes can no "
            f"longer be checked against the set of published SBOMs"
        )
    else:
        for missing in sorted(set(shipped) - declared_boms):
            problems.append(
                f"{missing}: publishes an SBOM but {NOTES.relative_to(REPO)}'s SBOM_MODULES omits "
                f"it, so the release body tells a reader to audit fewer components than it ships"
            )
        for phantom in sorted(declared_boms - set(shipped)):
            problems.append(
                f"{phantom}: listed in {NOTES.relative_to(REPO)}'s SBOM_MODULES but publishes no "
                f"SBOM -- the release body would point at a directory with nothing in it"
            )

    return problems


def main() -> None:
    """Check every module and exit non-zero with the full list of gaps.

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

    for stale in sorted(set(SHIPPED_INSIDE) - set(declared)):
        problems.append(
            f"{stale}: listed in SHIPPED_INSIDE but no longer a module -- drop the entry so the "
            f"exemption list keeps meaning something"
        )

    for module, (_reason, carriers) in SHIPPED_INSIDE.items():
        if module in declared:
            problems.extend(check_shipped_inside(module, carriers))

    shipped = [m for m in declared if m not in NOT_SHIPPED and m not in SHIPPED_INSIDE]
    for module in shipped:
        problems.extend(check_module(module, prepare, publish))

    problems.extend(check_fresh_generation(shipped, prepare, CI.read_text(encoding="utf-8")))
    problems.extend(check_release_notes(shipped, IMAGES.read_text(encoding="utf-8")))

    if problems:
        print("SBOM coverage gaps:\n")
        for problem in problems:
            print(f"  - {problem}")
        print(
            "\nEvery module that reaches a consumer publishes an SBOM, and every artifact the "
            "pipeline pushes is named in the release notes. If this one ships nothing, add it to "
            "NOT_SHIPPED in this script with the reason."
        )
        raise SystemExit(1)

    exempt = ", ".join(sorted(NOT_SHIPPED)) or "none"
    inside = ", ".join(sorted(SHIPPED_INSIDE)) or "none"
    print(
        f"SBOM coverage OK: {', '.join(shipped)} (not shipped: {exempt}; "
        f"shipped inside another module's artifact: {inside})"
    )
    print("Release-notes footer OK: image list matches the build matrix, SBOM list matches above")


if __name__ == "__main__":
    main()

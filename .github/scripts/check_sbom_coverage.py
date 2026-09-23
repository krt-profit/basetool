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
SBOM end to end, listed in ``NOT_SHIPPED`` with the reason it ships nothing, or
listed in ``SHIPPED_INSIDE`` with the modules whose artifacts -- and BOMs -- carry it.
A new module is a failure until somebody decides which it is -- that decision
being the whole point, since both drifts above were silence, not a wrong answer.

The **release notes** were added as a fifth place (v1.8.3) after the same failure
happened one step further downstream: ``extract_release_notes.py`` announced two
images while the pipeline built, scanned, signed and pushed three, so ``ingest``
shipped unmentioned for three releases. What a release says it contains is part
of what it publishes, so the notes are checked against the build matrix and the
shipped-module set rather than maintained by hand beside them.

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
IMAGES = REPO / ".github" / "workflows" / "release-images.yml"
NOTES = REPO / ".github" / "scripts" / "extract_release_notes.py"

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

# Modules that DO ship, but only as a library inside other modules' artifacts, with the
# modules that carry them. Such a module publishes no SBOM of its own: it appears as a component
# of each carrier's BOM (the carriers' `runtimeClasspath` is what their BOM enumerates), and a
# second, stand-alone BOM would describe an artifact nobody can download. The entry is only
# honest while every carrier really depends on it at runtime, so that is asserted, not trusted.
SHIPPED_INSIDE = {
    "logging-support": (
        "LogSafe and the PII maskers every application's logback configuration names (ADR-0205); "
        "a plain JAR inside the three boot JARs, never an artifact of its own",
        ("backend", "frontend", "ingest"),
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


def check_shipped_inside(module: str, carriers: tuple[str, ...]) -> list[str]:
    """Assert a library-only module is a runtime dependency of every module said to carry it.

    A ``SHIPPED_INSIDE`` entry exempts a module from publishing its own SBOM on the
    ground that it is listed in its carriers' BOMs. That ground disappears silently if
    a carrier stops depending on it -- or never did -- so each carrier's build script
    must declare it as ``implementation(project(":<module>"))``, the configuration
    its ``runtimeClasspath`` and therefore its BOM are built from.

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


def check_release_notes(shipped: list[str], images: str) -> list[str]:
    """Assert the release-notes footer announces everything the release ships.

    The fifth place, and the one that drifted after the other four were pinned:
    ``extract_release_notes.py`` writes the "Docker Images" and "SBOM" sections
    of the GitHub Release body from two hand-written tuples. Those are the only
    description of the release most readers ever see, so a module missing there
    is not a documentation gap -- it is an artifact that, as far as any consumer
    can tell, was not published. ``ingest`` sat in the build matrix, the scan
    matrix, the signing matrix and the SBOM asset list while the notes named two
    images; nothing failed, and three releases went out understating themselves.

    Both directions are checked. A tuple that lists something the release does
    not build is as wrong as one that omits what it does, and points a reader at
    a tag that cannot be pulled.

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

    # The build matrix is the authority on which modules become an image; the
    # notes must mirror it exactly. Read rather than restated, so this checker
    # cannot become the fifth list that quietly disagrees with the other four.
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

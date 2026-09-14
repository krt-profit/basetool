#!/usr/bin/env python3
"""Render a GitHub Release body for a tag from the reconciled CHANGELOG.

Given a tag such as ``v0.3.55`` this slices the dated ``## [v0.3.55](...) - DATE``
section that ``reconcile_changelog.py`` produced and re-emits its body with the
German Keep-a-Changelog headers (``### Added`` / ``### Changed`` / ``### Fixed``
/ ...) remapped to the release-notes skill's German rubric headings
(``## Neu`` / ``## Verbesserungen`` / ``## Fehlerbehebungen`` / ...). The bullet
text is copied verbatim: this is a deterministic CI extraction for the GitHub
Release body, not the skill's LLM-driven editorial rewrite, so it neither filters
internal entries nor simplifies the prose -- it only reshapes the headings.

When ``--version``, ``--registry`` and ``--owner`` are all supplied, a Docker
image + SBOM footer is appended, so the workflow obtains the complete release
body from one call. Building the whole body here (rather than with shell
``printf``) keeps the literal markdown backticks out of the workflow's shell,
where shellcheck would otherwise mistake them for command substitution (SC2016).

If the tag has no section -- e.g. the release contained only internal commits --
a neutral German placeholder line is emitted and the exit code stays 0, so the
release is still created.

Usage:
    extract_release_notes.py <tag> [changelog]
    extract_release_notes.py <tag> --version 0.3.55 --registry ghcr.io --owner krt-profit
"""

from __future__ import annotations

import argparse
import re
import sys

# Ensure umlauts print on any console (Windows defaults to cp1252 and crashes).
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):  # already wrapped / not reconfigurable
        pass

# A CHANGELOG ``### `` section's first word -> the release-notes skill's German
# rubric. Sections without a mapping keep their own first word as the heading so
# nothing is silently dropped (this CI extraction is content-preserving).
RUBRIC: dict[str, str] = {
    "Added": "Neu",
    "Changed": "Verbesserungen",
    "Fixed": "Fehlerbehebungen",
    "Security": "Sicherheit",
    "Removed": "Entfernt",
    "Deprecated": "Veraltet",
}

# The modules whose production image is built, scanned, signed and pushed on every
# release. This must stay in lock-step with the ``module:`` build matrix in
# ``release-images.yml``, and ``check_sbom_coverage.py`` asserts that it does --
# the list here silently lost ``ingest`` while the matrix kept building, scanning
# and signing it, so three releases announced two of the three images they ship.
# An image missing from the notes does not read as an omission; it reads as an
# image the release does not have, which is the same failure mode the SBOM
# checker was written for.
SERVICE_IMAGES: tuple[str, ...] = ("backend", "frontend", "ingest")

# The modules whose CycloneDX SBOMs are attached to the release and committed
# under ``<module>/docs/`` (REQ-OPS-025). Deliberately NOT the same set as the
# images above: ``keycloak-spi`` ships a provider-JAR bundle rather than a
# service image, and is still a component a consumer has to be able to audit.
SBOM_MODULES: tuple[str, ...] = ("backend", "frontend", "ingest", "keycloak-spi")


def changelog_section(tag: str, path: str) -> str:
    """Return the tag's CHANGELOG section with skill-style rubric headings.

    :param tag: the release tag whose section to extract, e.g. ``v0.3.55``.
    :param path: path to the reconciled CHANGELOG.md.
    :return: the section body with ``### Foo`` headers remapped to ``## Rubrik``,
             or a neutral German placeholder line if the tag has no section.
    """
    placeholder = (
        f"Für {tag} sind keine nutzersichtbaren Änderungen "
        "im Changelog vermerkt."
    )
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")

    # Match the reconciled, dated version heading "## [<tag>](...) - DATE".
    head = re.compile(rf"^## \[{re.escape(tag)}\]")
    start = next((i for i, line in enumerate(lines) if head.match(line)), None)
    if start is None:
        return placeholder
    end = next(
        (i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")),
        len(lines),
    )

    out: list[str] = []
    for line in lines[start + 1:end]:
        if line.startswith("### "):
            body = line[4:].strip()
            word = body.split()[0] if body else ""
            out.append(f"## {RUBRIC.get(word, word or 'Sonstiges')}")
        else:
            out.append(line)

    text = "\n".join(out).strip("\n")
    return text if text else placeholder


def image_and_sbom_footer(version: str, registry: str, owner: str) -> str:
    """Return the Docker-image + SBOM footer appended to the release body.

    :param version: the OCI image tag (the git tag without its leading ``v``).
    :param registry: the container registry host, e.g. ``ghcr.io``.
    :param owner: the GHCR / GitHub owner namespace, e.g. ``krt-profit``.
    :return: a markdown block linking the signed multi-arch images and noting the
             attached CycloneDX SBOMs.
    """
    pulls = "".join(
        f"- {module.capitalize()}: `{registry}/{owner}/basetool-{module}:{version}`\n"
        for module in SERVICE_IMAGES
    )
    pages = "".join(
        f"- [{module}](https://github.com/{owner}/basetool/pkgs/container/basetool-{module})\n"
        for module in SERVICE_IMAGES
    )
    dirs = [f"`{module}/docs/`" for module in SBOM_MODULES]
    sbom_dirs = f"{', '.join(dirs[:-1])} and {dirs[-1]}" if len(dirs) > 1 else dirs[0]

    return (
        "## Docker Images\n\n"
        "Multi-arch (linux/amd64, linux/arm64), cosign-signed (keyless / Sigstore). "
        "Pull by version tag:\n\n"
        f"{pulls}\n"
        "Also tagged `:latest`. Package pages:\n\n"
        f"{pages}\n"
        "## SBOM\n\n"
        "CycloneDX SBOMs for this release are attached below and committed under "
        f"{sbom_dirs}."
    )


def main() -> None:
    """Parse arguments and print the release body (changelog section + footer)."""
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("tag", help="Release tag, e.g. v0.3.55.")
    parser.add_argument("changelog", nargs="?", default="CHANGELOG.md",
                        help="Path to CHANGELOG.md. Default: CHANGELOG.md.")
    parser.add_argument("--version", help="OCI image tag (git tag without 'v').")
    parser.add_argument("--registry", help="Container registry host, e.g. ghcr.io.")
    parser.add_argument("--owner", help="GHCR/GitHub owner, e.g. krt-profit.")
    args = parser.parse_args()

    parts = [changelog_section(args.tag, args.changelog)]
    if args.version and args.registry and args.owner:
        parts.append(image_and_sbom_footer(args.version, args.registry, args.owner))
    # Blank line between the changelog section and the footer.
    print("\n\n".join(parts))


if __name__ == "__main__":
    main()

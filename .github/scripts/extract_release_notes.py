#!/usr/bin/env python3
"""Render a GitHub Release body for a tag from the reconciled CHANGELOG.

Copies the tag's section verbatim, remapping ``### Added`` / ``### Changed`` / ... to
the German rubric headings (``## Neu`` / ``## Verbesserungen`` / ...). With
``--version``, ``--registry`` and ``--owner`` a Docker image and SBOM footer is
appended. A tag without a section yields a German placeholder line; exit code 0.

Usage:
    extract_release_notes.py <tag> [changelog]
    extract_release_notes.py <tag> --version 0.3.55 --registry ghcr.io --owner krt-profit
"""

from __future__ import annotations

import argparse
import contextlib
import re
import sys

for _stream in (sys.stdout, sys.stderr):
    with contextlib.suppress(AttributeError, ValueError):
        _stream.reconfigure(encoding="utf-8")

RUBRIC: dict[str, str] = {
    "Added": "Neu",
    "Changed": "Verbesserungen",
    "Fixed": "Fehlerbehebungen",
    "Security": "Sicherheit",
    "Removed": "Entfernt",
    "Deprecated": "Veraltet",
}

SERVICE_IMAGES: tuple[str, ...] = ("backend", "frontend", "ingest")

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
    print("\n\n".join(parts))


if __name__ == "__main__":
    main()

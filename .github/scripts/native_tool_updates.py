#!/usr/bin/env python3
"""Propose updates of the native tools Gradle resolves from Ivy repositories (REQ-OPS-059).

Ruff, shfmt and the Node.js archive come from their publishers' release pages, so neither
Dependabot nor refreshVersions sees them. ``propose`` looks up the newest stable release of each,
moves its version in ``gradle/libs.versions.toml`` and replaces its component in
``gradle/verification-metadata.xml`` with the SHA-256 the publisher states for every platform the
build supports. A release qualifies once it is ``--cooldown-days`` old; a Node.js security release
qualifies at once. Node.js stays on the major line the catalog pins.

Usage:
    native_tool_updates.py propose [--root DIR] [--cooldown-days N] [--body-file FILE]
        [--outputs FILE]
    native_tool_updates.py --selftest

``propose`` prints one line per tool, writes the pull-request body to ``--body-file`` when a tool
moved, and appends ``changed``, ``title`` and ``reformat-subject`` to ``--outputs`` (the step's
``GITHUB_OUTPUT``). ``GH_TOKEN``, when set, authenticates the GitHub API requests only.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY / "scripts"))
from http_budget import connection_timeout, read_before_deadline

for _stream in (sys.stdout, sys.stderr):
    with contextlib.suppress(AttributeError, ValueError):
        _stream.reconfigure(encoding="utf-8")

CATALOG = Path("gradle/libs.versions.toml")
METADATA = Path("gradle/verification-metadata.xml")
DEFAULT_COOLDOWN_DAYS = 7
HTTP_TIMEOUT_SECONDS = 60.0
USER_AGENT = "basetool-native-tool-updates"
GITHUB_API = "https://api.github.com"
NODE_DIST = "https://nodejs.org/dist"
XML_INDENT = "   "
VERSION = re.compile(r"(\d+)\.(\d+)\.(\d+)")
SHA256 = re.compile(r"[0-9a-f]{64}")
SHASUMS_LINE = re.compile(r"([0-9a-f]{64}) [ *](\S+)")

Fetch = Callable[[str], bytes]


class UpdateError(Exception):
    """A lookup or an edit that cannot produce a correct proposal."""


@dataclass(frozen=True)
class Release:
    """One published release of a tool.

    :ivar version: ``MAJOR.MINOR.PATCH`` without a leading ``v``
    :ivar published: the UTC day the publisher released it
    :ivar url: the release's page, linked from the pull-request body
    :ivar security: whether the publisher marks it as a security release
    :ivar digests: per published file name, the digest the release listing carries
    """

    version: str
    published: date
    url: str
    security: bool = False
    digests: Mapping[str, str] = field(default_factory=dict)


def parse_version(text: str) -> tuple[int, int, int] | None:
    """Return the numeric parts of a plain ``MAJOR.MINOR.PATCH`` version, else ``None``."""
    match = VERSION.fullmatch(text)
    if match is None:
        return None
    major, minor, patch = (int(part) for part in match.groups())
    return major, minor, patch


def newest_qualifying(
    releases: Sequence[Release], current: str, today: date, cooldown_days: int
) -> Release | None:
    """Return the newest release above ``current`` that is old enough or a security release.

    :param releases: stable releases with plain versions
    :param current: the version the catalog pins
    :param today: the reference day for the cooldown
    :param cooldown_days: the age in days a release needs unless it is a security release
    :return: the release to propose, or ``None`` when ``current`` is the newest qualifying one
    """
    floor = parse_version(current)
    if floor is None:
        raise UpdateError(f"the catalog version {current!r} is not MAJOR.MINOR.PATCH")
    eligible = [
        release
        for release in releases
        if release.security or (today - release.published).days >= cooldown_days
    ]
    best = max(eligible, key=lambda release: parse_version(release.version), default=None)
    if best is None or parse_version(best.version) <= floor:
        return None
    return best


@dataclass(frozen=True)
class GitHubReleases:
    """The stable releases of a GitHub repository, with the SHA-256 GitHub records per asset."""

    repo: str
    origin: str = "GitHub release digest"

    def releases(self, fetch: Fetch, current: str) -> list[Release]:
        """Return the repository's non-draft, non-prerelease releases with plain version tags."""
        payload = json.loads(fetch(f"{GITHUB_API}/repos/{self.repo}/releases?per_page=100"))
        releases = []
        for entry in payload:
            version = entry["tag_name"].removeprefix("v")
            if entry["draft"] or entry["prerelease"] or parse_version(version) is None:
                continue
            published = datetime.fromisoformat(entry["published_at"].replace("Z", "+00:00"))
            releases.append(
                Release(
                    version=version,
                    published=published.astimezone(timezone.utc).date(),
                    url=entry["html_url"],
                    digests={asset["name"]: asset.get("digest") or "" for asset in entry["assets"]},
                )
            )
        return releases

    def checksums(self, fetch: Fetch, release: Release) -> dict[str, str]:
        """Return the SHA-256 of each asset of ``release`` whose digest GitHub recorded."""
        checksums = {}
        for name, digest in release.digests.items():
            algorithm, _, value = digest.partition(":")
            if algorithm == "sha256" and SHA256.fullmatch(value):
                checksums[name] = value
        return checksums


@dataclass(frozen=True)
class NodeLine:
    """The Node.js releases of the major line the catalog pins, with nodejs.org's checksums."""

    origin: str = "nodejs.org SHASUMS256.txt"

    def releases(self, fetch: Fetch, current: str) -> list[Release]:
        """Return the releases in ``index.json`` that share the major version of ``current``."""
        line = parse_version(current)
        if line is None:
            raise UpdateError(f"the catalog version {current!r} is not MAJOR.MINOR.PATCH")
        releases = []
        for entry in json.loads(fetch(f"{NODE_DIST}/index.json")):
            version = entry["version"].removeprefix("v")
            parsed = parse_version(version)
            if parsed is None or parsed[0] != line[0]:
                continue
            releases.append(
                Release(
                    version=version,
                    published=date.fromisoformat(entry["date"]),
                    url=f"https://nodejs.org/en/blog/release/v{version}",
                    security=bool(entry.get("security")),
                )
            )
        return releases

    def checksums(self, fetch: Fetch, release: Release) -> dict[str, str]:
        """Return the SHA-256 of each file ``SHASUMS256.txt`` of ``release`` lists."""
        text = fetch(f"{NODE_DIST}/v{release.version}/SHASUMS256.txt").decode("utf-8")
        checksums = {}
        for line in text.splitlines():
            match = SHASUMS_LINE.fullmatch(line.strip())
            if match:
                checksums[match.group(2)] = match.group(1)
        return checksums


@dataclass(frozen=True)
class Tool:
    """A native tool whose version the catalog holds and whose release files Gradle verifies.

    :ivar key: the version key in the catalog's ``[versions]`` table
    :ivar label: the tool's name in titles and in the pull-request body
    :ivar group: the Ivy organisation ``settings.gradle.kts`` declares for it
    :ivar module: the Ivy module, the component name in the verification metadata
    :ivar source: where its releases and their SHA-256 digests are published
    :ivar files: per supported platform, the artifact name Gradle verifies and the publisher's
        file name, both with ``{version}`` in place of the version
    :ivar formatter: whether a bump can change how ``spotlessApply`` formats the repository
    """

    key: str
    label: str
    group: str
    module: str
    source: GitHubReleases | NodeLine
    files: tuple[tuple[str, str], ...]
    formatter: bool

    def artifact_names(self, version: str) -> dict[str, str]:
        """Map each artifact name Gradle verifies for ``version`` to the publisher's file name."""
        return {
            artifact.format(version=version): published.format(version=version)
            for artifact, published in self.files
        }


TOOLS = (
    Tool(
        key="ruff",
        label="Ruff",
        group="sh.astral",
        module="ruff",
        source=GitHubReleases("astral-sh/ruff"),
        files=(
            (
                "ruff-{version}-x86_64-unknown-linux-gnu.tar.gz",
                "ruff-x86_64-unknown-linux-gnu.tar.gz",
            ),
            ("ruff-{version}-x86_64-pc-windows-msvc.zip", "ruff-x86_64-pc-windows-msvc.zip"),
        ),
        formatter=True,
    ),
    Tool(
        key="shfmt",
        label="shfmt",
        group="cc.mvdan",
        module="shfmt",
        source=GitHubReleases("mvdan/sh"),
        files=(
            ("shfmt-{version}-linux_amd64", "shfmt_v{version}_linux_amd64"),
            ("shfmt-{version}-windows_amd64.exe", "shfmt_v{version}_windows_amd64.exe"),
        ),
        formatter=True,
    ),
    Tool(
        key="node",
        label="Node.js",
        group="org.nodejs",
        module="node",
        source=NodeLine(),
        files=(
            ("node-{version}-linux-x64.tar.gz", "node-v{version}-linux-x64.tar.gz"),
            ("node-{version}-win-x64.zip", "node-v{version}-win-x64.zip"),
        ),
        formatter=False,
    ),
)


def _versions_table(catalog: str) -> re.Match[str]:
    """Return the match of the catalog's ``[versions]`` table, its entries in group 1."""
    match = re.search(r"^\[versions\]\n(.*?)(?=^\[|\Z)", catalog, re.MULTILINE | re.DOTALL)
    if match is None:
        raise UpdateError(f"{CATALOG} has no [versions] table")
    return match


def _version_entry(key: str) -> re.Pattern[str]:
    """Return the pattern of the plain string entry ``key = "…"`` in the ``[versions]`` table."""
    return re.compile(rf'^{re.escape(key)} = "([^"]*)"$', re.MULTILINE)


def catalog_version(catalog: str, key: str) -> str:
    """Return the version the catalog's ``[versions]`` table holds for ``key``."""
    entries = _version_entry(key).findall(_versions_table(catalog).group(1))
    if len(entries) != 1:
        raise UpdateError(f"{CATALOG} [versions] has {len(entries)} entries for {key!r}, not one")
    return entries[0]


def with_catalog_version(catalog: str, key: str, version: str) -> str:
    """Return the catalog with the ``[versions]`` entry ``key`` set to ``version``."""
    catalog_version(catalog, key)
    table = _versions_table(catalog)
    entries = _version_entry(key).sub(f'{key} = "{version}"', table.group(1))
    return catalog[: table.start(1)] + entries + catalog[table.end(1) :]


def _component_pattern(tool: Tool) -> re.Pattern[str]:
    """Return the pattern of the tool's ``<component>`` element, from its line to its end tag."""
    return re.compile(
        rf'^(?P<indent> *)<component group="{re.escape(tool.group)}" '
        rf'name="{re.escape(tool.module)}" version="(?P<version>[^"]*)">\n'
        rf"(?P<body>.*?)^(?P=indent)</component>\n",
        re.MULTILINE | re.DOTALL,
    )


def _component(metadata: str, tool: Tool) -> re.Match[str]:
    """Return the match of the tool's one component in the verification metadata."""
    matches = list(_component_pattern(tool).finditer(metadata))
    if len(matches) != 1:
        raise UpdateError(
            f"{METADATA} has {len(matches)} components {tool.group}:{tool.module}, not one"
        )
    return matches[0]


def component_artifacts(metadata: str, tool: Tool) -> tuple[str, list[str]]:
    """Return the version of the tool's component and the artifact names it verifies."""
    component = _component(metadata, tool)
    names = re.findall(r'<artifact name="([^"]+)">', component.group("body"))
    return component.group("version"), names


def render_component(indent: str, tool: Tool, version: str, checksums: Mapping[str, str]) -> str:
    """Render the tool's component as Gradle writes it, artifacts sorted by name."""
    lines = [f'{indent}<component group="{tool.group}" name="{tool.module}" version="{version}">']
    for name in sorted(checksums):
        lines += [
            f'{indent}{XML_INDENT}<artifact name="{name}">',
            (
                f'{indent}{XML_INDENT * 2}<sha256 value="{checksums[name]}" '
                f'origin="{tool.source.origin}"/>'
            ),
            f"{indent}{XML_INDENT}</artifact>",
        ]
    lines.append(f"{indent}</component>")
    return "\n".join(lines) + "\n"


def with_component(
    metadata: str, tool: Tool, current: str, version: str, checksums: Mapping[str, str]
) -> str:
    """Return the metadata with the tool's component at ``current`` replaced by ``version``.

    :raises UpdateError: when the component is missing, doubled or not at ``current``
    """
    component = _component(metadata, tool)
    if component.group("version") != current:
        raise UpdateError(
            f"{METADATA} verifies {tool.module} {component.group('version')}, "
            f"but the catalog pins {current}"
        )
    block = render_component(component.group("indent"), tool, version, checksums)
    return metadata[: component.start()] + block + metadata[component.end() :]


@dataclass(frozen=True)
class Bump:
    """A proposed move of one tool from the catalog's version to a newer release."""

    tool: Tool
    current: str
    release: Release


def release_checksums(tool: Tool, fetch: Fetch, release: Release) -> dict[str, str]:
    """Return the SHA-256 per artifact name Gradle verifies for ``release``.

    :raises UpdateError: when the publisher states no SHA-256 for a supported platform's file
    """
    published = tool.source.checksums(fetch, release)
    checksums = {}
    for artifact, name in tool.artifact_names(release.version).items():
        if name not in published:
            raise UpdateError(
                f"{tool.label} {release.version} publishes no SHA-256 for {name} ({tool.source.origin})"
            )
        checksums[artifact] = published[name]
    return checksums


def propose(root: Path, fetch: Fetch, today: date, cooldown_days: int) -> list[Bump]:
    """Move every tool with a newer qualifying release and return the bumps made.

    The catalog and the verification metadata under ``root`` are rewritten only when a tool moved.
    """
    catalog_path = root / CATALOG
    metadata_path = root / METADATA
    catalog = catalog_path.read_text(encoding="utf-8")
    metadata = metadata_path.read_text(encoding="utf-8")
    bumps = []
    for tool in TOOLS:
        current = catalog_version(catalog, tool.key)
        release = newest_qualifying(
            tool.source.releases(fetch, current), current, today, cooldown_days
        )
        if release is None:
            print(f"{tool.key} {current}: no newer qualifying release")
            continue
        checksums = release_checksums(tool, fetch, release)
        metadata = with_component(metadata, tool, current, release.version, checksums)
        catalog = with_catalog_version(catalog, tool.key, release.version)
        bumps.append(Bump(tool, current, release))
        print(f"{tool.key} {current} -> {release.version} (released {release.published})")
    if bumps:
        catalog_path.write_text(catalog, encoding="utf-8", newline="\n")
        metadata_path.write_text(metadata, encoding="utf-8", newline="\n")
    return bumps


def _enumerate(parts: Sequence[str]) -> str:
    """Join ``parts`` as an English enumeration: ``a``, ``a and b``, ``a, b and c``."""
    if len(parts) <= 1:
        return "".join(parts)
    return ", ".join(parts[:-1]) + " and " + parts[-1]


def title(bumps: Sequence[Bump]) -> str:
    """Return the pull-request title and bump-commit headline for ``bumps``."""
    return "chore(deps): bump " + _enumerate(
        [f"{bump.tool.label} to {bump.release.version}" for bump in bumps]
    )


def reformat_subject(bumps: Sequence[Bump]) -> str:
    """Return the headline of the commit that holds what ``spotlessApply`` reformatted."""
    formatters = [
        f"{bump.tool.label} {bump.release.version}" for bump in bumps if bump.tool.formatter
    ]
    if not formatters:
        return "style: reformat with spotlessApply"
    return "style: reformat with " + _enumerate(formatters)


def pull_request_body(bumps: Sequence[Bump]) -> str:
    """Return the pull-request body: the bumps, where the checksums come from, how to refuse."""
    rows = []
    for bump in bumps:
        release = bump.release
        note = ", security release" if release.security else ""
        rows.append(
            f"| {bump.tool.label} (`{bump.tool.key}`) | {bump.current} | {release.version} "
            f"| [{release.version}]({release.url}), {release.published.isoformat()}{note} |"
        )
    return "\n".join(
        [
            (
                "Weekly update proposal for the native tools Gradle resolves from their "
                "publishers, which Dependabot and refreshVersions do not see (REQ-OPS-059, "
                "ADR-0391)."
            ),
            "",
            "| Tool | From | To | Release |",
            "| --- | --- | --- | --- |",
            *rows,
            "",
            (
                "The checksums in `gradle/verification-metadata.xml` are the publishers' SHA-256 "
                "digests: the GitHub release asset `digest` and nodejs.org `SHASUMS256.txt`. The "
                "workflow's `./gradlew spotlessApply` verified the Linux files against them; the "
                "Windows files are verified on the first Windows build."
            ),
            "",
            (
                "When a formatter's new version changes the format, its reformat is a commit of "
                "its own and listed in `.git-blame-ignore-revs`. Read the linked release notes "
                "before merging."
            ),
            "",
            (
                "The workflow rebuilds this branch from `main` every week while the pull request "
                "is open. Closing it unmerged refuses these versions: the same set is not "
                "proposed again."
            ),
            "",
        ]
    )


def http_fetch(url: str, token: str | None, timeout: float = HTTP_TIMEOUT_SECONDS) -> bytes:
    """GET ``url`` and return the body; ``token`` is sent to the GitHub API only.

    :raises UpdateError: on a transport error, an HTTP error status or an expired time budget
    """
    headers = {"User-Agent": USER_AGENT}
    if urllib.parse.urlsplit(url).hostname == urllib.parse.urlsplit(GITHUB_API).hostname:
        headers["Accept"] = "application/vnd.github+json"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
        if token:
            headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    deadline = time.monotonic() + timeout
    try:
        with urllib.request.urlopen(request, timeout=connection_timeout(timeout)) as response:
            return read_before_deadline(response, deadline)
    except (urllib.error.URLError, TimeoutError) as error:
        raise UpdateError(f"GET {url} failed: {error}") from error


def write_outputs(path: Path, bumps: Sequence[Bump]) -> None:
    """Append the step outputs ``changed``, ``title`` and ``reformat-subject`` to ``path``."""
    lines = [f"changed={'true' if bumps else 'false'}"]
    if bumps:
        lines += [f"title={title(bumps)}", f"reformat-subject={reformat_subject(bumps)}"]
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def _sha(text: str) -> str:
    """Return a deterministic SHA-256 hex digest for a self-test fixture."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _github_fixture(tag: str, published: str, names: Sequence[str], **flags: bool) -> dict:
    """Build one release entry as the GitHub releases API returns it."""
    return {
        "tag_name": tag,
        "published_at": f"{published}T17:55:55Z",
        "html_url": f"https://github.com/example/releases/tag/{tag}",
        "draft": flags.get("draft", False),
        "prerelease": flags.get("prerelease", False),
        "assets": [{"name": name, "digest": f"sha256:{_sha(tag + name)}"} for name in names],
    }


def _fixture_fetch() -> dict[str, bytes]:
    """Return the URL-to-body table the self-test serves instead of the network."""
    ruff_files = ["ruff-x86_64-unknown-linux-gnu.tar.gz", "ruff-x86_64-pc-windows-msvc.zip"]
    ruff = [
        _github_fixture("0.19.0", "2026-11-15", ruff_files),
        _github_fixture("0.20.0", "2026-11-09", ruff_files, draft=True),
        _github_fixture("0.18.0rc1", "2026-11-01", ruff_files, prerelease=True),
        _github_fixture("0.18.0", "2026-11-09", ruff_files),
        _github_fixture("0.17.4", "2026-11-05", ruff_files),
    ]
    shfmt = [
        _github_fixture("v3.15.0", "2026-11-01", ["shfmt_v3.15.0_linux_amd64"], prerelease=True),
        _github_fixture(
            "v3.14.1",
            "2026-09-06",
            ["shfmt_v3.14.1_linux_amd64", "shfmt_v3.14.1_windows_amd64.exe"],
        ),
    ]
    node_index = [
        {"version": "v27.0.0", "date": "2026-11-01", "security": False},
        {"version": "v26.14.0", "date": "2026-11-16", "security": True},
        {"version": "v26.13.2", "date": "2026-11-01", "security": False},
        {"version": "v26.13.1", "date": "2026-10-20", "security": False},
    ]
    shasums = "".join(
        f"{_sha(name)}  {name}\n"
        for name in (
            "node-v26.14.0-darwin-arm64.tar.gz",
            "node-v26.14.0-linux-x64.tar.gz",
            "node-v26.14.0-win-x64.zip",
        )
    )
    return {
        f"{GITHUB_API}/repos/astral-sh/ruff/releases?per_page=100": json.dumps(ruff).encode(),
        f"{GITHUB_API}/repos/mvdan/sh/releases?per_page=100": json.dumps(shfmt).encode(),
        f"{NODE_DIST}/index.json": json.dumps(node_index).encode(),
        f"{NODE_DIST}/v26.14.0/SHASUMS256.txt": shasums.encode(),
    }


_FIXTURE_CATALOG = """[versions]
nodeGradle = "7.1.0"
ruff = "0.17.5"
shfmt = "3.14.1"
node = "26.13.1"

[libraries]
ruff-like = { module = "a:ruff", version.ref = "ruff" }
"""


def _neighbour(group: str, name: str) -> str:
    """Return a Maven component block the self-test expects ``propose`` to leave alone."""
    return (
        f'      <component group="{group}" name="{name}" version="0.17.5">\n'
        f'         <artifact name="{name}-0.17.5.jar">\n'
        f'            <sha256 value="{_sha(name)}" origin="Generated by Gradle"/>\n'
        "         </artifact>\n"
        "      </component>\n"
    )


def _fixture_components() -> list[str]:
    """Return the component blocks of the fixture metadata, in file order."""
    blocks = [_neighbour("antlr", "antlr")]
    for tool, version in zip(TOOLS, ("0.17.5", "3.14.1", "26.13.1")):
        checksums = {name: _sha(name) for name in tool.artifact_names(version)}
        blocks.append(render_component(XML_INDENT * 2, tool, version, checksums))
    blocks.append(_neighbour("sh.astral", "ruff-like"))
    return blocks


def _fixture_metadata() -> str:
    """Return a verification metadata file holding the three tools and two neighbours."""
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n<verification-metadata>\n   <components>\n'
        + "".join(_fixture_components())
        + "   </components>\n</verification-metadata>\n"
    )


def _check_proposal(failures: list[str]) -> None:
    """Run ``propose`` on fixture files and fixture releases and check every edit it makes."""
    table = _fixture_fetch()

    def fetch(url: str) -> bytes:
        return table[url]

    with tempfile.TemporaryDirectory(prefix="native-tool-updates-") as directory:
        root = Path(directory)
        (root / "gradle").mkdir()
        (root / CATALOG).write_text(_FIXTURE_CATALOG, encoding="utf-8", newline="\n")
        (root / METADATA).write_text(_fixture_metadata(), encoding="utf-8", newline="\n")
        with contextlib.redirect_stdout(io.StringIO()):
            bumps = propose(root, fetch, date(2026, 11, 17), DEFAULT_COOLDOWN_DAYS)
            catalog = (root / CATALOG).read_text(encoding="utf-8")
            metadata = (root / METADATA).read_text(encoding="utf-8")
            again = propose(root, fetch, date(2026, 11, 17), DEFAULT_COOLDOWN_DAYS)

    moved = [(bump.tool.key, bump.current, bump.release.version) for bump in bumps]
    if moved != [("ruff", "0.17.5", "0.18.0"), ("node", "26.13.1", "26.14.0")]:
        failures.append(f"bumps: expected ruff 0.18.0 and node 26.14.0, got {moved}")
    expected_catalog = (_FIXTURE_CATALOG.replace('ruff = "0.17.5"', 'ruff = "0.18.0"')).replace(
        'node = "26.13.1"', 'node = "26.14.0"'
    )
    if catalog != expected_catalog:
        failures.append(f"catalog: expected\n{expected_catalog}\ngot\n{catalog}")
    ruff_block = (
        '      <component group="sh.astral" name="ruff" version="0.18.0">\n'
        '         <artifact name="ruff-0.18.0-x86_64-pc-windows-msvc.zip">\n'
        f'            <sha256 value="{_sha("0.18.0ruff-x86_64-pc-windows-msvc.zip")}" '
        'origin="GitHub release digest"/>\n'
        "         </artifact>\n"
        '         <artifact name="ruff-0.18.0-x86_64-unknown-linux-gnu.tar.gz">\n'
        f'            <sha256 value="{_sha("0.18.0ruff-x86_64-unknown-linux-gnu.tar.gz")}" '
        'origin="GitHub release digest"/>\n'
        "         </artifact>\n"
        "      </component>\n"
    )
    if ruff_block not in metadata:
        failures.append(f"metadata: the Ruff component is not\n{ruff_block}in\n{metadata}")
    node_checksum = (
        f'value="{_sha("node-v26.14.0-win-x64.zip")}" origin="nodejs.org SHASUMS256.txt"'
    )
    if node_checksum not in metadata or "26.13.1" in metadata:
        failures.append("metadata: the Node.js component is not the 26.14.0 SHASUMS256.txt entry")
    antlr, ruff, shfmt, node, ruff_like = _fixture_components()
    if any(block not in metadata for block in (antlr, shfmt, ruff_like)):
        failures.append("metadata: a component that did not move changed")
    if ruff in metadata or node in metadata:
        failures.append("metadata: a component that moved still verifies its old version")
    if again:
        failures.append(f"a second run proposed {[bump.tool.key for bump in again]} again")
    if title(bumps) != "chore(deps): bump Ruff to 0.18.0 and Node.js to 26.14.0":
        failures.append(f"title: got {title(bumps)!r}")
    if reformat_subject(bumps) != "style: reformat with Ruff 0.18.0":
        failures.append(f"reformat subject: got {reformat_subject(bumps)!r}")
    if reformat_subject(bumps[1:]) != "style: reformat with spotlessApply":
        failures.append(
            f"reformat subject without a formatter: got {reformat_subject(bumps[1:])!r}"
        )
    if "| Node.js (`node`) | 26.13.1 | 26.14.0 |" not in pull_request_body(bumps):
        failures.append("body: the Node.js row is missing")
    if ", security release |" not in pull_request_body(bumps):
        failures.append("body: the security release is not marked")


def _check_selection(failures: list[str]) -> None:
    """Check the cooldown, the downgrade guard and the refusal of a release without digests."""
    old = Release("1.2.0", date(2026, 11, 1), "u")
    young = Release("1.3.0", date(2026, 11, 15), "u")
    young_security = Release("1.3.1", date(2026, 11, 16), "u", security=True)
    today = date(2026, 11, 17)
    cases = [
        ([old, young], "1.1.0", 7, "1.2.0"),
        ([old, young], "1.1.0", 0, "1.3.0"),
        ([old, young, young_security], "1.1.0", 7, "1.3.1"),
        ([old, young], "1.2.0", 7, None),
        ([old], "1.10.0", 7, None),
    ]
    for releases, current, cooldown, expected in cases:
        chosen = newest_qualifying(releases, current, today, cooldown)
        got = chosen.version if chosen else None
        if got != expected:
            failures.append(
                f"selection from {current} with cooldown {cooldown}: {got} != {expected}"
            )

    undigested = Release(
        "0.18.0",
        today,
        "u",
        digests={
            "ruff-x86_64-unknown-linux-gnu.tar.gz": f"sha256:{_sha('x')}",
            "ruff-x86_64-pc-windows-msvc.zip": "",
        },
    )
    try:
        release_checksums(TOOLS[0], lambda url: b"", undigested)
        failures.append("an asset without a digest was accepted")
    except UpdateError:
        pass

    metadata = _fixture_metadata()
    try:
        with_component(metadata, TOOLS[0], "0.17.4", "0.18.0", {})
        failures.append("a component at another version than the catalog's was replaced")
    except UpdateError:
        pass


def _check_repository(failures: list[str]) -> None:
    """Check that the repository's catalog and metadata hold each tool as the table names it."""
    catalog = (REPOSITORY / CATALOG).read_text(encoding="utf-8")
    metadata = (REPOSITORY / METADATA).read_text(encoding="utf-8")
    for tool in TOOLS:
        version = catalog_version(catalog, tool.key)
        component_version, names = component_artifacts(metadata, tool)
        expected = sorted(tool.artifact_names(version))
        if component_version != version or sorted(names) != expected:
            failures.append(
                f"{METADATA}: {tool.group}:{tool.module} is {component_version} with {names}, "
                f"but the catalog pins {version} and the tool table expects {expected}"
            )


def selftest() -> int:
    """Run every self-test check and return the process exit code."""
    failures: list[str] = []
    _check_proposal(failures)
    _check_selection(failures)
    _check_repository(failures)
    for failure in failures:
        print(f"SELFTEST FAIL: {failure}", file=sys.stderr)
    if failures:
        return 1
    print("selftest: ok")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    """Parse the command line and run the self-test or the proposal."""
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--selftest", action="store_true", help="run the built-in self-test")
    commands = parser.add_subparsers(dest="command")
    proposal = commands.add_parser("propose", help="move every tool with a newer release")
    proposal.add_argument("--root", type=Path, default=REPOSITORY, help="the repository root")
    proposal.add_argument(
        "--cooldown-days",
        type=int,
        default=DEFAULT_COOLDOWN_DAYS,
        help="the age in days a release needs unless it is a security release",
    )
    proposal.add_argument("--body-file", type=Path, help="where to write the pull-request body")
    proposal.add_argument("--outputs", type=Path, help="the step-output file to append to")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if args.command != "propose":
        parser.error("pass --selftest or the propose command")

    token = os.environ.get("GH_TOKEN")
    try:
        bumps = propose(
            args.root,
            lambda url: http_fetch(url, token),
            datetime.now(timezone.utc).date(),
            args.cooldown_days,
        )
    except UpdateError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    if bumps and args.body_file:
        args.body_file.write_text(pull_request_body(bumps), encoding="utf-8", newline="\n")
    if args.outputs:
        write_outputs(args.outputs, bumps)
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Checks that every relative link in the exchange documentation works on the published site.

A relative link must stay inside docs/exchange, which is all the site publishes, and point at a file
that exists; a link to a directory needs an index page there (README.md, index.md or index.html),
or the site answers 404. Links into the site's generated parts (the OpenAPI reference and the
schema copies, both with generated index pages) are checked against their sources in the
repository. An anchor into a Markdown page, or into the page itself, must be the id of one of its
headings as GitHub Pages renders it: kramdown's GFM parser lower-cases the heading's source text,
drops every character that is not a letter, digit, underscore, hyphen or space, turns each space
into a hyphen and numbers repeats with -1, -2 and so on. Absolute URLs are not followed.

Every page the site navigation (`_data/navigation.yml`) lists must exist too, or the layout would
silently drop its entry.

The site is for developers and carries no German: no umlaut, no sharp s and no German low
quotation mark in its sources, nor in the OpenAPI document and schemas it renders. Conformance
fixtures under examples/ are data under test and are not read.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)|^ {0,3}\[[^\]]+\]:[ \t]*<?([^\s>]+)>?", re.MULTILINE)

HEADING = re.compile(r"^ {0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$")

CUSTOM_ID = re.compile(r"[ \t]*\{:?[ \t]*#([A-Za-z][\w-]*)[ \t]*\}$")

FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})")

NOT_IN_ID = re.compile(r"[^\w\- \t]")

INDEX_PAGES = ("README.md", "index.md", "index.html")

GENERATED = {
    "reference": "ingest/src/main/resources/api/exchange-v1.openapi.json",
    "reference/exchange-v1.openapi.json": "ingest/src/main/resources/api/exchange-v1.openapi.json",
    "schemas": "ingest/src/main/resources/exchange/v1/schemas",
}

GENERATED_PAGES = {
    "reference/index.html": "ingest/src/main/resources/api/exchange-v1.openapi.json",
    "schemas/index.md": "ingest/src/main/resources/exchange/v1/schemas",
}

NAV_PATH = re.compile(r"^\s*path:\s*(\S+)\s*$", re.MULTILINE)

GERMAN = re.compile("[äöüÄÖÜßẞ„‚]")

PROSE_SUFFIXES = {".md", ".html", ".yml", ".yaml", ".py", ".js", ".css", ".txt"}

RENDERED_SOURCES = (
    "ingest/src/main/resources/api/exchange-v1.openapi.json",
    "ingest/src/main/resources/exchange/v1/schemas",
)


def german_text(repo: pathlib.Path) -> list[str]:
    """Returns every line of the site's sources that carries a German character.

    Reads the prose files under docs/exchange except the fixture data under examples/, and the
    OpenAPI document and schemas the reference and the schema index render.

    Args:
        repo: the repository root.

    Returns:
        One ``file:line: characters`` line per offending line.
    """
    docs = repo / "docs" / "exchange"
    files = [
        path
        for path in docs.rglob("*")
        if path.is_file()
        and path.suffix in PROSE_SUFFIXES
        and not (path.relative_to(docs).parts[0] == "examples" and path.suffix != ".md")
    ]
    for source in RENDERED_SOURCES:
        root = repo / source
        files += sorted(root.glob("*.json")) if root.is_dir() else [root] if root.exists() else []
    found = []
    for path in sorted(files):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            hits = GERMAN.findall(line)
            if hits:
                found.append(
                    f"{path.relative_to(repo).as_posix()}:{number}: {''.join(sorted(set(hits)))}"
                )
    return found


def broken_navigation(repo: pathlib.Path) -> list[str]:
    """Returns every page the site navigation lists that the site would not have.

    Args:
        repo: the repository root.

    Returns:
        One ``_data/navigation.yml: path (reason)`` line per missing page.
    """
    docs = repo / "docs" / "exchange"
    navigation = docs / "_data" / "navigation.yml"
    if not navigation.is_file():
        return ["docs/exchange/_data/navigation.yml (missing)"]
    broken = []
    for path in NAV_PATH.findall(navigation.read_text(encoding="utf-8")):
        where = f"docs/exchange/_data/navigation.yml: {path}"
        if path in GENERATED_PAGES:
            if not (repo / GENERATED_PAGES[path]).exists():
                broken.append(f"{where} (source missing)")
        elif not (docs / path).is_file():
            broken.append(f"{where} (missing)")
    return broken


def heading_ids(text: str) -> set[str]:
    """Returns the ids GitHub Pages gives the headings of a Markdown page.

    Follows kramdown's GFM parser: an explicit ``{#id}`` wins; otherwise the heading's source text
    is lower-cased, stripped of every character but letters, digits, underscores, hyphens and
    spaces, each space becomes a hyphen, and a repeated id gets ``-1``, ``-2`` and so on. Headings
    inside fenced code blocks are not headings.

    Args:
        text: the page's Markdown source.

    Returns:
        Every heading id of the page.
    """
    ids = set()
    seen: dict[str, int] = {}
    fence = None
    for line in text.splitlines():
        opening = FENCE.match(line)
        if opening:
            marker = opening.group(1)
            if fence is None:
                fence = marker
            elif (
                marker[0] == fence[0]
                and len(marker) >= len(fence)
                and not line.strip(marker[0]).strip()
            ):
                fence = None
            continue
        if fence is not None:
            continue
        heading = HEADING.match(line)
        if not heading:
            continue
        source = heading.group(2)
        custom = CUSTOM_ID.search(source)
        if custom:
            ids.add(custom.group(1))
            continue
        slug = NOT_IN_ID.sub("", source.lower()).replace(" ", "-").replace("\t", "-")
        count = seen.get(slug, -1) + 1
        seen[slug] = count
        ids.add(slug if count == 0 else f"{slug}-{count}")
    return ids


def markdown_page(resolved: pathlib.Path) -> pathlib.Path | None:
    """Returns the Markdown page a link target renders from, if it is one.

    Args:
        resolved: the link target inside docs/exchange, without its fragment.

    Returns:
        The Markdown file, or ``None`` for any other target.
    """
    if resolved.is_file():
        return resolved if resolved.suffix == ".md" else None
    if resolved.is_dir():
        for name in ("README.md", "index.md"):
            if (resolved / name).is_file():
                return resolved / name
    return None


def broken_links(repo: pathlib.Path) -> list[str]:
    """Returns every relative link under docs/exchange that would not work on the site.

    Args:
        repo: the repository root.

    Returns:
        One ``file: link (reason)`` line per broken link.
    """
    docs = (repo / "docs" / "exchange").resolve()
    broken = []
    ids: dict[pathlib.Path, set[str]] = {}
    for page in sorted(docs.rglob("*.md")):
        text = page.read_text(encoding="utf-8")
        for inline, reference in LINK.findall(text):
            target = inline or reference
            if re.match(r"^[a-z][a-z0-9+.-]*:", target):
                continue
            where = f"{page.relative_to(repo.resolve()).as_posix()}: {target}"
            path, _, fragment = target.partition("#")
            resolved = (page.parent / path).resolve() if path else page.resolve()
            if not resolved.is_relative_to(docs):
                broken.append(f"{where} (leaves the published site)")
                continue
            key = resolved.relative_to(docs).as_posix()
            if key in GENERATED:
                if not (repo / GENERATED[key]).exists():
                    broken.append(f"{where} (source missing)")
                continue
            if not resolved.exists():
                broken.append(f"{where} (missing)")
                continue
            if resolved.is_dir() and not any((resolved / name).exists() for name in INDEX_PAGES):
                broken.append(f"{where} (directory without an index page)")
                continue
            source = markdown_page(resolved)
            if fragment and source is not None:
                if source not in ids:
                    ids[source] = heading_ids(source.read_text(encoding="utf-8"))
                if fragment not in ids[source]:
                    broken.append(f"{where} (no such heading)")
    return broken


def selftest() -> None:
    """Proves the checker finds each kind of broken link and passes good ones."""
    with tempfile.TemporaryDirectory() as tmp:
        repo = pathlib.Path(tmp).resolve()
        docs = repo / "docs" / "exchange"
        docs.mkdir(parents=True)
        (repo / "docs" / "other.md").write_text("# other", encoding="utf-8")
        (docs / "a.md").write_text(
            "# A page\n\n[ok](b.md#b) [web](https://example.org) [anchor](#a-page) [gone](missing.md)"
            " [ref](reference/) [out](../other.md) [bare](bare/) [indexed](indexed/)",
            encoding="utf-8",
        )
        (docs / "b.md").write_text("# b", encoding="utf-8")
        (docs / "bare").mkdir()
        (docs / "indexed").mkdir()
        (docs / "indexed" / "README.md").write_text("# indexed", encoding="utf-8")
        found = broken_links(repo)
        assert found == [
            "docs/exchange/a.md: missing.md (missing)",
            "docs/exchange/a.md: reference/ (source missing)",
            "docs/exchange/a.md: ../other.md (leaves the published site)",
            "docs/exchange/a.md: bare/ (directory without an index page)",
        ], found
        spec = repo / "ingest/src/main/resources/api"
        spec.mkdir(parents=True)
        (spec / "exchange-v1.openapi.json").write_text("{}", encoding="utf-8")
        found = broken_links(repo)
        assert len(found) == 3, found
        (docs / "anchors.md").write_text(
            "# Anchors\n\n"
            "## Warehouse locations — `GET /exchange/v1/catalog/locations`\n\n"
            "## Rate limits, quota and back-off\n\n"
            "## Errors\n\n## Errors\n\n"
            "### 4. Poll the token endpoint\n\n"
            "## Custom {#own-id}\n\n"
            "```python\n# not-a-heading\n```\n\n"
            "[a](#warehouse-locations--get-exchangev1cataloglocations)"
            " [b](#rate-limits-quota-and-back-off) [c](#errors-1) [d](#4-poll-the-token-endpoint)"
            " [e](#own-id) [f](#not-a-heading) [g](#lager-locations--get-exchangev1cataloglocations)"
            " [h](#errors-2) [i](indexed/#indexed) [j](indexed/#gone) [k](b.md#b)"
            " [l](reference/#anything)\n\n[def]: b.md#nowhere\n",
            encoding="utf-8",
        )
        found = [line for line in broken_links(repo) if "anchors.md" in line]
        assert found == [
            "docs/exchange/anchors.md: #not-a-heading (no such heading)",
            "docs/exchange/anchors.md: #lager-locations--get-exchangev1cataloglocations (no such heading)",
            "docs/exchange/anchors.md: #errors-2 (no such heading)",
            "docs/exchange/anchors.md: indexed/#gone (no such heading)",
            "docs/exchange/anchors.md: b.md#nowhere (no such heading)",
        ], found
        (docs / "anchors.md").unlink()
        assert broken_navigation(repo) == ["docs/exchange/_data/navigation.yml (missing)"]
        (docs / "_data").mkdir()
        (docs / "_data" / "navigation.yml").write_text(
            "- section: S\n  items:\n    - title: B\n      path: b.md\n"
            "    - title: Gone\n      path: gone.md\n"
            "    - title: Reference\n      path: reference/index.html\n"
            "    - title: Schemas\n      path: schemas/index.md\n",
            encoding="utf-8",
        )
        found = broken_navigation(repo)
        assert found == [
            "docs/exchange/_data/navigation.yml: gone.md (missing)",
            "docs/exchange/_data/navigation.yml: schemas/index.md (source missing)",
        ], found
        assert german_text(repo) == [], german_text(repo)
        (docs / "b.md").write_text(
            "# b\n\nOpen „Verbundene Anwendungen“.\nPlain “English”.\n", encoding="utf-8"
        )
        (docs / "_layouts").mkdir()
        (docs / "_layouts" / "default.html").write_text("<p>Grüße</p>\n", encoding="utf-8")
        (docs / "examples" / "v1").mkdir(parents=True)
        (docs / "examples" / "README.md").write_text("# Fixtures\n", encoding="utf-8")
        (docs / "examples" / "v1" / "umlaut.json").write_text(
            '{"label": "Bärchen"}', encoding="utf-8"
        )
        (spec / "exchange-v1.openapi.json").write_text(
            '{"summary": "The warehouse"}', encoding="utf-8"
        )
        schemas = repo / "ingest/src/main/resources/exchange/v1/schemas"
        schemas.mkdir(parents=True)
        (schemas / "a.schema.json").write_text('{"title": "Örtlich"}\n', encoding="utf-8")
        found = german_text(repo)
        assert found == [
            "docs/exchange/_layouts/default.html:1: ßü",
            "docs/exchange/b.md:3: „",
            "ingest/src/main/resources/exchange/v1/schemas/a.schema.json:1: Ö",
        ], found
    print("selftest ok")


def main() -> int:
    """Runs the check, or its self-test with --selftest.

    Returns:
        0 when every link works, 1 otherwise.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return 0
    repo = pathlib.Path(__file__).resolve().parents[2]
    broken = broken_links(repo) + broken_navigation(repo)
    for line in broken:
        print(f"broken link: {line}")
    german = german_text(repo)
    for line in german:
        print(f"German text: {line}")
    return 1 if broken or german else 0


if __name__ == "__main__":
    sys.exit(main())

# Changelog fragments

A user-visible change adds one file here instead of editing [`CHANGELOG.md`](../CHANGELOG.md), so
parallel pull requests do not conflict. `release-prepare.yml` folds the fragments into the section
of the release and deletes them (REQ-OPS-051, ADR-0306).

- **Name:** `<name>.md` of lowercase letters, digits and hyphens — an issue number or a branch slug,
  e.g. `2877.md`, `hangar-import.md`, `hangar-import-fixed.md` for a second section.
- **First line:** `### <Section>`, one of `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`,
  `Security`. One section per file.
- **Then** one or more list items in the style of `CHANGELOG.md`: German, short, for members, a bold
  lead-in. Each item starts with `- `, continuation lines are indented by two spaces, blank lines
  between items are fine; no further heading and no loose text.
- UTF-8 without BOM, LF line endings, umlauts as real characters.

```markdown
### Fixed

- **Suche.** Ein leerer Filter blendet keine Einträge mehr aus.
```

`python3 .github/scripts/check_changelog_fragments.py --base origin/main` runs the check of
`repo-lint.yml` locally. A pull request that adds an item under `## [Unreleased]` fails it; an entry
that is merged but not yet released lives here until the release.

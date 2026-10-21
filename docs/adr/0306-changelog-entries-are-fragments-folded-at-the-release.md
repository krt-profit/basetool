# ADR-0306 — Changelog entries are fragments, folded into the release section at the release

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** REQ-OPS-051 · REQ-OPS-029 · spec
  [`deployment-delivery.md`](../specs/deployment-delivery.md) · runbook
  [`deployment.md` → Cutting a release](../deployment.md#cutting-a-release) · the same method in
  `krt-profit/p4k-extractor` (its ADR-0035)

## Context

Every pull request with a user-visible change adds its entry to `CHANGELOG.md`, at the same few
lines under `## [Unreleased]` → `### Added`, `### Changed` or `### Fixed`. With many pull requests
open at once, every merge leaves the others conflicting in `CHANGELOG.md`, and each conflict costs a
merge with `main`, a CI round and, for a stacked or queued pull request, another wait. About a third
of all commits touch the file.

The release side already rewrites the file mechanically: `release-prepare.yml` runs
`reconcile_changelog.py` and `cut_release.py`, and the release notes skill reads the dated sections.

## Decision

1. **A pull request writes a fragment, not the changelog.** A user-visible change adds
   `changelog.d/<name>.md`: one `### <section>` line of Keep a Changelog, then the items in the style
   of `CHANGELOG.md`. The name is free within lowercase letters, digits and hyphens, because many
   pull requests have no issue number.
2. **`[Unreleased]` is closed.** `check_changelog_fragments.py` checks every fragment and fails a
   pull request that adds an item under `[Unreleased]`. The items standing there today stay and are
   cut by the next release.
3. **The release folds the fragments.** `cut_release.py` cuts `[Unreleased]` as before, appends each
   fragment's items to their section in the order the fragments were added on `main`, and deletes
   the fragments; `create_signed_commit.py` gains `--delete` so the release commit carries the
   deletions.
4. **A fragment goes to the release that shipped it.** The fold looks up the commit that added the
   fragment and, when an older release tag already contains it, puts the items into that tag's
   section. This replaces, for fragments, the blame-based attribution `reconcile_changelog.py` gives
   to `[Unreleased]` items.
5. **The release notes read the fragments.** `gather_changes.py` prints the fragments at the window
   end as a source of their own.
6. **An own stdlib script, no fragment tool.** towncrier, scriv and changie were considered.

## Consequences

- Two pull requests no longer conflict over the changelog; only two fragments of the same name do,
  which the author resolves by renaming.
- `CHANGELOG.md` on `main` no longer shows what is merged but unreleased; that is the content of
  `changelog.d/`.
- Within a section, a release lists the fragments in merge order after any legacy items.
- After the fold, `git blame` on an entry points to the release commit; the pull request that wrote
  it is found through the history of the deleted fragment.
- A follow-up that corrects an unreleased entry edits the other pull request's fragment.
- A habit of editing `CHANGELOG.md` directly is caught by the gate rather than by review.

## Alternatives considered

- **`merge=union` for `CHANGELOG.md`.** Cheap, and it works locally for single-line items, but
  GitHub's mergeability check does not apply it, and a union merge interleaves lines silently — a
  duplicated item or one under the wrong section passes unnoticed.
- **The entry in the pull request body or a commit trailer, collected at the release.** No conflict,
  but the entry leaves the reviewed diff, the release needs the GitHub API, and a body edited at the
  squash merge loses it.
- **Generated from Conventional Commits** (git-cliff, release-drafter). The commit subjects are
  English and technical; the changelog is German and written for members.
- **A fragment tool.** scriv fits the format best (Keep a Changelog sections, collision-free names,
  free Markdown), towncrier is the most widespread, changie keeps fragments as YAML. Each adds a
  dependency to `release-prepare.yml`, which writes branches and opens pull requests, and none of
  them attributes a fragment to the tag that shipped it, keeps the legacy `[Unreleased]` items, writes
  the linked version heading or closes `[Unreleased]` — a wrapper would still be needed, and the
  core of the fold is small.

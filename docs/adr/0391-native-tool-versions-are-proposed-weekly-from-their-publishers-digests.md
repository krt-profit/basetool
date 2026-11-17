# ADR-0391 — Native tool versions are proposed weekly from their publishers' digests

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** @greluc
- **Related:** REQ-OPS-059 · REQ-OPS-058 · REQ-OPS-044 · REQ-OPS-034 · ADR-0390 · ADR-0208 ·
  ADR-0201 · [`native-tool-updates.yml`](../../.github/workflows/native-tool-updates.yml) ·
  [`native_tool_updates.py`](../../.github/scripts/native_tool_updates.py)

## Context

- Ruff and shfmt (ADR-0390) and the Node.js archive are resolved through Ivy repositories declared
  in `settings.gradle.kts`. Their versions are bare `[versions]` entries of the catalog, and
  `gradle/verification-metadata.xml` holds one SHA-256 per supported platform, Linux and Windows on
  x86-64 (ADR-0208).
- Nothing proposed their updates. `.github/dependabot.yml` has no Gradle ecosystem, and the weekly
  refreshVersions run queries Maven repositories only. Every bump was a manual change, so the
  formatters every agent has to run (REQ-OPS-058) would fall behind their releases unnoticed.
- `--write-verification-metadata` records the SHA-256 of whatever it downloads and verifies nothing,
  and it writes only the platform it runs on; the other platform's entry was copied from a release
  page by hand.
- `main` requires verified signatures and merges through the merge queue with a merge commit
  (REQ-OPS-044), and `GITHUB_TOKEN` may not open pull requests in this organisation (ADR-0201).

## Decision

We will let a weekly workflow propose these bumps, taking the checksums from what the publishers
state rather than from a download.

1. **One weekly pull request.** `native-tool-updates.yml` runs every Monday and on dispatch, in the
   `release` environment, against `main`. `.github/scripts/native_tool_updates.py propose` does the
   lookups and the edits; the workflow commits, opens one pull request from
   `chore/native-tool-updates` through the `basetool-release` App, assigned to @greluc and labelled
   `dependencies` and `automated`, and rebuilds it from `main` every week while it is open.
2. **Which release.** The newest release that is neither a draft nor a prerelease and is at least
   seven days old: Ruff and shfmt from the GitHub releases of `astral-sh/ruff` and `mvdan/sh`,
   Node.js from `https://nodejs.org/dist/index.json` on the major line the catalog pins. A Node.js
   security release qualifies at once. A version never moves down; another Node.js major line stays
   a change by hand.
3. **Which checksums.** The SHA-256 the publisher states for each supported platform's file: the
   `digest` GitHub records for a release asset, and the release's `SHASUMS256.txt` on nodejs.org. A
   file without one fails the run. The script replaces the tool's whole component, both platforms,
   with entries sorted the way Gradle writes them; the run's `./gradlew spotlessApply` then verifies
   the Linux files against them.
4. **Which commits.** All commits are GitHub-signed through `createCommitOnBranch`
   (`create_signed_commit.py`) with the App's token and signed off by it. They are built on a branch
   of their own, and the pull request's branch is moved to the result in one ref update, so an open
   pull request never points at `main` itself. The bump is one commit; files `spotlessApply`
   changes are a second; a third appends that second commit's hash to `.git-blame-ignore-revs`. The
   merge commit keeps the hash valid on `main`.
5. **Refusing and catching up.** A pull request closed unmerged is not proposed again with the same
   title, which names every tool and version. When `main` already carries every qualifying release,
   an open proposal is closed with its branch.
6. **The script checks the repository.** Its self-test, run by `repo-lint.yml`, also asserts that
   the catalog and the metadata hold each tool with the artifact names the script would write, so a
   change to an Ivy pattern or a component that leaves the script behind fails on the pull request
   that makes it.

## Consequences

- Ruff, shfmt and Node.js bumps arrive without anyone looking for them; reviewing one means reading
  the linked release notes and the pull request's CI.
- A release reaches the proposal a week after it is published, a Node.js security release the next
  Monday.
- The run verifies the Linux files only. The Windows checksum is the publisher's statement, verified
  by the first Windows build that resolves it.
- The trust is the publisher's channel over HTTPS: GitHub's asset digest, nodejs.org's checksum
  file. `SHASUMS256.txt.asc` is not verified, as Gradle verifies no PGP signature here (ADR-0208).
- `chore/native-tool-updates` is rewritten every week; a commit pushed to it by hand is lost.
- Closing refuses only the identical set: any newer release of any tool opens a proposal again.
- A Node.js major line change, and the exchange-docs workflow's `setup-node` major with it, stays a
  change by hand.

## Alternatives considered

- **Dependabot.** It has no ecosystem for these Ivy repositories and cannot write the verification
  metadata.
- **Renovate** with a custom manager. A second bot with write access and its own configuration
  format; writing the metadata needs post-upgrade tasks, which only a self-hosted Renovate runs.
- **`--write-verification-metadata` in the workflow.** It records the download instead of checking
  it, and only for Linux.
- **`peter-evans/create-pull-request` with `sign-commits`.** It recreates the local commits through
  the API, so the hash a blame-ignore entry has to name is unknown until after the push.
- **The reformat folded into the bump commit.** `git blame` would then name the bump for every
  reformatted line.
- **No cooldown.** A release that is withdrawn or fixed within days would reach the proposal the
  same week.

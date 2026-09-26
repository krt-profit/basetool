# ADR-0215 — A Dependabot compose bump carries its regenerated units

- **Status:** Accepted
- **Date:** 2026-09-26
- **Deciders:** @greluc (owner; asked for a bump that is complete on its own after #2100, #2101 and
  #2103 on 2026-09-26)
- **Related:** [ADR-0203](0203-compose-stays-the-source-of-the-units-and-the-scripts-speak-podman-only.md)
  · [ADR-0201](0201-release-tags-and-release-prs-are-created-by-the-basetool-release-app.md)
  (Amendment 2) · [`docs/specs/deployment-delivery.md`](../specs/deployment-delivery.md)
  (`REQ-OPS-035`, `REQ-OPS-004`) · [`docs/deployment.md`](../deployment.md) → *Dependabot image bumps*

## Context

The compose files are the source of the production units (ADR-0203): `scripts/generate-quadlet.py`
translates them into `quadlet/systemd/`, both are committed, and `repo-lint.yml` fails a change whose
units no longer match (`quadlet-drift`). The monitoring image tags are also written in prose, and
`scripts/check-monitoring-image-pins.sh` fails a document that still names the old one.

Dependabot's `docker-compose` ecosystem edits `docker-compose*.yml` and nothing else. **Every compose
image bump therefore arrives red** on the required *Repository gates* job. On 2026-09-26 two of them
were merged red — #2101 (`redis_exporter` v1.92.0) and #2100 (a `postgres:18-alpine` digest) — and
`main` carried units that still named the old images until #2103 regenerated them. Production runs the
units, not compose: a release cut from that `main` would have shipped the old images while the
changelog said otherwise.

A second, older trap sits in the same PRs. The digest Dependabot writes is the one the tag resolved to
when it opened the PR, and an Alpine-based tag is rebuilt in place, so the pin can be stale before
anyone reviews it (measured on #1945, 2026-09-21). The owner's rule was to re-resolve every digest by
hand before merging.

Getting a commit onto a Dependabot branch from CI has three constraints:

- A `pull_request` run triggered by Dependabot gets a read-only `GITHUB_TOKEN` and reads **Dependabot
  secrets**, not Actions secrets.
- A commit pushed with `GITHUB_TOKEN` triggers no workflow. The nine required checks would never report
  on the new head, and `main`'s ruleset (strict status checks) would block the merge for good.
- `main` requires verified signatures and every PR commit passes `dco.yml`.

## Decision

**A workflow on Dependabot compose PRs completes the bump and commits the result onto the Dependabot
branch through the `basetool-release` App.** `.github/workflows/dependabot-compose.yml`:

1. Runs on `pull_request` (`opened`, `synchronize`, `reopened`) touching `docker-compose*.yml`, only
   when the PR's author is `dependabot[bot]` and its head is in this repository.
2. **Refuses a branch that is more than a compose bump** (`dependabot_compose_followup.py guard`):
   every commit since the merge base must be a non-merge commit by Dependabot touching only the
   top-level `docker-compose*.yml`, or one by the `basetool-release` App. The scripts that run next
   are then `main`'s own.
3. **Re-resolves every digest the branch bumped** (`refresh-digests`) with
   `docker buildx imagetools inspect`, and moves a pin whose tag names a newer index. A registry that
   cannot be read leaves Dependabot's digest in place and prints a warning.
4. Runs `python3 scripts/generate-quadlet.py` and `scripts/check-monitoring-image-pins.sh --fix`, then
   both checks, so a commit that would still fail is never made. A regeneration that adds or removes a
   file is refused — an image bump modifies.
5. When anything changed, mints a `basetool-release` token scoped to `contents: write` from the
   **Dependabot secret** `RELEASE_APP_PRIVATE_KEY` and commits the modified files with
   `createCommitOnBranch` (`.github/scripts/create_signed_commit.py`), asserting the head it read as
   `expectedHeadOid`. GitHub signs the commit; its author is `basetool-release[bot]`, whose address is
   added to `dco.yml`'s bot list, and it carries a matching `Signed-off-by`.

An App token's commit triggers the PR's workflows again, so every required check reports on the
completed head. That run finds nothing to change and ends there.

## Consequences

- **A compose bump is mergeable on its own**, and its units and its documented pins match the image it
  names. The hand-run regeneration and the hand-run digest check are gone from the merge path.
- **The App's key is stored twice**: as an Actions secret (the release workflows, refreshVersions) and
  as a Dependabot secret (this workflow). Rotating it means updating both; a stale Dependabot copy
  fails only this workflow, at the mint step, with the bump left red as before.
- **The key is reachable from Dependabot-triggered runs.** A secret is injected only into a step that
  names it, and only this workflow names it on a Dependabot PR; the npm and Actions bumps that run
  other workflows never receive it. The token it mints is scoped to `contents: write` on this
  repository.
- **Dependabot stops rebasing a PR it no longer owns alone.** `@dependabot recreate` rebuilds the
  branch; the workflow then adds its commit again. A branch updated with *Update branch* carries a
  human merge commit, which the guard refuses — `@dependabot recreate` is the fix there too.
- **A digest can move between PR and merge** when the tag was rebuilt, and the workflow's log says so.
  The PR title still names Dependabot's digest; the diff is the truth.
- A `postgres` or `redis` digest refresh still recreates the stateful container on the next deploy
  tick. That is an operator's decision and stays one: merging the PR is it.

## Rejected alternatives

- **Generate the units at deploy time from compose.** The generator (about 1,200 lines) refuses rather
  than guesses; moved onto the host, a refusal becomes a failed production deploy instead of a red PR,
  and the units that run would no longer be the units anyone reviewed. It would also leave the
  documented monitoring pins stale, so the PR would stay red on `monitoring-image-pins` anyway.
  ADR-0203 keeps the units committed for exactly the reviewability this would lose.
- **`pull_request_target` or `workflow_run`.** Both run with Actions secrets in `main`'s context while
  handling the PR's content, which zizmor's `dangerous-triggers` audit fails and which would need an
  ignore entry. The `pull_request` run with a Dependabot secret gets the same result without either.
- **`GITHUB_TOKEN` with `contents: write`.** Its commit triggers no workflow, so the required checks
  never run on the completed head and the PR can never merge.
- **Let the drift gate ignore `Image=` lines.** Production would keep the old image with a green check,
  which is the failure the gate exists to catch.
- **Renovate with post-upgrade tasks.** Running arbitrary commands after an update needs a
  self-hosted Renovate; a second dependency bot would also duplicate Dependabot's other ecosystems.
- **A second App only for this.** A narrower blast radius, bought with another key to create, store and
  rotate. The scoped token already limits each use; revisit if the App's installation grows.

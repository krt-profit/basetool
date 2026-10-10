# ADR-0201 — Release tags and release PRs are created by the `basetool-release` GitHub App

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc (the App, its key and the tag ruleset were set up on 2026-09-22; "use the
  App, fix it permanently" decided in chat the same evening)
- **Related:** [ADR-0137](0137-one-image-build-per-commit-and-no-buildkit-layer-cache.md) ·
  [ADR-0145](0145-build-provenance-anchored-outside-the-registry.md) ·
  [`docs/specs/deployment-delivery.md`](../specs/deployment-delivery.md) (`REQ-OPS-021`) ·
  [`docs/deployment.md`](../deployment.md) · [`.github/SECURITY.md`](../../.github/SECURITY.md)

## Context

A release is two workflows. `release-prepare.yml` cuts the changelog on a `release/vX.Y.Z` branch
and opens a PR; `release-publish.yml` runs when that PR is merged and creates the `vX.Y.Z` tag, whose
push starts `release-images.yml`. Both used a `RELEASE_TOKEN` secret — a personal access token —
for the one step each that `GITHUB_TOKEN` cannot do: opening a PR (the organisation forbids Actions
from doing that with `GITHUB_TOKEN`) and creating a tag **that triggers other workflows** (events
caused by `GITHUB_TOKEN` never do).

On 2026-09-22 the repository's tag ruleset **"Version"** (`refs/tags/v*`, `refs/tags/V*`) gained
`creation` and `update` next to its existing `deletion` and `non_fast_forward`, with exactly two
bypass actors: @greluc and a new GitHub App, **`basetool-release`** (Contents: write, Pull requests:
write, Metadata: read), installed for selected repositories. Its private key went into the secret
`RELEASE_APP_PRIVATE_KEY`. Nothing used it yet.

The v1.10.0 release found the gap: `release-publish.yml` tried to create the tag with the PAT and
got `Resource not accessible by personal access token` (HTTP 403). The tag had to be created by
hand. v1.9.2, four hours earlier and before the ruleset change, had gone through untouched.

## Decision

**Both release workflows mint a short-lived token of the `basetool-release` App for their one
privileged step, and nothing else creates a release tag.**

- `release-publish.yml` mints a token scoped to `contents: write` and creates the tag with it. There
  is **no fallback** to `GITHUB_TOKEN`: the ruleset refuses it, and the old fallback's promise — "the
  tag is still created, only the image build must be kicked by hand" — is no longer true. A run
  without the App's key stops with an error naming this ADR.
- `release-prepare.yml` mints a token scoped to `contents: read` and `pull-requests: write` and opens
  the PR with it. The release **commit** stays `GITHUB_TOKEN`'s: its `github-actions[bot]` author is
  the one the DCO check exempts, and an App-authored commit would need an exemption of its own for no
  gain.
- The token comes from `actions/create-github-app-token`, pinned by SHA, with the App's **client
  ID** as a literal in the workflow. The client ID is public (`GET /apps/basetool-release` returns
  it), so a variable would hide nothing, and it names the same App the ruleset names — rotating one
  without the other is a change that should be visible in review. The deprecated `app-id` input is
  not used; it is the one a v4 of the action removes.
- Each token is scoped with `permission-*` inputs to its step's need, not to everything the
  installation allows. zizmor's `github-app` audit enforces that: an unscoped mint is a high finding
  that fails `repo-lint`.

## Consequences

- **Release tags have exactly two possible authors**, both named in the ruleset: the App (the normal
  path) and @greluc (the manual path, as used for v1.10.0). A tag pushed by anyone else is refused
  before it exists, which is the property the signing identity in `SECURITY.md` quietly relied on —
  `release-images.yml@refs/tags/vX.Y.Z` is only as trustworthy as the ability to create that ref.
- **`RELEASE_TOKEN` is no longer read by any workflow**, and neither is the variable
  `RELEASE_APP_ID` (the numeric App id, which only the deprecated input took). Both can be deleted;
  deleting a secret is left to the owner.
- One secret now carries the whole release path. If `RELEASE_APP_PRIVATE_KEY` expires or is
  rotated without updating the secret, both workflows stop at their first step with an explicit
  error instead of half-publishing.
- The App's installation permissions are broader than either step needs (it can write contents and
  PRs in every repository it is installed on). The scoped tokens narrow each use to this repository
  and one permission set; the installation, which covers selected repositories, is the ceiling.

## Rejected alternatives

- **Repair the PAT** — widen its permissions, or make whoever owns it a bypass actor. Which of the
  two the 403 needs was not established, and it does not matter: either way a long-lived personal
  token keeps the power to create release tags, which is the opposite of what the ruleset change
  was for.
- **Keep a `GITHUB_TOKEN` fallback.** It cannot create a `v*` tag any more, so a fallback would only
  change the failure from an error into a wrong promise.
- **Store the client ID in a repository variable.** It is public; the indirection would only make
  the workflow harder to read and let the App change without a diff.

## Amendment 1 (2026-09-23) — the refreshVersions PR is opened by the App too

`refresh-versions.yml` was the last workflow on a personal access token: it opened its weekly
`chore/refresh-versions` PR with `REFRESH_VERSIONS_TOKEN`, for the same reason the release path had
used `RELEASE_TOKEN` — the organisation forbids `GITHUB_TOKEN` from opening pull requests. It now
mints a `basetool-release` token the same way, scoped to `contents: write` (create-pull-request
pushes the branch) and `pull-requests: write`, and stops with an explicit error when
`RELEASE_APP_PRIVATE_KEY` is missing (audit item CI-SEC-16). The commit keeps its
`github-actions[bot]` author, so the DCO exemption is unchanged.

- **`REFRESH_VERSIONS_TOKEN` is no longer read by any workflow.** Together with `RELEASE_TOKEN` and
  the variable `RELEASE_APP_ID` it can be deleted once a refreshVersions run has opened its PR
  through the App; deleting it is left to the owner.
- No workflow in the repository reads a personal access token any more.

## Amendment 2 (2026-09-26) — the App completes Dependabot compose bumps

`dependabot-compose.yml` mints a `basetool-release` token scoped to `contents: write` and commits
the regenerated Quadlet units onto a Dependabot compose-bump branch ([ADR-0215](0215-a-dependabot-compose-bump-carries-its-regenerated-units.md)).
Unlike the release commit, this one **is App-authored**: it is created through `createCommitOnBranch`
so GitHub signs it, and it must trigger the PR's required checks, which a `GITHUB_TOKEN` commit does
not. `dco.yml` therefore lists `basetool-release[bot]` among its bot authors.

- A Dependabot-triggered run reads **Dependabot secrets** only, so `RELEASE_APP_PRIVATE_KEY` is also
  stored there. Rotating the key updates both the Actions and the Dependabot secret.

## Amendment 3 (2026-10-04) — the key moves into a `main`-only `release` environment

The App's private key was a repository-wide secret, so any workflow on any branch could read it
(audit item CI-SEC-16). `release-prepare.yml` (dispatched on `main`) and `refresh-versions.yml`
(scheduled, so on `main`) now declare `environment: release` on the job that mints the token. An
environment secret of the same name takes precedence over the repository secret, so the jobs keep
working before and after the owner's one-time step:

1. *Settings → Environments → New environment* `release`; *Deployment branches and tags* →
   *Selected branches and tags* → add the branch rule `main`. No reviewers, no wait timer.
2. In that environment add the secret `RELEASE_APP_PRIVATE_KEY` with the App's PEM.

- **`release-publish.yml` is not confined yet.** It runs on `pull_request: closed`, and GitHub
  evaluates an environment's branch rule against `GITHUB_REF`, which for that event is
  `refs/pull/<n>/merge`; a `main`-only rule refuses it, and admitting `refs/pull/*/merge` would admit
  every pull request. Confining it needs a trigger whose ref is `main` — a `push` to `main` that
  recognises the release merge, or `pull_request_target` — which is a decision of its own. Until
  then the repository secret stays, and deleting it would stop the publish job.
- The Dependabot secret store's copy (Amendment 2) is unaffected: Dependabot-triggered runs read
  only that store.
- A key rotation now updates up to three copies: the `release` environment, the repository secret
  while it exists, and the Dependabot secret.

## Amendment 4 (2026-10-04) — publishing runs on the `main` push

Decided by @greluc on 2026-10-04: **`release-publish.yml` is triggered by `push` to `main`**, not by
`pull_request: closed` and not by `pull_request_target`. This supersedes the first bullet of
Amendment 3.

- A `detect` job (`contents: read`, `pull-requests: read`, no secret) asks the API which pull
  requests belong to the pushed commit (`commits/{sha}/pulls`) and selects the one that is merged
  into `main`, comes from this repository, has a `release/v…` head and whose `merge_commit_sha` is
  the pushed commit. Any other push ends there, green. A malformed `release/…` branch name fails the
  run, as before. The lookup retries briefly when GitHub has not associated the commit yet.
- The `publish` job runs only on a match, in `environment: release`, with the tag and version from
  `detect`. Its concurrency group is `release-publish-<tag>`, so two runs for one release still
  serialise. Everything it did before is unchanged: the tag at the merge commit (skipped when it
  exists), the release notes, the attestations of the eight SBOM assets, the release create-or-edit.
- `GITHUB_REF` is now `refs/heads/main`, which the `main`-only branch rule admits. All three
  token-minting release jobs read the key from the environment, so the **repository secret can be
  deleted** once the environment holds it. A key rotation then updates two copies: the `release`
  environment and the Dependabot secret.
- The owner's one-time step, after this change merges: create the `release` environment with the
  branch rule `main` and the secret, as in Amendment 3, then delete the repository secret
  `RELEASE_APP_PRIVATE_KEY`. Until the environment exists, GitHub creates it unprotected on the
  first job that names it and the jobs fall back to the repository secret, so nothing breaks in
  between; an environment created that way still needs the branch rule added.
- A re-run of a failed `publish` job re-runs on the same commit and the same `detect` output; the
  manual recovery path (the owner creates the tag, then re-runs) is unchanged.

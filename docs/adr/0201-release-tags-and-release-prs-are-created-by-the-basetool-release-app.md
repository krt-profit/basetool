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

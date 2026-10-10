# ADR-0246 — Pull requests merge through a merge queue, not through the strict up-to-date policy

- **Status:** Accepted — the ruleset change (owner) follows the merge of the workflow change
- **Date:** 2026-10-10
- **Deciders:** @greluc
- **Related:** spec [`deployment-delivery.md`](../specs/deployment-delivery.md) (`REQ-OPS-044`,
  `REQ-OPS-027`) ·
  [ADR-0169](0169-the-e2e-concurrency-group-is-keyed-on-the-gates-own-verdict.md) (the E2E gate) ·
  [ADR-0214](0214-code-carries-no-comments-besides-javadoc.md) ·
  `.github/workflows/merge-group-shims.yml` · `.github/workflows/codeql.yml`

## Context

The `main` ruleset (16482244) requires 11 status checks with `strict_required_status_checks_policy`
on, so every merge makes every open pull request out of date. `main` takes about three merges an
hour, each outdated PR must be updated and its whole CI re-run, and the repository runs on the
GitHub Free plan with 20 concurrent jobs, where queue time rather than compute dominates (median 5
to 16 minutes per job; CI 17.9 and CodeQL 21.0 minutes wall time on a PR at the median). The
policy's purpose is real: two PRs that are green alone must not break `main` together (a duplicate
Flyway version, an ADR number, a compile break). A merge queue gives the same guarantee by building
`main` plus the PR before the merge, without every author updating the branch.

Merge queues are available for public repositories owned by an organization (GitHub changelog
2023-07-12 and the accepted answer of community discussion 51483); private repositories need
Enterprise Cloud. This repository is public and its organization is on the Free plan.

## Decision

We will replace the strict policy by a ruleset `merge_queue` rule on `main`: `SQUASH`, `ALLGREEN`,
one pull request per group, up to three groups building, a 90 minute check timeout, and
`strict_required_status_checks_policy` off.

- The checks whose verdict depends on the combined tree run on `merge_group`: `Build, Test & Lint`,
  the four `repo-lint` contexts, `Validate Gradle Wrapper`, `Check migration version numbering` and
  the two required CodeQL analyses. The Flyway and ADR numbering checks read their base from
  `github.event.merge_group.base_sha`.
- **CodeQL runs for real in every merge group, without uploading.** `codeql.yml` triggers on
  `merge_group`, analyses the two required languages (`java-kotlin`, `javascript-typescript`) and
  calls `analyze` with `upload: never` and `upload-database: false`. The job extracts and analyses
  the combined tree and fails on an extraction, build or query error; it never writes a SARIF file
  to Code Scanning.
  - The upload is what races the queue: the queue deletes the temporary
    `gh-readonly-queue/main/pr-<n>-<sha>` branch once its group merges or is discarded, and an
    upload that starts afterwards fails with "ref not found" (`github/codeql-action#1572`; other
    repositories measured it on about half of their groups when CodeQL was not a required check).
    With no upload there is no ref to lose.
  - Nothing is lost by not uploading: GitHub's documented exception says code scanning merge
    protection with rulesets does not apply to merge queue groups, and `analyze` succeeds when it
    finds alerts, so an uploaded group result would gate nothing and only add alerts on a ref that
    is deleted minutes later. Findings are judged on the pull request head (upload, merge
    protection) and on `main` after the merge (upload, baseline refresh).
  - The pinned action (v4.38.2) supports `upload: never`: it still post-processes the SARIF to disk
    and skips the upload, the wait for processing and the database upload.
  - The matrix is chosen by event with `fromJSON`, not by a job-level `if`: a skipped matrix job
    keeps its unexpanded name and a required context would stay pending.
- The checks whose verdict is about the PR's own commits (DCO, gitleaks) are answered in a merge
  group by `merge-group-shims.yml`, whose job names equal the required contexts.
- `e2e.yml` never triggers on `merge_group`.
- One PR per group keeps one push per merge, which `release-publish.yml` (the release PR is found
  by the pushed head SHA) and `release-images.yml` rely on.
- **The repository stays public.** Public visibility is a standing condition of this decision: the
  queue does not exist for a private repository on the Free or Team plan. Making the repository
  private, or moving it out of an organization, reopens this ADR before it happens.
- **Emergency path.** If `gh pr merge --admin` does not merge past the queue, the repository owner
  edits the ruleset (removes the `merge_queue` rule or re-enables the strict policy) and merges
  the PR the old way. An agent never edits the ruleset.

## Consequences

- A PR is never `BEHIND`; no update-branch round; the combined tree is built once per merge.
- The queue consumes runner slots from the same 20 as the PRs; each group costs one `Build, Test &
  Lint` run and one CodeQL run for each of two languages (about 20 minutes for `java-kotlin`). A
  plan upgrade stays the lever for latency and is not part of this decision.
- The merge group's CodeQL result gates on the analysis completing, not on findings; a finding that
  only exists in the combined tree appears on `main` after the merge, as it does for any code that
  reaches `main`.
- The required contexts and the shim names are coupled; a rename changes both or wedges the queue.
  The check names `Analyze (java-kotlin)` and `Analyze (javascript-typescript)` are produced by the
  `codeql.yml` matrix names and must stay.
- `cache-janitor.yml`, which follows every completed CodeQL run, also fires for a merge group; it is
  idempotent and keeps the caches of `main`.
- The owner's direct-merge bypass may not work past the queue; the emergency path above applies.
- The feature exists only while the repository is public.

## Alternatives considered

- **Keep the strict policy.** Rejected: the cost grows with the number of open PRs and merges.
- **Drop the strict policy with no queue.** Rejected: loses the guarantee that the merged tree was
  built.
- **Shim CodeQL like DCO and gitleaks.** Rejected by the owner: the combined tree must be analysed
  before the merge.
- **Run CodeQL in the group with the default upload.** Rejected: the SARIF upload races the queue's
  branch deletion, and the uploaded result gates nothing in a group.
- **Skip the shimmed checks with a job-level `if`.** Rejected: a skipped matrix job keeps its
  unexpanded name and the required context stays pending.
- **Run DCO and gitleaks for real in the group.** Rejected: both read the PR's commit range, which
  a merge group does not carry; the queue admits a PR only after they passed on its head.
- **Group several PRs per merge.** Rejected for now: release publishing and image builds act on the
  pushed head only.
- **Mergify or another queue app.** Rejected: GitHub's native queue is available and needs no
  app permissions.

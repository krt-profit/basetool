# ADR-0315 — The small required gates run as one job, and for real in the merge group

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-OPS-044 ([`deployment-delivery.md`](../specs/deployment-delivery.md)) · amends [ADR-0246](0246-pull-requests-merge-through-a-merge-queue-not-the-strict-up-to-date-policy.md) · [ADR-0171](0171-an-adr-number-is-claimed-against-the-base-branch.md) · ADR-0310 (runner budget)

## Context

Four required checks each had a workflow and a job of their own:

| Check | Workflow | Work on 2026-10-23 |
|---|---|---|
| `Validate Gradle Wrapper` | `wrapper-validation.yml` | 0.1 min |
| `Check migration version numbering` | `flyway-migrations.yml` | 0.2 min |
| `gitleaks` | `gitleaks.yml` | 0.3 min |
| `Verify Signed-off-by on every commit` | `dco.yml` | 0.2 min |

Each waited up to 17 minutes for one of the organisation's 20 concurrent runners (ADR-0310). DCO and
gitleaks did not run in the merge group at all: `merge-group-shims.yml` answered their contexts with
a green job that only echoed a sentence, on the reasoning that the queue adds no author commit. The
wrapper validation also ran on every push to `main`, after the same tree had passed it in the pull
request and in the merge group.

## Decision

**One workflow, `commit-gates.yml`, with one job, `Commit & repo gates`, runs all four checks as
steps.** It triggers on `pull_request`, `merge_group` and `workflow_dispatch`, not on a push to `main`.
Each check is a step with `if: ${{ !cancelled() }}`, so one failure does not hide another.

**DCO and gitleaks run for real in the merge group.** The job resolves the commit range from the
event — `pull_request.base.sha..head.sha` on a pull request, `merge_group.base_sha..head_sha` in a
merge group — and both checks read that range: DCO checks each non-merge commit's sign-off,
gitleaks scans the range with `--no-merges` and the base commit's `.gitleaks.toml`. A
`workflow_dispatch` has no range: DCO is skipped and gitleaks scans the whole history, as before.
`merge-group-shims.yml` is removed.

The four contexts in the `main` ruleset are replaced by `Commit & repo gates`. The ruleset changes
together with the merge of this change (REQ-OPS-044).

## Consequences

- Four runners per pull request and three per merge group become one.
- The merge group's DCO and gitleaks verdicts are computed, not asserted. With one pull request per
  group the range holds the queue's merge commit, which DCO skips as a merge, and the pull request's
  own commits, so the verdict matches the pull request's.
- A wrapper JAR that reaches `main` without a pull request — a direct push by an administrator — is
  no longer validated on `main`.
- The four checks share a 10-minute timeout and a log; each step's name carries its check's prefix
  (`wrapper /`, `dco /`, `gitleaks /`, `flyway /`).
- A new required check must run on `merge_group` itself; there is no shim to add it to.

## Alternatives considered

- **Keep the workflows separate.** Rejected for the runner cost above.
- **One job, but keep the shims for DCO and gitleaks.** The owner chose to check for real on
  2026-10-23.
- **Only merge what does not change a required context's name.** All four are required, so nothing
  would have been merged.

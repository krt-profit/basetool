# ADR-0311 — The exchange-label job runs only on events that can change its verdict

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-XCH-036 ([`external-exchange.md`](../specs/external-exchange.md)) · [ADR-0169](0169-the-e2e-concurrency-group-is-keyed-on-the-gates-own-verdict.md) · ADR-0310 (runner budget)

## Context

`e2e.yml` triggers on `opened`, `synchronize`, `reopened` and `labeled`. Its suite jobs are gated on
the `e2e` label (ADR-0169), but the `Exchange changes carry the e2e label` job ran on every one of those
events. A pull request that receives several labels therefore starts one runner per label: on
2026-10-23 one pull request produced **seven** E2E runs, each holding a runner for 0.1 to 0.3 minutes
of work after up to 19.6 minutes in the queue, while the organisation's 20 concurrent jobs were
already the bottleneck (ADR-0310).

The job's verdict depends on the changed files and on whether the `e2e` label is present. A `labeled`
event for any other label changes neither.

## Decision

**The job runs on `opened`, `synchronize` and `reopened`, and on `labeled` only when the label is
`e2e`.** Its condition is
`github.event_name == 'pull_request' && (github.event.action != 'labeled' || github.event.label.name == 'e2e')`.

## Consequences

- A label other than `e2e` no longer occupies a runner.
- **A later label can hide an earlier failure.** A skipped job reports a `skipped` check of the same
  name. When a pull request that touches the exchange path without the `e2e` label fails the check
  and then receives another label, the pull request shows the check as `skipped`, not as failed. The
  check is not required, so merging was never blocked by it; the owner accepted this on 2026-10-23.
  Adding `e2e` re-runs the job and turns it green; a further push re-runs it either way.

## Alternatives considered

- **Keep the job on every event.** Rejected for the runner cost above.
- **Move the check into a workflow without a `labeled` trigger.** The check exists to react when the
  label is added; without the trigger, adding `e2e` would not clear a failure until the next push.

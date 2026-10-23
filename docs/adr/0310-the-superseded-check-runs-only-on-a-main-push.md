# ADR-0310 — The superseded check runs only on a `main` push

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-OPS-049, REQ-OPS-044 ([`deployment-delivery.md`](../specs/deployment-delivery.md)) · amends [ADR-0276](0276-every-main-push-gets-its-own-ci-run-and-a-superseded-one-skips.md) · ADR-0311 to ADR-0316 (the same runner-budget review)

## Context

The organisation runs on GitHub's free plan, which allows **20 concurrent jobs** for the whole
organisation. On 2026-10-23 the jobs of one pull-request push waited **14 to 19 minutes** for a runner
and then ran for **0.1 to 1.5 minutes** (runs `39925040xxx`). The time a pull request takes is set by
the queue, not by the work.

`ci.yml` runs two jobs in sequence: `Superseded check` and then `Build, Test & Lint`. Each waits for its
own runner. In the same pull-request run the gate waited 14.3 minutes and the build a further 3.4
minutes. ADR-0276 already records that the gate decides "build" on every event that is not a push to
`main` — on a pull request and in a merge group it only costs a queue round trip and a runner.

## Decision

**The `Superseded check` job runs only on `push`.** On a pull request, a merge group and a
`workflow_dispatch` it is skipped. `Build, Test & Lint` keeps its condition
`!cancelled() && needs.supersede.outputs.skip != 'true'`: a skipped predecessor is not cancelled and its
output is empty, so the build runs.

On a push to `main` nothing changes: the gate decides, and a superseded run skips green as ADR-0276
describes.

## Consequences

- A pull request and a merge group wait for one runner instead of two before the build starts, and
  each uses one job fewer.
- A push to `main` still waits twice, and its verdict arrives no sooner.
- The fail-open property is unchanged: the build is skipped only on an explicit `skip=true`, which only
  the gate on a push can produce.

## Alternatives considered

- **Fold the decision into the build job as its first step.** Saves the second wait on `main` too, but
  every later step needs the same `if:`, a step added without it would run on a superseded push
  silently, and a skipped `main` run would show a green `Build, Test & Lint` instead of a skipped one.
  Rejected by the owner on 2026-10-23.

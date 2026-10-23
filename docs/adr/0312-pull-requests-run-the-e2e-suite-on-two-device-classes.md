# ADR-0312 — Pull requests run the E2E suite on two device classes; the nightly run keeps all six

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-UI-009 ([`ui-design-system.md`](../specs/ui-design-system.md)), REQ-XCH-036 · [ADR-0169](0169-the-e2e-concurrency-group-is-keyed-on-the-gates-own-verdict.md), [ADR-0200](0200-e2e-images-are-built-once-per-run-and-shared-as-an-artifact.md) · ADR-0310 (runner budget)

## Context

`e2e.yml` runs every engine on every device class: 3 browsers × 6 devices, **18 jobs**. In the nightly
run of 2026-10-23 (`39920870427`) each cell ran 17.6 to 25.6 minutes after a 10.8-minute image build.
On a pull request with the `e2e` label those 18 jobs hold 18 of the organisation's 20 concurrent
runners for about 25 minutes, and every other pull request, merge group and `main` push queues behind
them (ADR-0310).

`TouchClassLayoutE2eTest` measures each device class only in the cell that passes that class, and
`check_e2e_device_matrix.py` keeps the workflow axis and the test's `DEVICE_CLASSES` in agreement, so
every class must still be run somewhere.

## Decision

**A pull request runs the three engines on `375x812` and `1600x900`: 6 jobs.** The nightly
`schedule` and every `workflow_dispatch` run the full matrix of 18. The `device` axis is one
expression, `fromJSON(github.event_name == 'pull_request' && '[…two…]' || '[…six…]')`.

`check_e2e_device_matrix.py` compares the **full** axis with `DEVICE_CLASSES` as before and also fails
when the pull-request axis is empty or holds a class the full axis does not.

## Consequences

- An `e2e`-labelled pull request occupies 6 runners instead of 18, for the same duration per cell.
- **The four middle classes — `810x1080`, `1024x768`, `1180x820` and `1280x800` — are measured only
  nightly.** A layout regression that shows only there reaches `main` and is reported the next morning,
  not on the pull request. A pull request that needs them runs the workflow by hand
  (`workflow_dispatch`), which always runs the full matrix.
- Every test class still runs in every pull-request cell; the suite itself is not reduced.

## Alternatives considered

- **Keep the full matrix on pull requests.** Rejected for the runner cost above.
- **Three engines on one device class.** Three jobs, but neither the phone nor the widest layout
  would be covered on the pull request.
- **A choice between full and reduced on manual runs.** The owner chose the full matrix for every
  manual run on 2026-10-23.

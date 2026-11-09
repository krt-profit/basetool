# ADR-0360 — Material promises track uncovered demand and explicit provision

- **Status:** Accepted — planned, not implemented
- **Date:** 2026-11-09
- **Deciders:** Repository owner (@greluc)
- **Related:** [REQ-ORDERS-024](../specs/orders-material-claims.md) · REQ-ORDERS-025/-036/-037/-046 · [ADR-0356](0356-job-orders-use-profit-hierarchy-responsibility-and-participation.md) · amendment to ADR-0092 · issues #3207, #3202, #3203

## Context

The implemented MaterialClaim stores an intent amount and does not record fulfilled contribution.
Its aggregate lock guards claim totals against the required amount, while ITEM intentionally
retains its full original material denominator. Inventory quality coverage is recalculated from current stock and
does not prove historical provision by a unit. The accepted model needs distinct outstanding
promises, current stock and true historical credit without a new enrollment or settlement process.

## Decision

REQ-ORDERS-024 is the canonical quantity contract. A promise is still outstanding contribution.
Capacity is current remaining material demand minus matching earmarked stock, floored at zero;
ITEM demand covers not-yet-manufactured items. Preserve current quality allocation with each
physical quantity used at most once.

Fulfill a matching promise once through explicit unit-attributed suitable provision/allocation
by an authorized responsible or own-Staffel writer with inventory rights. Neither stock owner nor
executing Staffel infers attribution. Credit first, then atomically reduce remaining promises only
as necessary. Reductions are cancelled need, not fulfillment. Use exact largest-remainder
apportionment in whole PIECE/0.001 SCU quanta with immutable participation-identity tie breaks.
Derived reductions need no additional Staffel approval; meaningful edits retain scoped versions.

True historical contribution survives consumption, release, relinking and loss. Increased need
does not revive/grow promises. False credit requires connected correction within current capacity,
without reviving ended promises. Migrate historical credit only from verifiable unit attribution.

Replace ADR-0092's whole-order claim lock as a prescribed new-model boundary with an invariant
guard at order-plus-material scope across all quality buckets. Every related demand, stock,
promise, attribution and correction mutation joins it, including initially empty sets. Preserve
Inventory/JobOrder ownership through narrow synchronous APIs and one deterministic common lock
protocol. Exact SQL, tables and classes remain implementation choices, with real-database race
evidence required. No implementation is authorized by this accepted planning record.

## Consequences

Promise and stock views can explain actual progress without double credit. History must retain
attribution independently of changing stock projections. Relevant inventory and demand writes must
maintain capacity atomically, while unrelated sections/materials keep narrow client versions.
Legacy coarse-lock tests are baseline only; the new credit, cross-quality races, corrections,
rounding, lifecycle and migration acceptance remains unchecked.

## Alternatives considered

- Keep the full original ITEM denominator: rejected; promises represent remaining uncovered need.
- Fulfill on manufacture or final delivery: rejected; provision is credited once at explicit allocation.
- Derive historical credit from current stock/owner/executor: rejected; those do not prove contribution.
- Restore promises after genuine loss: rejected; new obligations require a deliberate permitted action.
- Keep a whole-order lock or only lock one tier: rejected as the prescribed target; use the actual shared material invariant.

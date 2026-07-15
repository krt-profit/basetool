# ADR-0099 — Job-order item production booking (Herstellung) consumes linked stock and gates delivery

- **Status:** Accepted
- **Date:** 2026-07-15
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`orders-item-production.md`](../specs/orders-item-production.md) `REQ-ORDERS-025`
  · builds on [ADR-0098](0098-inventory-associations-as-to-many-quantity-splits.md) (Variante C
  order slices) · [`audit.md`](../specs/audit.md) `REQ-AUDIT-001` · issue #1182

## Context

An `ITEM` job order's line (`JobOrderItem`) recorded only a `deliveredAmount` against its ordered
`amount`. There was no record of how many units had actually been **manufactured**, and item
delivery could be booked with no link to the stock the manufacture consumed — so an order's
Variante-C earmarked inventory (per-order quantity slices, [ADR-0098](0098-inventory-associations-as-to-many-quantity-splits.md))
was only ever drawn down at delivery, if at all.

Operators needed a distinct "Herstellung" step: record that `N` whole units of a line were built
and, in the same atomic action, consume the exact linked inventory the line's recipe required. The
line's recipe already exists as a per-line **snapshot** (`JobOrderItemMaterial`, the source of the
aggregated-materials view); the order's earmark on an inventory entry already exists as a Variante-C
job-order slice. The forces at play:

- **Correctness under concurrency.** Consuming stock touches the same `@Version`-locked
  `InventoryItem` rows the Lager and handover flows touch; a naive multi-step read-modify-write here
  is exactly the optimistic-locking / detach trap the codebase has been bitten by (the
  `…WithinTransaction` and no-bulk-update-in-loop rules).
- **A well-formed quantity violation is not a bad request.** Producing more than the line needs, or
  a consumption plan that does not match the required demand, is a *semantic* rejection of a
  syntactically valid, version-current request — it must not read as a 400 (malformed) or a 409
  (stale), which the frontend routes differently.
- **Delivery must not run ahead of manufacture.** Once manufacture is tracked, handing over a unit
  that was never built is incoherent.

## Decision

We will add a per-line **`manufacturedAmount`** counter and a dedicated production-booking service
that atomically records manufacture and draws down the order's linked stock.

- **`JobOrderItem.manufacturedAmount`** (INTEGER, `NOT NULL DEFAULT 0`, V219) under the invariant
  `0 ≤ deliveredAmount ≤ manufacturedAmount ≤ amount`, DB-enforced by three `CHECK` constraints and
  backfilled `manufactured_amount = delivered_amount` for legacy rows (already-delivered units count
  as manufactured, so every existing row is immediately valid).
- **Delivery is gated by manufacture.** `JobOrderItemHandoverService` caps a line's deliverable
  quantity at `manufacturedAmount − deliveredAmount` (was `amount − deliveredAmount`); over that is
  a 400.
- **`JobOrderItemProductionService.bookProduction`** behind
  `POST /api/v1/orders/{id}/items/{itemId}/production` (`@ResponseStatus CREATED`), gated
  `(LOGISTICIAN or OFFICER or ADMIN) and @ownerScopeService.canEditJobOrder(#id)` — the item-handover
  authorisation. In one `@Transactional` method it: rejects a non-`ITEM` order (400); enforces the
  line's optimistic lock via `OptimisticLock.checkRequired` (stale/absent → 409, code
  `OPTIMISTIC_LOCK`); rejects `amount > amount − manufacturedAmount` (422); derives the required
  per-material demand from the line's snapshot (`requiredQuantity × amount / lineAmount`, rounded per
  quantity type) and requires the `consumption` plan to **exactly cover** every required material and
  name no other (mismatch → 422); then, per consumption entry under a `findByIdForUpdate` pessimistic
  lock, checks the entry `@Version` (409), that it is earmarked to this order (else 400), holds the
  claimed material (400), is positive and whole for PIECE (400), and does not exceed the order's own
  slice or the entry's stock (422). A depleted entry is **deleted**; otherwise its amount and the
  order's earmark slice are decremented and the entry's mission earmarks are auto-clamped by the same
  amount (`AllocationReductions`, rest-first then proportional) to hold the Variante-C R5 invariant
  without a 422. It advances `manufacturedAmount` by dirty checking (single `@Version` bump) and
  flushes so the returned DTO carries the fresh version.
- The 422 is a dedicated **`ProductionAllocationException`** (`AppExceptionKind.PRODUCTION_ALLOCATION`,
  localized detail) — distinct from 400 and 409 so `krt-fetch.js` shows an inline toast (fixable
  without losing the edit) rather than the 409 reload-confirm.
- **Audit.** One `JOB_ORDER_PRODUCTION_BOOKED` (`JOB_ORDER`) plus one
  `INVENTORY_CONSUMED_BY_PRODUCTION` (`INVENTORY`) per consumed entry, from snapshots captured before
  the writes, with no user free text — both audited areas keep their logs in sync (`REQ-AUDIT-001`).
- **Frontend.** `JobOrderWriteController.bookProductionAjax` relays the JSON payload, re-fetches the
  order, and returns it; a backend error is propagated **verbatim** (preserving the RFC 7807 `code`)
  so the 409/422 distinction survives to the client. Success re-renders and broadcasts the
  `production` + `kpi` live-sync sections to peers.

## Consequences

- Manufacture and delivery are now separate, ordered facts: stock is drawn down as production
  happens, and a delivery can never run ahead of what was built.
- The flow reuses the established concurrency patterns rather than inventing one: dirty-checked
  counter bump (no explicit `save`, mirroring the handover services), pessimistic entry lock, no
  `@Modifying(clearAutomatically)` bulk update inside the consume loop (so the persistence context is
  never detached mid-operation), and audit from pre-write snapshots (never re-reading a deleted row).
- Because production only draws from the order's **own** slice on a split entry, a contribution
  shared across several orders is safe: consuming for order A never touches order B's earmark or the
  free rest. The mission dimension is auto-clamped to preserve R5, so a book-out never fails the
  cross-dimension invariant with a surprise 422.
- The exact-coverage rule keeps production honest against the recipe snapshot; the frontend
  pre-validates coverage, so the 422 is defence-in-depth against a stale or hand-crafted payload.
- New surface to maintain in lock-step: a Flyway migration (V219), two audit event types (viewer
  filter + DE/EN labels), and the `production` + `kpi` live-sync keys at all three mirror points.
- Un-booking / correcting a production run is deliberately not modelled yet; `manufacturedAmount` is
  append-only for now.

## Alternatives considered

- **Reuse `deliveredAmount` and treat delivery as manufacture.** Rejected — it conflates two real
  states (built vs. handed over) and would let a delivery imply a manufacture that consumed no stock,
  which is exactly what operators needed to stop.
- **Book production without consuming stock (a bare counter).** Rejected — the whole point is to draw
  the order's earmarked inventory down as production happens; a counter with no stock effect would
  leave the Lager and the order out of sync until delivery.
- **Auto-pick which inventory entries to consume (proportional across earmarks).** Rejected in favour
  of an explicit, caller-supplied consumption plan validated for exact coverage — the operator
  decides which physical entries a run drew from (provenance), and an automatic split cannot express
  "these SCU came from that entry".
- **Map the quantity violation to 400 or 409.** Rejected — a version-current, well-formed request
  with an out-of-range amount is neither malformed nor stale; a distinct 422
  (`PRODUCTION_ALLOCATION`) lets the client keep the edit and correct the allocation inline.


# ADR-0063 — Split `InventoryItemService` into read + checkout services behind a delegating facade

- **Status:** Proposed
- **Date:** 2026-07-02
- **Deciders:** Repository owner (@greluc)
- **Related:** issue #921 (L2, epic #905) · ADR-0061 (the `MissionService` split precedent) · ADR-0003 (group-on-read stacks) · spec [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) (`OwnerScopeService` scope) · `REQ-FE-003` (`saveAndFlush` `@Version` parity) · `REQ-AUDIT-001` · the CLAUDE.md optimistic-lock / bulk-update-after-loop rules

## Context

`InventoryItemService` had grown to ~1450 LOC behind one `@Transactional(readOnly = true)` bean with
14 dependencies and three distinct responsibilities: the read/aggregation surface (per-material
aggregate, the `/grouped` Material→Stack roll-ups, the lazy per-stack drilldowns, the flat and
per-material listings, the craftability stock slices, the job-order material collection), the
create/update/note cycle, and the checkout writes (book-out consume/transfer/sell, personal↔shared
rebooking, bulk checkout, delivered toggle, admin global wipe). Issue #921 prescribed an L2 split.
**Part 1** (commit `a21ee48`, PR #950 first slice) decomposed the ~180-LOC `bookOutInventoryItem`
by checkout type into `bookOutTransfer` / `createSaleFinanceEntry` private methods. **This ADR
covers the class split.**

The checkout code is concurrency-hot. Inventory is **append-only** (ADR-0003): book-out `TRANSFER`
and `rebookPersonal` always insert a new target row and decrement (or delete) the source, never
folding into an existing stack — which removes the read-add-write race a merge path would carry.
`bulkCheckout` follows the bulk-update-after-loop discipline (clear associations on managed rows
inside the loop via dirty-checking, flush once, delete the batch). Partial book-outs / rebookings
`saveAndFlush` the reduced source row so its `@Version` stays current within the transaction and a
follow-up in-place edit cannot 409 (`REQ-FE-003`). Any regression here is a silent 409, a lost
audit event, or a cross-tenant leak, so the split had to be **behaviour-preserving to the byte**.

## Decision

**Extract two focused `@Service`s and keep `InventoryItemService` as a delegating facade.**

- **`InventoryAggregationService`** — every read/aggregation projection. Class-level
  `@Transactional(readOnly = true)`; holds no write repositories; multi-org-unit scoping stays on
  `OwnerScopeService.currentScopePredicate()` exactly as before.
- **`InventoryCheckoutService`** — every checkout write (`bookOutInventoryItem` + its
  `bookOutTransfer` / `createSaleFinanceEntry` / `recordBookOutTail` helpers, `rebookPersonal`,
  `bulkCheckout`, `updateDelivered`, `deleteAllGlobalInventory`). **No** class-level `readOnly`
  default — every public method is a write and carries its own `@Transactional`, so a mutating
  method is never trapped in a read-only transaction.

Each moved method body is **verbatim**. `InventoryItemService` keeps **every** public method and its
signature; each moved method's body becomes a one-line delegation. The facade retains the
`createInventoryItem` / `updateInventoryItem` / `updateNote` cycle (the residual CRUD that belongs
to neither cluster) and the `assertMaterialRequiredByJobOrder` guard. The three tiny pure helpers
that both clusters need (`inventoryLabel`, `jobOrderRef`, `roundAmount`) are **duplicated** into the
services that use them rather than promoted to a shared type — matching the JobOrder-split precedent
(`orderLabel`) and keeping each service self-contained; the `QUANTITY_EPSILON` constant moves to the
checkout service, the `STACK_ORDER` comparator to the aggregation service.

**Delegation, not controller-repoint.** The three callers — `InventoryItemController`,
`MaterialCollectionController`, `BlueprintCraftabilityService` — are left untouched; their entry
points and the per-operation `@Transactional` boundaries are byte-identical. The facade keeps its
class-level `@Transactional(readOnly = true)` and keeps `@Transactional` on every delegating write
method, so the read-write transaction opens at the facade and the sub-service's `@Transactional`
joins it (propagation `REQUIRED`): a single transaction, unchanged `@Version` writeback and audit
timing. There is **no cross-cluster call** among the moved methods (verified: internal references
are within-cluster), so the `…WithinTransaction` double-`@Version`-bump hazard does not arise. The
former `bookOutInventoryItem` did **not** touch `JobOrderMaterial` rows or call
`completeJobOrderWithinTransaction` — the class Javadoc that claimed so was stale and is corrected in
this split, not carried forward.

**Coupling.** The bean-injection direction is one-way (`InventoryItemService` → the two
sub-services); the sub-services do not inject the facade, so there is no Spring cycle. The facade
stays in the ArchUnit `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` whitelist and keeps its
`OwnerScopeService` dependency (used by `createInventoryItem`) so the multi-tenant org-unit stamp
cannot be dropped. Because the split moves the `OwnerScopeService.currentScopePredicate()` filtering
wholesale out of the facade into the two extracted services — the facade itself no longer calls it —
**both `InventoryAggregationService` and `InventoryCheckoutService` are added to that same
whitelist**, so the defensive guard follows the scoped data and a future maintainer who drops
`OwnerScopeService` from either service (silently un-scoping the squadron-wide reads or the global
wipe) fails the build rather than leaking across org units.

## Consequences

- **No behaviour change.** `openapi.json` is unchanged (no controller/DTO touched); audit events,
  optimistic-lock/version semantics, the append-only invariant, the bulk-update-after-loop ordering
  and the `saveAndFlush` `@Version` parity are preserved verbatim. The full backend suite — including
  the book-out / rebook / bulk-checkout / version-flush and ArchUnit tests — passes unchanged in
  substance.
- **Tests follow the code.** The single-cluster unit tests retarget their `@InjectMocks` onto the new
  service (aggregate → `InventoryAggregationService`; book-out / rebook / bulk-checkout →
  `InventoryCheckoutService`). The **mixed** tests (`InventoryItemServiceTest`, the version-flush
  test) keep `@InjectMocks InventoryItemService` and wire a real, mock-fed sub-service into it via
  `ReflectionTestUtils.setField` in `@BeforeEach` — because Mockito does **not** inject one
  `@InjectMocks` target into another. This runs the real delegated logic against the same mock pool,
  so their existing assertions hold without moving test methods.
- **Each responsibility is now independently testable** with a small dependency set: the aggregation
  service sheds every write repository, the checkout service sheds the mappers/repositories only the
  read side used, and `InventoryItemService` reads as a facade over the create/update/note core plus
  two delegations.

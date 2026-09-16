# ADR-0062 — Split `JobOrderService` into a facade + four focused collaborators

- **Status:** Proposed
- **Date:** 2026-07-02
- **Deciders:** Repository owner (@greluc)
- **Related:** issue #921 (L2, epic #905) · ADR-0061 (the `MissionService` split, same pattern) · ADR-0047 (`support` dependency-leaf) · the CLAUDE.md Concurrency rules (`…WithinTransaction`, bulk-update-after-loop, pessimistic reorder locks)

## Context

`JobOrderService` had grown to 1710 LOC with 16 dependencies, mixing read-projection, write-lifecycle
and resolution concerns. Issue #921 (part 1) called for extracting `JobOrderAssigneeService`,
`JobOrderPriorityService`, `JobOrderStockProjectionService` and `JobOrderOrgUnitResolver`.

This is a **concurrency-hot** class — it has shipped 409/duplicate-priority bugs before, and the
CLAUDE.md Concurrency section exists because of them. Three invariants had to survive the split
byte-for-byte:

1. **`completeJobOrderWithinTransaction`** (`@Transactional(propagation = MANDATORY)`) flushes its
   pending `@Version` bump **before** `normalizePriorities()` takes its `PESSIMISTIC_WRITE` lock, so
   the lock query never reads a stale row.
2. **`unlinkMaterial`** runs the `@Modifying(clearAutomatically)` `unlinkJobOrderMaterial` bulk
   update in the sanctioned order (snapshot label → unlink → remove + save).
3. The whole-sequence `PESSIMISTIC_WRITE` reorder lock (`lockAllJobOrders`) that serialises
   concurrent priority edits.

## Decision

**Keep `JobOrderService` as the lifecycle facade and extract four collaborators, each moved
verbatim.** Two shapes emerged, both distinct from the pure delegating facade of ADR-0061:

- **Injected helpers the facade *calls*** (not public methods it delegates):
  - **`JobOrderOrgUnitResolver`** — `resolveResponsibleOrgUnit` / `resolveIntakeSpecialCommand` /
    `resolveRequestingOrgUnit` + the intake-SK setting key. Read-only; `createJobOrder` /
    `createItemJobOrder` / `updateJobOrder…` call it. The now-unused `SystemSettingService` dep left
    `JobOrderService`.
  - **`JobOrderStockProjectionService`** — `mapToDtoWithStock` (single-order) and the page-batched
    list path, now the public `mapPageWithStock`, plus the stock/claim resolvers, `loadStockIndex`,
    `sumStockAtFloor`, `enrichAggregatedWithClaims`, `isSpecialCommandResponsible`, `bucketKey` and
    `GOOD_QUALITY_FLOOR`. Read-only; the 16 write/read return paths call `mapToDtoWithStock`,
    `getAllJobOrders` calls `mapPageWithStock`. The now-unused `JobOrderItemHandoverMapper` dep left.
- **Facade-delegated public methods** (`JobOrderService` keeps the method, one-line delegation):
  - **`JobOrderAssigneeService`** — `addAssignee` / `removeAssignee` / `updateAssigneeNote` /
    `deleteAssigneeNote` (+ the shared `setAssigneeNote`). The now-unused `UserRepository` dep left.
  - **`JobOrderPriorityService`** — `updateJobOrderPriority` (delegated) and `normalizePriorities`
    (now called from the five lifecycle edges that leave a priority gap).

**Concurrency is preserved, not re-designed.** The three hot invariants above stay in
`JobOrderService` (`completeJobOrderWithinTransaction`, `unlinkMaterial`) or move verbatim
(`normalizePriorities`, `updateJobOrderPriority`). Critically, **`normalizePriorities` carries no
`@Transactional` of its own** — identical to the private helper it replaced — so a bean-to-bean call
from a `@Transactional` facade method runs in the caller's transaction (no new commit boundary), and
`completeJobOrderWithinTransaction`'s `flush()` still precedes the call. `JobOrderPriorityService`
has no class-level `@Transactional(readOnly)`, so its `updateJobOrderPriority` keeps its own
read-write `@Transactional`. The tiny audit-label helper `orderLabel` is duplicated into the two
sub-services that need it rather than shared.

## Consequences

- **`JobOrderService`: 1710 → ~1140 LOC.** No API change (`openapi.json` unchanged); audit events,
  lock/flush/version semantics and the handover/booking/checkout flows are preserved. The full
  backend suite — including the handover-completion and priority/status concurrency tests and
  ArchUnit — passes unchanged in substance.
- **The bean graph is one-way** (`JobOrderService` → the four collaborators; the assignee/priority
  services also depend on the projection service). No Spring cycle.
- **Tests follow the code**: single-cluster unit tests retarget onto the new service; the mixed
  `JobOrderServiceTest` / `…AssigneeAndListTest` / `…PriorityAndStatusTest` wire real, mock-fed
  collaborators into the CUT via `ReflectionTestUtils` — chaining the real projection into the
  assignee/priority services first — because Mockito does not inject one `@InjectMocks` target into
  another.
- **`InventoryItemService` is out of scope** for this PR: issue #921's second half
  (`InventoryCheckoutService` + `InventoryAggregationService` + the `bookOutInventoryItem`
  decomposition) ships as a follow-up PR.

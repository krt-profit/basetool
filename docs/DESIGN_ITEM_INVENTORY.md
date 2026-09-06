> **Doc type:** Design spec (partially shipped) — the agreed blueprint for the item-stock
> feature. The backend/API scope (PR 2) has shipped: REQ-INV-029…032 live in
> [`docs/specs/inventory-items.md`](specs/inventory-items.md) and ADR-0101 is accepted —
> those are the living truth for the shipped parts. The **Börse phase (§8) has shipped** as
> REQ-MARKET-014 / ADR-0108 (stock-backed item offers, see
> [`docs/specs/materialboerse.md`](specs/materialboerse.md)) — that is now the living truth for
> it, and the §8 text below is a historical plan. The frontend item views/flows (PR 3) still
> implement against this document; it freezes as a historical plan once they ship.
> **Owner area:** INV / ORDERS / MARKET / UI / FE · **Related ADRs:** ADR-0053, ADR-0087,
> ADR-0098, ADR-0099 (existing); ADR-0101 (accepted with the backend PR).

# Design — Item stock tracking in the Lager (warehouse)

## 1. Context & goal

The Lager today tracks **materials only**: an `InventoryItem` row is `user · material ·
location · quality · amount · personal · owningOrgUnit` plus Variante-C allocation slices
to job orders and missions (REQ-INV-027, ADR-0098). Game **items** (ship components,
weapons — `GameItem`) exist only as catalog entries and as ITEM-order lines
(`JobOrderItem`); manufacturing an item (REQ-ORDERS-025, ADR-0099) consumes linked
material stock and increments `JobOrderItem.manufacturedAmount`, but the produced item
**vanishes** — no stock row is created anywhere.

This design makes items (initially: items that are the output of at least one active
blueprint) trackable in the Lager:

1. Items and materials are **separate views** on the existing Lager pages.
2. Items support the **same functions** as materials (Einbuchen, Ausbuchen, Umbuchen,
   allocations, notes, merge, filters, live sync) — with two deviations: **no quality**,
   and allocations go **only to ITEM job orders** (no missions).
3. **Production booking (Herstellung) creates item stock**; the booking user chooses
   **where** (location) and **for whom** (owner user + owning org unit) the produced
   items are booked in.
4. Catalog pickers (materials *and* items) move from plain `<select>` dropdowns to the
   established **searchable combobox** (`krt-searchable-select`, ADR-0053/REQ-FE-011).
5. The data model is **forward-compatible with stock-backed Materialbörse item offers**
   (§8): in a later phase, item offers on the trade board can be released from item stock
   rows, exactly like material offers are released from material stock rows today.

## 2. Requirements

### Functional

| #  |                                                                                                                                     Requirement                                                                                                                                      |
|----|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| F1 | Item stock rows carry `gameItem · user · location · amount · personal · owningOrgUnit` — **no quality** (`quality IS NULL`). Amounts are positive whole units.                                                                                                                       |
| F2 | Only items with ≥ 1 active blueprint are bookable (catalog predicate, §5.4).                                                                                                                                                                                                         |
| F3 | `/inventory`, `/inventory/my` and `/inventory/all` offer a Material ↔ Items view switch; the drilldowns are per-catalog pages (`/inventory/material/{id}`, `/inventory/game-item/{id}`) and carry no switch. The item view mirrors the material tree (group → stack → lazy entries). |
| F4 | Einbuchen, Ausbuchen (DISCARD/TRANSFER/SELL), Umbuchen (location/user/org-unit/personal), notes, write-time merge, bulk checkout and org-unit reconcile all work for item rows with material-equal semantics.                                                                        |
| F5 | Item rows can be allocated (Variante C) **only** to `JobOrderType.ITEM` orders whose lines request that `GameItem`; the mission dimension is rejected.                                                                                                                               |
| F6 | `POST …/items/{itemId}/production` additionally books the produced amount into the Lager; the request names location, owner user, owning org unit, personal flag; the produced stock is auto-earmarked to the producing order (default, deselectable).                               |
| F7 | Material and item catalog pickers are searchable comboboxes; item pickers search server-side (`remoteSource`), material pickers filter the preloaded list locally.                                                                                                                   |
| F8 | All item mutations live-update in place (krtFetch) and propagate to peers via the existing `inventory` room.                                                                                                                                                                         |
| F9 | Nothing in the schema or the offer model blocks the planned Materialbörse extension "item offers released from item stock" (§8).                                                                                                                                                     |

### Non-functional & constraints

- **Scale:** blueprint-bearing items ≈ low thousands (SC-Wiki sync); stock rows per org in
  the hundreds. Same query patterns as materials; one new partial index (§4.2). No caching
  changes.
- **Concurrency:** identical optimistic-locking regime (`@Version` echo, `OptimisticLock`
  helpers); production book-in happens inside the already-locked production transaction
  and only creates rows or merges under the existing FOR-UPDATE merge lock (REQ-INV-026)
  — no new lock scopes (§5.6, verified transaction-safe).
- **Tenancy:** unchanged — strict-staffel scope with owner escape (REQ-ORG-003/011),
  create-time stamping via `OwnerScopeService` (REQ-ORG-004/016).
- **Compatibility:** the `/api/v1/inventory` surface stays backward-compatible: existing
  fields keep their meaning; item support arrives via nullable additions + a defaulted
  `catalog` query parameter.
- **Design system:** all UI/UX work follows the **latest version** of the KRT / DAS
  KARTELL design system (`docs/specs/ui-design-system.md` + the
  `.claude/skills/das-kartell-design` submodule as visual source of truth) — see the
  binding note at the top of §6.

## 3. Key decision — one aggregate, catalog-discriminated (proposed ADR-0101)

**Decision: extend `InventoryItem` with a nullable `gameItem` reference (XOR with
`material`) instead of building a parallel item-inventory aggregate.**

|                    |                                                  A) Extend `InventoryItem` (chosen)                                                  |                                  B) Parallel `ItemInventory` aggregate                                  |
|--------------------|--------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Reuse              | Allocations tables, merge, book-out/rebook, Herkunft picker, audit, tenancy, live sync, bulk checkout, reconciler, wipe — all reused | All of it duplicated (entity + 2 allocation tables + 3 services + mapper + controller + templates + JS) |
| Drift risk         | Low — one code path, branched on row kind                                                                                            | High — every future Lager change lands twice                                                            |
| Schema             | 2 relaxed + 1 new column + CHECKs on one hot table                                                                                   | Clean separation, but 3 new tables                                                                      |
| Query risk         | Every material-assuming query must be audited (§4.4 — non-trivial, but a bounded one-time list)                                      | None                                                                                                    |
| Materialbörse (§8) | Offer `inventoryItem` FK works for both kinds — stock-backed item offers become a natural extension                                  | Offers would need a second FK + kind plumbing                                                           |
| UI                 | Same templates, conditional columns                                                                                                  | Full second page set                                                                                    |

The deciding factor is the user requirement "same functions as material": with (A) that is
true *by construction*; with (B) it is an ongoing promise. The row kind is **derived**
(`material_id` vs `game_item_id` set), not a stored discriminator — the XOR CHECK makes a
separate column redundant, and partial indexes key on `game_item_id IS NOT NULL`.

The cost of (A) is the query audit in §4.4. Two verified subtleties make it tractable:
Hibernate resolves **id-only** dereferences (`i.material.id`) from the FK column without a
join (NULL rows are *not* dropped), while **attribute** navigation (`i.material.name`)
produces an implicit inner join (NULL rows *are* dropped) — so each query needs its own
verdict, not a blanket rule. And the entity mapping change itself is load-bearing:
`InventoryItem.material` is `@ManyToOne(optional = false)` today, which Hibernate uses for
join optimisation — flipping it to `optional = true` is part of the schema change, not a
cosmetic edit.

## 4. Data model & migration (V220)

### 4.1 Entity changes

`backend/.../model/InventoryItem.java`:

- `material` → `@ManyToOne(optional = true)` / `@JoinColumn(nullable = true)`;
  `quality` → nullable (`@Min(0) @Max(1000)` kept for non-null values).
- New `@ManyToOne(fetch = LAZY) GameItem gameItem` (FK `game_item_id`, nullable).
- Invariants (bean-validation + service guards + DB CHECKs):
  - exactly one of `material` / `gameItem` is set;
  - material row ⇒ `quality` NOT NULL; item row ⇒ `quality` IS NULL;
  - item row ⇒ `amount` is a positive whole number (validator + service, §5.1 — app-side,
    like PIECE materials; no DB CHECK, matching the PIECE posture);
  - item row ⇒ `missionAllocations` empty (service-enforced; no DB cross-table check).
- Stack identity for item rows: `user · gameItem · location · personal · owningOrgUnit`
  (quality dimension dropped). Merge identity likewise; items follow the **PIECE
  auto-merge** rule of REQ-INV-026 (whole units always merge on write).

### 4.2 Migration `V220__add_inventory_game_item_rows.sql`

```sql
ALTER TABLE inventory_item ALTER COLUMN material_id DROP NOT NULL;
ALTER TABLE inventory_item ALTER COLUMN quality DROP NOT NULL;
ALTER TABLE inventory_item ADD COLUMN game_item_id uuid REFERENCES game_item (id);
ALTER TABLE inventory_item ADD CONSTRAINT chk_inventory_item_catalog_xor
  CHECK ((material_id IS NULL) <> (game_item_id IS NULL));
ALTER TABLE inventory_item ADD CONSTRAINT chk_inventory_item_quality_by_kind
  CHECK ((material_id IS NOT NULL AND quality IS NOT NULL)
      OR (game_item_id IS NOT NULL AND quality IS NULL));
CREATE INDEX idx_inventory_item_item_stack_key
  ON inventory_item (game_item_id, user_id, location_id, personal, owning_org_unit_id)
  WHERE game_item_id IS NOT NULL;
```

No backfill — all existing rows are material rows and already satisfy both CHECKs.

### 4.3 Allocations

`InventoryJobOrderAllocation` / `InventoryMissionAllocation` are reused unchanged
(including the per-(entry, order) `delivered` marker). The restriction "items only to
ITEM orders that request the gameItem" is a service invariant (§5.5). Mission allocations
on item rows are rejected with 400 (`problem+json`, code `BAD_REQUEST` — 422 stays
reserved for over-allocation, matching the existing convention).

### 4.4 Existing-query remediation (verified, binding checklist)

With `material_id` nullable, the following repository/consumer seams have been verified
and classified. This list is part of the design contract — each entry gets a regression
test with a NULL-material (item) row.

**Breaks — must be fixed in the same change:**

|                                                        Seam                                                         |                                                                                                                                                                                                        Failure                                                                                                                                                                                                        |                                                                                                                                                                                                                      Fix                                                                                                                                                                                                                      |
|---------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `InventoryItemRepository.findGlobalStacks` / `findUserStacks` / `getAggregatedInventory`                            | entity-valued SELECT/GROUP BY of `i.material`; null group NPEs in `InventoryAggregationService` (`buildGroupedFromStacks`, aggregate mapping)                                                                                                                                                                                                                                                                         | explicit `LEFT JOIN i.material m` (mirroring the existing `LEFT JOIN i.owningOrgUnit`); material variants gain `WHERE i.material IS NOT NULL`; new sibling item-stack projections group by the item stack key (§5.2)                                                                                                                                                                                                                          |
| `findReleasableForUser` (Materialbörse picker)                                                                      | `LOWER(i.material.name)` search + `ORDER BY i.material.name` — implicit inner join drops NULL-material rows                                                                                                                                                                                                                                                                                                           | rewrite with explicit `LEFT JOIN` + `COALESCE(m.name, gi.name)`; in-repo template: `MaterialExchangeOfferRepository.findBoard` (its Javadoc documents exactly this trap). Keeps an explicit `i.material IS NOT NULL` guard until §8 ships                                                                                                                                                                                                     |
| `findByJobOrderIdOrdered` (order-detail Materialsammlung + orphaned-link warning)                                   | its consumer `InventoryAggregationService.getMaterialCollection` unconditionally dereferences `item.getMaterial().getName()` — and §5.6's auto-earmark creates item rows linked to the order on the feature's **default** flow, so a naive COALESCE rewrite would 500 the Materialsammlung                                                                                                                            | **stays material-only by design**: add an explicit `i.material IS NOT NULL` (the Materialsammlung is a material surface; a dedicated item-earmark projection ships with the order-detail item-stock panel, §11.3). The orphaned-link query (`getOrphanedLinkedInventory`, REQ-ORDERS-019) consumes the same seam and must gain its gameItem-aware branch **in the same change** as §5.5, or every legitimate item earmark is flagged orphaned |
| `findMaterialStockRowsByJobOrderIds` → `JobOrderStockProjectionService.loadStockIndex`                              | projection emits `materialId = null` for item earmarks; `Collectors.groupingBy` NPEs → **500 on the paged order list**                                                                                                                                                                                                                                                                                                | filter `i.material IS NOT NULL` in-query (material collection stays material-only); item earmarks get their own projection when the order UI surfaces them                                                                                                                                                                                                                                                                                    |
| Flat list family: `findGlobalByFilters` / `findUserByFilters` / `findByUser` behind `GET /all`, `GET /my-inventory` | two defects: (a) item rows would silently appear inside today's material-only flat lists with `material = null` (contract break for OpenAPI consumers); (b) the controllers' **default Pageable sort `material.name,asc`** injects `ORDER BY i.material.name` at runtime — the same attribute-dereference inner-join trap, only smuggled in via the sort parameter, so row visibility would depend on the chosen sort | the flat family gets the same `catalog` discriminator as §5.2 (default `MATERIAL` ⇒ `i.material IS NOT NULL`); the ITEM variant defaults to a `gameItem.name` sort whitelist. Binding sub-checklist: audit **every** Pageable sort whitelist containing `material.*` for this runtime dereference and add a regression test with an item row present under the default sort                                                                   |
| `findGlobalStackEntries` / `findUserStackEntries`                                                                   | `i.material.id = :materialId` equality never matches NULL — item stacks could not be drilled into (these queries **already** carry the quality NULL-branch; only the material/gameItem key is missing)                                                                                                                                                                                                                | add the material NULL-branch mirroring the existing pattern (`(:materialId IS NULL AND i.material IS NULL) OR i.material.id = :materialId`) plus the `gameItem` key                                                                                                                                                                                                                                                                           |
| `findMergeGroupForUpdate`                                                                                           | `i.material.id = :materialId` **and** `i.quality = :quality` never match NULL (unlike the entry queries, this one has no quality NULL-branch) — **merge silently degenerates to a permanent no-op** for item rows                                                                                                                                                                                                     | add NULL-branches for material *and* quality plus the `gameItem` key. Note: this query's Javadoc is stale (pre-Variante-C) — rewrite it while touching the query                                                                                                                                                                                                                                                                              |
| New-row **copy sites**: transfer (Umbuchen) and personal-rebook in `InventoryCheckoutService`                       | both build the moved/split row by copying `material`/`quality` field-by-field; without copying `gameItem` the new row violates `chk_inventory_item_catalog_xor` → `DataIntegrityViolationException` → 500 on every item transfer/rebook                                                                                                                                                                               | every new-row copy site (transfer, personal-rebook, and any future split) copies `gameItem` alongside `material`/`quality`; §9 pins regression tests that exercise the XOR CHECK                                                                                                                                                                                                                                                              |

**Needs a null-branch in the consumer (queries are safe at SQL level):**

- Write services dereferencing `material` off loaded rows for PIECE/SCU precision checks:
  `InventoryItemService` (allocation validation), `InventoryCheckoutService` (book-out /
  rebook), `JobOrderHandoverService`, `JobOrderItemProductionService` (consumption guard —
  item rows are never valid consumption sources; the existing "entry must hold the claimed
  material" check already rejects them once null-safe), `InventoryItemMapper` (nullable
  `material`/`quality`, new `gameItem`).
- `InventoryAuditLabels.label(item)` — the audit subject-label snapshot renders
  `material.getName()` with an em-dash fallback; item rows must render the gameItem name
  instead (§7.2), or every reused audit event logs `— @ <location>`.
- `InventoryItemRepository.sumOwnedStockByMaterialAndQuality` (blueprint availability):
  consumer already filters null material ids; add `i.material IS NOT NULL` in-query for
  explicitness.

**Verified safe, no change:** `MaterialExchangeOfferRepository.clampOfferedAmountToStock`
(keys on the offer's own `inventoryItem.id` FK), the filter *predicates* of
`findGlobalByFilters`/`findUserByFilters` (id-only dereference = FK column, no join — the
flat-family fix above targets the default *sort* and the contract, not the predicates),
`findByMaterialAndPersonalFalse(+Scoped)`, `sumAmountByMaterialAndJobOrderAndMinQuality`,
`deleteJobOrderAllocationsByJobOrder*`, `deleteAllNonPersonal`, `updateOwner`,
`InventoryOrgUnitReconciler` (restamps `owningOrgUnit` only, never dereferences
material), and all `@EntityGraph` id lookups.

## 5. Backend API

### 5.1 Writes — existing endpoints, widened DTOs

|                             Endpoint                             |                                                                                                                                                       Change                                                                                                                                                        |
|------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST /api/v1/inventory`                                         | `InventoryItemCreateDto` + `UUID gameItemId`; `materialId`/`quality` become optional. XOR + quality rules validated class-level.                                                                                                                                                                                    |
| `POST /{id}/book-out`                                            | DTO unchanged. Service: item rows reject `missionReductions`; whole-number amount check on the loaded row's kind; SELL creates no mission finance entries (no mission slices exist — same code path as a material row without slices). TRANSFER moves job-order earmarks onto the new row exactly as for materials. |
| `POST /{id}/personal-rebook`                                     | Unchanged (append-only split + merge; PIECE-style auto-merge for items).                                                                                                                                                                                                                                            |
| `POST/PATCH/DELETE /{id}/allocation`                             | Service: item rows accept `field=JOB_ORDER` only; target must pass the gameItem gate (§5.5).                                                                                                                                                                                                                        |
| `POST /bulk-checkout`, `PUT /{id}/note`, `PATCH /{id}/delivered` | Work per entry id; no shape change. `delivered` applies to item earmarks the same way (per-(entry, order) marker).                                                                                                                                                                                                  |

**Validation — verified hole that must be closed, not just "reused":**
`ValidQuantityAmountValidator` today early-returns *valid* when `materialId == null`, and
`InventoryItemCreateDto.amount` carries no `@Min` of its own — once `materialId` becomes
optional, a gameItem-only payload would silently skip **all** amount validation.
Therefore: `QuantityAware` gains a `default UUID gameItemId() { return null; }` accessor
(overridden by the create DTO), and the validator enforces *positive + whole* whenever
`gameItemId` is set — unconditionally, with no catalog lookup (all items are whole units;
the `MaterialPieceTypeLookup` seam stays material-only). Book-out/rebook amounts cannot be
bean-validated (the DTO carries no catalog reference) — the whole-number rule lives in
`InventoryCheckoutService` next to the existing "amount ≤ available" check. While there,
the same service check closes the pre-existing gap that PIECE **material** book-outs are
not whole-number-validated server-side either (cheap, same code path, spec-noted).

`InventoryItemDto` gains a nullable `gameItem` reference DTO (id, name, manufacturer,
kind) and a now-nullable `quality`; `withVersion()` copy semantics unchanged.

### 5.2 Reads — `catalog` discriminator parameter

The grouped/stack/aggregate read family gains an enum query parameter
`catalog=MATERIAL|ITEM` (default `MATERIAL`, so existing clients are untouched):

- `GET /my-inventory/grouped`, `GET /all/grouped` — item variant groups
  `GameItem → stack` (no quality dimension) via new repository projections
  (`findUserItemStacks` / `findGlobalItemStacks`, GROUP BY the item stack key).
- `GET /my-inventory/stack/entries`, `GET /all/stack/entries` — item variant addresses
  the stack by `gameItemId` instead of `materialId`+`quality`; paging/ordering identical
  (REQ-INV-005).
- `GET /aggregated` — item variant returns per-item totals (no avg/max quality columns).
- New `GET /game-item/{gameItemId}` — item drilldown, parallel to `/material/{materialId}`.
- **Flat lists** `GET /all`, `GET /my-inventory` — same `catalog` parameter (§4.4: default
  `MATERIAL` keeps today's contract and excludes item rows explicitly); the ITEM variant
  swaps the sort whitelist/default from `material.name` to `gameItem.name`.
- Filters: `gameItemIds`, `jobOrderIds`, `personalOnly`/`nonPersonalOnly`; `minQuality`
  and `missionIds` are rejected for `catalog=ITEM` (400).

*Alternative considered:* a parallel `/api/v1/inventory/items/**` path family. Rejected —
it doubles six endpoints whose parameters differ in only one identity dimension, and the
unified DTO (nullable `material`/`gameItem`/`quality`) already carries both shapes
self-descriptively in OpenAPI.

### 5.3 Item catalog search — gating verified

New `GET /api/v1/inventory/item-catalog?q=&page=…` on `InventoryItemController`,
delegating to the blueprint-output query (§5.4). A dedicated endpoint is **required, not
just cleaner**: the existing `GET /api/v1/orders/item-catalog` was deliberately
`permitAll()` at every layer while it fed the anonymous item-order request form, so reusing
it for the Lager picker would have hung Member-facing UI on an anonymous endpoint and invited
accidental coupling. (The form went with ADR-0149 and the `permitAll` with ADR-0159; the two
endpoints stayed separate, which is why nothing had to move when it did.) The new endpoint needs no method-level annotation — it inherits
`hasAnyRole(ADMIN, OFFICER, LOGISTICIAN, KRT_MEMBER)` from the `/api/v1/inventory/**` URL
umbrella in `SecurityConfig`, matching the controller's read-handler style.

The frontend proxy `GET /inventory/item-search` falls under the frontend catch-all
`anyRequest().authenticated()` and **must relay with the token-carrying
`backendApiClient.get(...)`** — which since ADR-0159 is the only way there is: `getPublic(...)`,
which the orders item search used for anonymous parity, no longer exists.

### 5.4 Catalog predicate — "items with blueprints"

Bookable items are `GameItem`s that are the `outputItem` of ≥ 1 active (non-soft-deleted)
blueprint — a new `BlueprintRepository.findItemsWithActiveBlueprint(q, pageable)` query.
This is deliberately a **superset** of the order picker's `findOrderableItems` (which
additionally requires a resolved RESOURCE ingredient, issue #304): an item crafted purely
from sub-components is still physical stock worth tracking. Consequence: such an item may
have zero allocatable orders — acceptable, same as a material no order requests.
(`GameItem.isCraftable` is *not* used — the blueprint relation is authoritative, matching
the orders feature. The predicate also guarantees a resolvable blueprint product key,
which §8 relies on.)

### 5.5 Allocation gate — mirror of REQ-ORDERS-018 (shape verified)

A new `JobOrderItemService.requiredGameItemIds(order)` returns the distinct
`JobOrderItem.gameItem` ids for an ITEM order and the **empty set** for a MATERIAL order
(empty set = no link possible — the existing semantics). Enforced by a private
`assertGameItemRequiredByJobOrder` throwing `BadRequestException` (400, RFC 7807, code
`BAD_REQUEST`) at exactly the two write seams where the material gate sits today: the
check-in allocation-slice loop in `createInventoryItem` and the JOB_ORDER branch of
`addAllocation` — and deliberately *not* in `changeAllocation`/`removeAllocation`
(amount-only edits of an already-validated target).

Three read/maintenance surfaces mirror the material gate as well:

- `JobOrderReferenceDto` gains `requiredGameItemIds` (sibling of the existing
  `requiredMaterialIds`), so pickers can filter client-side (§6.2);
- the orphaned-link warning (REQ-ORDERS-019) also flags item rows whose gameItem is no
  longer requested by the linked order;
- the requester item-line edit cleanup (`updateItemJobOrderAsRequester`) additionally
  drops game-item allocation slices for removed lines.

Note: material rows remain allocatable to ITEM orders through their blueprint-derived
material requirements (`requiredMaterialIds` walks `JobOrderItemMaterial` for ITEM
orders) — the game-item gate is a **parallel** set, not a replacement.

### 5.6 Production booking creates stock (extends REQ-ORDERS-025 / ADR-0099)

`JobOrderItemProductionCreateDto` gains a nested `bookIn` object — **optional at the API
level during rollout, required once the frontend ships**: PR 2 accepts `bookIn == null`
and then behaves exactly like today (no stock created — the phantom status quo), so the
live Herstellung flow keeps working between the PR-2 and PR-3 deployments; PR 3's modal
always sends it, and the same PR flips the backend validation to reject a missing
`bookIn` (the two PRs ideally ride one release train, but the flow is never broken either
way). `personal = true` and `allocateToOrder = true` are mutually exclusive — personal
stock never carries allocations (the standing `assertNotPersonal` invariant); the service
rejects the combination with 400 and the modal disables "dem Auftrag zuordnen" while
"persönlich" is checked (mirroring `syncPersonalAllocations` on the Einbuchen page).

```json
{
  "amount": 2,
  "version": 4,
  "consumption": [ … ],
  "skippedMaterialIds": [ … ],
  "bookIn": {
    "locationId": "…",            // required — "wo"
    "ownerUserId": "…",           // optional — "bei wem", default: acting user
    "owningOrgUnitId": "…",       // optional — org-unit picker semantics (REQ-ORG-004/016)
    "personal": false,
    "allocateToOrder": true       // auto-earmark the produced units to this order
  }
}
```

`JobOrderItemProductionService.bookProduction` flow, appended after the existing
`manufacturedAmount` advance + flush + offer-clamp loop, same transaction:

1. Resolve owner (`ownerUserId` or actor) and stamp `owningOrgUnit` via
   `OwnerScopeService` create-on-behalf semantics (target memberships ∪ caller editable
   scope, REQ-ORG-016).
2. Create the item stock row (`gameItem = line.gameItem`, whole `amount`); if
   `allocateToOrder`, attach the `InventoryJobOrderAllocation` slice **before** saving —
   added to the row's `jobOrderAllocations` list with the back-reference set, persisted
   by the single `save(newItem)` through the existing `CascadeType.ALL` cascade (never a
   separate pre-save of the allocation).
3. Call `mergeStockIfRequested(savedRow, …)` — slice-first-then-merge, mirroring the
   transfer flow's `applyTransferInherit` precedent, so `InventoryAllocations.unionInto`
   folds a same-order slice of an absorbed victim correctly. R5 `fits()` holds by
   construction (the row carries exactly its own produced amount).
4. Audit: new `INVENTORY_RECEIVED_FROM_PRODUCTION` (mirrors
   `INVENTORY_RECEIVED_FROM_REFINERY`) with `jobOrderId`, `gameItemId`, `amount`,
   `locationId` keys — alongside the existing `JOB_ORDER_PRODUCTION_BOOKED` and
   per-entry `INVENTORY_CONSUMED_BY_PRODUCTION` events.

**Transaction safety — verified against the CLAUDE.md landmines.** `bookProduction`
contains no persistence-context-clearing bulk update (`clampOfferedAmountToStock` is
plain `@Modifying`, run after the consumption loop for snapshot stability); a fresh
`InventoryItem` is transient, so `save()` dispatches to `persist()` — no `merge()`, no
double `@Version` bump; all previously loaded entities stay managed. Appending steps 1–4
at the end needs **no reordering**. Two standing constraints: never introduce a
`@Modifying(clearAutomatically = true)` query into this flow, and if the response DTO
needs the new row's version, read it after flush (`saveAndFlush`).

**Merge-helper extension (verified gaps).** `mergeStockIfRequested` is public with
`@Transactional(MANDATORY)` — it composes with `bookProduction`'s transaction and has
cross-service precedent. Two things must change for item rows, otherwise the merge
**silently never fires**: (a) its kind branch currently skips when `material == null` —
gameItem rows are treated like PIECE (always merge); (b) `findMergeGroupForUpdate`'s
`i.quality = :quality` predicate needs the NULL-branch + gameItem key from §4.4. Its
existing offer-backed-row exclusion (NOT EXISTS on active offers) then automatically
protects future stock-backed item offers (§8).

*Rejected alternative:* a permanently optional/skippable `bookIn`. Production without
book-in is exactly the phantom-item status quo this feature removes; a producer who keeps
the item privately sets `personal = true` instead (which then implies
`allocateToOrder = false`, see above). The transitional optionality above is a rollout
mechanism, not a feature.

## 6. Frontend design

**Binding: all UI/UX work in this feature follows the latest version of the KRT / DAS
KARTELL design system** — the rules in
[`docs/specs/ui-design-system.md`](specs/ui-design-system.md) and the visual source of
truth in the design-system submodule
(`.claude/skills/das-kartell-design/README.md`). Implementers must work against the
**current state** of that submodule (materialise/update it before any UI work; never
build UI surfaces from memory of an older design-system state): brand colours, Lato-only
typography, square-first sci-fi HUD components, no native browser dialogs, and the four
responsive device classes apply to every surface this design adds or touches (view
switch, item tree, modals, comboboxes, production book-in section).

### 6.1 View split — Material ↔ Items

Each Lager page gets a two-tab HUD switch (same pattern as the order-detail tab layout,
REQ-ORDERS-026; page-scoped tab CSS):

```
/inventory                 ?view=items → per-item aggregate overview (no quality columns)
/inventory/my              ?view=items → item tree: GameItem → stack → lazy entries
/inventory/all             ?view=items → same, org-scoped
/inventory/game-item/{id}              → item drilldown (parallel to /inventory/material/{id}, no switch)
```

**Terminology rule (binding for implementers):** catalog items are always named
`gameItem` in URLs, identifiers, params and DTO fields — never bare "item", which in this
codebase denotes an `InventoryItem` *row* (`/inventory/{id}/…`) or a `JobOrderItem` line.
The drilldown route therefore mirrors the backend's `/game-item/{gameItemId}` (§5.2), not
"/inventory/item/{id}".

- The `view` query parameter drives which fragment the server renders. Mechanism: the
  grouped-table swap URLs are built from the page's own filter/query state — exactly what
  `filterMyInventory`/`filterInventory` do today — with `view=` added to that state and
  `history: true` keeping the address bar authoritative (there is no `preserveQuery`
  option in krtFetch; the query-state-in-URL pattern per REQ-FE-008 is page-built).
- Item tree columns: item (name, manufacturer, kind badge), amount (whole), location,
  owner, org unit, order-allocation chips + rest, note, actions. No quality, no mission
  chips.
- Filters in item view: gameItem multi-select, `jobOrderIds`, personal/non-personal
  (`/my`). The gameItem filter is **not** a verbatim mirror of the material checkbox list
  (which renders the full catalog — fine for ~150 materials, unusable for thousands of
  items): it is populated only with gameItems that currently have stock rows in the
  viewer's scope (bounded, served by the grouped query's key set), keeping the
  full-catalog search to the pickers (§6.6). Pinned in REQ-INV-030.
- `localStorage` expansion keys are view-scoped (`expanded_rows_lager_items_*`,
  `expanded_stacks_lager_items_*`) so the two trees remember state independently.
- Templates: the tree/stack/entry fragments are parameterised on the view (Thymeleaf
  fragment arguments) rather than duplicated; the entries fragment
  (`fragments/inventory-stack-entries.html`) branches per row kind.

### 6.2 Einbuchen (`/inventory/input`)

A Material ↔ Item mode toggle (same mechanism as `orders-create`'s `orderModeToggle`,
disabling the inactive form so its `required` fields don't block submit). Item mode:

- **Item picker:** remote combobox `data-krt-combobox="remote-game-items"` (§6.6).
- **Amount:** whole-number input — the quantity-type wiring treats a gameItem row as
  PIECE (integer input mode, step 1). Per REQ-INV-026 the SCU merge **opt-in checkbox is
  never rendered** in item mode (items always auto-merge, like PIECE materials — there is
  no choice to offer); the same suppression applies to the item-row book-out
  TRANSFER/Umbuchen modals in §6.3.
- **Location/owner/org-unit/personal:** unchanged fragments (`fragments/owner-picker`).
  **Owner decision (2026-07-16):** the location selects in the booking flows (Einbuchen,
  Umbuchen target location, production book-in) become searchable comboboxes — originally
  planned as local-filter mode, since **superseded by ADR-0100**: they ship as
  server-searched `remote-locations` comboboxes (no preloaded catalog), like every other
  catalog picker. Each site runs the §6.6 binding grep checklist before conversion.
- **Allocation rows:** same preload-and-filter mechanism as material mode (verified: the
  hidden `<template>` rows carry the scoped OPEN/IN_PROGRESS order list and material
  relevance is a client-side `data-materials` CSV filter). Item mode mirrors it: the
  order `<option>`s additionally carry `data-game-items` (CSV from the new
  `JobOrderReferenceDto.requiredGameItemIds`), and the existing `filterOrderSelects`
  logic keys on the chosen gameItem. No mission section in item mode;
  `syncPersonalAllocations` (personal ⇒ hide allocations) applies to both modes; the
  gap-free `jobOrderAllocations[i].*` index binding is target-agnostic and unchanged.

### 6.3 Ausbuchen / Umbuchen

The existing modals on `inventory-my`/`inventory-admin` operate per entry row and work
as-is; the Herkunft (deduct-from) picker (`inventory-herkunft.js`) renders only the
job-order dimension for item rows (the mission split block is absent from the DOM, which
the picker already tolerates as "dimension without chips"). Quality is simply not shown.
Owner picker keeps the four-org-unit-kind memberships source (REQ-INV-007, #1328/#1330).

### 6.4 Production modal (order detail) — touchpoints verified

The Herstellung UI lives inline in `orders-detail.html` (`#production-modal`, opened per
line via `data-trigger="od-open-production"`; class-based open/close per ADR-0093) and is
driven by `orders-detail.js` (`openProductionModal`, `_prodReconcile`, `bookProduction`,
submit via the conflict-aware `krtOrderWrite` wrapper). The new book-in section goes
inside `#production-form` between `#production-materials` and the actions row, following
the `production-*` id / `data-prod-*` role conventions:

- location picker (server-searched `remote-locations` combobox per the §6.2 owner
  decision as superseded by ADR-0100), owner combobox
  (`remote-users`, default: acting user), org-unit picker, personal checkbox,
  "dem Auftrag zuordnen" checkbox (default on; disabled + cleared while "persönlich" is
  checked, §5.6).
- **Org-unit picker semantics — pinned to the #1328/#596 convention so the REQ-ORG-004
  ">1 memberships + no picker output → 400" branch is unreachable from the UI:** the
  picker is always rendered when the resolved owner has ≥ 1 membership, preset to a
  concrete default (the owner's single/primary unit; the order's responsible unit when it
  is among the owner's memberships), has no empty placeholder option, is re-populated
  from the memberships source (same as the Umbuchen picker) whenever the owner combobox
  changes, and is hidden only for a membershipless owner.
- `openProductionModal` initialises defaults, `_prodReconcile` gates the submit on a
  chosen location, `bookProduction` adds the `bookIn` payload; the frontend mirror DTO
  and `JobOrderWriteController.bookProductionAjax` relay it.
- i18n joins the `PRODUCTION_I18N` bootstrap block + `messages*.properties`
  (`orders.production.bookIn.*`).

### 6.5 Live update & multi-user sync (REQ-FE-010/015)

- All new/changed mutations go through `krtFetch` fragment swaps — no reload.
- **No new section keys.** Both views ride the existing `inventory` topic, section
  `stock`; the receivers' `refresh` re-renders whichever view is active (the `view`
  param is part of the preserved fragment URL). Broadcast/accept-list/receiver stay in
  parity automatically because no seam map changes.
- Item writes that touch order earmarks reuse the existing cross-feature fan-out:
  `broadcastOrdersChanged(orderIds)` → `order:{id}` sections. Production success already
  refreshes + broadcasts `['items','aggregated','header','kpi','item-handovers',
  'item-handover-lines']` on `order:{id}`; it **additionally** calls
  `sendChanged('inventory', ['stock'])` so Lager viewers see the new stock without
  reload — `inventory`/`stock` already exists at all three mirror points, so no
  REQ-FE-010 seam risk.

### 6.6 Combobox standardisation for catalog pickers (extends ADR-0053; new REQ-FE-016)

**Component contract — verified and load-bearing:** enhancing a
`select[data-krt-combobox]` **removes the native select from the DOM** (`replaceChild`)
and drops all per-option metadata; only `name`/`id`/generic `data-*` passthrough,
`.value`, and a bubbling `change` on the hidden input survive (`data-testid` moves to the
visible textbox — e2e locators hit the textbox). Programmatic preselection goes through
`element.krtCombobox.setValue(v)`.

Three existing consumers read per-option metadata (`selectedOptions[0].dataset.…`) from
material selects and would break **silently** (wrong unit/step, no SCU hint) if the
marker were just added: `orders-create.js` (`refreshMaterialUnit`), `orders-detail.js`
(edit-line variant), `inventory-input.js` (SCU/PIECE mode switch). The refinery create
select reads `data-refined-id`/`data-refined-name` the same way. Therefore this design
**extends the component** rather than patching every consumer ad hoc:

> `krt-searchable-select` carries each option's extra `data-*` (everything outside the
> combobox-owned keys) through `makeItem` and mirrors the selected option's dataset onto
> the hidden input via **one shared helper invoked on every value-set path** — there are
> four, and covering only `commit()` would re-ship the silent-break class this contract
> exists to close: (1) click/keyboard `commit()`, (2) enhance-time preselect seeding
> (edit mode / validation-error redisplay — `inventory-input.js` reads the quantity type
> on load), (3) `reconcile()`'s typed-exact-match path (sets the value + fires `change`
> without commit), (4) the programmatic `element.krtCombobox.setValue()` API. The helper
> tracks the set of option-mirrored keys per instance and **removes stale keys** before
> applying the new option's dataset (picking an option that lacks a key the previous one
> carried must not leave the old value behind), and it **never overwrites select-level
> passthrough keys** (`data-role`, `data-trigger`, `data-url-template`, … — the keys the
> enhancer copied from the source select at enhance time are reserved; option keys
> colliding with them are ignored). Consumers change one line: read
> `hidden.dataset.quantityType` instead of `selectedOptions[0].dataset.quantityType`.
> Remote sources may return an optional `data` map per option for the same purpose
> (`remote-game-items` does not need it initially). This contract is part of REQ-FE-016,
> with tests for the typed-exact-match, preselect and stale-key paths — not only
> click-commit.

**Items — remote.** New registry entry in a small `krt-catalog-search.js` (loaded in
`fragments/head.html` before the enhancer, parallel to `krt-user-search.js`):
`remote-game-items` → `GET /inventory/item-search?q=` (§5.3). The existing
`orders-create` item picker keeps its direct `krtSearchableSelect(…, {remoteSource})`
wiring (it needs blueprint chaining on select) — both share the component.

**Materials — local filter.** The seven identified plain material `<select>`s get the
bare `data-krt-combobox` marker (preloaded options, client-side filtering — the material
catalog is small enough to keep server-rendering it; remote mode would force new
endpoints plus edit-mode seeding for no UX gain):

|                        Site                        |                            File                             |                                                                                                                                                                              Consumers to re-point (reads **and** writes)                                                                                                                                                                              |
|----------------------------------------------------|-------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Einbuchen material picker                          | `inventory-input.html` (`#materialId`)                      | `inventory-input.js` SCU/PIECE mode (dataset read incl. the on-load preselect path)                                                                                                                                                                                                                                                                                                                    |
| Job-order create material line (+ JS row template) | `orders-create.html` / `orders-create.js`                   | `refreshMaterialUnit` (dataset read); dynamically cloned rows call `krtEnhanceComboboxes(row)` after insertion; **`importFromScmdb`** — its `targetRow.querySelector('select')` would resolve to the *minQuality* select once the material select is replaced, silently writing a material UUID into the quality control: re-target by `data-role` and set values via `element.krtCombobox.setValue()` |
| Job-order edit material line                       | `orders-detail.html`                                        | edit-line unit refresh (dataset read)                                                                                                                                                                                                                                                                                                                                                                  |
| Refinery create input material                     | `refinery-orders-create.html` / `refinery-orders-create.js` | `data-refined-id`/`-name` lookups (dataset read); **`addMaterialRow` clones the LIVE first row** (`cloneNode(true)`) — a cloned enhanced combobox is dead (listeners dropped, no native select left to re-enhance, duplicated ARIA ids) and `input.selectedIndex = 0` no-ops: switch row creation to an inert `<template>` source + `krtEnhanceComboboxes(row)`, route resets through `setValue('')`   |
| Material browse/navigate select                    | `inventory-material.html`                                   | none (`data-trigger` passthrough + bubbling `change` keep the navigation handler working)                                                                                                                                                                                                                                                                                                              |
| Admin material-alias create + edit                 | `admin/material-aliases.html`                               | none                                                                                                                                                                                                                                                                                                                                                                                                   |

Per the §6.2 owner decision, the **location selects of the booking flows** (Einbuchen,
Umbuchen target location — existing forms, PR 1; production book-in — new in PR 3) join
the conversion as bare local comboboxes, under the same checklist below.

Binding checklist for **every** conversion site (this table and any future one): grep the
page's JS for `querySelector('select')`, `.selectedOptions`, `.selectedIndex`, `.options[`,
direct `.value =` writes, and `cloneNode` on containers holding the picker — each hit is
either re-pointed (hidden-input dataset / `setValue()`) or the site must not be converted.

The Materialbörse's hand-rolled `data-mb-*` comboboxes already provide search and are out
of scope here (candidate for consolidation onto the shared enhancer in §8).

## 7. Cross-cutting

### 7.1 Security & roles

Unchanged. Item rows use the same gates as material rows: create `isAuthenticated()`,
per-entry writes `@ownerScopeService.canEditInventoryItem` (Member+ with owner escape,
REQ-ORG-011); reads inherit the `/api/v1/inventory/**` role umbrella; production booking
keeps its LOGISTICIAN/OFFICER/ADMIN + `canEditJobOrder` gate. `ROLES_AND_PERMISSIONS.md`
gets a wording touch-up only (Lager rows now cover "Material- und Item-Bestand").

### 7.2 Audit (REQ-AUDIT-001)

- Item rows reuse the existing `INVENTORY_*` event types (same lifecycle); `AuditDetails`
  payloads carry `gameItemId=` instead of `materialId=` (ids only, no free text/PII).
- **Subject-label snapshot (verified gap):** `InventoryAuditLabels.label(item)` renders
  `material.getName()` with an em-dash fallback — without a gameItem branch every reused
  event would log its deletion-proof identity snapshot as `— @ <location>` in the viewer
  and the PDF/JSON exports. The label renders `<gameItem.name> @ <location>` for item
  rows, with a regression test per reused event type on a NULL-material row (§9).
- One **new** event type: `INVENTORY_RECEIVED_FROM_PRODUCTION` — the enum, the
  `auditService.record(...)` call, the unified viewer's INVENTORY filter list
  (`AdminAuditLogPageController` — a **frontend-module** artifact), the DE/EN
  `audit.event.*` labels, and the REQ-AUDIT-001 coverage-list reconciliation all ship in
  the **same PR** (PR 2 therefore spans both modules for the audit surface — the binding
  audit same-PR rule does not allow deferring the viewer filter or labels to PR 3).

### 7.3 Observability (REQ-OBS-005…011)

Evaluated: **no new metric, alert, dashboard, log stream or probe is required.** No new
scheduled job, status enum, or public surface ships; the new endpoints are authenticated
API paths under the existing HTTP server metrics and access-log/MDC (`orgUnitId`) rules;
audit volume rides `basetool_audit_events_total{domain="INVENTORY"}` (the new event type
is domain-internal and the `AuditDomainSilenceAnomaly` alert is domain-level). This
rationale is recorded in the implementation PR description.

### 7.4 i18n

New keys under `inventory.items.*` (view switch, tree, filters), `inventory.form.item.*`
(Einbuchen item mode), `orders.production.bookIn.*` (production modal section),
`audit.event.INVENTORY_RECEIVED_FROM_PRODUCTION` — DE + EN, umlauts `\uXXXX`-escaped in
`.properties`.

### 7.5 Specs, ADRs, docs (same-PR obligations)

- **New spec** `docs/specs/inventory-items.md` (registered in `INDEX.md`):
  - REQ-INV-029 — catalog-discriminated stock rows (XOR, no quality, whole units).
  - REQ-INV-030 — Material/Items view split on all Lager surfaces.
  - REQ-INV-031 — item allocations only to qualifying ITEM orders; no mission dimension.
  - REQ-INV-032 — production booking books produced stock in (location/owner/org-unit
    chosen by the actor; auto-earmark default).
- **Amendments:** `inventory-lager.md` (REQ-INV-002/026/027 notes for the item
  dimension; REQ-INV-005 — item stacks are addressed by `gameItemId` with no quality key;
  REQ-INV-028 — the aggregated overview gains an item variant without the quality
  columns), `inv-material-quantities.md` / `whole-number-amounts.md` (server-side
  whole-number enforcement now also covers book-out/rebook — the PIECE-material gap §5.1
  closes), `orders-item-production.md` (REQ-ORDERS-025 gains the book-in step),
  `orders-overview-materials.md` (REQ-ORDERS-018 sibling for gameItems, REQ-ORDERS-019
  extension), `frontend-ajax-mutations.md` (**REQ-FE-016** — catalog pickers are
  searchable comboboxes incl. the metadata-carry contract; **REQ-FE-015** — the
  inventory-room prose gains the new receiving surfaces (item views, gameItem drilldown)
  and the new cross-publisher (production booking on the order page → `inventory`/
  `stock`); ADR-0053 gets a follow-up note).
- **New ADR-0101** — "Track game items as catalog-discriminated inventory rows" (records
  §3, the production book-in decision, and the §8 forward-compatibility, building on
  ADR-0098/0099).
- README feature overview, CHANGELOG, and the German wiki pages (Lager, Aufträge — later
  Materialbörse) move with the implementation PRs.

## 8. Planned extension — Materialbörse item offers released from item stock

Today the trade board (spec `materialboerse.md`, ADR-0082/0086/0087) has two offer kinds
in one aggregate (`MaterialExchangeOffer.kind`): **MATERIAL** offers bind to exactly one
own Lager row (`inventoryItem` FK; `offeredAmount` ≤ current stock at release/edit; V210
partial-unique = one ACTIVE offer per row; REQ-MARKET-013 ratchet
`clampOfferedAmountToStock` runs in every stock-decrement transaction), while **ITEM**
offers are *free-stated*: a blueprint `itemProductKey` + display `itemName` + user-typed
`itemQuantity`, with **no** stock link (a V213 CHECK forbids `inventory_item_id` on ITEM
offers) and no clamp.

Once item stock rows exist, item offers can be **released from stock, analogous to
material offers**. Per the owner decision (2026-07-16) this phase is scheduled **directly
after the core PRs as part of the same epic** (§10 Phase 5); the design is fixed now so
the core feature stays forward-compatible:

- **Offer model:** an ITEM offer *optionally* carries `inventoryItem` (pointing at a
  gameItem-kind row). Stock-backed item offers validate `itemQuantity ≤ row.amount`
  (whole units) at release/edit — the item sibling of `requireOfferableAmount`;
  free-stated offers remain supported (craft-on-demand listings, REQ-MARKET-012).
- **Identity bridge:** stock rows key on `GameItem`, offers on the blueprint
  `product_key` (deliberately not a `game_item` FK — ADR-0087). For a stock-backed
  release, `itemProductKey`/`itemName` are derived from the row's gameItem via its
  blueprint product; §5.4's catalog predicate guarantees the product key resolves. The
  row's `inventory_item_id` carries the physical truth, so the known product-key↔item
  many-to-one fuzziness stays a display concern only.
- **Migrations:** relax the V213 branch-exclusivity CHECK (ITEM offers may carry
  `inventory_item_id`); the V210 partial-unique index then applies as-is — stock-backed
  item offers get one-ACTIVE-offer-per-row semantics (consistent: the row *is* the
  physical stock), while free-stated item offers keep the deliberate multi-listing
  freedom. REQ-MARKET-012 is amended accordingly.
- **Ratchet:** the REQ-MARKET-013 clamp becomes kind-aware — a sibling
  `clampItemQuantityToStock` (or a generalised query branching on kind) wired into the
  same stock-decrement call sites, which for item rows are the book-out / transfer /
  bulk-checkout paths in `InventoryCheckoutService` that already invoke the material
  clamp today.
- **Release picker:** `findReleasableForUser` (rewritten with LEFT JOIN + COALESCE in
  §4.4) drops its interim `i.material IS NOT NULL` guard and returns both kinds; the
  release modal (`materialboerse-release.js`) gains a stock-backed item branch.
- **Prerequisite cleanup (verified defect):** `MaterialExchangeService.updateOffer` is
  not kind-aware today — it unconditionally validates `offeredAmount` against
  `offer.getInventoryItem()`, which NPEs/400s on ITEM offers (item quantity is currently
  immutable after release). The kind-aware edit path ships with this phase.
- **What the core feature already guarantees:** the offer FK works for item rows
  unchanged (`ON DELETE CASCADE`); `mergeStockIfRequested` keeps excluding offer-backed
  rows from merges (§5.6); the clamp is verified NULL-material-safe (§4.4). No schema or
  API choice in §§4–5 needs revisiting.
- **Paperwork:** new REQ-MARKET-0xx (stock-backed item offers + ratchet), REQ-MARKET-012
  amendment, and an ADR superseding ADR-0087's "stated quantity only" decision.

## 9. Testing strategy

- **Backend unit/service** (Mockito, Given/When/Then, `// covers REQ-INV-0xx`):
  create/validate XOR + quality/whole-number rules (incl. the closed validator hole —
  gameItem-only payloads with zero/fractional/negative amounts); allocation guards
  (ITEM-order-only, mission rejection, wrong-item order → 400 `BAD_REQUEST`);
  book-out/rebook/merge on item rows (incl. merge NULL-quality branch — the
  silent-no-op regression — and the transfer/rebook gameItem copy sites vs the XOR
  CHECK); production book-in (fresh row, merge path, auto-earmark union, on-behalf
  stamping, personal×allocate rejection, `bookIn == null` legacy fallback, audit events);
  audit subject labels render the gameItem name (not `—`) per reused event type; **one
  regression test per rewritten query in §4.4 with a NULL-material row present** (stacks,
  entries, flat lists under the default `material.name` sort, releasable picker,
  Materialsammlung, orphaned-link warning, order stock index 500).
- **MockMvc/controller**: `catalog=ITEM` read family, item-catalog endpoint gating,
  RFC-7807 shapes; `OpenApiGeneratorTest` regenerates `openapi.json`.
- **Frontend MockMvc**: view-switch rendering, combobox markers present
  (`data-krt-combobox`), production modal book-in fields, metadata-carry (hidden input
  exposes `data-quantity-type` after commit).
- **E2E (Playwright, `e2e` label)**: `ItemInventoryOperationsE2eTest` (einbuchen → tree →
  ausbuchen/umbuchen), production book-in creating visible stock, live peer-sync of an
  item write on `/inventory/all` (existing `InventorySharedLagerLiveSyncE2eTest`
  pattern), combobox interaction via `E2eSupport.selectComboboxByValue` (locators target
  the visible textbox — `data-testid` moves there).
- `LiveSyncSectionMapParityTest` — unaffected (no new seam keys), asserted to stay green.

## 10. Delivery plan (three stackable PRs + one follow-up phase)

1. **PR 1 — Combobox conversion** (independent, immediate UX win): the component
   metadata-carry extension (§6.6 contract incl. all four value-set paths), the seven
   material selects, all consumer re-points from the §6.6 table (dataset reads,
   `importFromScmdb`, the refinery row-clone rework), **and the migration of every
   existing Playwright interaction with the converted selects** from `selectOption` to
   `E2eSupport.selectComboboxByValue` (the enhancer removes the native select — the old
   locators cannot survive the conversion). `UI`/`FE` **+ `e2e`** labels (without `e2e`,
   CI would skip the very suite this PR touches and the breakage would surface on
   unrelated later PRs).
2. **PR 2 — Backend item stock domain**: V220, entity/DTO/validator changes, the §4.4
   remediation list, read family (`catalog` param incl. flat lists), item-catalog
   endpoint, allocation gate (§5.5), production book-in (transitional optional `bookIn`)
   + OpenAPI + specs (REQ-INV-029/031/032, ADR-0101). **Spans both modules for the audit
     surface** (§7.2: event type + record call + viewer filter + DE/EN labels + coverage
     list in one PR). `BE`/`INV`/`ORDERS`.
3. **PR 3 — Frontend item views & flows**: view switch, item tree/drilldown, Einbuchen
   item mode, modal adaptations, production modal book-in section (and the backend flip
   of `bookIn` to required, §5.6), live-sync fan-out, i18n, wiki. `FE`/`INV`/`UI`, `e2e`.
4. **PR 4 — Order-detail item-stock panel** (decided follow-up, immediately after PR 3):
   per-line earmarked item stock on the order detail page, analogous to the
   Materialsammlung — with its **own** item projection (the §4.4 material-only guards on
   the Materialsammlung seams stay), fragment + live-sync section keys per the
   three-mirror rule. `FE`/`INV`/`ORDERS`/`UI`, `e2e`. **Shipped** as REQ-ORDERS-028
   ([`orders-item-production.md`](specs/orders-item-production.md)) — endpoint
   `GET /api/v1/orders/{id}/item-stock`. Later reorganised (REQ-ORDERS-028/031): the stock
   now renders inline in each ordered item's expand row, and collecting it (owner/location
   transfer + delivered) moved to the standalone Itemsammelübersicht page.
5. **Phase 5 — Materialbörse stock-backed item offers** (§8): **directly after PR 3/4 as
   part of the same epic** (owner decision 2026-07-16 — not backlogged), including the
   kind-aware `updateOffer` fix. `MARKET`/`INV`.
6. **Phase 6 — delivery consumes item stock (best-effort)**: **Shipped** as
   [`REQ-ORDERS-030`](specs/orders-item-production.md) — item handover consumes
   `min(handed-over amount, the order's earmarked item stock)`, never blocking, so legacy
   manufactured-without-stock lines stay deliverable and the phantom stock a delivery leaves
   behind disappears. Consumed rows draw only this order's earmark slice oldest-first under a
   `FOR UPDATE` lock, are audited as the reused `INVENTORY_HANDED_OVER`, and the success path
   live-syncs the `item-stock` panel + the `inventory`/`stock` seam. No ADR — a behaviour
   refinement fully captured in the REQ.

Each PR carries its spec/README/CHANGELOG slice per the repo's same-PR rules.

## 11. Scope decisions & boundaries

The former open items were decided by the owner on 2026-07-16 (recorded in §10):

1. **Delivery consumes item stock — DECIDED: best-effort — SHIPPED** as
   [`REQ-ORDERS-030`](specs/orders-item-production.md) (§10 Phase 6). Item handover
   consumes `min(handed-over, the order's earmarked item stock)`, never blocking, so
   legacy manufactured-without-stock lines stay deliverable and phantom stock disappears
   for the normal flow.
2. **Order-detail item-stock panel — DECIDED: dedicated follow-up PR right after PR 3**
   (§10 PR 4). Until then the allocation chips in the Lager views carry the visibility;
   the §4.4 material-only guards keep the order pages correct in the interim.
3. **Materialbörse stock-backed item offers — DECIDED: directly after the core PRs**
   (§8, §10 Phase 5), including the kind-aware `updateOffer` defect fix.
4. **Location pickers — DECIDED: converted to searchable comboboxes** in the booking
   flows (§6.2/§6.6) despite the small catalog, for consistent picker UX — shipped as
   server-searched `remote-locations` comboboxes per ADR-0100.

Still deliberately out of scope:

5. **Non-blueprint items** (bought/looted components): the schema supports them (any
   `GameItem` FK); only the catalog predicate (§5.4) gates the picker; widening it later
   is a one-query change — but note §8's identity bridge assumes a blueprint product key,
   so widening interacts with the Börse phase.
6. **Boundary to "Mein Inventar" (`PersonalInventoryItem`)**: the app already has a
   second personal item surface — free-text item names, UEX-city locations, its own
   audited area and wiki page. The boundary is: **Lager item stock = catalog-linked
   `GameItem` rows with org visibility, allocations and booking flows; Mein Inventar =
   free-text personal records** (blueprints V3, no catalog link, no allocations). A
   Lager item row with `personal = true` is still catalog-linked org data, not a Mein-
   Inventar entry. ADR-0101 records this boundary (and that no consolidation is
   intended for now); the wiki pages for Lager and Mein Inventar cross-link it so end
   users know which surface to use.

## 12. Open questions

- None. All former owner decisions were resolved on 2026-07-16 (§11); §4.4 and §6.6 are
  binding implementation checklists derived from verified code behaviour, not open
  designs.


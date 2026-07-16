> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-16.
> **Owner area:** INV / ORDERS · **Related ADRs:** ADR-0101 (builds on ADR-0098/0099); design
> context in [`docs/DESIGN_ITEM_INVENTORY.md`](../DESIGN_ITEM_INVENTORY.md).

# Inventory — game-item stock rows (Items im Lager)

## Context & goal

The Lager historically tracked **materials** only, while game items (`GameItem` — ship
components, weapons; initially those with at least one active blueprint) existed solely
as catalog entries and ITEM-order lines. Manufacturing consumed material stock but the
produced item vanished — no stock row anywhere. This spec makes items first-class
warehouse stock: catalog-discriminated rows on the existing `InventoryItem` aggregate,
separate Material/Items views, material-equal booking flows, ITEM-order-only
allocations, and production booking that books the produced units in.

## Requirements

### REQ-INV-029 — Catalog-discriminated stock rows (gameItem XOR material)

An `InventoryItem` row references **exactly one** catalog entry: a `Material` (with
`quality` 0–1000) or a `GameItem` (with `quality IS NULL`). Game-item rows hold
**positive whole-unit** amounts, follow the PIECE auto-merge rule of REQ-INV-026, and
their stack identity is `user · gameItem · location · personal · owningOrgUnit` (no
quality dimension). Only items that are the output of ≥ 1 active blueprint are bookable
(the catalog predicate is deliberately a superset of the order picker's
RESOURCE-ingredient requirement). Tenancy, owner escape, audit and append-only
semantics are identical to material rows.

**Acceptance**

- [ ] DB CHECKs enforce the XOR and the quality-by-kind pairing (V220); a payload
  violating either is a 400 validation error, never a 500.
- [ ] A gameItem-only payload with a zero/negative/fractional amount is rejected 400
  (the validator hole closed — `materialId == null` no longer skips amount rules).
- [ ] Material-only read surfaces (stacks, flat lists incl. their default
  `material.name` sort, Materialsammlung, Materialbörse picker, order stock index,
  blueprint availability) exclude item rows explicitly — verified per query with an
  item row present.
- [ ] Item rows merge on write (PIECE rule); the merge FOR-UPDATE group query matches
  NULL material/quality + the gameItem key (no silent no-op).

**Enforced by:** `InventoryItemServiceTest` / `InventoryStockMergeTest` item cases,
`ValidQuantityAmountValidatorTest` · **Code:** `InventoryItem`, V220,
`ValidQuantityAmountValidator`, `InventoryItemRepository` · **Issues:** —

### REQ-INV-030 — Separate Material / Items views on the Lager surfaces

**Status:** the API `catalog` contract (last sentence and the API acceptance items) is
live; the view-switch UI described here ships with the follow-up frontend PR (PR 3).

`/inventory`, `/inventory/my` and `/inventory/all` offer a Material ↔ Items view switch
(`view=items` riding the page's query state); the item view mirrors the material tree
(GameItem → stack → lazy entries, REQ-INV-002/005 semantics) without quality or mission
columns and with view-scoped expansion persistence. Drilldowns are per-catalog pages
(`/inventory/material/{id}`, `/inventory/game-item/{id}`) without a switch. The item
view's gameItem filter is populated only with gameItems that currently have stock rows
in the viewer's scope — never the full catalog. The API read family carries a
`catalog=MATERIAL|ITEM` discriminator (default `MATERIAL` — existing clients
unaffected); a catalog-mismatched filter is rejected with 400 and never silently
ignored (`minQuality`/`missionIds`/`materialIds` under `ITEM`, `gameItemIds` under
`MATERIAL`).

**Acceptance**

- [ ] Both views render independently with persisted expansion state; fragment swaps
  preserve the active view (REQ-FE-005/008 patterns).
- [ ] `catalog=ITEM` grouped/stack/aggregated/flat reads return only item rows, sorted
  by `gameItem.name` where the material default was `material.name`.
- [ ] Peer live-sync refreshes whichever view is active (single `inventory`/`stock`
  seam, REQ-FE-015 — no new section keys).

**Enforced by:** `InventoryItemControllerTest` catalog cases, item-view e2e (PR 3) ·
**Code:** `InventoryItemController`, `InventoryAggregationService`,
`InventoryPageController` (PR 3) · **Issues:** —

### REQ-INV-031 — Item allocations only to qualifying ITEM orders; no mission dimension

A game-item row's Variante-C slices (REQ-INV-027) may target **only** `JobOrderType.ITEM`
orders whose lines request that `GameItem` (`requiredGameItemIds` — the gameItem sibling
of REQ-ORDERS-018's material gate, enforced at the same two write seams: check-in slice
loop and allocation-add; deliberately not on amount-only edits). The mission dimension is
rejected (400, code `BAD_REQUEST`; 422 stays reserved for over-allocation). Material
rows remain allocatable to ITEM orders through their blueprint-derived material
requirements — the gates are parallel, not a replacement. The orphaned-link warning
(REQ-ORDERS-019) and the requester line-edit cleanup cover gameItem slices too.

**Acceptance**

- [ ] Allocating an item row to a MATERIAL order, to an ITEM order not requesting the
  gameItem, or to a mission → 400; to a qualifying ITEM order → slice created, R5
  Σ ≤ amount enforced (422 on violation).
- [ ] An item earmark whose order no longer requests the gameItem is flagged orphaned.

**Enforced by:** `InventoryItemServiceAllocationTest` item cases · **Code:**
`InventoryItemService`, `JobOrderItemService.requiredGameItemIds`,
`JobOrderQueryService` · **Issues:** —

### REQ-INV-032 — Production booking books the produced stock in

`POST /api/v1/orders/{id}/items/{itemId}/production` (REQ-ORDERS-025) additionally
creates the produced units as item stock: the request's `bookIn` block names the
location ("wo"), owner user ("bei wem", default: actor), owning org unit (create-on-
behalf stamping semantics, REQ-ORG-004/016) and personal flag, and auto-earmarks the
produced units to the producing order by default (`allocateToOrder`, deselectable;
mutually exclusive with `personal = true` — personal stock never carries allocations).
Book-in runs in the same transaction as the consumption (slice-first-then-merge
composition over the REQ-INV-026 merge helper), and is audited as
`INVENTORY_RECEIVED_FROM_PRODUCTION`. **Rollout:** `bookIn` is optional at the API
level while the production modal predates it (null ⇒ legacy no-stock behaviour) and
becomes required when the PR-3 frontend ships.

**Acceptance**

- [ ] A production booking with `bookIn` creates (or merges into) the matching item
  stack, earmarked to the order unless deselected; `personal + allocateToOrder` →
  400.
- [ ] `bookIn == null` behaves exactly like before (counter + consumption only).
- [ ] Both audit events (`JOB_ORDER_PRODUCTION_BOOKED`,
  `INVENTORY_RECEIVED_FROM_PRODUCTION`) are written in the same transaction; the
  inventory event carries `jobOrderId`, `gameItemId`, `amount`, `locationId` keys.

**Enforced by:** `JobOrderItemProductionServiceTest` book-in cases · **Code:**
`JobOrderItemProductionService`, `JobOrderItemProductionCreateDto` · **Issues:** —

## Out of scope

Delivery-consumes-stock (decided follow-up, design §10 Phase 6), the order-detail
item-stock panel (PR 4), Materialbörse stock-backed item offers (Phase 5, design §8),
non-blueprint items, and the "Mein Inventar" boundary (design §11).

## Open questions

None — owner decisions of 2026-07-16 are recorded in `docs/DESIGN_ITEM_INVENTORY.md`
§10/§11.

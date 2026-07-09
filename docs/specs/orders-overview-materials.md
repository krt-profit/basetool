> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-09.
> **Owner area:** ORDERS · **Related ADRs:** none

# Order-overview Materialien column

## Context & goal

The order-overview list (Auftragsverwaltung, `GET /orders`) shows one row per job order. Its
**Materialien** column is meant to answer, at a glance, *what has to be gathered and how far
along the gathering is* — the same question for both order kinds.

For a `MATERIAL` order this has always been the order's material requirements with a
per-material collection progress (`currentStock / amount`). For an `ITEM` order the column
previously showed the **ordered items and their delivery count** instead — a different
question (how many finished items were handed over), which broke the parallel between the two
kinds and hid the procurement progress that the order's blueprint-derived materials actually
need. This spec makes the `ITEM` column show the **aggregated material list with material
collection progress**, exactly like a `MATERIAL` order.

The aggregated material list (one row per material+quality, summed across the ordered items'
snapshotted blueprint requirements) already exists for the item-order detail page
([`orders-item-blueprint-coverage.md`](orders-item-blueprint-coverage.md) is a neighbouring
item-order view); this spec adds the per-bucket stock that the overview progress needs.

## Requirements

### REQ-ORDERS-017 — Item-order overview shows aggregated materials with collection progress

The order-overview list's **Materialien** column MUST, for an `ITEM` order, render the order's
**aggregated material list** — the materials derived and summed from the ordered items'
blueprints, one row per material — with the same three columns a `MATERIAL` order shows:
material name, total required amount (unit-formatted by the material's `quantityType`), and a
**collection progress** percentage. It MUST NOT show the ordered items or their delivery
count. A `MATERIAL` order's column is unchanged.

The progress per row is `currentStock / totalQuantity`, clamped to 100 %, where
`currentStock` is the total of inventory **linked to that order** for the material at or above
the aggregated bucket's quality floor (`GOOD` → 650, `NONE` → no floor) — the same per-bucket
sum the `MATERIAL` requirement rows use (`InventoryItemRepository`
`sumAmountByMaterialAndJobOrderAndMinQuality`). `currentStock` is carried on
`AggregatedMaterialDto` and populated by the backend for every item order it returns (list and
detail); it is `0.0` when nothing is linked. An item order whose blueprints derived no
material renders the empty-materials placeholder.

**Acceptance**

- [ ] An item order's overview row shows its aggregated material names, required amounts, and a
  collection-progress percentage — not the ordered items or a delivered/ordered count.
- [ ] The progress equals `currentStock / totalQuantity` (100 % once stock meets the requirement).
- [ ] A material order's overview column is unchanged (per-material requirement + progress).
- [ ] An item order with no derived materials shows the empty-materials placeholder.

**Enforced by:** `JobOrderListRenderTest` (frontend render),
`JobOrderServiceTest` (UpdateItemJobOrderTests — aggregated stock enrichment) · **Code:**
`JobOrderService.enrichAggregatedWithClaims`, `JobOrderItemService.aggregateMaterials`,
`AggregatedMaterialDto.currentStock`, `templates/orders-index.html` · **Issues:** #595

### REQ-ORDERS-018 — Inventory may only be linked to an order that requires its material

Because the order's material view (the `MATERIAL` requirement rows and the `ITEM` aggregated
list, REQ-ORDERS-017) is built **solely from the order's requirements** with linked stock
matched onto those rows, an inventory item linked for a material the order does **not** require
has no row to surface under — it binds stock to the order while staying invisible everywhere in
the order.

Linking an inventory item to a job order — whether on create or via the in-place
association edit (the Lager "Auftrag" picker) — MUST be rejected with HTTP `400` when the item's
material is not one of the order's **required materials**. The required-material set is
kind-agnostic: for a `MATERIAL` order its material lines, for an `ITEM` order the materials
derived and snapshotted from the ordered items' blueprints (`JobOrderItemService.requiredMaterialIds`).
Every job-order picker that links stock to an order MUST hide an order that does not require the
material being linked, and MAY still list the order the row is **already** assigned to, so an
existing (possibly orphaned) link stays visible and clearable. This covers both the Lager
"Auftrag" picker and the refinery store ("Einlagern") dialog's per-item "Auftrag" picker, which
links the refined output material to an order that needs it. The picker filter MUST be correct
for `ITEM` orders too — these carry no `job_order_material` rows, so every picker keys on the
kind-agnostic `JobOrderReferenceDto.requiredMaterialIds` (served by `/api/v1/orders/lookup`)
rather than the MATERIAL-only `materials` list; a picker fed by the full order list and filtered
on `materials` would silently drop every `ITEM` order.

**Acceptance**

- [ ] Creating or updating an inventory item with a job-order link whose material the order does
  not require returns `400` and persists nothing.
- [ ] The same link succeeds when the order requires the material (both order kinds).
- [ ] The Lager "Auftrag" dropdown for a row offers only orders that require that row's material,
  plus the order the row is already assigned to.
- [ ] The refinery store dialog's "Auftrag" dropdown for an output material offers only orders
  that require that material (both order kinds, `ITEM` orders included).

**Enforced by:** `InventoryItemServiceTest` (create/update gate),
`JobOrderItemServiceTest` (`requiredMaterialIds`),
`InventoryPageControllerMvcTest` / `RefineryOrderStoreJobOrderDropdownTest` (picker filters) ·
**Code:** `InventoryItemService.createInventoryItem` / `updateInventoryItem`,
`JobOrderItemService.requiredMaterialIds`, `JobOrderReferenceDto.requiredMaterialIds`,
`JobOrderService.findAllActiveReference`, `templates/fragments/inventory-stack-entries.html`,
`RefineryOrderPageController.fetchActiveJobOrders`, `templates/refinery-orders-details.html`

### REQ-ORDERS-019 — Order detail surfaces orphaned linked inventory

To make pre-existing orphaned links (inventory linked before the REQ-ORDERS-018 gate, or via a
future path) discoverable, the order **detail** page MUST surface every inventory item linked to
the order whose material is **not** among the order's requirements, as a warning section listing
material, owner, location, quality and amount. The list is the order-linked inventory minus the
required-material set; it is empty (section hidden) when every linked item matches a requirement.
The link itself is undone from the Lager (setting the entry's "Auftrag" back to none). Computing
the warning MUST NOT add an N+1 to the order **list** endpoint — it is only resolved on the
detail view.

**Acceptance**

- [ ] An order with an inventory item linked for a non-required material shows the
  orphaned-inventory warning listing that item; an order with none shows no warning.
- [ ] The warning renders for both order kinds and is not computed for the order-list rows.

**Enforced by:** `JobOrderServiceTest`
(`getOrphanedLinkedInventoryReturnsOnlyLinksWhoseMaterialIsNotRequired`) · **Code:**
`JobOrderService.getOrphanedLinkedInventory`, `JobOrderController` `GET
/api/v1/orders/{id}/inventory/orphaned`, `JobOrderPageController.viewOrderDetail`,
`templates/orders-detail.html`

### REQ-ORDERS-020 — Order list is paginated server-side

The order-overview list (`GET /orders`) MUST fetch one **server-side page** of orders instead of
the former unbounded `size=1000` pull, rendering the shared pagination component (the `.pagination`
page-nav + the square `.page-btn` size picker from `fragments/pagination.html`, REQ-INV-013 /
REQ-API-005). The page sizes are **{50, 100, 200} with a default of 100** — a deliberate deviation
from the shared 10/50/100 / default-50 contract — because the LOGISTICIAN drag-and-drop priority
reorder operates on the rows of the **current page** (it derives the target priority from adjacent
visible rows and re-renders the whole results fragment), so the active queue must comfortably fit
on the first page in the common case. The default status filter (`OPEN`+`IN_PROGRESS`) keeps the
queue — the only draggable rows, since completed/rejected orders carry no priority — naturally
bounded. A crafted out-of-list `size` snaps back to the default; the sort stays `priority,asc` so
queue order is preserved across pages. Page and size links MUST keep the active status and
squadron-scope filter, and the pagination controls live **inside** the `ordersResults` AJAX-swap
fragment so a filter change re-renders them.

**Acceptance**

- [ ] A result spanning more than one page renders the page-nav and the 50/100/200 size picker; a
  short result (≤ the smallest size, single page) renders neither.
- [ ] Every page-nav and size-picker link carries the active `status` (repeatable) and `scope`
  params; changing the size jumps back to page 0.
- [ ] The drag-reorder still persists and re-renders within the current page (sort stays
  `priority,asc`).
- [ ] A `?size=` outside {50,100,200} falls back to 100; a negative `?page=` clamps to 0.

**Enforced by:** `JobOrderPaginationMvcTest`, `JobOrderPageCookieTest` (fetch URL) · **Code:**
`JobOrderPageController.viewOrders` / `buildPaginationBaseUrl`, `templates/orders-index.html`,
`templates/fragments/pagination.html` · **Issues:** #2 (performance audit)

### REQ-ORDERS-022 — Overview surfaces the responsible (processing) org unit

The order-overview list's first cell (the one carrying the order id and the `MATERIAL`/`ITEM`
kind badge) MUST also render the order's **responsible org unit** — the *processing* unit
("Bearbeitende Einheit", `JobOrderDto.responsibleOrgUnit`, the squadron or Spezialkommando
handling the job per REQ-ORG-003) — under the id and type, so the handling unit is visible at a
glance from the overview and not only on the detail page. It is rendered as a `squadron-badge`
(shorthand as text, full name as `title`) beneath a small caption label (`orders.index.responsible`,
DE "Bearbeitende Einheit" / EN "Responsible unit"), with the muted `squadron-badge-muted` em-dash
fallback when the unit is absent — the same badge idiom the **Auftraggeber** cell uses for
`requestingOrgUnit`. This is **not** a new table column; it stacks inside the existing first cell.
It stays distinct from the **Auftraggeber** (`requestingOrgUnit`, the *customer*) column, which is
unchanged. The data is already carried on every list row's `JobOrderDto`, so no controller/DTO or
backend change is needed and the order-list endpoint gains no query; the badge rides the
`ordersResults` AJAX-swap fragment, so it re-renders on filter/scope changes and drag-reorder too.

**Acceptance**

- [ ] Every overview row shows its responsible unit's shorthand (title = full name) under the id
  and kind badge; an order without a responsible unit shows the muted em-dash fallback.
- [ ] The caption uses the `orders.index.responsible` key (DE + EN + neutral fallback); no
  hard-coded label.
- [ ] The requesting-unit (Auftraggeber) column is unchanged and the two org units stay visually
  distinct.

**Enforced by:** `JobOrderListRenderTest` (frontend render — responsible-badge assertion) ·
**Code:** `templates/orders-index.html`, `JobOrderDto.responsibleOrgUnit`,
`.order-responsible-label` (`styles.css`), `orders.index.responsible` (i18n) · **Issues:** #1188

## Out of scope

- **Quality-floor gating on the link (REQ-ORDERS-018).** The gate and the orphaned-link check key
  on **material membership only**, not on the order's minimum quality. An inventory item below a
  required material's quality floor may still be linked — it simply contributes `0` to that bucket's
  stock and is **not** flagged as orphaned, because its material *is* required. This is intentional:
  the material view still shows the row, so the link is never invisible; the per-material picker
  (`getInventoryItemsForJobOrderMaterial`) continues to filter candidates by quality as a separate
  concern.
- The item-order **detail** page's aggregated panel — it keeps its material+quality rows and
  claim columns; this change only adds the stock the overview consumes and does not alter the
  detail rendering.
- The item-handover / delivery tracking (delivered vs. outstanding finished items) — still
  shown on the detail page's ordered-items table, just no longer surfaced in the overview.
- Claim columns on the overview — claims remain a detail-page concern.

## Open questions

None.

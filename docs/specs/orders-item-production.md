> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-15.
> **Owner area:** ORDERS/UI · **Related ADRs:** [ADR-0099](../adr/0099-job-order-item-production-booking.md)

# Item-order production booking (Herstellung) & order-detail tab layout

## Context & goal

An `ITEM` job order (Auftrag) requests finished items to be crafted. Until now a Bearbeiter
recorded only **delivery** (`deliveredAmount`) against each ordered line — there was no record of
how many units had actually been **manufactured**, and delivery could be booked without any link to
the stock the manufacture consumed. Operators needed a distinct "Herstellung" step: record that
`N` units of a line were built, and in the same action consume the exact linked inventory the
recipe required, so the order's earmarked stock (Variante C, [`inventory-lager.md`](inventory-lager.md)
`REQ-INV-027`) is drawn down as production happens rather than only at delivery.

The same rework restructured the (by now long) order-detail page onto the mission-detail
**tab layout** ([`mission-detail-tabs.md`](mission-detail-tabs.md) `REQ-MISSION-004`): a high-signal
KPI band above a `.tab-nav`, one `.tab-pane` per section, so a `MATERIAL` and an `ITEM` order each
show only the tabs that apply to it.

This spec governs both halves: the production-booking domain (`REQ-ORDERS-025`) and the tab/KPI
presentation (`REQ-ORDERS-026`). The decision behind the booking flow is
[ADR-0099](../adr/0099-job-order-item-production-booking.md).

## Requirements

### REQ-ORDERS-025 — Production booking (Herstellung) consumes linked stock and gates delivery

An `ITEM` order's line (`JobOrderItem`) MUST carry a **`manufacturedAmount`** (whole units already
manufactured) alongside its `deliveredAmount` and ordered `amount`, subject to the invariant

```
0 ≤ deliveredAmount ≤ manufacturedAmount ≤ amount
```

DB-enforced by three `CHECK` constraints (`chk_job_order_item_manufactured` ≥ 0,
`chk_job_order_item_manufactured_ge_delivered`, `chk_job_order_item_manufactured_le_amount`, V219).
The column is `NOT NULL DEFAULT 0` and legacy rows are backfilled `manufactured_amount =
delivered_amount` ("what was already handed over must have been made"), so every existing line
satisfies the invariant immediately.

**Delivery is gated by manufacture.** Item delivery (`JobOrderItemHandoverService`) caps the
deliverable quantity of a line at `manufacturedAmount − deliveredAmount`, not `amount −
deliveredAmount`: a unit can only be handed over once it has been produced. Attempting to deliver
more than the manufactured-but-undelivered quantity is rejected with HTTP 400.

The *Item-Übergaben* tab surfaces this to the operator. While the order is not yet fully delivered
(`isFullyDelivered` false) but nothing is manufactured-but-undelivered to hand over, it shows a hint
that no manufactured items are available for handover and points the operator at the Herstellung step
(now on the *Bestellte Items* tab). The "all items delivered" note shows only once every ordered unit
is delivered (`isFullyDelivered` — every line's `deliveredAmount ≥ amount`). Both empty states leave
`hasOutstandingItemLines` false, so that flag plus `isFullyDelivered` disambiguate them.

**Booking.** A production run is booked through
`POST /api/v1/orders/{id}/items/{itemId}/production`
(`JobOrderItemProductionService.bookProduction`), gated `(hasRole('LOGISTICIAN') or
hasRole('OFFICER') or hasRole('ADMIN')) and @ownerScopeService.canEditJobOrder(#id)` — the same
authorisation as the item handovers. The payload is `JobOrderItemProductionCreateDto` (`amount` ≥ 1,
the line `version`, a `consumption` list of `JobOrderItemProductionConsumptionDto`, each naming an
`inventoryItemId`, the `materialId` it holds, a positive `amount`, and the entry `version`, and an
optional `skippedMaterialIds` — the required materials the operator marked "nicht ausbuchen" and does
not want booked out of stock). The service:

- Loads the order and **rejects a non-`ITEM` order** with HTTP 400 (`BadRequestException`, "not an
  item order").
- Resolves the line and enforces the line's optimistic lock via
  `OptimisticLock.checkRequired(line.version, dto.version, …)` — a stale **or absent** client
  version is an `ObjectOptimisticLockingFailureException` → HTTP **409** (code `OPTIMISTIC_LOCK`).
- **Rejects `amount > (amount − manufacturedAmount)`** — producing more than the line still needs —
  with `ProductionAllocationException` → HTTP **422** (code `PRODUCTION_ALLOCATION`).
- Computes the **required per-material demand** for `amount` units from the line's **snapshotted
  recipe** (`JobOrderItemMaterial`, the same snapshot that feeds the aggregated-materials view):
  `demand = roundForQuantityType(requiredQuantity × amount / lineAmount)` per material, summed per
  material id. A material listed in **`skippedMaterialIds`** ("nicht ausbuchen") is **excluded from
  the demand map**, so it is neither required to be covered nor may be named by a `consumption`
  entry, and none of its linked stock is touched. The `consumption` plan MUST **exactly cover** every
  remaining (non-skipped) required material's demand (equal within an SCU floating-point epsilon) and
  MUST NOT name a material the line does not require or has skipped; either mismatch is a
  `ProductionAllocationException` → HTTP **422**.
- For **each consumption entry**, under a pessimistic write lock
  (`inventoryItemRepository.findByIdForUpdate`): checks the entry's own optimistic lock
  (`OptimisticLock.check`, stale → 409); requires the entry to be **earmarked to this order**
  (`InventoryAllocations.jobOrderSlice`, else `IllegalStateException` "does not belong" → 400);
  requires the entry to hold the claimed material (else 400); requires the consumed amount to be
  positive and a whole number for `PIECE` materials (else 400); and **caps the consumed amount at
  the order's own earmark slice and the entry's stock** (over-draw → `ProductionAllocationException`
  → 422) — production draws only from this order's slice, never a sibling order's slice or the free
  rest.
- **Reduces the entry**: deletes the row when depleted, otherwise decrements
  `InventoryItem.amount`, shrinks this order's earmark slice (`InventoryAllocations.reduceJobOrder`)
  **and** auto-clamps the entry's mission earmarks by the same consumed amount
  (`AllocationReductions`, rest-first then proportional) so the Variante-C per-dimension invariant
  (`REQ-INV-027` R5) holds without a 422; a Materialbörse offer backed by the entry is re-clamped to
  the new stock.
- Advances `manufacturedAmount` by `amount` via Hibernate dirty checking (no explicit `save`, single
  `@Version` bump) and flushes so the returned DTO carries the advanced line version.

Because the manufactured counter advances on **every** booking — whether the material was booked out
of stock or marked skipped — the **aggregated-materials demand shrinks with production**:
`JobOrderItemService.aggregateMaterials` sums only the material for the units **not yet
manufactured** per line (`requiredQuantity × (amount − manufacturedAmount) / amount` per material,
rounded per bucket), so the *Aggregierte Materialien* view and the item-order *Offene Menge* KPI
(derived from `totalQuantity`) drop by the manufactured portion and reach 0 once a bucket is fully
manufactured (the row is kept so its quality bucket and claims stay visible). This is what makes a
skipped material's demand actually disappear even though its stock was never drawn down. The
squadron **material-claim** targets (`MaterialClaimService.requiredByBucket`, the Eintragungen supply
sign-up) intentionally stay on the **full** order requirement — a supply commitment is against the
whole order, independent of how far production has progressed.
- **Audits**: one `JOB_ORDER_PRODUCTION_BOOKED` (domain `JOB_ORDER`) for the booking — its details
recording the manufactured `amount`, the number of `consumed` entries and the count of `skipped`
(not-booked-out) materials — plus one `INVENTORY_CONSUMED_BY_PRODUCTION` (domain `INVENTORY`) per
consumed entry, from snapshots captured before the writes; no user free text / no PII in the details
payload (`REQ-AUDIT-001`). A skipped material produces no `INVENTORY_CONSUMED_BY_PRODUCTION` event.
- Returns the refreshed ordered-item-line DTO (advanced `manufacturedAmount` + version).

Aufträge and Mein Inventar are **audited areas**, so both event types are recorded, added to the
unified viewer's per-area filter, and carried in the DE/EN i18n labels (`REQ-AUDIT-001`,
[`audit.md`](audit.md)).

**Frontend.** The Herstellung modal renders one reconciliation card per required material; each card
carries a **"Nicht ausbuchen" checkbox** (`data-prod-skip`). Ticking it flags the card, disables that
material's stock inputs, drops the material from the "buchen" coverage gate, and adds its id to the
posted `skippedMaterialIds` (no `consumption` is sent for it) — so the operator can record production
while leaving a material's linked stock untouched. The relay `JobOrderWriteController.bookProductionAjax`
(`POST /orders/{id}/items/{itemId}/production`, consumes JSON) forwards the payload to the backend,
re-fetches the order, and returns it so `orders-detail.js` re-renders the affected sections in place
(no reload, `REQ-FE-001`). A `BackendServiceException` is relayed **verbatim** via
`propagateBackendError`, preserving the RFC 7807 `code` so `krt-fetch.js` keeps its reload-vs-toast
distinction — a **409** (`OPTIMISTIC_LOCK`) drives the optimistic-lock reload-confirm, a **422**
(`PRODUCTION_ALLOCATION`) an inline toast. A successful booking re-renders and **broadcasts** the
`production` and `kpi` sections to peers viewing the same order (`REQ-FE-010` / `REQ-FE-015`); both
keys are present at all three live-sync mirror points — the acting client's `ORDER_SECTIONS` seam
map, the server relay's `ORDER_DETAIL` broadcastable set (`LiveSyncTopicClass`), and the receiver's
apply map.

**Acceptance**

- [ ] Booking `N` units advances `manufacturedAmount` by `N`, and delivery of that line can then be
  booked up to `manufacturedAmount − deliveredAmount` but no further (400 beyond it).
- [ ] A booking whose `amount` exceeds `amount − manufacturedAmount` is rejected with 422
  (`PRODUCTION_ALLOCATION`).
- [ ] A consumption plan that under- or over-covers any required material, or names a material the
  line does not require, is rejected with 422.
- [ ] A material listed in `skippedMaterialIds` ("nicht ausbuchen") advances `manufacturedAmount`
  without any coverage requirement and without touching its linked stock (no
  `INVENTORY_CONSUMED_BY_PRODUCTION` event); naming a skipped material in `consumption` is rejected
  with 422.
- [ ] The aggregated-materials demand (and the item-order *Offene Menge* KPI) reflects only the
  not-yet-manufactured units: booking `N` units reduces each line's contribution to `requiredQuantity
  × (amount − manufacturedAmount) / amount`, reaching 0 for a fully manufactured line — regardless of
  whether the material was booked out or skipped. The material-claim (Eintragungen) target stays on
  the full order requirement.
- [ ] A consumption entry not earmarked to the order, or holding a different material, or drawing
  more than the order's slice/stock, is rejected (400 / 422 respectively); a whole depletion deletes
  the row and a partial draw decrements amount + order slice and clamps the mission earmarks.
- [ ] A stale line version, or a stale version on any consumed entry, is rejected with 409
  (`OPTIMISTIC_LOCK`); the refreshed line DTO carries the advanced version.
- [ ] A non-`ITEM` order is rejected with 400.
- [ ] A booking records one `JOB_ORDER_PRODUCTION_BOOKED` and one
  `INVENTORY_CONSUMED_BY_PRODUCTION` per consumed entry, none carrying free text.
- [ ] The endpoint is reachable only by a LOGISTICIAN/OFFICER/ADMIN with `canEditJobOrder`; the
  success response re-renders the `production` + `kpi` sections in place and propagates them to a
  peer without a reload.

**Enforced by:** `JobOrderItemProductionServiceTest` (amount/coverage/slice caps, delete-on-depletion,
mission auto-clamp, 409/422 mapping, audit, skipped/not-booked-out material),
`JobOrderItemServiceTest` (aggregate reduced by manufactured units, 0 for a fully manufactured line),
`JobOrderControllerTest` (`bookProduction` auth +
mapping), `JobOrderItemHandoverServiceTest` (delivery capped at manufactured-but-undelivered),
`V219MigrationTest` (backfill + CHECK constraints) · **Code:**
`JobOrderItemProductionService.bookProduction`, `JobOrderController.bookProduction`,
`JobOrderItemHandoverService.createItemHandover`, `JobOrderItemProductionCreateDto` /
`JobOrderItemProductionConsumptionDto`, `ProductionAllocationException`, `AuditEventType`
(`JOB_ORDER_PRODUCTION_BOOKED` / `INVENTORY_CONSUMED_BY_PRODUCTION`),
`JobOrderWriteController.bookProductionAjax`, migration `V219` · **Issues:** #1182 · **ADR:**
ADR-0099

### REQ-ORDERS-026 — Order-detail tab layout and KPI band

The order-detail page (`/orders/{id}`) MUST render, below the read-only **overview** header (kept
unchanged), a high-signal **KPI band** followed by a `.tab-nav` with **one `.tab-pane` per section**,
adopting the mission-detail tab pattern (`REQ-MISSION-004`).

**KPI band.** A `.kpi-band` of KPI tiles whose set depends on the order type:

- **`MATERIAL`** — *Materialien erfüllt* (`fulfilled / total` with a mini progress bar), *Offene
  Menge*, the claims tile (see below), and *Übergaben* (handover count).
- **`ITEM`** — *Items geliefert* (`delivered / amount` with a mini progress bar), *Offene Menge*
  (material), the claims tile, and *Übergaben*.

The **claims KPI tile is shown only for SK-public orders** — the tile renders only when the order
supports material claims (`kpi.supportsClaims`), i.e. a public Spezialkommando order that can carry
Eintragungen ([`orders-material-claims.md`](orders-material-claims.md)); a strict-staffel order that
cannot be claimed omits the tile entirely. The KPI band is its own AJAX-swappable fragment
(`kpiSection`, container `#order-kpi-results`, live-sync key `kpi`) so a production booking, a claim,
a delivery or an assignee change refreshes the KPIs in place.

**Tabs.** Each tab and its pane carry the **same condition**, so a tab appears only when its pane
exists. The tab sets:

- **`MATERIAL`** — *Materialien*, *Bearbeiter* (unless requester-redacted view), *Übergaben*, and
  *Verknüpft* (only when orphaned linked inventory is present).
- **`ITEM`** — *Bestellte Items* (which, for a LOGISTICIAN+ editor, also carries the production
  booking surface of `REQ-ORDERS-025` — see below; there is no separate *Herstellung* tab),
  *Aggregierte Materialien* (unless requester view), *Blaupausen* (only when the caller may see the
  blueprint-coverage view, `REQ-ORDERS-016`), *Bearbeiter* (unless requester view), *Item-Übergaben*,
  and *Verknüpft* (as above).

**Tab behaviour.** Tabs use the WAI-ARIA tabs pattern (`role="tablist"/"tab"/"tabpanel"`,
`aria-selected`, `aria-controls`, roving `tabindex`). The active tab resolves from a **`?tab=`** URL
parameter (falling back to `#tab=`, then the last tab from `localStorage`, then the first present
tab). `ArrowLeft`/`ArrowRight` move focus and selection along the tablist; selecting a tab pushes the
`?tab=` state so browser back/forward re-applies it (`popstate`). Switching tabs only toggles the
active pane's visibility — every pane is server-rendered, so no data is fetched on a tab switch. The
tab counts (`.tab-count`) reflect each section's item count.

This is a **presentation restructure**: every backend contract, DTO, optimistic-lock version and
permission gate of the previous panel layout is preserved; the redacted requester view
(`REQ-ORDERS-023`) keeps hiding the same sections.

The **production booking surface folds into the *Bestellte Items* tab** for LOGISTICIAN+ editors —
there is no separate *Herstellung* tab. Each item line carries a leading chevron that reveals the
per-unit material demand (*Bedarf je Stück*) in a collapsible sub-row (hidden by default, mirroring
the bank request table's Notiz/Begründung detail row, so a multi-material recipe no longer widens the
row) and, as the last column, a *Herstellung erfassen* button that opens the booking modal.
Manufactured and delivered each render a progress bar. The machine-readable per-material demand the
modal consumes stays on the main row, so booking is unaffected; a booking re-renders the *Bestellte
Items* section rather than a separate production section. Requesters/read-only viewers see the plain
status columns without the chevron, demand sub-row or button.

**Acceptance**

- [ ] A `MATERIAL` order shows the material KPI tiles and the material tab set; an `ITEM` order shows
  the item KPI tiles and the item tab set.
- [ ] The claims KPI tile renders only for an SK-public order (claims supported); a strict-staffel
  order omits it.
- [ ] The *Bestellte Items* tab shows the chevron/demand sub-row and the *Herstellung erfassen*
  button only for a LOGISTICIAN+ editor of an `ITEM` order; a read-only or requester viewer sees only
  the plain status columns (no separate *Herstellung* tab exists).
- [ ] `?tab=<key>` selects that tab on load and is honoured over `#tab=`, `localStorage`, and the
  default; back/forward re-applies the tab.
- [ ] Arrow-key navigation moves selection along the tablist; `aria-selected` tracks the active tab.
- [ ] Requester-redacted views omit the same tabs (Bearbeiter, Aggregierte Materialien, Übergaben)
  they omitted before.
- [ ] On the *Bestellte Items* tab a line's per-unit demand is hidden behind a chevron and revealed
  in a sub-row on click; both Hergestellt and Geliefert render a progress bar; booking a production
  run from that tab still works (its machine-readable demand stays on the main row).
- [ ] The Item-Übergaben tab shows the "record production first" hint while the order is not fully
  delivered and nothing is manufactured-but-undelivered, and the "all delivered" note only once every
  ordered unit is delivered (`isFullyDelivered`).

**Enforced by:** `JobOrderItemDetailRenderTest` (item tab set + KPI tiles; the folded-in Herstellung
button and per-unit-demand chevron in the items table; the `isFullyDelivered` item-handover message
split; no separate production tab), `JobOrderListRenderTest`, `JobOrderPageControllerNoReloadMvcTest`
(no-reload section swaps, the production-booking relay) and `JobOrderProductionE2eTest` (the full UI
booking flow + the produce-first hint) ·
**Code:** `orders-detail.html` (`kpiSection` fragment, `.tab-nav` + `.tab-panes`), `orders-detail.js`
(tab controller, `ORDER_SECTIONS` seam map), `JobOrderPageController` · **Issues:** #1182

## Out of scope

- Editing the ordered recipe / blueprint from the production modal — the recipe is the line's
  snapshot; production consumes against it and never rewrites it.
- Moving inventory between orders — production only draws down **this** order's earmark on an entry
  (never a sibling order's slice or the free rest); reassigning earmarks is the Variante-C allocation
  flow (`REQ-INV-027`).
- A production step for `MATERIAL` orders — they request raw materials, have no per-item recipe, and
  carry no `manufacturedAmount`.
- Un-booking / correcting a production run — a booking is append-only against `manufacturedAmount`;
  correction is out of scope for this iteration.

## Open questions

None.

> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-16.
> **Owner area:** ORDERS/UI · **Related ADRs:** [ADR-0099](../adr/0099-job-order-item-production-booking.md),
> [ADR-0101](../adr/0101-inventory-game-item-rows.md) (production book-in)

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

This spec governs both halves — the production-booking domain (`REQ-ORDERS-025`) and the tab/KPI
presentation (`REQ-ORDERS-026`) — plus the order-detail **Item-Bestand panel** that surfaces the
item stock earmarked to the order (`REQ-ORDERS-028`, the item sibling of the Materialsammlung) and
the **owner/location redaction** that hides the fulfilling side's inventory owner and Standort from a
requesting-side viewer of an SK-public order (`REQ-ORDERS-029`, ADR-0107). The decision behind the
booking flow is [ADR-0099](../adr/0099-job-order-item-production-booking.md).

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
more than the manufactured-but-undelivered quantity is rejected with HTTP 400. The same delivery now
also **consumes the order's earmarked item stock** for the delivered game items, best-effort — see
`REQ-ORDERS-030` — so the phantom stock a delivery would otherwise leave behind disappears, while a
legacy line manufactured before item stock existed still delivers unblocked.

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

**Book-in (REQ-INV-032, ADR-0101).** A production booking now **also books the produced units
into the Lager** as game-item stock ([`inventory-items.md`](inventory-items.md)): the request's
`bookIn` block names the location, the owner user (default: actor), the owning org unit
(create-on-behalf stamping semantics) and the personal flag, and auto-earmarks the produced
units to the producing order by default (`allocateToOrder`, deselectable; mutually exclusive
with `personal = true` → 400). The book-in runs in the same transaction as the consumption and
is audited as `INVENTORY_RECEIVED_FROM_PRODUCTION`. **Rollout:** `bookIn` is
transitional-optional at the API level (`null` ⇒ the legacy no-stock behaviour above) until the
frontend's production modal ships it, then becomes required. The full contract lives in
REQ-INV-032.

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
- [ ] A booking with a `bookIn` block creates (or merges into) the matching game-item stack,
  earmarked to the order unless deselected, audited as `INVENTORY_RECEIVED_FROM_PRODUCTION` in
  the same transaction (REQ-INV-032); `bookIn == null` preserves the legacy counter-only
  behaviour during rollout.
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

### REQ-ORDERS-028 — Order detail surfaces the earmarked item stock (Item-Bestand panel)

An `ITEM` order's detail page MUST surface the **game-item stock earmarked to the order**
([`inventory-items.md`](inventory-items.md) REQ-INV-029/031) — the item sibling of the material
Materialsammlung — as an **Item-Bestand panel** on the *Bestellte Items* tab. The panel groups the
order's linked game-item rows **per game item** (name-sorted): each group shows the item (name +
manufacturer) and a context chip with the order's own progress — the whole-unit sum of the
earmarked slices plus `manufacturedAmount`/`orderedAmount` from the order's matching lines
(REQ-ORDERS-025; both `0` for an orphaned earmark, which REQ-ORDERS-019 keeps flagging on the
*Verknüpft* tab). Each entry row shows owner, location, the **THIS-order earmark slice** in whole
units (with the entry's total stock as context when the row is only partially earmarked), and a
**delivered toggle** for the per-(entry, order) delivered marker (Variante C, REQ-INV-027).

**Backend.** `GET /api/v1/orders/{id}/item-stock` (`JobOrderItemStockController`) returns the
grouped shape (`JobOrderItemStockGroupDto` → `JobOrderItemStockEntryDto`), gated exactly like the
sibling per-order stock reads: `isAuthenticated() and @ownerScopeService.canSeeJobOrder(#id)`; an
unknown order is 404. The projection reuses the entity-graphed
`InventoryItemRepository.findGameItemRowsByJobOrderIdOrdered` (owner/location display order kept
inside each group) and reads each entry's this-order slice off the `@BatchSize`-batched allocation
collection — no N+1. The entry `version` is the row's `@Version` (0 for a still-`null` persisted
version), which the delivered toggle echoes.

**Delivered toggle.** The toggle PATCHes the existing `/inventory/{id}/delivered` (entry id +
`jobOrderId` + version echo — the same write the Materialsammlung uses, backend gate
`canEditInventoryItem`, stale version → 409). On success the panel's whole `item-stock` section is
re-rendered in place through the order seam, refreshing **every** row's `data-version` (the DOM
version sync rule), and the section is broadcast to peers; on failure the checkbox reverts.

**Live update & peer sync (REQ-FE-001/010/015).** The panel is an AJAX-swappable fragment
(`itemStockSection`, container `#order-item-stock-results`) with the **new `order:{id}` section key
`item-stock`**, registered at all three mirror points in the same change: the `ORDER_SECTIONS` seam
map in `orders-detail.js` (drives both the broadcast and the receive-side apply), the relay's
`LiveSyncTopicClass.ORDER` whitelist, and — because receivers derive their container map from the
same seam map — the receiving apply path; `LiveSyncSectionMapParityTest` pins the set-equality. The
panel refreshes live on: a peer's delivered flip (`item-stock` broadcast), a production booking
(its success refresh list gains `item-stock` — the book-in auto-earmarks the produced units), an item
handover that consumes the earmark (`REQ-ORDERS-030` — its success path likewise re-renders and
broadcasts `item-stock` and pokes `inventory`/`stock`), and a Lager-side earmark change
(`inventory-my.js` / `inventory-admin.js` `broadcastOrdersChanged` now sends
`materials`/`aggregated`/`item-stock` to each affected `order:{id}` room; a section whose container a
page does not render is silently skipped).

**Redaction.** The panel renders only for an `ITEM` order and is omitted from the
requester-redacted view (REQ-ORDERS-023), mirroring the *Aggregierte Materialien* pane. A
requester-only viewer (`redacted == true`) additionally cannot reach the endpoint at all — it is
gated on `canSeeJobOrder`, which a requester-only viewer fails (403). For a caller who *can* see the
order but is on the **requesting** side of an **SK-public** order, the per-entry owner and location
are blanked by the backend (REQ-ORDERS-029, ADR-0107) — the panel still renders the amounts and the
delivered marker, with owner/location shown as `—`. An order with no earmarked item stock renders an
empty-state message.

**Acceptance**

- [ ] An `ITEM` order with game-item rows earmarked to it lists them on the *Bestellte Items* tab,
  grouped per game item with the allocated/manufactured/ordered context chip; a `MATERIAL` order
  renders no panel (and the frontend never fetches the endpoint for it); an `ITEM` order without
  earmarks shows the empty state.
- [ ] Each entry row shows the THIS-order slice (whole units) — never a sibling order's slice —
  plus the entry total as context on a partial earmark, and the delivered checkbox reflects the
  per-(entry, order) marker.
- [ ] Flipping the delivered toggle persists via `PATCH /inventory/{id}/delivered` with the order
  id + entry version, re-renders the section in place (fresh `data-version` on every row, no
  reload) and reaches a peer viewing the same order; a failed write reverts the checkbox.
- [ ] A production booking with auto-earmark makes the produced stock appear in the panel without
  a reload (own view and peers); a Lager-side (un)earmark of an item row refreshes peers' panels.
- [ ] `GET /api/v1/orders/{id}/item-stock` is reachable only by a caller who can see the order
  (403 otherwise, 404 for an unknown order).
- [ ] The `item-stock` key is present at the seam map **and** the relay whitelist
  (`LiveSyncSectionMapParityTest` stays green).

**Enforced by:** `JobOrderItemStockControllerTest` + `InventoryItemServiceTest`
(`getItemStockForJobOrder` grouping / slice / delivered / orphan context / 404),
`JobOrderItemDetailRenderTest` (panel + empty state + fragment swap + MATERIAL-order absence),
`LiveSyncSectionMapParityTest`, `JobOrderProductionE2eTest` (panel populated after a booked
production with auto-earmark, then the delivered toggle flipped and its marker persisted)
· **Code:** `JobOrderItemStockController`,
`InventoryAggregationService.getItemStockForJobOrder`, `JobOrderItemStockGroupDto` /
`JobOrderItemStockEntryDto`, `JobOrderPageController.viewOrderDetail` (`itemStockSection`
dispatch), `orders-detail.html` (`itemStockSection` fragment), `orders-detail.js`
(`ORDER_SECTIONS['item-stock']`, `onItemStockDeliveredToggle`), `LiveSyncTopicClass.ORDER`,
`inventory-my.js` / `inventory-admin.js` (`broadcastOrdersChanged`) · **Issues:** — · **Design:**
[`DESIGN_ITEM_INVENTORY.md`](../DESIGN_ITEM_INVENTORY.md) §10 PR 4 / §11.2

### REQ-ORDERS-029 — Requesting-side viewers see redacted inventory owner/location

The four per-order reads that expose the **owner identity and location** of the stock linked to a
job order — the Item-Bestand panel (`GET /orders/{id}/item-stock`, REQ-ORDERS-028), the material
collection (`GET /orders/{id}/material-collection`), and the two inventory pickers
(`GET /orders/{id}/materials/{matId}/inventory`, `GET /orders/{id}/inventory/orphaned`) — MUST blank
the per-entry owner and location for a caller who can see the order but is **not entitled to its
responsible (processing) side**. Concretely: on a Spezialkommando-responsible (SK-public) order,
`canSeeJobOrder` admits every profit-eligible member — including members of the merely **requesting**
squadron — but the fulfilling side's owner/Standort must not leak to the requesting side (owner
decision 2026-07-17, ADR-0107).

**Gate.** `OwnerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)` — identical to
`canSeeJobOrderBlueprintOwners`: membership of the order's responsible org unit (or an admin with
matching scope), **no SK-public escape**. For a squadron-responsible order it coincides with
`canSeeJobOrder`, so the redaction only ever engages on the SK-public path; a `null` responsible unit
or unknown order returns `false` (redact by default).

**Redactor.** When the gate is `false` the endpoint passes its projection through
`JobOrderInventoryOwnerRedactor`, which blanks **only** the owner (`ownerName`/`ownerId`, and the
nested `user` + `owningSquadron` on the picker `InventoryItemDto`) and the location
(`location`/`locationId`) — amounts, quality, the delivered marker and the ordered/manufactured
context are always kept, so the requesting side still sees the order's progress. The redactor
reconstructs each record field-by-field (never a wither) so a newly added owner/location field is a
compile error until it is classified (mirrors `MissionGuestRedactor`, REQ-SEC-007). The frontend
renders a blanked owner/location as `—` (item-stock panel, material-collection page); the JS
drill-down / orphaned / Herstellung surfaces already fall back to a dash.

A requester-**only** viewer (`redacted == true`, i.e. `!canSeeJobOrder`) is refused all four
endpoints with `403` and never reaches the redactor at all.

**Accepted trade-off.** A cross-unit LOGISTICIAN processing an SK-public order (requesting-squadron
member, not in the responsible SK) also sees `—` for owner/location in the pickers. Nothing breaks
functionally (production books by `inventoryItemId` + slice + version, never by owner/location); it
is deliberately kept uniform rather than widening the picker gate to `… or canEditJobOrder`.

**Acceptance**

- [ ] On an SK-public order, a member of only the requesting squadron gets owner/location blanked on
  all four endpoints (amounts/delivered kept); a member of the responsible SK and an admin see them.
- [ ] A squadron-responsible order is unaffected — every viewer who passes `canSeeJobOrder` sees
  owner/location.
- [ ] A requester-only viewer (`redacted`) gets `403` on all four endpoints.
- [ ] The redacted item-stock panel / material-collection page render `—` for owner/location.

**Enforced by:** `OwnerScopeServiceTest.CanSeeJobOrderInventoryOwnersTests` (the gate — squadron /
SK-member / requesting-side / admin / unknown), `JobOrderInventoryOwnerRedactorTest` (the three
redaction passes blank owner/location, keep the rest, null-safe), `JobOrderItemStockControllerTest`
+ `MaterialCollectionControllerTest` + `JobOrderControllerTest` (redaction wiring: unredacted when
entitled, redactor output otherwise) · **Code:**
`AccessGateService.canSeeJobOrderInventoryOwners` + `OwnerScopeService` facade,
`JobOrderInventoryOwnerRedactor`, `JobOrderItemStockController` / `MaterialCollectionController` /
`JobOrderController` (the two pickers), `orders-detail.html` + `material-collection.html` (`—`
fallbacks) · **Issues:** — · **ADR:** [ADR-0107](../adr/0107-job-order-inventory-owner-redaction.md)

### REQ-ORDERS-030 — Item delivery consumes the order's earmarked item stock (best-effort)

An `ITEM` order's item handover (delivery, `JobOrderItemHandoverService.createItemHandover`) MUST, in
the same transaction that advances each line's `deliveredAmount`, **consume the order's earmarked item
stock** for the delivered game items — drawing `min(handed-over units, the whole-unit sum of the
order's earmark slices on that game item)` out of the Lager, oldest-first (`createdAt`, `id`
tiebreak). This removes the phantom item stock a delivery would otherwise leave behind: in the normal
flow a produced line is booked in as stock earmarked to the order (`REQ-INV-032`,
[`inventory-items.md`](inventory-items.md)) and the matching delivery draws that same stock back down
to zero.

**Best-effort, never blocking.** The consumption never fails the handover. When the earmarked stock is
smaller than the handed amount — a **legacy line** manufactured before item stock existed
(`manufacturedAmount > 0` with no earmark, backfilled `manufactured := delivered`, `REQ-ORDERS-025`),
or a line whose earmark covers only part of the delivery — the available stock is consumed and the
shortfall is delivered anyway. A stock shortfall is a silent no-op, **not** a `400`/`422`;
`BadRequestException` stays reserved for genuine bad input (non-item order, foreign line, over-delivery
beyond `manufacturedAmount − deliveredAmount`). The delivery ceiling stays gated by
`manufacturedAmount` (`REQ-ORDERS-025`), independent of how much stock backs it.

**Consumption rules** (per game-item row, oldest-first under a `FOR UPDATE` lock):

- Draws only **this order's own earmark slice** on the row (`InventoryAllocations.jobOrderSlice`),
  never a sibling `ITEM` order's slice or the unallocated rest — capped at the slice amount (Variante C
  R5, `REQ-INV-027`). Because the slice ≤ the row amount, the physical remainder never goes negative.
- Shrinks that slice (`InventoryAllocations.reduceJobOrder`) and the row's `amount` by the drawn
  units; a **depleted** row is deleted (book-out depletion convention), a partially drawn row keeps its
  reduced amount and this-order slice.
- Item rows carry **no mission dimension** (`REQ-INV-031`), so there is no mission earmark to clamp.
- Audited as one **`INVENTORY_HANDED_OVER`** (domain `INVENTORY`, `source = ITEM_HANDOVER`) per
  consumed row — the **same** cross-domain event the material handover reuses (`REQ-AUDIT-001`,
  [`audit.md`](audit.md)); no new `AuditEventType`, viewer filter or i18n label is added. Details carry
  the order `#displayId`, the game-item name, the consumed/remaining amounts and the depletion flag —
  no user free text / no PII.
- A **non-depleted** consumed row that still exists ratchets any active **stock-backed Materialbörse
  item offer** on it down to its reduced whole-unit stock (`clampItemQuantityToStock`,
  `REQ-MARKET-013/014`, ADR-0108) — the item-offer sibling of the material handover's
  `clampOfferedAmountToStock`; a **depleted** row's offer is cascade-removed with the row (V210 `ON
  DELETE CASCADE`), so it needs no clamp. This is the item-delivery decrement site of REQ-MARKET-013.

**Concurrency (CLAUDE.md landmines).** The flow issues **no** `@Modifying(clearAutomatically = true)`
bulk update, so the persistence context is never detached: the consumed game-item rows are
mutated/deleted while managed (none of them part of the `JobOrder` aggregate), the still-managed order
drives the completion check, and completion runs through `completeJobOrderWithinTransaction` — a single
`@Version` bump, no re-fetch, no 409 even when one delivery consumes several rows and completes the
order. Consumed rows are locked `FOR UPDATE` oldest-first
(`findGameItemRowsByJobOrderAndGameItemForUpdate`) so two racing deliveries against one earmark pool
serialise. The per-consumed-row snapshot list is collected in the loop; after the writes (handover
save + completion) the audit **and** the stock-backed item-offer ratchet run once over it —
`clampItemQuantityToStock` for each non-depleted row (REQ-MARKET-013/014, ADR-0108) — the same
collect-then-run shape as the material handover's `clampOfferedAmountToStock`.

**Live update & peer sync (`REQ-FE-001/010/015`).** A successful item handover re-renders the `items` /
`item-handovers` / `item-handover-lines` / `header` sections **and** the `item-stock` panel
(`REQ-ORDERS-028`) in place on `order:{id}`, and pokes the global `inventory`/`stock` seam so Lager
viewers see the drawn-down stock — mirroring the production-booking success path. All keys pre-exist at
the three live-sync mirror points; no seam-map change.

**Rationale / no ADR.** This is a behaviour refinement of an already-decided feature (owner decision
2026-07-16, [`DESIGN_ITEM_INVENTORY.md`](../DESIGN_ITEM_INVENTORY.md) §10 Phase 6 / §11.1), building on
the item-stock model (ADR-0101) and production booking (ADR-0099); it introduces no new architectural
choice, so the rationale is captured in this requirement rather than in a separate ADR.

**Acceptance**

- [ ] Delivering `N` units of a line whose earmarked stock is ≥ `N` draws exactly `N` out of the
  order's earmark oldest-first; a fully drained row is deleted, a partially drawn row keeps its reduced
  amount and this-order slice.
- [ ] A legacy line (`manufacturedAmount > 0`, no earmarked stock) still delivers; nothing is consumed
  and no `INVENTORY_HANDED_OVER` is emitted.
- [ ] A line whose earmark is smaller than the delivery consumes the available stock and delivers the
  rest anyway (never a 400/422 for the shortfall).
- [ ] Consumption draws only this order's slice, never a sibling order's; the delivery ceiling stays
  `manufacturedAmount − deliveredAmount`.
- [ ] A delivery that consumes several earmarked rows and completes the order bumps the order
  `@Version` once (no 409); each consumed row records one `INVENTORY_HANDED_OVER`.
- [ ] The success response re-renders the ordered-items / handover / `item-stock` sections in place and
  reaches a peer (and Lager viewers) without a reload.
- [ ] A non-depleted consumed row backing an active stock-backed Materialbörse item offer ratchets
  that offer's `itemQuantity` down to the row's reduced stock; a depleted row's offer is
  cascade-removed with the row.

**Enforced by:** `JobOrderItemHandoverServiceTest` (consume-and-delete, partial draw, this-order-slice
cap with free rest / sibling order untouched, per-game-item aggregation, legacy best-effort,
partial-stock best-effort, multi-row oldest-first, complete-without-refetch, item-offer ratchet on a
reduced row / no clamp on a depleted row, audit), `JobOrderItemHandoverE2eTest` (item stock drops
after a UI handover) · **Code:** `JobOrderItemHandoverService.createItemHandover` /
`consumeEarmarkedItemStock`,
`InventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate`,
`InventoryAllocations.reduceJobOrder`, `MaterialExchangeOfferRepository.clampItemQuantityToStock`,
`AuditEventType.INVENTORY_HANDED_OVER`, `orders-detail.js`
(item-handover success `item-stock` + `inventory`/`stock` broadcast) · **Issues:** — · **Design:**
[`DESIGN_ITEM_INVENTORY.md`](../DESIGN_ITEM_INVENTORY.md) §10 Phase 6 / §11.1

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

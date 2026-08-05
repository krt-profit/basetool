> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-08-04.
> **Owner area:** ORDERS · **Related ADRs:** none

# Cross-order material demand

## Context & goal

The order area answers "what does *this* order need" well: every overview row carries its material
list with a per-material collection progress ([`orders-overview-materials.md`](orders-overview-materials.md),
REQ-ORDERS-017), and the per-order Materialsammlung (`/orders/{id}/material-collection`) lets a user
work one order's materials.

What was missing is the **cross-order** question a logistician actually asks before a gathering run:
*across all orders that are still open or in progress, how much of each material do we need in
total, and how much of that is already covered?* Answering it meant opening every order and adding
up by hand — and the answer went stale as soon as anyone booked stock in.

This spec adds a dedicated read-only page that folds the order material lists across **all**
non-terminal orders the caller may see into one row per material bucket, split by the
**responsible** (processing) org unit. It introduces no new data and no new visibility: it is a
different projection of figures the order detail already shows.

Tracked by [#1522](https://github.com/krt-profit/basetool/issues/1522).

## Requirements

### REQ-ORDERS-034 — Cross-order material demand, grouped by responsible org unit

The order area MUST offer a **material-demand overview** at `/orders/material-demand`, reachable
from its own entry in the navigation's `logistics` group, that aggregates the material still to be
gathered across every job order the caller may see whose status is `OPEN` or `IN_PROGRESS`.

**Which orders contribute.** Terminal orders (`COMPLETED`, `REJECTED`) MUST NOT contribute — they
are excluded in the query, not filtered out afterwards. Both order kinds contribute, kind-agnostically
as elsewhere in the order domain: a `MATERIAL` order through its `JobOrderMaterial` lines, an `ITEM`
order through the blueprint-derived, snapshotted `JobOrderItemMaterial` requirements
(`JobOrderItemService.aggregateMaterials`).

**Outstanding, not original, demand.** The required amount is the **outstanding** requirement,
consistent with the order detail: a `MATERIAL` line's `amount` is already decremented by handovers,
and an `ITEM` order's aggregation already scales each line by its not-yet-manufactured share
(REQ-ORDERS-025). Neither reduction may be applied a second time by the aggregation.

**Grouping and rows.** The page MUST render one section per **responsible** org unit
(`JobOrder.responsibleOrgUnit`, REQ-ORDERS-022 / REQ-ORG-003) — the unit that has to procure the
material, and the side the job-order visibility scope keys on. Grouping on the *requesting* unit
would answer a different question and is explicitly not what this page does. Within a section there
is one row per `(material, qualityRequirement)` bucket — the same bucket key the rest of the order
domain uses, so a `MATERIAL` line with a stored 650-floor and an `ITEM` requirement of `GOOD` land in
the *same* row rather than appearing as two materials. Each row carries four amounts, formatted per
the material's `quantityType` (whole units for `PIECE`, three decimals for SCU — REQ-ORDERS-001/002),
rounded on the **sum** rather than per contribution:

|    Column    |                                                           Meaning                                                           |
|--------------|-----------------------------------------------------------------------------------------------------------------------------|
| Bedarf       | summed outstanding required amount across the section's orders                                                              |
| Bestand      | summed inventory **linked to those orders** for the bucket, at or above its quality floor (`GOOD` → 650, `NONE` → no floor) |
| Eintragungen | summed material claims lodged on those orders' buckets; `0` when no contributing order is a public SK order                 |
| Offen        | `Bedarf − Bestand`, floored at 0                                                                                            |

`Bestand` is the same per-bucket sum the order's own material list shows, resolved through the same
batched index (`JobOrderStockProjectionService.loadOrderLinkedStockIndex`), so a row always
reconciles with the orders behind it. **`Offen` MUST ignore `Eintragungen`**: a claim (REQ-ORDERS-024)
is a signal-only promise that has moved no inventory, so subtracting it would understate what a
gathering run still has to collect. For the same reason the two are separate columns and are never
summed. Note this `Offen` is deliberately *not* the per-order `openAmount` of `AggregatedMaterialDto`,
which is a claims figure (`required − claims`); the two answer different questions.

**Drill-down.** Each row MUST expand to the orders contributing to it — order id (linking to its
detail page), kind, status and that order's own required / booked / claimed shares — so a user gets
from "we are short 850 SCU Titanium" to the orders that need it. It starts **collapsed**; the set of
expanded buckets is persisted per browser in `localStorage` (bare key `orders_demand_expanded`, the
REQ-ORDERS-027 / REQ-UI-017 idiom) and re-applied on load **and after every fragment swap**, so a
live-sync refresh never closes what the user opened.

**Scope and gating.** The page MUST show only orders within the caller's visibility scope. The scope
is pushed into SQL through the shared `ScopeSpecifications.JOB_ORDER_SCOPE_PREDICATE` — including the
SK-public escape — rather than filtered row-by-row afterwards, and the viewer-side profit gate
(`canViewJobOrders`) is applied first, so a caller outside the order workflow gets the empty state
rather than a partial aggregate. The nav entry is gated on the same capability as the order queue. The page states no
order count: the figure said nothing the rows do not, and the projection carries no such field.
The endpoint is deliberately **unpaged**: the response is a fold over the caller's whole visible
queue, and paging the underlying orders would silently truncate the sums (ADR-0104, no silent caps).

**Filtering and sorting.** Both are pure client-side presentation over the already-rendered rows —
the page is one unpaged fold (ADR-0104), so narrowing it needs no round trip. The controls live in a
**collapsible filter panel** behind a *Filter* button, the idiom the Lager uses (REQ-INV-037): the
panel does not displace the tables, the button carries the count of active filter dimensions so a
shortened table is never unexplained, and the panel is rendered **expanded** server-side and
collapsed by the client, so a JS-less caller keeps every row. Three filters:

- **Material** — a multi-select of the materials the page actually shows, with an in-dropdown text
  search. The search is presentation only: hiding an option never changes what the table shows.
- **Quality** — the `GOOD` / `NONE` buckets.
- **Hide covered** — hides every row whose `Offen` is 0. Defined on **stock**, not on claims: a
  bucket that is only *claimed* still has to be gathered and MUST stay visible, the same reason
  `Offen` itself ignores `Eintragungen`.

The selections are persisted as **exclusions** rather than selections. The option list is built from
the data present at page load, so a material that only appears after a live-sync swap has no
checkbox; keyed on exclusions it is simply not excluded and stays visible, where a stored selection
list would silently hide it.

The **Material**, **Bedarf**, **Bestand**, **Eintragungen** and **Offen** column headers sort: first
click ascending, second descending, third back to the server's own order, with `aria-sort` and a
direction indicator on the active column. One selection drives **every** group table so the units
stay comparable side by side, and each bucket's two rows — the figures and its drill-down — move as
a pair. Sorting reads the raw values from `data-*` attributes, never the rendered cell text, which
is localised (`1000,000 SCU`) and would sort as a string. Quality is not sortable: it is a two-value
bucket the filter already narrows.

Filter and sort state persists per browser in one JSON object under `orders_demand_filters`
(REQ-UI-017 / ADR-0120), alongside the drill-down's own `orders_demand_expanded`, and — like the
drill-down — is re-applied after every live-sync fragment swap, which otherwise restores the
server-rendered unfiltered, unsorted order. A group left with no visible row is hidden rather than
shown as an empty table, and an all-filtered page states that no material matches.

**Read-only.** The page offers no mutation, so it logs **no** audit event (REQ-AUDIT-001 covers
state-mutating activity) and holds no write seam. Booking stock in stays in the Lager and the
per-order Materialsammlung.

**Live update (REQ-FE-001/010/015).** The page joins the existing global `orders` room and carries
the section key **`demand`**, while the order list carries `queue`; the two seam maps therefore
*partition* the `LiveSyncTopicClass.ORDERS_QUEUE` whitelist rather than each matching it. Every write
that changes what the page shows MUST poke it: the order detail's queue cross-publish sends
`['queue', 'demand']` (a status change adds or removes an order's whole contribution), and the two
inventory pages send `orders`/`demand` after a write that touches order-linked stock (the `Bestand`
column). A peer's change re-fetches the `demandResults` fragment in place, with no full-page reload.

**Performance.** The aggregation MUST NOT introduce an N+1 across orders, materials or inventory:
the orders load in one query with both requirement branches eager-fetched, the linked stock through
one batched index, and the claims through the batched per-order claim lookup (REQ-DATA-003).

**Acceptance**

- [ ] `/orders/material-demand` lists, per responsible org unit, one row per `(material, quality)`
  bucket aggregated over that unit's `OPEN` + `IN_PROGRESS` orders, with Bedarf / Bestand /
  Eintragungen / Offen unit-formatted per `quantityType`.
- [ ] A `MATERIAL` order's 650-floor line and an `ITEM` order's `GOOD` requirement for the same
  material aggregate into one row, with the required amounts summed.
- [ ] `COMPLETED` / `REJECTED` orders never contribute to any total; the repository is asked only for
  `OPEN` + `IN_PROGRESS`.
- [ ] `Offen` equals `Bedarf − Bestand` floored at 0 and is unchanged by any claimed amount; a fully
  covered bucket shows 0, never a negative value.
- [ ] A `PIECE` material's aggregated amounts are whole units, rounded on the sum (2.4 + 3.4 → 6, not
  5).
- [ ] A row expands to its contributing orders with their individual shares and links to each order's
  detail page; the expanded state survives a reload and a fragment swap.
- [ ] The repository query carries the caller's scope triple; a caller failing `canViewJobOrders`
  gets an empty overview and the query is never issued.
- [ ] An unreachable backend degrades the page to its empty state rather than an error page.
- [ ] Every label comes from the DE + EN message bundles under `orders.demand.*` / `nav.orders.*`.
- [ ] The `orders-index.js` and `orders-material-demand.js` seam maps together cover exactly the
  `ORDERS_QUEUE` whitelist, and the order-detail / inventory cross-publishes carry `demand`.
- [ ] The filter panel sits behind a *Filter* button, does not displace the tables, shows the active
  filter count, and is rendered expanded server-side; with no stored choice it starts collapsed only
  when nothing is filtered.
- [ ] The material multi-select lists each distinct material exactly once across groups and quality
  buckets; its text search narrows the options without changing the table.
- [ ] "Hide covered" removes exactly the rows whose `Offen` is 0; a bucket covered only by
  `Eintragungen` stays visible.
- [ ] Clicking a sortable header sorts every group table, the second click reverses, the third
  restores the server order, and the drill-down stays attached to its material row.
- [ ] Amounts sort by value, not by their formatted text.
- [ ] A group with no visible row is hidden; an all-filtered page states that nothing matches.
- [ ] Filter and sort state survives a reload and a live-sync fragment swap.
- [ ] The page shows no order count and neither DTO carries one.

**Enforced by:** `JobOrderMaterialDemandServiceTest` (aggregation, grouping, scope + status gate,
claims-vs-gap separation, PIECE rounding), `JobOrderControllerTest`
(`getMaterialDemand_returnsTheAggregatedOverviewUnchanged`), `JobOrderMaterialDemandRenderTest`
(render, drill-down, empty state, fragment seam, backend-failure degradation),
`LiveSyncSectionMapParityTest` (`ordersQueueSeamMaps_partitionTheOrdersQueueTopicWhitelist`,
`orderDetailCrossPublish_keepsTheDemandOverviewInSync`,
`inventoryPages_pokeTheDemandOverviewWhenOrderLinkedStockChanges`),
`JobOrderMaterialDemandE2eTest` (browser: the two-order fold, the drill-down toggle and its
localStorage restore across a reload, the nav entry, the collapsible panel + active-filter
count, hide-covered, the material filter and its search, and the sort cycle) ·
**Code:** `JobOrderMaterialDemandService`, `JobOrderController` `GET
/api/v1/orders/material-demand`, `JobOrderRepository.findScopedOrdersWithMaterialRequirements`,
`JobOrderStockProjectionService.loadOrderLinkedStockIndex` / `qualityFloorFor`,
`MaterialDemandOverviewDto` / `MaterialDemandGroupDto` / `MaterialDemandRowDto` /
`MaterialDemandOrderShareDto`, `JobOrderMaterialDemandPageController`,
`templates/orders-material-demand.html`, `templates/fragments/material-amount.html`,
`static/js/orders-material-demand.js`,
`fragments/sidebar.html`, `LiveSyncTopicClass.ORDERS_QUEUE` · **Issues:** #1522

## Out of scope

- **Mutating anything.** The page is a read-only projection; booking stock in, linking inventory and
  marking deliveries stay in the Lager and the per-order Materialsammlung.
- **Game items / item handovers.** The item sibling is the Itemsammelübersicht
  ([`orders-item-production.md`](orders-item-production.md), REQ-ORDERS-031); this page is about
  materials. An `ITEM` order appears here only through its blueprint-derived *material* demand.
- **Widening visibility.** The page can only ever show orders the caller may already see; it layers
  no new access path on top of the scope in
  [`org-unit-tenancy.md`](org-unit-tenancy.md) / [`security-and-access.md`](security-and-access.md).
- **Server-side filtering, paging or sorting.** The page's filters and column sorting are
  client-side presentation over the single unpaged fold; none of them reaches the backend.
- **The order list's status and squadron filters** (REQ-ORDERS-027) are still not mirrored here: the
  grouping by responsible unit already answers "whose demand is this", and a status filter would
  contradict the page's definition of non-terminal orders.
- **The per-order material views.** The overview list column (REQ-ORDERS-017) and the order detail's
  material / aggregated tables are unchanged; this spec adds a second, aggregate reader of the same
  figures.

## Open questions

None.

> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-03.
> **Owner area:** REFINERY · **Related ADRs:** none

# Refinery-order overview list

## Context & goal

The refinery-order overview (`GET /refinery-orders`) shows one row per refinery order — id, owner,
end time, location, mission, materials — for the whole organisation (read-only for normal members),
with run segments, a search and a "Meine Aufträge" (own-orders) toggle. It used to fetch the entire order
set in a single unbounded `size=1000` response and render every row at once. As the order history
grows this is wasteful and unbounded; this spec pins the page down to a server-side page using the
shared pagination component, exactly like the blueprint availability overview (REQ-INV-013) and the
unit hangar overview (REQ-HANGAR-001).

## Requirements

### REQ-REFINERY-019 — Refinery-order list is paginated server-side

The refinery-order overview MUST fetch exactly one **server-side page** of orders for whatever it
lists (from `/api/v1/refinery-orders/all` or, when the own-orders toggle is on,
`/api/v1/refinery-orders/my-orders`). It MUST NOT pull a large or unbounded page (the former
`size=1000`) to filter, search, sort or page in memory — not for a segment, not for the search and
not for a counter. It renders the shared pagination component — the `.pagination` page-nav plus the
square `.page-btn` size picker from `fragments/pagination.html` — and adopts the shared page-size
contract (REQ-INV-013 / REQ-API-005): **page sizes {10, 50, 100} with a default of 50**; a
client-supplied `size` outside that set snaps back to the default before the backend call, and a
negative `page` clamps to 0.

**Segments.** The list is split by the `view` parameter into four segments, each one backend
request (amended 2026-10-03; the list used to page only by status and filter the open segments in
memory):

| `view` | Backend request | Sort |
| --- | --- | --- |
| `RUNNING` (default) | `status=OPEN,IN_PROGRESS` | `endsAt,asc` |
| `READY` | `status=OPEN,IN_PROGRESS&ready=true` | `endsAt,asc` |
| `COMPLETED` | `status=COMPLETED` | `startedAt,desc` |
| `ALL` | every status | `startedAt,desc` |

A legacy `status` filter without a `view` maps onto a segment: none or `OPEN`+`IN_PROGRESS` →
`RUNNING`, `COMPLETED` alone → `COMPLETED`, every status → `ALL`. Any other subset (e.g.
`CANCELED`) stays an **exact** filter, sorted `startedAt,desc`, and the `ALL` segment is shown as
selected. An order's end is `startedAt + durationMinutes`; an open order whose end has passed or is
unknown counts as ready, as `RefineryProgress` draws it.

**Search.** The toolbar search is sent to the backend as `q` and narrows the requested segment; the
backend matches it case-insensitively as a substring of the owner's display name or username, the
location name, the refining-method name, or an input or output material name of any of the order's
goods. Its LIKE wildcards are matched literally.

**Counters.** The four segment counters are the `totalElements` of four `size=1` requests of the
segments without the search; the ready counter is the open statuses plus `ready=true`.

**Backend list API.** `GET /api/v1/refinery-orders/all` (the caller's org-unit scope, unchanged)
and `GET /api/v1/refinery-orders/my-orders` (the caller's own orders) take, besides `page`, `size`
and the repeatable `status`:

- `q` — optional search as above. An order with several matching goods is one row and counts once
  in `totalElements`. The search never widens the endpoint's scope.
- `ready` — optional boolean; `true` keeps only orders whose end is unknown or at or before the
  server's current time. It combines with `status` as AND.
- `sort` — `field,asc|desc` over `startedAt`, `endsAt`, `durationMinutes`, `expenses` and `id`
  (`id` is appended as tiebreaker); without `sort` the backend orders by `startedAt` ascending.
  `endsAt` is computed by the database (`RefineryOrder.endsAt`, a read-only formula); orders with
  an unknown end sort last ascending.

Page and size links MUST preserve the active filter — the `view` (or the legacy exact `status`
params), the `onlyMine` toggle and the search `q` — and the pagination controls live **inside** the
`refineryOrdersResults` AJAX-swap fragment so an in-place filter change re-renders them.

**Acceptance**

- [ ] A result spanning more than one page renders the page-nav and the 10/50/100 size picker; a
  short result (≤ the smallest size, single page) renders neither.
- [ ] Every page-nav and size-picker link carries the active `view` (or exact `status`), `onlyMine`
  and `q` params; changing the size jumps back to page 0.
- [ ] Every segment, with or without a search, is exactly one backend page of the requested size;
  no request asks for `size=1000`.
- [ ] `RUNNING` and `READY` are ordered by end ascending, `COMPLETED` and `ALL` by start
  descending; `READY` sends `ready=true`.
- [ ] The search reaches the backend as `q`; the backend finds an order by each searched field,
  returns it once however many of its goods match, and keeps the caller's scope.
- [ ] `ready=true` keeps an order whose end is exactly now and drops one whose end is later.
- [ ] A `?size=` outside {10,50,100} falls back to 50; a negative `?page=` clamps to 0.

**Enforced by:** `RefineryOrderPaginationMvcTest`, `RefineryOrdersListPatternRenderTest`,
`RefineryOrderListViewResolutionTest`, `RefineryOrderDurationTest` (`testViewOrders_*`),
`RefineryOrderRepositoryListFilterTest`, `RefineryOrderControllerTest` (`ListFilterTests`),
`RefineryOrderServiceLifecycleTest` · **Code:** `RefineryOrderPageController.viewOrders` /
`segmentQuery` / `orderPageUri` / `buildPaginationBaseUrl`, `templates/refinery-orders-index.html`,
`templates/fragments/pagination.html`, `RefineryOrderController` (`/all`, `/my-orders`),
`RefineryOrderService.getAllRefineryOrders` / `getMyRefineryOrders`,
`RefineryOrderRepository.findFilteredScoped` / `findOwnedFiltered` / `LIST_FILTER` · **Issues:** #2
(performance audit)

### REQ-REFINERY-020 — A location counts as a refinery iff it hosts a refinery terminal

The set of locations offered as refineries — the create/edit form's location picker, and the
candidate set the screenshot import resolves its location read against — MUST be derived from the
presence of a **live UEX terminal with `type = 'refinery'`** at the location's city or space
station. It MUST NOT be derived from UEX's parent-level `has_refinery` boolean on
`city` / `space_station` / `outpost`.

The offered set MUST additionally exclude **hidden** locations (`location.hidden = true`), on the
same terms as every other location picker.

**Why:** UEX publishes both statements and they disagree. Measured against the live UEX API on
2026-07-28, 21 terminals carry `type = 'refinery'`, while the parent flag is wrong in *both*
directions:

|                Disagreement                |                              Locations                              |              Effect before this requirement               |
|--------------------------------------------|---------------------------------------------------------------------|-----------------------------------------------------------|
| Parent flag `0`, refinery terminal present | MIC-L5 Modern Icarus Station, ARC-L4 Faint Glen Station, Patch City | Missing from the picker although members can refine there |
| Parent flag `1`, no refinery terminal      | People's Service Station Alpha / Delta / Lambda / Theta             | Offered as refineries that do not exist in-game           |

The `type = 'refinery'` terminal is the same record UEX's own site renders its refinery list from,
so it is the signal to trust.

**How:** `terminal.type` mirrors the upstream discriminator verbatim. The derived truth lives in
`city.has_refinery_terminal` / `space_station.has_refinery_terminal`, recomputed from the live
refinery terminals by `UexUniverseSyncService.reconcileRefineryTerminalFlags()`. UEX's raw
`has_refinery` claim is kept untouched alongside it for diagnostics — the same "raw upstream value
next to the effective value" split `terminal.uex_has_loading_dock` already uses.

**Bootstrap and starvation — binding placement in the sweep.** The derived flags have no local
bootstrap: `terminal.type` is not derivable from anything already in the database, so until a sweep
has populated it every `has_refinery_terminal` is `FALSE` (the V226 default) and the picker resolves
to an **empty** list — not merely a shorter one, and the create/update gate then rejects every
location. The sweep repeats only every 24 h (`krt.uex.scheduler-delay`, default 86400000 ms; it does
start at boot via `initialDelay = 0`), so a tick that never reaches the terminal step costs the
refinery feature a full day. Two placement rules therefore bind:

1. **`syncTerminals()` MUST lead the sweep**, ahead of every step that can abort the tick. It is the
   one topology step with no FK into another — it writes only `terminal`, storing UEX's denormalised
   parent names — so nothing is lost by hoisting it. Behind the rest of the topology (its position
   as originally shipped) any single failing endpoint aborted the tick before terminals were ever
   fetched.
2. **`reconcileRefineryTerminalFlags()` MUST run from the sweep's `finally`**, not at the end of
   `syncTerminals()`. It matches terminals against `city` / `space_station` rows by name, so it has
   to run after those are synced; and in the `finally` a later step aborting the sweep no longer
   costs the flags, since the terminals it derives from are already committed. It performs no
   network call — a pure local derivation — so it is safe there even when the sweep aborted because
   UEX was unreachable. Its own failure is caught and logged rather than propagated, so it can
   neither replace the sweep's exception nor skip the master-data cache eviction sharing that block.

Storing the derived value rather than resolving it per read is **binding, not an optimisation**: the
create/update gate reads the flag off the already-loaded `Location` parent in memory. Issuing a query
there would auto-flush a transaction that is midway through rewriting the order and its goods, so the
goods `clear()` + re-add would race its own freshly written rows and fail with
`ObjectOptimisticLockingFailureException` (409).

The picker and the write-path gate MUST read the **same** flag, so the create/update gate accepts
exactly the locations the form offered — a stricter gate would reject a location the picker just
handed the user.

**Hidden locations.** `LocationRepository.findLocationsWithRefinery` was for the whole life of the
repository the only Location lookup that ignored the admin's `hidden` flag — `findAllReference`,
`searchReference`, `findByHiddenFalse` and `findByHomeLocationTrueAndHiddenFalseOrderByNameDesc`
all filter it, and the flag's documented meaning is that hidden entries "do not appear in trade
lists or selection fields". Hiding a refinery-hosting location therefore produced a dead end
*within a single page*: it stayed selectable as **Raffinerie** on the refinery-order form while
vanishing from the **Lagerort** picker of that same page's Einlagern dialog and from the Lager
Einbuchen picker, so a user could open an order at a location they could not then book the yield
into. Two consequences bind:

- The `AND` must be **parenthesised** against the two terminal branches
  (`hidden = false AND (city OR station)`). `AND` binds tighter than `OR`, so an unparenthesised
  predicate filters the city branch only and leaks every hidden station-backed refinery.
- The write-path gate `RefineryOrderService.validateLocationHasRefinery` deliberately does **not**
  mirror the `hidden` predicate; it stays keyed on the refinery flag alone. This makes the gate
  *more permissive* than the picker, which the same-flag rule above permits — it forbids a
  **stricter** gate, since only a stricter one can reject what the form just offered. The looser
  gate is what lets an order created before its location was hidden still be edited and saved. The
  detail page complements this by keeping the order's own location in the dropdown even when the
  backend omits it (`RefineryOrderPageController.withPreservedLocation`); without that the
  `required` select would render unselected and block every later save of a formerly valid order.

**Acceptance**

- [ ] A location whose parent carries `has_refinery = false` but hosts a live refinery terminal
  (MIC-L5, ARC-L4, Patch City) is offered by the picker and accepted by create/update.
- [ ] A location whose parent carries `has_refinery = true` but hosts no refinery terminal
  (People's Service Station Alpha/Delta/Lambda/Theta) is *not* offered and is rejected on create.
- [ ] A hidden location is not offered by the picker, whether its refinery terminal sits on its
  city or on its space station, and hiding one does not remove its still-visible siblings.
- [ ] An existing order whose location was hidden after creation still renders that location as the
  selected option on the detail page and can still be saved.
- [ ] A terminal that is not `type = 'refinery'` never flags its parent.
- [ ] A `type = 'refinery'` terminal with `is_available_live = false` never flags its parent, so a
  decommissioned refinery drops out on the next sweep.
- [ ] The sweep corrects a stale derived flag in both directions, and leaves the raw `has_refinery`
  claim unmodified.
- [ ] Editing a refinery order's location does not produce a 409.
- [ ] `syncTerminals()` still runs when an unrelated topology step throws, so one failing UEX
  endpoint cannot leave the picker empty until the next daily tick.
- [ ] The derived flags are still reconciled when a step downstream of the terminals aborts the
  sweep, and a failing reconciliation neither replaces the sweep's exception nor skips the
  master-data cache eviction.
- [ ] An environment with no UEX sweep (the E2E stack, which runs with
  `KRT_UEX_SCHEDULER_ENABLED=false`) seeds `has_refinery_terminal` itself — see
  `frontend/src/e2e/resources/uex-catalog-seed.sql`.

**Enforced by:** `UexUniverseSyncRefineryFlagTest`, `UexSchedulerTest`, `LocationRepositoryRefineryTest`,
`RefineryOrderServiceLifecycleTest` (`CreateRefineryOrderTests`), `RefineryOrderLocationDropdownTest`
· **Code:** `UexUniverseSyncService.reconcileRefineryTerminalFlags`,
`LocationRepository.findLocationsWithRefinery`, `RefineryOrderService.validateLocationHasRefinery`,
`RefineryOrderPageController.withPreservedLocation`, `Terminal.type`, migration `V226`

### REQ-REFINERY-022 — A stored refinery order is stored exactly once

Storing an order books its output as inventory rows and writes one `INVENTORY_RECEIVED_FROM_REFINERY`
event per row plus one `REFINERY_ORDER_STORED` event. It MUST happen at most once per order.

- The order carries a `storedAt` marker, set only by the store operation and never cleared. The store
  operation refuses an order with `storedAt` set, or with status `COMPLETED`, with
  `error.refinery_order.already_stored`.
- An update (`PUT`) of an order with `storedAt` set MUST NOT change its status; an attempt is refused
  with 400 `error.refinery_order.stored_status_locked`. Cancelling through `DELETE` stays possible and
  does not re-open storing.
- Create (`POST /api/v1/refinery-orders` and the user-scoped variant) ignores a client-supplied
  `status`, `storedAt`, `id`, `version` and `owner` where the caller may not set it: the order is always
  created `OPEN`, unstored, with the owner and org unit resolved server-side. The create form therefore
  offers no status choice. A `PUT` may set any status, as the edit form's status select does; this is
  deliberate (owner decision 2026-10-01) and only a stored order is locked. An order a `PUT` sets to
  `COMPLETED` without a store is accepted; it cannot be stored afterwards, so nothing is duplicated.
- Store refuses (400) an item whose job order does not require the item's material, on the same terms
  as `POST /inventory` (REQ-ORDERS-018).
- Migration `V259` adds the nullable column `refinery_order.stored_at` and backfills it for orders that
  are already `COMPLETED`.

**Enforced by:** `RefineryOrderServiceLifecycleTest` (`UpdateRefineryOrderTests`, `clientSuppliedServerManagedFields_areIgnoredOnCreate`),
`RefineryOrderServiceTest` · **Code:** `RefineryOrderService.updateRefineryOrder` /
`storeRefineryOrder`

## Out of scope

- The order **detail**, **create**, **store**, **cancel** and screenshot-**import** flows — covered
  by [`refinery-screenshot-import.md`](refinery-screenshot-import.md) and the controller's other
  handlers; this spec only governs the list view's pagination. The store dialog's personal marker
  (booking refinery output straight into the receiver's private pool) is specified in
  [`inventory-lager.md`](inventory-lager.md) `REQ-INV-035`.
- User-chosen sort columns. The UI sorts each segment by the fixed order of REQ-REFINERY-019; the
  backend sort whitelist (`{startedAt, endsAt, durationMinutes, expenses, id}`) is not exposed as
  column headers.

## Open questions

None.

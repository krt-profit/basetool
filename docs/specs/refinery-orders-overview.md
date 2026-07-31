> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-28.
> **Owner area:** REFINERY · **Related ADRs:** none

# Refinery-order overview list

## Context & goal

The refinery-order overview (`GET /refinery-orders`) shows one row per refinery order — id, owner,
end time, location, mission, materials — for the whole organisation (read-only for normal members),
with a status filter and a "Meine Aufträge" (own-orders) toggle. It used to fetch the entire order
set in a single unbounded `size=1000` response and render every row at once. As the order history
grows this is wasteful and unbounded; this spec pins the page down to a server-side page using the
shared pagination component, exactly like the blueprint availability overview (REQ-INV-013) and the
squadron hangar overview.

## Requirements

### REQ-REFINERY-019 — Refinery-order list is paginated server-side

The refinery-order overview MUST fetch one **server-side page** of orders (from
`/api/v1/refinery-orders/all` or, when the own-orders toggle is on, `/api/v1/refinery-orders/my-orders`)
instead of the former unbounded `size=1000` pull, and render the shared pagination component — the
`.pagination` page-nav plus the square `.page-btn` size picker from `fragments/pagination.html`. It
adopts the shared page-size contract (REQ-INV-013 / REQ-API-005): **page sizes {10, 50, 100} with a
default of 50**; a client-supplied `size` outside that set snaps back to the default before the
backend call, and a negative `page` clamps to 0. The default sort stays `startedAt,desc` so the
newest orders remain on the first page.

Page and size links MUST preserve the active filter — the repeatable `status` params and the
`onlyMine` toggle — and the pagination controls live **inside** the `refineryOrdersResults`
AJAX-swap fragment so an in-place status-filter change re-renders them.

**Acceptance**

- [ ] A result spanning more than one page renders the page-nav and the 10/50/100 size picker; a
  short result (≤ the smallest size, single page) renders neither.
- [ ] Every page-nav and size-picker link carries the active `status` (repeatable) and `onlyMine`
  params; changing the size jumps back to page 0.
- [ ] The default view shows `OPEN`+`IN_PROGRESS`, sorted `startedAt,desc`, with the newest order on
  page 0.
- [ ] A `?size=` outside {10,50,100} falls back to 50; a negative `?page=` clamps to 0.

**Enforced by:** `RefineryOrderPaginationMvcTest`, `RefineryOrderDurationTest`
(`testViewOrders_*`) · **Code:** `RefineryOrderPageController.viewOrders` / `buildPaginationBaseUrl`,
`templates/refinery-orders-index.html`, `templates/fragments/pagination.html` · **Issues:** #2
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

## Out of scope

- The order **detail**, **create**, **store**, **cancel** and screenshot-**import** flows — covered
  by [`refinery-screenshot-import.md`](refinery-screenshot-import.md) and the controller's other
  handlers; this spec only governs the list view's pagination. The store dialog's personal marker
  (booking refinery output straight into the receiver's private pool) is specified in
  [`inventory-lager.md`](inventory-lager.md) `REQ-INV-035`.
- New sortable columns. The backend sort whitelist for these endpoints is
  `{startedAt, durationMinutes, expenses, id}`; the UI keeps the fixed `startedAt,desc` order. The
  end-time column the list displays is a derived value (`startedAt + durationMinutes`) and is not
  server-sortable.

## Open questions

None.

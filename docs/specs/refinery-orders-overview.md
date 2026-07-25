> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-21.
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

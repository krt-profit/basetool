# ADR-0293 — Long admin catalogue lists paginate server-side

- **Status:** Accepted
- **Date:** 2026-10-19
- **Deciders:** @greluc
- **Related:** spec [`admin-catalog-completeness.md`](../specs/admin-catalog-completeness.md)
  (REQ-ADMIN-001, REQ-ADMIN-002, REQ-ADMIN-004) · narrows
  [ADR-0102](0102-admin-catalog-page-walk-no-silent-truncation.md) · applies option 1 of
  [ADR-0104](0104-no-silent-caps-on-complete-list-surfaces.md) · REQ-API-005, REQ-FE-005,
  REQ-UI-027

## Context

ADR-0102 made the admin catalogue pages render the complete catalogue by walking every backend
page, and rejected a pagination UI because it would break the client-side filter. It ended with
"revisit if a catalogue outgrows the load-all model wholesale".

Three pages have. The page-by-page design-system review of October 2026 measured
`/admin/materials` at 213 rows, `/admin/locations` at 66 and `/ship-data` at 281 ship types, each
rendered as one table of 40 000 to 85 000 px. Every materials row carries a `<select>` with every
material in it. The client-side filter hid rows but still shipped and laid out all of them, and
the page-head counts were list sizes rather than totals.

Every other long list in the app pages server-side with the shared list footer (`listFoot`,
`.pagination`) and a server-side search.

## Decision

1. **`/admin/materials`, `/admin/locations` and the ship-type list on `/ship-data` paginate
   server-side**, like the other lists: 25, 50 or 100 rows per page (default 25), the shared
   `listFoot` with its page links and the page-size picker inside the swapped fragment.
2. **The search is server-side.** `GET /api/v1/materials`, `/api/v1/locations` and
   `/api/v1/ship-types` take an optional `query`, a case-insensitive *contains* match on the name
   that respects `includeHidden` and treats LIKE wildcards literally. A search is not cached; the
   unsearched pages keep their caches. The sort stays `name,asc` over the existing whitelists.
3. **Every page, search and size change swaps `?fragment=results` in place** through `krtFetch`
   and keeps the address bar in step (REQ-FE-005). The row edits keep their in-place writes.
4. **The counts are totals.** The page-head chip and the ship-type section chip show
   `totalElements`, and `krt-list-count.js` keeps them current after a swap.
5. **What needs the whole catalogue still walks it** through `CatalogPages.fetchAll`: the
   refined-material dropdowns and the create dialog on `/admin/materials` and the search
   suggestions on all three pages. REQ-ADMIN-002's truncation banner stays for those walks.
6. **The manufacturers on `/ship-data` stay one complete, client-filtered list.** They are about
   fifty rows, and the shared pagination fragment carries one `page` / `size` pair per URL, so a
   second paginated list on the same page would need a second parameter pair the fragment does
   not have.

## Consequences

- The three pages render at most 100 rows. A row on another page is reached through the search or
  the page links, not by scrolling.
- The client-side `filterTable` is gone from `/admin/materials` and `/admin/locations`; it stays
  for the manufacturer list on `/ship-data`.
- The shared `CachedCatalog` reads of the same endpoints are unchanged: they send no `query` and
  keep walking the full catalogue.
- A new admin catalogue that grows past a page of rows follows this ADR rather than ADR-0102.

## Alternatives considered

- **Client-side truncation** (render the first rows, reveal the rest on demand): keeps the
  complete fetch and the per-row dropdown cost, and the counts and the filter would still work on
  hidden rows. Rejected by @greluc in favour of the pagination the other lists use.
- **A separate admin search endpoint per catalogue:** the existing `/search` endpoints return
  visible rows only and a slimmer DTO. An optional `query` on the list endpoints serves the admin
  view and every other caller without a new route.

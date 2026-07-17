# ADR-0104 — No silent caps on complete-list surfaces; pick the remedy per surface

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`inventory-lager.md`](../specs/inventory-lager.md) `REQ-INV-033` ·
  generalises three prior 2026-07-16 applications of the same defect class —
  [ADR-0100](0100-catalog-pickers-search-server-side.md) (pickers search server-side),
  [ADR-0102](0102-admin-catalog-page-walk-no-silent-truncation.md) (admin catalog pages page-walk
  the complete catalogue), [ADR-0103](0103-cached-frontend-catalogues-page-walk-inside-cache.md)
  (cached catalogues page-walk inside the cache) · [`data-persistence.md`](../specs/data-persistence.md)
  `REQ-DATA-012` (the `truncated`-flag disclosed-cap precedent) ·
  [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) `REQ-FE-005` · backend `size`
  clamp rationale in `PaginationUtil` (SEC-03)

## Context

Several frontend pages present what a user reads as **the complete list** of something — every
inventory row of a material or game item, every catalogue entry, every booking. A recurring defect
built such a page by fetching **one** large fixed-size slice of the paginated backend API
(`?size=1000`, UEX `size=10000`) and rendering it with no pagination controls, no search, and no
"more results exist" indicator.

That construction fails silently at scale: rows beyond the fixed size are not just invisible, they
are **unreachable** — no interaction on the page can surface them, and nothing tells the user that
anything is missing. The failure mode is worse than a crash: the page looks correct and complete
while quietly lying about the data. It bit three surface families that were fixed on 2026-07-16 —
the owner pickers (ADR-0100), the seven admin catalog pages (ADR-0102), the cached frontend
catalogues (ADR-0103) — and it survived in the two inventory drilldowns, the per-material page
(`/inventory/material/{id}`) and its item sibling the per-game-item page
(`/inventory/game-item/{id}`), each of which still fetched a single `size=1000` slice.

Those three prior ADRs each fixed one surface with the remedy that fit it, but there was no standing
rule that a silent cap is a defect, and — because ADR-0102 deliberately **rejected** server-side
pagination for its client-side-filtered admin pages in favour of a complete page-walk — no shared
guidance on *which* remedy fits *which* surface. A later author could reasonably read ADR-0102 as
"page-walk everything", which is wrong for a large per-key list like the drilldowns.

## Decision

We treat **any silent cap on a complete-list surface as a defect**, and we record the
remedy-selection framework so each surface gets the right fix. A surface that presents itself as the
complete list of a collection must do exactly one of:

1. **Paginate server-side** — URL-driven `page`/`size`, the shared `fragments/pagination.html`
   pager + size picker **inside** the swapped results container, in-place fragment swap
   (REQ-FE-005). Page sizes are whitelisted and snap back to a default so a crafted URL cannot
   request an unbounded page. **Fits** large, per-key or filterable row lists with no client-side
   full-set filter — e.g. the inventory drilldowns (REQ-INV-033), the aggregated `/inventory`
   overview, hangar, orders, bank bookings.
2. **Fetch the collection completely** — page-walk the backend from page `0` to the last page
   through the shared `support.CatalogPages.fetchAll` helper (bounded by a runaway `MAX_*_PAGES`
   backstop that flips a `truncated` flag). **Fits** bounded reference catalogues rendered as one
   DOM unit with instant client-side filtering, where a server-pagination UI would break the filter
   — the admin catalog pages (ADR-0102), cached catalogues (ADR-0103), personal blueprints (#823).
3. **Cap and disclose** — when a hard bound is deliberate read-shape hardening, surface the
   truncation in the UI (a `truncated` flag / "showing first N" banner), never implicitly — the
   REQ-DATA-012 finance-summary cap.

A fixed-size single-page fetch with none of the three is not an acceptable state for new or existing
surfaces; whoever touches such a surface converts it.

## Consequences

- The two inventory drilldowns move to option 1 (REQ-INV-033) as this ADR's first application: both
  paginate server-side with the shared pager, and the pre-existing `size=1000` fetch is gone.
- The three 2026-07-16 ADRs (0100/0102/0103) are retained as the specific applications; this ADR is
  their umbrella and the tie-breaker on remedy choice, not a supersession — none of them changes.
- Reviews get a bright-line test: a `?size=<bignum>` fetch feeding a `th:each` with no pager, no
  page-walk and no truncation indicator is rejectable on sight.
- List surfaces gain a little controller/template plumbing (page model + pager fragment, or the
  `fetchAll` helper), paid once per surface; in exchange their correctness no longer depends on a
  magic number outliving the data volume.
- Existing surfaces are not swept preemptively; they are converted when touched (the
  no-opportunistic-cleanup rule stands).
- **Monitoring:** no new endpoints, jobs, metrics or status enums — pagination reuses existing
  authenticated backend GETs and their WebClient/Resilience4j instrumentation.

## Alternatives considered

- **Leave the three per-surface ADRs to speak for themselves (no umbrella)** — rejected: nothing
  stated the general rule, and ADR-0102's narrow rejection of pagination for its case could mislead
  a future author into page-walking a large per-key list. The framework is the value this ADR adds.
- **Mandate one single mechanism for all surfaces** — rejected: page-walking a genuinely large
  per-key list (drilldowns) is a heavy DOM and many round-trips, while server-paginating a
  client-side-filtered admin catalogue breaks its filter. The right remedy is surface-dependent.
- **Raise the single-request bound (e.g. `size=100000`)** — rejected: moves the cliff instead of
  removing it, still lies past the new bound, and worsens single-response latency/memory.


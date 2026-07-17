# ADR-0105 — Trade matrix: server-side filtering (over the ADR-0102/0103 page-walk)

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** @greluc
- **Related:** spec REQ-UI-014 · REQ-UI-015 ([`materials-pages-completeness.md`](../specs/materials-pages-completeness.md)) · an application of the ADR-0104 no-silent-caps framework (options 1+2) · builds on ADR-0102 (admin catalog page-walk) · ADR-0103 (page-walk inside `getCached`) · ADR-0069 (inline-JS extraction)

## Context

The materials trade-matrix overview (`GET /materials/overview`) renders a dense material × terminal
grid. The frontend fetched the whole matrix (`MATERIALS_MATRIX`, `size=100000`) and filtered it
**client-side in memory** over the four dimensions material / star-system / loading-dock / auto-load.

Two forces converged after ADR-0102/0103 shipped:

1. **Truncation is already solved for the matrix.** ADR-0103 made `MATERIALS_MATRIX` a
   `Fetch.PAGE_WALK` catalogue, so `getCached` now assembles **every** backend page via
   `CatalogPages.fetchAll` before caching. The client therefore already filters over the *complete*
   universe — no silent cell loss past the page-size clamp. What survived was **not** a correctness
   gap but a scalability one: the whole universe is shipped to, deserialized by, and reshaped in
   every browser on every visit purely to be filtered down locally, and materials × terminals is the
   multiplicative worst case.

2. **The per-material detail price list was still truncating.** `GET /materials/{id}` fetched
   `/prices?size=1000` (a per-id `get`, not a page-walked catalogue) and rendered it as the complete,
   unpaginated price table — the exact silent-truncation defect ADR-0102 named, just on a surface the
   admin page-walk never covered.

## Decision

**We will filter the trade matrix server-side, and reuse the existing page-walk everywhere a whole
paged collection is rendered — not add a parallel assembly mechanism.**

- **Matrix filtering moves to the backend.** `GET /api/v1/materials/matrix` gains four optional,
  whitelisted query parameters (`materialNames`, `starSystems`, `hasLoadingDock`, `isAutoLoad`),
  applied in JPQL through the codebase's `:param IS NULL OR x IN :param` idiom (empty collections
  normalised to `null`; a bare call is byte-for-byte the old full-matrix query). The grid re-fetches
  `/materials/overview/data` with its selection as parameters on each filter change (debounced, with
  a stale-response guard); the controller relays them. So the browser receives and reshapes only the
  matching slice.
- **Every fetch stays complete via the ADR-0102/0103 page-walk.** The unfiltered default is the
  cached `getCached(MATERIALS_MATRIX)` page-walk unchanged. A filtered fetch (uncached — its URI
  varies by selection and is deliberately not an allowlisted `CachedCatalog`) page-walks through the
  same `CatalogPages.fetchAll`, so even a large filtered slice is complete. Filter values are passed
  as **WebClient URI variables** (`get(uriTemplate, type, Object...)`), so a multi-word material name
  is strictly encoded (`%20`, not the `URLEncoder` form-encoding of #371) for the exact `IN` match.
- **The detail price list reuses `CatalogPages.fetchAll` too** (`size=10000` chunks), so it renders
  every terminal that trades the material (REQ-UI-015). No new seam — the same helper ADR-0102
  introduced.

## Consequences

- **Bounded client cost.** A filtered visit ships only the selected slice, not the whole universe;
  the DOM and payload track what the user asked to see. The unfiltered first paint is unchanged
  (cached page-walk).
- **A filtered grid shows only terminals/materials with a price row in the slice.** Columns are
  derived from the returned rows, so a filtered grid no longer renders all-empty terminal columns — a
  deliberate, documented behaviour change (REQ-UI-014), arguably an improvement.
- **A filter change is now a network round-trip** (debounced ~200 ms, stale responses discarded),
  where it was an in-memory re-render.
- **Uncached filtered fetches are heap-guarded.** A filtered fetch misses the `@Cacheable(sync=true)`
  single-flight that keeps the unfiltered matrix to one concurrently-buffered (up to 64 MB) copy, and
  the shared `backendApi` bulkhead's 100 permits is far above a heap-safe count. So the filtered path
  is gated by a small dedicated semaphore (a few permits) — exactly the guard `WebClientConfig`
  prescribes once a second heavy read path is added — bounding how many matrix-sized payloads buffer
  at once. It blocks (not rejects) under contention, mirroring the single-flight's wait-for-the-loader
  behaviour.
- **Filter option lists stay whole.** They are still derived server-side from the complete
  (page-walked) catalogue, so narrowing a filter never removes options.
- **No mechanism duplication.** Completeness continues to flow through the single ADR-0102/0103
  page-walk (`CatalogPages.fetchAll`), not a second parallel assembler.

## Alternatives considered

- **Keep assembling the whole matrix and filter client-side (status quo after ADR-0103).** Correct,
  but ships the multiplicative worst case to every client on every visit only to discard most of it
  locally. Rejected on scalability once the universe grows.
- **Add a new `getAllPages`/`getCachedAllPages` seam for this change.** Rejected: it would duplicate
  the ADR-0102/0103 `CatalogPages.fetchAll` + `Fetch.PAGE_WALK` machinery that already does exactly
  this. Reuse keeps one page-walk contract.
- **Paginate the detail price table with UI controls.** Rejected: the list is short enough to render
  whole, and paginating it would break the single-`<datalist>` terminal filter/sort that scans the
  full table (ADR-0069). Page-walking it preserves that UX with no new controls.
- **Raise the `size` numbers.** Rejected — it moves the cliff, not removes it, and `MAX_PAGE_SIZE`
  is a hard security clamp; the page-walk is the actual remedy.


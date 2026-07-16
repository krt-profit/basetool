# ADR-0103 — Cached frontend catalogues page-walk complete inside the cached fetch

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`admin-catalog-completeness.md`](../specs/admin-catalog-completeness.md)
  `REQ-ADMIN-003` · extends [ADR-0102](0102-admin-catalog-page-walk-no-silent-truncation.md)
  (the same silent-truncation defect class, fixed on the uncached admin catalog pages) · the
  FE-CACHE-1/2 cache allowlist in
  [`data-persistence.md`](../specs/data-persistence.md) `REQ-DATA-007`

## Context

The frontend's `CachedCatalog` allowlist (FE-CACHE-1, REQ-DATA-007) still pinned single bounded
pages for its paged reference catalogues: `SQUADRONS` / `SPECIAL_COMMANDS` (`size=1000`, sidebar
org-unit switcher, job-order and mission pickers), `TERMINALS` (`size=10000`, profit calculator),
plus the material / location / ship-type / refining-method / job-type / frequency-type /
manufacturer catalogues and the `size=100000` material×terminal matrix. Every consumer treats the
cached response as "the whole list" (pickers, sidebar fragments, client-side filtered grids), so a
catalogue growing past its bound would silently drop rows on **every** page that reads it — the
defect class ADR-0102 just fixed on the admin catalog pages, but amplified by the cache: one
truncated fetch is served app-wide for the whole TTL.

Forces specific to the cached layer:

- The cache key is the enum constant's `name()` and the **only** cached-read entry point is
  `getCached(CachedCatalog, …)` (FE-CACHE-1's unrepresentability doctrine). Walking pages *outside*
  `getCached` would cache only page 0 — the walk has to happen **inside** the cached fetch so the
  cached value itself is the complete catalogue.
- ~18 call sites across controllers and advices consume these catalogues as
  `PageResponse<X>.content()`; none reads the envelope's paging fields, none mutates the list in
  place.
- The backend serves stable pages for every one of these endpoints (`PaginationUtil` whitelists and
  appends the `id` sort tiebreaker on all of them).
- Not every constant is a paged catalogue: some are non-paged list/subset/setting endpoints, and
  `ITEM_CATALOG` is a deliberate `size=1` single-row existence probe.

## Decision

Each `CachedCatalog` constant declares a **fetch mode** — `Fetch.PAGE_WALK` or `Fetch.SINGLE` — and
`getCached` dispatches on it:

- For a `PAGE_WALK` catalogue, `getCached` walks every backend page through the shared
  `CatalogPages.fetchAll` (ADR-0102), appending `&page=0..n` to the pinned URI (whose `size=` is
  now the walk's chunk size, not a bound), and caches **one merged synthetic `PageResponse`**
  (all rows, backend `totalElements`, immutable content). Call sites stay unchanged.
- The `Class`-typed `getCached` overloads **reject** a `PAGE_WALK` constant
  (`IllegalArgumentException`): a `Class` token cannot decode the generic `PageResponse` the walk
  requires, and a plain single GET of such a catalogue would silently truncate it. Together with
  the enum-declared mode this keeps the unsafe state unrepresentable — no public API fetches a
  bounded single page of a walked catalogue.
- Hitting the `MAX_CATALOG_PAGES` runaway cap logs a **WARN** naming the catalogue instead of a
  banner: these catalogues feed pickers, sidebar fragments and advices on many pages — there is no
  page-level truncation surface, and the log line reaches Loki. The admin pages' loud banner
  (REQ-ADMIN-002) is unaffected (they use uncached walks).
- A mid-walk failure propagates unchanged and nothing is cached (`@Cacheable` does not cache
  exceptions), so callers keep their established degradation contracts and a partial catalogue can
  never be served from the cache.
- `SINGLE` stays the mode for non-paged endpoints (org-unit option lists, lookup projections,
  settings) and the deliberate `ITEM_CATALOG` probe. `FrontendCacheSplitTest` pins the mode of
  every constant alongside its URI.

## Consequences

- **Positive:** the sidebar org-unit switcher, all catalogue pickers and the profit-calculator /
  materials-matrix grids can no longer silently truncate; the fix reuses the audited ADR-0102
  helper; the cache amplification now works *for* completeness (the walk runs at most once per TTL
  per catalogue, not per render).
- **Negative / accepted:** a catalogue past its chunk size costs additional sequential backend
  round-trips on the first fetch of a TTL window (today every covered catalogue fits one chunk, so
  the common case stays one request); the `sync = true` per-key lock is held for the whole walk on
  a cache miss, which is the desired single-flight behaviour.
- **Monitoring:** no new endpoints, jobs, metrics or status enums — the walk multiplies existing
  instrumented WebClient GETs only in the overflow case (same rationale as ADR-0102); the cap-hit
  WARN flows through the existing log pipeline.

## Alternatives considered

- **Raise the single-request bounds (e.g. `size=100000` everywhere)** — rejected for the same
  reason as in ADR-0102: still a silent bound, with worse latency/memory behaviour per request.
- **Walk in the callers / in `CachedCatalogListLoader`** — rejected: the `@Cacheable` boundary is
  `getCached`, so pages 1..n would bypass the cache (or page 0 alone would be cached); every call
  site would need converting and could opt out silently.
- **A separate `getCachedComplete` method the caller must choose** — rejected: the wrong choice
  (plain `getCached` on a paged catalogue) would compile and truncate; declaring the mode on the
  constant makes the safe path the only representable one (FE-CACHE-1 doctrine).
- **A truncation banner surface for cached catalogues** — rejected: consumers are cross-page
  fragments and pickers with no shared render seam for a warning; the cap is a runaway backstop
  (≥100 000 rows at the smallest chunk), not an expected state, so a WARN log is proportionate.


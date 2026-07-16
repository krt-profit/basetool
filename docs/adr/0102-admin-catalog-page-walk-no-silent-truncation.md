# ADR-0102 — Admin catalog pages page-walk the complete catalogue instead of rendering one bounded page

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`admin-catalog-completeness.md`](../specs/admin-catalog-completeness.md)
  `REQ-ADMIN-001/002` · sibling of
  [ADR-0100](0100-catalog-pickers-search-server-side.md) (pickers search server-side; "no
  complete-list endpoint carries a silent cap" — this ADR is the same defect class on the admin
  full-list pages) · generalises the issue #823 page-walk
  (`PersonalInventoryBlueprintsPageController.fetchAllOwned`) · backend `size` clamp rationale in
  `PaginationUtil` (SEC-03)

## Context

Seven admin surfaces render a reference catalogue as "the whole list" and filter it
client-side:
`/admin/materials`, `/admin/locations`, `/admin/mission-data` (job types / squadrons / frequency
types), `/admin/special-commands`, `/admin/settings` (intake-SK picker, promotion squadron
toggles), `/admin/uex-data` (cities / space stations / outposts / POIs / terminals) and
`/ship-data` (manufacturers / ship types). Each of
them issued exactly **one** bounded backend page request (`size=1000`, UEX `size=10000`) and
presented the result as the complete catalogue.

That is a *silent-truncation* defect class: there is no pagination UI, so a row beyond the bound
cannot be viewed or edited in the admin UI **at all**, with no error and no hint. The client-side
filter autocompletes only narrow what was fetched, so searching for a truncated row finds
nothing. The UEX page compounded it by deriving its summary-chip totals from the truncated lists
(`list.size()`), so the counts lied too — and UEX terminals is the catalogue whose real-world
size comes nearest to its bound.

Forces:

- The pages' interaction model is deliberate and good for admins: the full catalogue in the DOM,
  instant client-side filtering, hierarchical grouping (UEX star-system tree). A server-side
  pagination UI would break the filters ("only narrows what was fetched" would still hold per
  page) and require redesigning seven screens.
- The backend already serves stable pages: `PaginationUtil` appends an `id` sort tiebreaker, so
  walking pages cannot skip or duplicate rows (modulo concurrent writes), and its `size` clamp is
  100 000 — high by design (SEC-03) for exactly these load-all surfaces.
- The codebase already shipped this fix shape once: issue #823 made the personal-blueprints list
  walk all pages (`fetchAllOwned`, `MAX_PAGES` safety cap) instead of showing a capped first
  page.
- An unbounded walk must not exist: a backend bug that reports inconsistent `totalPages` would
  otherwise hang the request loop.

## Decision

Admin catalogue pages fetch the **complete** catalogue by walking every backend page, through
one shared helper — `frontend support.CatalogPages.fetchAll(pageFetcher)`:

- The helper pulls page `0..n` until the backend reports the last page (or an empty page),
  concatenating the contents, and returns them together with the backend-reported
  `totalElements` and a `truncated` flag.
- `MAX_CATALOG_PAGES = 100` bounds the walk purely as a runaway backstop; at the smallest chunk
  size in use (1000) it matches the backend's own `size` clamp. Hitting the cap sets
  `truncated=true`.
- Truncation is **loud**: every covered page renders a shared warning banner
  (`admin.catalog.truncated`, via `fragments/components :: alert('warning', …)`) when any of its
  catalogue walks was truncated. A partial list is never presented as complete. On the two pages
  whose catalogue tables are re-rendered through an AJAX fragment swap (mission-data's three
  `*-results` fragments, special-commands' `results` fragment) the banner lives **inside** the
  swapped fragment — per-section flags (`jobTypesTruncated` / `squadronsTruncated` /
  `frequencyTypesTruncated`) on mission-data — so a truncation first surfaced by the
  include-inactive toggle appears (and clears) without a full reload, and a truncated job-type
  walk never banners the unaffected squadron section.
- Displayed totals come from `totalElements`, not fetched-list sizes (the UEX summary chips), so
  counts stay truthful even under truncation.
- Existing per-call chunk sizes stay as they are (1000 / UEX 10000), so a catalogue that fits one
  chunk still costs exactly one request — the walk only adds requests past the old bound.
- Exceptions from the page fetch propagate unchanged; every controller keeps its established
  error contract (error attribute, degraded empty picker, async `exceptionally` handler).

## Consequences

- **Positive:** every catalogue row is viewable and editable again regardless of catalogue
  growth; client-side filters search the true full set; UEX counts are truthful; the fix shape is
  one audited helper instead of seven hand-rolled loops; new admin surfaces of this shape have a
  ready-made primitive (REQ-ADMIN-001 makes using it binding).
- **Negative / accepted:** a catalogue past its chunk size costs additional sequential backend
  round-trips on page load (mitigated by generous chunk sizes — today every covered catalogue
  fits in one chunk); very large catalogues produce a heavy DOM, which is inherent to the
  load-all interaction model and unchanged by this decision.
- **Monitoring:** no new endpoints, jobs, metrics or status enums — the walk multiplies existing
  authenticated backend GETs only in the overflow case; existing WebClient/Resilience4j
  instrumentation covers them.

## Alternatives considered

- **Real server-side pagination UI per page** — rejected: breaks the client-side-filter
  interaction model (a filter would again only see the fetched page), and redesigning seven admin
  screens is disproportionate to the defect. Revisit if a catalogue outgrows the load-all model
  wholesale.
- **Raise the single-request bound (e.g. `size=100000`)** — rejected: still a silent bound, just
  a bigger one; a single huge response also has worse latency/memory behaviour than chunked
  walking and would invite bumping the backend clamp whenever it is hit.
- **Fail the page on overflow** — rejected: turning "catalogue grew past N" into an outage of the
  admin surface is worse than rendering the complete list slightly slower.
- **Derive totals from `totalElements` only (report's stated minimum)** — rejected as the sole
  fix: it makes the counts honest but still leaves rows unviewable and uneditable, which is the
  actual defect.


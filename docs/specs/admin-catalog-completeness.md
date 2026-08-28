> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-16.
> **Owner area:** ADMIN · **Related ADRs:** ADR-0102, ADR-0103

# Admin catalog pages — complete rendering, no silent truncation

## Context & goal

The admin reference-catalog pages (materials, locations, mission data, Spezialkommandos, UEX
data, ship data, and the admin-settings pickers) render the *whole* catalogue in one view and let the
admin narrow it with client-side filters. Historically each page fetched exactly one bounded
backend page (`size=1000` / `size=10000`) and presented that chunk as the complete list: rows
beyond the bound were invisible and uneditable in the admin UI, with no error, no pagination
control and no hint that anything was missing — and the UEX page even derived its summary
counts from the truncated lists, so the counts lied too. This spec pins the fix: every such
page assembles the complete catalogue by walking all backend pages, derives displayed totals
from the backend-reported `totalElements`, and — should the walk ever stop early at its safety
cap — says so loudly instead of pretending completeness.

The same defect class existed one layer down: the frontend's **cached** reference catalogues
(`CachedCatalog` / `BackendApiClient.getCached`, FE-CACHE-1 in
[`data-persistence.md`](data-persistence.md) REQ-DATA-007) pinned single bounded pages
(`size=1000` / `size=10000` / `size=100000`) for catalogues that pickers, the sidebar org-unit
switcher and client-side filtered grids consume as "the whole list" — amplified by the cache, which
would serve one truncated fetch app-wide for a whole TTL. REQ-ADMIN-003 extends the
no-silent-truncation rule to that layer (ADR-0103).

## Requirements

### REQ-ADMIN-001 — Admin catalog pages render the complete catalogue

An admin page that presents a reference catalogue as "the whole list" (no pagination UI, with
client-side filtering over the rendered rows) MUST fetch **every** row of that catalogue, not
one bounded page. The frontend assembles the complete set by walking the backend's pages until
the last page is reached (shared helper: `CatalogPages.fetchAll`), bounded only by a safety cap
against inconsistent backend page math. Covered surfaces: `/admin/materials` (materials),
`/admin/locations` (locations), `/admin/mission-data` (job types, squadrons, frequency types),
`/admin/special-commands` (Spezialkommandos), `/admin/settings` (promotion squadron toggles; the
intake-SK picker was removed with the anonymous order form, ADR-0149), `/admin/uex-data` (cities, space stations, outposts, POIs, terminals),
`/ship-data` (manufacturers, ship types). Any *new* admin surface of this shape falls under this
requirement as well, and an existing surface later found to match the shape is converted when it
is next touched.

**Acceptance**

- [ ] With a catalogue larger than one backend page chunk, every row is rendered / selectable
  on the page (multi-page responses are concatenated in order).
- [ ] With a catalogue that fits one chunk, exactly one backend page request is made (no
  regression for the common case).
- [ ] A backend failure keeps each page's pre-existing error contract (error banner or
  degraded empty list) — the walk does not swallow or alter it.

**Enforced by:** `CatalogPagesTest`, `AdminMaterialsPageControllerTest`,
`AdminLocationsPageControllerTest`, `AdminMissionDataPageControllerTest`,
`AdminSettingsPageControllerTest`, `AdminSpecialCommandsPageControllerTest`,
`AdminUexPageControllerTest`, `ShipDataPageControllerTest` · **Code:**
`frontend support.CatalogPages` + the seven admin page controllers · **Issues:** —

### REQ-ADMIN-002 — Truncation is loud, and counts are truthful

If a catalogue page walk stops at the safety cap (`CatalogPages.MAX_CATALOG_PAGES`) while the
backend still reports more pages, the page MUST render a prominent warning banner
(`admin.catalog.truncated`, DE + EN) — a partial list must never be presented as complete.
Displayed totals/summary counts MUST come from the backend-reported
`PageResponse.totalElements`, never from the size of the fetched list, so they stay truthful
even under truncation (the UEX summary chips are the canonical case). On pages whose catalogue
list is re-rendered through an AJAX fragment swap (mission-data's three `*-results` fragments,
special-commands' `results` fragment), the banner lives **inside** the swapped fragment — gated
by per-section flags where one page walks several catalogues — so a truncation that first
appears on the swap path (e.g. the include-inactive toggle enlarging the catalogue) is surfaced
without a full reload, and a stale banner clears the same way.

**Acceptance**

- [ ] When the walk hits the cap, the truncation model flag (`catalogTruncated`, or the
  per-section `jobTypesTruncated` / `squadronsTruncated` / `frequencyTypesTruncated` on
  mission-data) is `true` and the warning banner renders on the affected page — on both the
  full-page and the fragment-swap render path.
- [ ] When the walk completes normally, no banner renders.
- [ ] The UEX summary-chip totals equal the backend `totalElements` of each catalogue, not the
  fetched list sizes.

**Enforced by:** `CatalogPagesTest`, `AdminUexPageControllerTest` (totalElements-derived
totals), `AdminSpecialCommandsPageControllerTest` and `AdminMissionDataPageControllerTest`
(cap-hit → truncation flags) · **Code:** `frontend support.CatalogPages`,
`AdminUexPageController`, the shared `fragments/components :: alert` banner in the seven admin
templates · **Issues:** —

### REQ-ADMIN-003 — Cached reference catalogues are assembled complete inside the cache

A paged catalogue in the frontend's `CachedCatalog` allowlist whose consumers treat the cached
response as "the whole list" MUST be assembled by walking **every** backend page *inside* the
cached fetch — the cache key is the enum constant, so a walk outside `getCached` would cache only
page 0. Each constant declares its fetch mode (`Fetch.PAGE_WALK` / `Fetch.SINGLE`);
`getCached` walks a `PAGE_WALK` catalogue through the shared `CatalogPages.fetchAll`
(REQ-ADMIN-001's helper; the pinned URI's `size=` is the walk's chunk size) and caches one merged
`PageResponse`. The `Class`-typed `getCached` overloads MUST reject a `PAGE_WALK` constant, so no
public API can fetch a bounded single page of a walked catalogue. If the walk stops at the
`MAX_CATALOG_PAGES` safety cap, a WARN log line naming the catalogue MUST be emitted — these
catalogues feed pickers, sidebar fragments and advices with no page-level banner surface, so the
log (not a banner) is the sanctioned loudness here. Deliberate single-page probes are exempt and
pinned as such (`ITEM_CATALOG`, a `size=1` existence probe); non-paged list / subset / setting
endpoints are `SINGLE` by shape.

Covered page-walked catalogues: `SQUADRONS`, `SQUADRONS_UNSORTED`, `SPECIAL_COMMANDS`,
`JOB_TYPES_MISSION`, `JOB_TYPES_CREW`, `MATERIALS`, `MATERIALS_MATRIX`, `LOCATIONS`,
`SHIP_TYPES`, `SHIP_TYPES_SORTED`, `REFINING_METHODS`, `FREQUENCY_TYPES_ACTIVE`, `TERMINALS`,
`MANUFACTURERS`. A *new* paged `CachedCatalog` constant falls under this requirement unless it is
a deliberately-bounded probe documented on the constant.

**Acceptance**

- [ ] With a catalogue larger than one chunk, `getCached` issues `&page=0..n` requests and the
  cached `PageResponse.content()` contains every row in backend order; with a one-chunk
  catalogue exactly one request is made.
- [ ] The merged envelope's `totalElements` is the backend-reported total.
- [ ] A `Class`-typed `getCached` call on a page-walked constant fails fast without any backend
  request.
- [ ] A walk that hits `MAX_CATALOG_PAGES` logs a WARN naming the catalogue; a mid-walk backend
  failure propagates to the caller and caches nothing.

**Enforced by:** `FrontendCacheSplitTest` (per-constant fetch-mode + URI pins, `&page=`
appendability), `BackendApiClientHappyPathTest` (merge order, single-request common case,
`Class`-overload guard, cap-hit WARN), `CatalogPagesTest` · **Code:**
`frontend service.CachedCatalog` (`Fetch` mode), `BackendApiClient.getCached` /
`fetchCompleteCatalog` · **Issues:** —

## Out of scope

- Server-side pagination / search UIs for these admin pages. The pages' interaction model
  (client-side filter over the full catalogue) is deliberate; ADR-0102 records why page-walking
  was chosen over a pagination UI.
- Non-admin list surfaces with real pagination (hangar, refinery orders, audit log, sync
  reports, blueprints) — those are governed by their own specs.
- The backend's `size` clamp itself (`PaginationUtil.MAX_PAGE_SIZE`, SEC-03 rationale in its
  Javadoc).

## Open questions

None.

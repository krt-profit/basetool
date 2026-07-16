> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-16.
> **Owner area:** ADMIN · **Related ADRs:** ADR-0101

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

## Requirements

### REQ-ADMIN-001 — Admin catalog pages render the complete catalogue

An admin page that presents a reference catalogue as "the whole list" (no pagination UI, with
client-side filtering over the rendered rows) MUST fetch **every** row of that catalogue, not
one bounded page. The frontend assembles the complete set by walking the backend's pages until
the last page is reached (shared helper: `CatalogPages.fetchAll`), bounded only by a safety cap
against inconsistent backend page math. Covered surfaces: `/admin/materials` (materials),
`/admin/locations` (locations), `/admin/mission-data` (job types, squadrons, frequency types),
`/admin/special-commands` (Spezialkommandos), `/admin/settings` (intake-SK picker, promotion
squadron toggles), `/admin/uex-data` (cities, space stations, outposts, POIs, terminals),
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

## Out of scope

- Server-side pagination / search UIs for these admin pages. The pages' interaction model
  (client-side filter over the full catalogue) is deliberate; ADR-0101 records why page-walking
  was chosen over a pagination UI.
- Non-admin list surfaces with real pagination (hangar, refinery orders, audit log, sync
  reports, blueprints) — those are governed by their own specs.
- The backend's `size` clamp itself (`PaginationUtil.MAX_PAGE_SIZE`, SEC-03 rationale in its
  Javadoc).

## Open questions

None.

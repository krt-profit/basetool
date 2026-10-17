> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-03.
> **Owner area:** UI · **Related ADRs:** ADR-0105 (builds on ADR-0102/0103)

# Materials pages — complete lists + server-filtered matrix

## Context & goal

The two materials-browsing surfaces — the per-material detail page (`GET /materials/{id}`) and the
trade-matrix overview (`GET /materials/overview`) — render whole reference lists. The admin
page-walk (ADR-0102/0103) already assembles the cached matrix complete; this spec closes the two
remaining gaps on these pages: the detail price list must show *every* terminal that trades the
material (it still fetched one bounded page), and the matrix must filter **server-side** so the
browser receives only the matching slice instead of the whole universe (a scalability concern, not a
truncation one, now that the page-walk makes the fetched set complete). Both reuse the existing
`CatalogPages.fetchAll` page-walk — no parallel mechanism. The decision and trade-offs are recorded
in [ADR-0105](../adr/0105-materials-matrix-server-side-filtering.md).

## Requirements

### REQ-UI-014 — The trade matrix is filtered server-side

`GET /materials/overview` renders a virtual-scroll grid whose four filter dimensions — material
names, star-system names, has-loading-dock, is-auto-load — are applied by the **backend**
`GET /api/v1/materials/matrix` endpoint as optional query parameters, not by an in-memory filter over
the whole fetched universe. The frontend grid re-fetches `GET /materials/overview/data` with the
current selection as parameters whenever a filter changes (debounced, with a stale-response guard),
and the controller relays the selection to the backend. Every matrix fetch — filtered or not — is
assembled complete across all backend pages via `CatalogPages.fetchAll` (the page-walk of
ADR-0102/0103): the unfiltered default through the cached `getCached(MATERIALS_MATRIX)` page-walk,
a filtered request uncached (its URI varies by selection and is deliberately not an allowlisted
`CachedCatalog`). An absent dimension means "no filter" (a bare `/matrix` call returns the full
matrix); an empty multi-select is normalised to no filter, never an invalid `IN ()`. Filter values
are relayed as WebClient URI variables so a multi-word value is strictly encoded for the exact `IN`
match (not `URLEncoder` form-encoding, #371). The filter **option lists** are derived server-side
from the whole (page-walked) catalogue, so narrowing a filter never removes options. Because the
grid's columns are derived from the returned rows, a filtered grid shows only the terminals and
materials that have a price row inside the filtered slice (no all-empty columns) — a deliberate
behaviour, not a defect. The category-grouping toggle and category collapse remain pure client-side
presentation and trigger no re-fetch.

The page is a wide data view (up to `--content-max-data`, REQ-UI-027). Its toolbar holds the two
multi-selects as dropdown buttons that name their selection („Materialien: Alle", „System: Stanton",
„Materialien: 6 von 42"), the boolean filters behind „+ Filter" with one removable chip each, the
„Nach Kategorie gruppieren" switch and the legend („bester Verkauf" / „bester Einkauf"). In every
material row the cell with the highest sale price is ringed in the success colour and the cell with
the lowest purchase price in the info colour (ties mark every tied cell); the material cell shows
„Spanne <best sale − best purchase>" and the warning flags. Terminal heads carry their planet tint
and each cell a planet stripe; the material column and the head stay put while scrolling. The page
title carries the freshness chip „UEX · vor n min" (minutes, hours or days since the newest
`uexSyncedAt` of the cached terminal catalogue; absent when that catalogue is unavailable).

*Amended 2026-10-03 (website overhaul phase 3): page head with the UEX freshness chip, filters as
dropdown buttons with a selection summary plus „+ Filter" chips, best-sale/best-purchase markers,
the spread and the legend.*

„+ Filter" also holds „Nur mit Einkaufsmöglichkeit": it hides every material row that has no
positive purchase price in any of the shown terminals, and a category left without rows disappears
with its head. Like the grouping switch it is client-side presentation over the fetched grid and
triggers no re-fetch; it has a removable chip like the boolean filters. Every toolbar control
follows the design system's control rule (REQ-UI-001): the two dropdown buttons and the „Filter"
button (the shared `filterPopover`, `.btn-ghost` with its active-filter count) carry the control
fill and the Grau-2 border, and a dropdown with a narrowed selection is marked like a selected
control (orange tint and border).

*Amended 2026-10-17: the purchase-option row filter and the readable toolbar controls.*

**Acceptance**

- [ ] `GET /api/v1/materials/matrix` accepts optional `materialNames`, `starSystems`,
  `hasLoadingDock`, `isAutoLoad`; each absent dimension is unconstrained and a bare call returns the
  full matrix.
- [ ] An empty `IN` collection is treated as "no filter" (no `IN ()` error).
- [ ] Each filter and their intersection restrict the returned rows correctly, and hidden terminals
  / rows with no active buy-sell side are excluded under every filter combination.
- [ ] The frontend `GET /materials/overview/data` relays an active selection to the backend
  page-walk (and bypasses the unfiltered cache), while no selection uses the cached page-walk.
- [ ] Changing a filter re-fetches and re-renders; out-of-order responses are discarded; a failed
  re-fetch hides the stale grid; grouping / collapse do not re-fetch.

**Enforced by:** `MaterialMatrixQueryDataTest` (JPQL filters + intersection + hidden/active-side) ·
`MaterialControllerTest` / `MaterialServiceTest` (param relay + empty→null) ·
`MaterialsPageControllerTest` (filtered page-walk vs cached path) · **Code:**
`MaterialPriceRepository.findMatrixItems`, `MaterialService.getMatrixItems`,
`MaterialController.getMaterialMatrixItems`, `MaterialsPageController.getMatrixData`,
`static/js/catalogue/materials-matrix.js` · **Issues:** —

### REQ-UI-016 — The matrix filter selection persists per browser

The four filter dimensions of REQ-UI-014 (material names, star-system names, has-loading-dock,
is-auto-load) are **persisted per browser** in `localStorage` (single key
`materials_matrix_filters`, one JSON object) and restored into the filter widgets **before the
initial fetch**, so a reload — or a visit days later — reopens the matrix with the last-used
selection already applied; the first data request carries it. The persistence follows the
established orders-queue filter idiom (REQ-ORDERS-027): a multi-select dimension with all (or
zero) options checked is stored as `null` = "no filter", so options added to the catalogue later
are included automatically; absence of the key means "no saved preference" (server-rendered
defaults: everything checked, booleans off). On restore, saved values whose option no longer
exists are dropped; a saved subset none of whose values still exist falls back to the "all"
default. The preference is a pure client-side concern — nothing is stored server-side, and a
storage-denying privacy mode degrades to the defaults without breaking the page (same guard as
REQ-UI-010). The select-all checkbox and the dropdown button's selection summary („Alle", the one
picked name, or „n von N") are synchronised with the restored state.

*Amended 2026-10-03 (website overhaul phase 3): the dropdown summary reads „n von N" instead of „N
ausgewählt"; the boolean filters also appear as removable chips.*

*Amended 2026-10-17: the purchase-option row filter of REQ-UI-014 is persisted in the same object
(`hasBuy`, default off).*

**Acceptance**

- [ ] Changing any filter persists the whole selection immediately (not debounced with the
  re-fetch); a reload restores the widgets and the restored selection is applied to the first
  `GET /materials/overview/data` request.
- [ ] A dimension left at "all" is stored as `null` and keeps newly added options included.
- [ ] Stale saved values are dropped; an entirely stale subset falls back to "all"; with
  `localStorage` unavailable the page renders with the defaults.

**Enforced by:** `MaterialsOverviewFilterPersistenceE2eTest` (boolean filters + grouping survive a
reload; multi-select subset restored where catalogue data exists) · **Code:**
`static/js/catalogue/materials-matrix.js` · **Issues:** —

### REQ-UI-015 — The detail price list is the complete list across all pages

`GET /materials/{id}` renders the material's price-per-terminal table as the **complete** list of
every terminal that trades the material, assembled across all backend pages via
`CatalogPages.fetchAll` (ADR-0102/0103), not a single bounded page. The rendered table has no
pagination, so the assembled list must be whole: a material traded at more terminals than one backend
page holds must still show all of them. A backend failure degrades to the existing empty "not
available" placeholder rather than a partial list.

Above the table the page head names the material with chips for its category and flags (illegal as
a danger chip, the two volatility flags as warning chips), followed by four figures: „Bester
Verkauf" (highest sale price and its terminal), „Bester Einkauf" (lowest purchase price and its
terminal), „Ø Verkauf" (mean of the positive sale prices, with the number of buying terminals) and
„Aktualisiert" (age of the last UEX sweep, labelled „Letzter UEX-Sync"). The table lists Terminal ·
Ort · Verkauf · Einkauf · UEX-Stand; the location and the planet stripe come from the cached price
matrix's rows of the same material (a matrix failure only empties the location). Zero or negative
prices show as „–". The rows are sorted by sale price, highest first, terminals buying nothing last
by name. Each of the Terminal, Ort, Verkauf and Einkauf headers is a sort button of the shared
sortable header ([REQ-UI-030](ui-design-system.md)): a click on an inactive column sorts by it in
its first direction (Terminal and Ort A–Z, Verkauf highest first, Einkauf lowest first), a second
click reverses it; rows without a value stay last, ties go by terminal name, and the header carries
the matching `aria-sort`. „UEX-Stand" is not sortable. Sorting happens in the browser and keeps the
terminal search's narrowing. On a phone, where the stacked table hides its header row, the four
sort buttons stay visible as a strip above the rows, so the table sorts on every device class.

„UEX-Stand" is the time UEX last updated that terminal's price (`date_modified` of the UEX price
row, stored by the commodity sync and returned as `uexUpdatedAt` by
`GET /api/v1/materials/{id}/prices`). It is distinct from the sweep time in the „Aktualisiert"
figure: a sweep can be minutes old while a terminal's price was last reported weeks ago. The cell
shows the relative age in the same units as the figure („vor 3 Std", „vor 19 Tagen") with the
absolute time in Europe/Berlin below it, inside a `<time datetime>` carrying the exact instant. An
update **older than seven days** is stale: the age is shown in the warning colour with the warning
icon and a visually hidden „veraltet", so colour is never the only signal. A row without an update
time shows „–".

*Amended 2026-10-03 (website overhaul phase 3): chips, the four figures, the location column, the
sale-price sort. Amended 2026-10-17 (#2634): the column headers sort, the „Verkauf · Einkauf"
segment is gone.*

*Amended 2026-10-17 (#2649): the per-row „UEX-Stand" column with the seven-day stale mark; the
sweep figure is labelled „Letzter UEX-Sync".*

**Acceptance**

- [ ] The controller fetches the price list via the page-walk, not a single fixed-`size` page.
- [ ] The rendered `prices` model attribute contains the concatenation of every page's content.
- [ ] A backend failure leaves `prices` empty and surfaces the placeholder, never a half-assembled
  list.
- [ ] Every price row carries `uexUpdatedAt` (or `null`), and every table row shows its relative UEX
  age, the absolute time and a `<time datetime>`; a row without one shows „–".
- [ ] A row whose UEX update is more than seven days old is marked stale by icon, warning colour and
  hidden text; exactly seven days is not stale.
- [ ] Every column header of the price table is a sort `<button>`; the sale column starts active and
  descending, and no separate sort segment is rendered.
- [ ] At phone width the sort buttons are visible and sort the table.

**Enforced by:** `MaterialsPageControllerTest` /
`MaterialsPageControllerMvcTest.getMaterialDetail_*` / `MaterialTerminalPricesTest` / `UexAgeTest` /
`MaterialMatrixQueryDataTest.detailPriceList_carriesTheUexUpdateTimePerRow` ·
**Code:** `MaterialsPageController.getMaterialDetail`, `CatalogPages.fetchAll`,
`MaterialPriceRepository.findPricesByMaterialId`, `MaterialTerminalPrices`, `UexAge`,
`catalogue/material-detail.html`, `static/js/catalogue/material-detail.js` ·
**Issues:** #2649

## Out of scope

- **The page-walk mechanism itself** (`CatalogPages.fetchAll`, `CachedCatalog.Fetch.PAGE_WALK`, the
  `MAX_CATALOG_PAGES` runaway cap) — owned by [ADR-0102](../adr/0102-admin-catalog-page-walk-no-silent-truncation.md)
  / [ADR-0103](../adr/0103-cached-frontend-catalogues-page-walk-inside-cache.md); this spec only
  applies it to two more surfaces.
- **Category grouping / flat view** of the same pages — governed by
  [`materials-overview-grouping.md`](materials-overview-grouping.md) (REQ-UI-010); orthogonal, both
  apply.
- **Price freshness / UEX sync** — the overview may lag a sync by up to the catalogue cache TTL; the
  detail page stays uncached for authoritative prices. Unchanged by this spec.
- **What data a user may see** (scope, redaction) — unchanged; see
  [`security-and-access.md`](security-and-access.md).

## Open questions

- If the matrix ever needs paged infinite-scroll on the client (rather than one complete assembly of
  the filtered slice), promote that to a follow-up ADR. Not needed while a filtered slice fits a
  single virtualised grid.

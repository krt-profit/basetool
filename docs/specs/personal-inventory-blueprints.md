> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-02.
> **Owner area:** INV/UI · **Related ADRs:** [ADR-0017](../adr/0017-default-blueprints-admin-curated-materialized.md), [ADR-0024](../adr/0024-opt-in-global-blueprint-sharing.md), [ADR-0035](../adr/0035-blueprint-craftability-from-own-stock.md), [ADR-0046](../adr/0046-blueprint-craftability-bridges-piece-item-ingredients.md)

# Personal inventory — "Meine Blueprints" master-detail (V3)

## Context & goal

The personal blueprint collection (`/personal-inventory/blueprints`) uses the **master-detail
layout (V3)** approved in the design system. Binding visual sources: the V3 block of
`.claude/skills/das-kartell-design/proposals/blueprints-page-varianten.html` and the canonised
components in `krt-components.css` (`.master-detail`, `.master-list`, `.master-row(.is-active)`,
`.detail-pane`, `.quality-block`/`.quality-row`/`.quality-affects`, `.empty-state`, `.tab-nav`,
`.krt-modal*`).

The rebuild is a pure presentation restructure: every endpoint, form, and behaviour of the
previous table layout is preserved.

## Requirements

### REQ-INV-008 — Master-detail structure, deeplink, and add bar

The page head keeps the greeting plus a facts subtitle ("N Blueprints · M mit Notiz") and carries
a compact **add bar**: the typeahead search (staged selections render as compact chips under the
bar; the "Hinzufügen" CTA stays disabled until something is staged), the **JSON import**
button and the **SC Extractor release link** (REQ-INV-038). The PI tabs (Items | Blueprints)
render as a `.tab-nav` with a `.tab-count`.

The collection renders as `.master-detail`: left a filterable `.master-list` (one input doubles
as instant client-side row filter and — on submit — the existing `?q=` server filter), one
`.master-row` per blueprint (product name, ✎ marker when a note exists); right a permanent
`.detail-pane`. The active row syncs with a `?bp={id}` URL parameter (deeplink); ↑/↓/Home/End
move the selection (listbox semantics: `role="listbox"`/`option`, `aria-selected`). Empty
collection and no-selection states use `.empty-state`. On viewports ≤900px the layout collapses
to list → detail navigation with a back button in the pane.

The list loads the caller's **complete** owned set in one render — there is **no** server-side page
cap (issue #823). The page controller pages the backend in chunks and concatenates until the last
page, so the facts subtitle ("N Blueprints"), the `.tab-count`, and the instant client-side filter
all cover **every** owned blueprint, never a truncated first page. Loading the full set never blocks
the render because the heavy per-row work stays off the critical path: the recipe is lazy per
selection (REQ-INV-009) and craftability is one bulk async fetch (REQ-INV-019) — so "all are listed
and searchable, only what you open / what the badge pass covers is computed".

### REQ-INV-009 — Detail pane on the existing lazy recipe endpoint, calculation unchanged

The detail pane shows product name, "Erhalten am" (UTC → local), note edit ✎ and remove 🗑 as
icon buttons (`title`+`aria-label`, reusing the existing modal flows), and **"Zutaten &
Stat-Beitrag nach Zutat-Qualität"** as one `.quality-block` per requirement group from the
existing lazy endpoint (`GET /personal-inventory/blueprints/{id}/recipe`, cached per id): source
slot, material + quantity (SCU/units) + min. quality, ONE quality slider spanning the group's
effective band (`aria-label` "Qualität {material}", `aria-valuetext`), and the group's affected
stats as chips with **live factors**. The factor interpolation is the pre-existing
implementation, kept verbatim (`computeModifierValue`: ordered segments — interpolated when
`linear`, held constant for stepped forms — else linear interpolation across the effective
band); the mock's `1 + (max−1)·q/1000` formula is explicitly NOT used. Slider state is
view-only (no persistence). Blueprints without requirement groups fall back to the flat
ingredient list with a dash. The note renders below the recipe only when present.

### REQ-INV-010 — No regression of functions

All previous functions stay: typeahead search + staging + batch add; JSON import (file pick,
preview modal with Alle/Keine/Anwenden, per-row search/notes); `?q=` server filter; note edit
modal (optimistic-locking `version`, hidden `acquiredAt`, maxlength 2000); remove confirm
(danger `.krt-modal--danger` with `.btn-danger`, naming the product); per-blueprint recipe data;
UTC→local "Erhalten am"; CSRF hidden inputs; CSP nonces. Modals use the `.krt-modal*` frame
(one filled CTA, ghost cancel, Esc).

### REQ-INV-011 — Items page on the shared DS components

The personal inventory **items** page (`/personal-inventory`) uses the same shell as the
blueprints page: the greeting head carries a facts subtitle ("N Einträge", total element
count), the PI tabs render as the shared `.tab-nav` (Items active with a `.tab-count`,
Blueprints plain), an empty collection (optionally filtered via `?q=`) renders as an
`.empty-state` instead of an empty table row, and both modals (create/edit form, delete
confirm) use the `.krt-modal*` frame — the delete confirm as `.krt-modal--danger` with a
`.btn-danger` CTA naming the entry. Everything else is unchanged: action bar (create CTA +
`?q=` filter form), table columns, per-row edit/delete icon buttons with the
optimistic-locking `data-version` payload, UEX location typeahead, quantity sanitizing,
CSRF hidden inputs, CSP nonces, and the inline re-render of the form modal on validation
errors (`showItemModal`).

### REQ-INV-016 — Default blueprints are auto-granted to every user and non-removable

A small set of crafting blueprints is unlocked by default on every Star Citizen account (the
starter pistol, rifle, their magazines, and the Field Recon Suit pieces). These can no longer be
earned in-game and therefore never appear in an SCMDB / Basetool Blueprint Extractor import, so the
basetool MUST grant them itself.

The system MUST maintain, for **every** user, an owned `personal_blueprint` row for each entry in
the admin-managed default set (REQ-INV-017). Provisioning MUST be idempotent and MUST cover both
existing and future users:

- a brand-new user receives the defaults synchronously when their `app_user` row is first created
  (the grant runs in the same `UserService.syncUser` transaction, so the rows are committed before
  the first request returns);
- a deploy / drift is reconciled by a startup backfill and a periodic sweep
  (`DefaultBlueprintProvisioningTask`), both bulk `INSERT … SELECT … ON CONFLICT (owner_user_id,
  product_key) DO NOTHING` so a re-run never duplicates;
- adding a new default grants it to all existing users at once (REQ-INV-017).

Granting MUST use the same normalized `product_key` the rest of the blueprint feature uses, so a
granted default lines up with the catalog, the product search "owned" flag, the availability
overview (REQ-INV-012/013) and the job-order coverage view — i.e. a default counts as genuinely
owned everywhere, for every user.

A default blueprint MUST NOT be removable by the user: the personal-blueprint list **hides** the
delete control for it (driven by a non-visible `removable` flag on the response, never a visible
badge), and the delete endpoint MUST refuse a default product server-side
(`error.personalBlueprint.defaultNotRemovable` → 409). Removing a product from the default set
(REQ-INV-017) does NOT revoke the rows users already hold — they simply become ordinary, removable
owned blueprints. Provisioning can be disabled per environment via
`APP_DEFAULT_BLUEPRINTS_PROVISIONING_ENABLED`; the sweep interval is
`APP_DEFAULT_BLUEPRINTS_PROVISIONING_INTERVAL` (default `PT1H`).

**Acceptance criteria:**

- [ ] Given a user with no default blueprints, when provisioning runs, then a `personal_blueprint`
  row exists for each default and a re-run inserts nothing further.
- [ ] Given a default blueprint in a user's list, then the list renders no delete control for it,
  and a direct `DELETE` of it returns 409 with code `BUSINESS_CONFLICT`.
- [ ] Given an admin removes a product from the default set, then users keep the rows they already
  hold and those rows become removable.

**Code links:** [`DefaultBlueprintProvisioningService`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/DefaultBlueprintProvisioningService.java),
[`PersonalBlueprintRepository#grantDefaultBlueprintsToAllUsers`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/repository/PersonalBlueprintRepository.java),
[`UserService#syncUser`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/UserService.java),
[`DefaultBlueprintProvisioningTask`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/task/DefaultBlueprintProvisioningTask.java),
[`PersonalBlueprintService#requireRemovable`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/PersonalBlueprintService.java).

### REQ-INV-017 — Admin curation of the default-blueprint set

The default set lives in the `default_blueprint` table and is curated by administrators, so it can
follow CIG's starter loadout without a code deploy. The set is **seeded once** on first boot from a
curated starter list (`DefaultBlueprintCatalog`), guarded by a `defaultBlueprints.seeded`
`SystemSetting` flag so a later admin removal is never resurrected; each starter name is resolved
against the live blueprint catalog to stamp the canonical key / name / output item, and an
unresolved name is still seeded (degraded) so the default is granted regardless.

Administrators MUST be able to list the set, add a product (picked from the blueprint catalog
type-ahead, resolved to a `product_key` + name + output item), and remove an entry, through an
ADMIN-gated surface (`/api/v1/admin/default-blueprints`, `@PreAuthorize("hasRole('ADMIN')")`). A
duplicate add MUST return 409. Adding MUST immediately grant the new default to all existing users
(REQ-INV-016).

**Acceptance criteria:**

- [ ] Given a fresh database with the catalog populated, when the app boots, then the starter
  defaults are seeded once and granted to all users, and a reboot does not re-seed.
- [ ] Given an admin adds a catalog product as a default, then it appears in the set and every user
  gains the owned row; adding the same product again returns 409.
- [ ] Given a non-admin calls the default-blueprint admin endpoints, then the request is rejected
  with 403.

**Code links:** [`DefaultBlueprintService`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/DefaultBlueprintService.java),
[`AdminDefaultBlueprintController`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/controller/AdminDefaultBlueprintController.java),
[`DefaultBlueprintBootstrap`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/config/DefaultBlueprintBootstrap.java),
[`V157__create_default_blueprint.sql`](../../backend/src/main/resources/db/migration/V157__create_default_blueprint.sql).

### REQ-INV-018 — Opt-in global blueprint sharing

By default a user's owned `personal_blueprint` rows count toward the leadership
blueprint-availability overview (REQ-INV-012) and the item-order blueprint-coverage view
(REQ-ORDERS-015) **only for the org units the user is a member of**. A user MAY opt in, via a
toggle on their profile page, to share their blueprints **globally**: while
`app_user.share_blueprints_globally` is `true`, the user is counted in **both** views for
**every** org unit, even when org-unit membership would otherwise exclude them — so a Staffel
member's blueprint can satisfy an SK order's coverage across org-unit boundaries.

This is a deliberate, user-controlled carve-out to the org-unit scoping ([ADR-0024](../adr/0024-opt-in-global-blueprint-sharing.md));
it preserves the surrounding guarantees:

- **Opt-in only**, default off (`NOT NULL DEFAULT FALSE`); nothing changes for users who do not
  enable it.
- It widens **whose** blueprints are counted, never **who may open** the views — the
  viewer-access gates (`OwnerScopeService.canAccessBlueprintOverview`,
  `canSeeJobOrderBlueprintOwners`) are unchanged, so a non-member still cannot open either view.
- **Read-only** exposure by **display name only** (`User.getEffectiveName()`, never the `sub` or
  e-mail); it grants no edit rights on the owner's blueprints.
- In both views, an owner shown **only** via this opt-in (not a member of the relevant org unit) is
  marked with a **discreet "not a unit member" hint**, so leadership can tell a cross-unit volunteer
  apart from their own members at a glance. In the admin "all org units" overview scope — where no
  single unit applies — no owner is marked.

The flag is self-service: the user reads / sets it through `GET` / `PUT
/api/v1/users/me/blueprint-sharing` (JWT-scoped, optimistic-locked), saved in place on the
profile page (REQ-FE-001) next to the payout preference. The two aggregations union the opted-in
users' `owner_user_id`s into their org-unit member set before counting; an owner who is both a member
and a global sharer is counted once.

**Acceptance criteria:**

- [ ] Given a user enables the toggle and owns a required blueprint, when the coverage view of an
  order whose responsible org unit they are **not** a member of is built, then they are counted
  and appear as an owner row.
- [ ] Given the same user, then they appear in the availability overview of a leadership viewer
  who oversees an org unit the user does not belong to.
- [ ] Given the user disables the toggle, then they are removed from both views again.
- [ ] The opt-in never lets a viewer open a view they could not already open (the access gates
  are unchanged), and the owner is exposed by display name only.
- [ ] An owner shown only via global sharing is rendered with a discreet "not a unit member"
  marker in both the availability drill-down and the order-coverage view; a genuine member of the
  relevant unit is not marked.
- [ ] Given a user who never opts in, then they are counted only in their own org units
  (unchanged behaviour).

**Code links:** [`UserService#updateUserShareBlueprintsGlobally`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/UserService.java),
[`UserController`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/controller/UserController.java) (`/me/blueprint-sharing`),
[`PersonalBlueprintOverviewService`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/PersonalBlueprintOverviewService.java),
[`JobOrderItemBlueprintOwnersService`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/JobOrderItemBlueprintOwnersService.java),
[`V163__add_share_blueprints_globally_to_user.sql`](../../backend/src/main/resources/db/migration/V163__add_share_blueprints_globally_to_user.sql).

### REQ-INV-019 — Craftability of own blueprints from "My Inventory" stock

The blueprint view annotates each of the caller's owned blueprints with whether and how many times
it can be crafted **right now** from the caller's own stock, the output stats that stock's quality
would deliver, and what is missing — answering "what can I craft now, how often, with what stats,
and what's short?" without opening every recipe by hand ([ADR-0035](../adr/0035-blueprint-craftability-from-own-stock.md), issue #781).

It is **strictly owner-scoped** (JWT `sub`): owned blueprints, stock, and refinery yield all come
from the caller — no other user's data is ever read. It is **read-only** (no migration), computed
over existing data via `GET /api/v1/personal-blueprints/craftability?includeRefinery={false|true}`.

- **Stock source.** The caller's "My Inventory" / "Mein Lager" entries (`InventoryItem` where
  `user == me`, i.e. the `/inventory/my` set — both personal and shared rows the user owns), pooled
  **per material across all storage locations**. This is NOT the free-text personal-inventory
  feature; matching is by the real `Material` FK, so it is exact.
- **Ingredient scope.** **RESOURCE** (commodity) ingredients are evaluated, and so are the **ITEM**
  ingredients the wiki counts in pieces but that resolve to a shared `material` by name — a hand-mined
  gem such as Hadanite or Beradom the wiki models as a non-craftable game item. Such an ITEM is
  **bridged** to its PIECE material (the same bridge the job-order path applies in
  `JobOrderItemService.bridgedMaterial`, so a craftability figure and the order requirement consume
  the identical `material` row) and evaluated as that requirement. A **craftable** ITEM (the output of
  another blueprint — a genuine sub-assembly) and an unresolved ITEM carry no material and are marked
  "not evaluated"; a recipe with no evaluable material requirement at all is reported as not
  assessable ([ADR-0046](../adr/0046-blueprint-craftability-bridges-piece-item-ingredients.md)). The
  bridge is resolved once for the caller's whole owned set (one craftable-output query, one game-item
  load, one material-by-name query) so the cost stays bounded.
- **Qualifying stock.** Only stock at or above a per-material **quality floor** counts toward
  availability **and** the effective quality. The floor is the stricter of (a) the ingredient's
  `min_quality` and (b) the **no-degradation floor**: the lowest quality at which none of the slot's
  stat modifiers would *worsen* the output. Worsening is defined on the multiplier (the only datum
  available — there is no absolute base stat): below neutral (×1.0) for a `higher`-is-better stat,
  above neutral for a `lower`-is-better stat. A modifier that worsens across its entire band imposes
  no floor (it is treated as inherently penalised and ignored) so a recipe never silently becomes
  uncraftable.
- **Craftable count.** Per material the recipe's RESOURCE lines are aggregated into one required
  amount per craft; `N = floor( min over materials of ( qualifying available / required ) )`. The
  binding ("limiting") material is named.
- **Quantity unit (SCU vs Stück).** A RESOURCE ingredient may resolve to a material whose
  `QuantityType` is `PIECE` rather than `SCU`, and a bridged ITEM ingredient is always a PIECE
  material counted in whole units. The computation is done in the material's **own unit**
  on both sides — `InventoryItem.amount` and the folded-in refinery yield are already piece counts
  for a PIECE material, and the per-craft requirement is **rounded to a whole piece** (the same
  rounding the job-order path applies, `JobOrderItemService.roundForQuantityType`), so a recipe never
  demands a fractional piece and the count stays in step with the rest of the app. The breakdown
  carries each material's `quantityType`, so the UI labels every amount "SCU" or "Stück" and formats
  pieces as whole numbers (matching the inventory views); SCU materials keep their fractional amount.
- **Effective quality & projected stats.** The effective ingredient quality is the **SCU-weighted
  average of the best-quality qualifying stock consumed first**, over one craft's requirement. Each
  build slot's stat slider in the detail pane **defaults to** that effective quality (instead of the
  band maximum), so the chips show the stats the user's own material would actually deliver; the
  slider still lets the user explore other qualities. Stat projection reuses the existing modifier
  model verbatim (`computeModifierValue`: ordered segments honoured, else linear across the band).
- **Refinery fold-in.** A view toggle, **default OFF**, folds in the yield of the caller's own
  `OPEN` + `IN_PROGRESS` refinery orders ("not yet completed or cancelled"); the refinery yield's
  quality participates in the effective-quality calculation. Every figure is produced **twice**
  (inventory alone, and with refinery), so the toggle switches client-side without a refetch. A
  blueprint craftable only thanks to the refinery is marked (`⟢`).
- **UI.** The master-list row carries a craft-status badge (`×N` craftable / `fehlt` not craftable /
  `×N ⟢` refinery-only); the detail pane shows the craftable counter, the limiting material, and a
  per-material consumption / shortfall breakdown. The craftability toolbar (which hosts the refinery
  fold-in toggle) also carries a **"show only craftable" view filter** — a client-side toggle,
  default OFF, that hides every master row not currently craftable. It is a pure view filter over the
  already-fetched craftability data (no refetch), honours the refinery toggle (a refinery-only
  craftable blueprint passes the filter iff the refinery toggle is on) and combines (AND) with the
  master search filter; like the refinery toggle it lives outside the swapped list fragment, so its
  state survives an add / import / remove re-render. Follows the DAS KARTELL master-detail design
  (status-green / warning-yellow / research-blue accents, orange reserved for the add CTA),
  responsive across all four device classes, all strings via i18n (de + en + fallback).

**Acceptance criteria:**

- [ ] Given the caller owns a blueprint and enough qualifying RESOURCE stock, then the row and
  detail show `×N` with `N = floor(min over materials of available/required SCU)`, pooled across
  all locations.
- [ ] Given a material has stock spread over several quality tiers, then the reported effective
  quality is the SCU-weighted average of the best-quality stock consumed first over one craft, and
  the slot slider defaults to it.
- [ ] Given stock below an ingredient's `min_quality` or below the no-degradation floor, then that
  stock does not count toward availability or the effective quality.
- [ ] Given a not-fully-craftable blueprint, then the detail lists the short materials and the
  shortfall in SCU and the row shows "fehlt".
- [ ] Given a RESOURCE ingredient that resolves to a `PIECE`-quantity material, then its required /
  available / missing amounts are computed and displayed in whole pieces labelled "Stück" (the
  per-craft requirement rounded to a whole piece), not as fractional SCU.
- [ ] Given the refinery toggle is on, then the caller's `OPEN` + `IN_PROGRESS` refinery yield is
  added (quantity and quality), counts are recomputed, and a blueprint craftable only via refinery
  is marked `⟢`.
- [ ] Given the "show only craftable" filter is on, then the list shows only rows currently craftable
  (`×N` badge); rows shown as `fehlt` or not-evaluated are hidden, the filter combines with the
  search text, and turning the refinery toggle on additionally reveals the refinery-only craftable
  rows.
- [ ] Given a recipe with a non-craftable PIECE-material ITEM ingredient (a hand-mined gem such as
  Hadanite or Beradom), then it is bridged to that material and evaluated like a RESOURCE PIECE
  requirement (required / available / missing in whole "Stück", and it can be the limiting material);
  a craftable sub-assembly or an unresolved ITEM is marked "not evaluated", and a recipe with no
  evaluable requirement reports as not assessable.
- [ ] Everything is owner-scoped by JWT `sub`; no other user's blueprints, stock, or refinery
  orders are visible.

**Code links:** [`BlueprintCraftabilityService`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/BlueprintCraftabilityService.java),
[`BlueprintModifierMath`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/BlueprintModifierMath.java),
[`PersonalBlueprintController#craftability`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/controller/PersonalBlueprintController.java),
[`InventoryItemService#getOwnedStockSlices`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/InventoryItemService.java),
[`RefineryOrderService#getOwnedOpenRefineryYieldSlices`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/RefineryOrderService.java),
[`BlueprintCraftabilityDto`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/model/dto/BlueprintCraftabilityDto.java),
[`PersonalInventoryBlueprintsPageController#craftability`](../../frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/controller/PersonalInventoryBlueprintsPageController.java),
[`personal-inventory-blueprints-recipe.js`](../../frontend/src/main/resources/static/js/personal-inventory-blueprints-recipe.js).

### REQ-INV-021 — Import preview: auto-selected top suggestion, honest include checkbox

In the JSON-import preview modal (REQ-INV-010) every parsed row carries an **include checkbox** that
is the single source of truth for what "Anwenden" imports: a row is imported **iff** its checkbox is
ticked **and** it has resolved to a product. The two states must never disagree — a ticked row always
carries a resolved `product_key`, an unresolved row is never ticked. The preview groups rows by match
status:

- **Auto-matched** (exact / alias / structural tag, REQ-INV-006/007/019) — carry their resolved
  product and render ticked.
- **Suggestions** (a fuzzy candidate set, no exact match) — **auto-select their top suggestion**: the
  row resolves to the highest-ranked candidate (the name already displayed) and renders ticked, so a
  plain "Anwenden" imports the suggested match with no extra confirm click. The user may pick a
  different suggestion / search hit (which re-points the row's product and keeps it ticked) or untick
  the row to skip it. A suggestion-less row (none offered) resolves to nothing and stays unticked.
- **Unmatched** — resolve to nothing and render unticked; the row becomes ticked only after the user
  picks a product via the inline search.
- **Already owned** — rendered disabled, never re-imported.

*Why:* the previous preview pre-ticked suggestion rows but left them unresolved, so "Anwenden"
silently skipped them unless the user first clicked the suggestion button — even though the suggested
name was already shown (issue #824). Tying the checkbox to a resolved product, and defaulting a
suggestion to its top candidate, makes "ticked ⇒ will be imported as shown" always true. "Anwenden"
still learns a `blueprint_external_alias` for every resolved pick (REQ-INV-020), so a confirmed
suggestion auto-matches on the next import.

**Acceptance**

- [ ] A suggestion row renders ticked with `data-key` set to the top suggestion's product key, and
  "Anwenden" with no further interaction imports it as that product.
- [ ] Picking a different suggestion / search hit re-points the row's product and leaves it ticked;
  unticking excludes it from "Anwenden".
- [ ] An unmatched row (no suggestion) renders unticked with no product key and is excluded from
  "Anwenden" until the user resolves it; a suggestion-less suggested row likewise stays unticked.
- [ ] No row is ever submitted while ticked-but-unresolved or unticked-but-resolved (checkbox ⇔
  resolved product).

**Enforced by:** manual Preview verification (no JS unit-test harness in CI; the JS is lint-gated by
eslint/prettier and the server-side apply contract — blank/unresolvable picks skipped — by
`BlueprintImportServiceTest`) · **Code:**
[`personal-inventory-blueprints-import.js`](../../frontend/src/main/resources/static/js/personal-inventory-blueprints-import.js)
(`renderRow`, `apply`),
[`BlueprintImportService#applyImport`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/BlueprintImportService.java)
· **Issues:** [#824](https://github.com/krt-profit/basetool/issues/824)

### REQ-INV-022 — Admin twin shares the member page's design-system surface

The ADMIN-gated twin `/admin/personal-blueprints` (`AdminPersonalBlueprintsPageController`) — which
lets an administrator pick a member and manage that member's owned blueprints — MUST render on the
same DAS KARTELL design-system components as the member page (REQ-INV-008…010), not the legacy
table/dialog markup it had drifted to. Specifically:

- the owned-blueprint list renders on the DS `.data-table` (shared canonical table treatment), with
  the empty result as a centred `.krt-pi-empty` cell;
- the note-edit, remove-confirm and JSON-import dialogs use the `.krt-modal*` frame (head / body /
  foot, icon close with `aria-label`, exactly one filled CTA + ghost cancel, Esc) — never native
  `.modal` / `.modal-content` / `.close (×)`; the remove confirm is `.krt-modal--danger` with a
  `.btn-danger` CTA naming the product (no `.krt-danger !important`);
- the multi-select staging tokens render as DS `.chip.chip--primary` (the shared `renderStaging`
  emits one chip path for both pages); and
- the ADMIN-mode banner is a flat primary tint — no gradient, which the DS reserves for the greeting
  banner.

The admin-only member `<select>` is the one extra element and intentionally stays a full reload
(switching the member changes the per-user endpoints embedded in the inline script, which a fragment
swap cannot refresh); the owned-list `?q=` filter is an in-place fragment swap of `#bp-results`
(REQ-FE-002), and the edit / remove / import flows reuse the member page's wiring verbatim
(`data-trigger`, IDs, optimistic-lock `version`, CSRF hidden inputs, CSP nonces).

*Why:* the twin was the largest remaining design-system gap between the two pages (legacy
`.krt-table`, `.modal`+`.close (×)` dialogs, `.btn krt-danger !important`, the bespoke `.krt-bp-chip`
and a gradient banner). Putting it on the shared components removes that gap and keeps a single chip
implementation rather than a second bespoke one.

**Acceptance**

- [ ] The owned-blueprint list renders as a `.data-table`; no `.krt-table` / `.krt-pi-table` remains
  on the page.
- [ ] All three dialogs use `.krt-modal-overlay` / `.krt-modal`; the remove confirm is
  `.krt-modal--danger` with a `.btn-danger` CTA, and no `.modal` / `.close (×)` / `.krt-danger`
  remains.
- [ ] Staged selections render as `.chip.chip--primary`; the admin banner has no gradient.
- [ ] The member `<select>`, the `#bp-results` filter swap (REQ-FE-002), and the edit / remove /
  import flows keep working unchanged.

**Enforced by:** [`AdminPersonalBlueprintsPageControllerMvcTest`](../../frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/controller/AdminPersonalBlueprintsPageControllerMvcTest.java)
(admin gate + `fragment=results` swap target), the frontend lint gates
(`htmlhint` / `stylelint` / `eslint` / `prettier`) and `MessageBundleConsistencyTest` (DE/EN/default
i18n) · **Code:**
[`admin/personal-blueprints.html`](../../frontend/src/main/resources/templates/admin/personal-blueprints.html),
[`personal-inventory-blueprints.js`](../../frontend/src/main/resources/static/js/personal-inventory-blueprints.js)
(`renderStaging`), [`personal-inventory.css`](../../frontend/src/main/resources/static/css/personal-inventory.css)

### REQ-INV-023 — "Delete all my blueprints" one-click clear

The blueprint page (`/personal-inventory/blueprints`) offers a single control that clears the
caller's **entire removable owned-blueprint set** in one action, so a user who wants to start over
(e.g. before a fresh SCMDB import) does not have to remove blueprints one by one.

- **Scope = removable only.** The clear preserves the auto-granted **default blueprints**
  (REQ-INV-016) exactly as the per-row remove guard (`requireRemovable`) does: it deletes every owned
  row whose `product_key` is **not** in the `default_blueprint` set and leaves the defaults intact
  (they would only be re-provisioned anyway). A set of only defaults (or an empty set) clears nothing.
- **Owner-scoped, JWT `sub`.** `DELETE /api/v1/personal-blueprints` derives the owner from the token,
  never the request, and removes only that caller's rows. It returns the number of removed blueprints
  (`PersonalBlueprintBulkDeleteResult`) so the UI can confirm the outcome. Implemented as one
  set-based bulk delete (`PersonalBlueprintRepository#deleteRemovableByOwnerSub`) — no per-row
  `@Version` churn.
- **Guarded, in-place, no reload.** The control opens a `.krt-modal--danger` confirm that names the
  consequence ("removes all your blueprints; defaults are kept; cannot be undone"). On confirm the
  form write goes through `krtFetch.submitForm` (the form-write standard, REQ-FE-002) and the
  collection card re-renders in place (REQ-FE-001/005) with a "{n} Blueprints entfernt" toast — no
  full-page reload; the classic `POST → redirect` is the no-JS fallback. The button hides itself once nothing removable remains (it lives outside the swapped
  fragment, so `recountAndSync` re-toggles it on every list swap).
- **Not audited.** Personal blueprints are not part of the audited `PERSONAL_INVENTORY` area (which
  covers `PersonalInventoryItem` stock only); like the existing single remove, the bulk clear logs at
  INFO and records no audit event.

**Acceptance criteria:**

- [ ] Given a user owns removable blueprints plus defaults, when they confirm "delete all", then the
  removable rows are gone, the defaults remain, and the toast/return count equals the number removed.
- [ ] Given a user owns only defaults (or nothing), then the control is hidden and a direct `DELETE`
  removes nothing (count `0`).
- [ ] The clear is owner-scoped: another user's blueprints are never touched.
- [ ] On success the list re-renders in place (no full-page reload); the no-JS path redirects with a
  success toast.

**Code links:** [`PersonalBlueprintController#deleteAll`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/controller/PersonalBlueprintController.java),
[`PersonalBlueprintService#deleteAllOwn`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/PersonalBlueprintService.java),
[`PersonalBlueprintRepository#deleteRemovableByOwnerSub`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/repository/PersonalBlueprintRepository.java),
[`PersonalInventoryBlueprintsPageController#deleteAll`](../../frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/controller/PersonalInventoryBlueprintsPageController.java),
[`personal-inventory-blueprints.js`](../../frontend/src/main/resources/static/js/personal-inventory-blueprints.js)
(`submitDeleteAll`).

### REQ-INV-024 — Admin "delete all users' blueprints" global purge

The admin blueprint surface (`/admin/personal-blueprints`) offers a **global purge** that clears the
removable owned blueprints of **every** user at once — a maintenance/reset tool for administrators.

- **Scope = removable only, all owners.** Like REQ-INV-023 it preserves the auto-granted defaults
  (REQ-INV-016), deleting every user's owned rows whose `product_key` is not in the `default_blueprint`
  set, so the purge never fights the default-provisioning sweep. One set-based bulk delete
  (`PersonalBlueprintRepository#deleteAllRemovable`); the removed count is returned.
- **ADMIN-gated.** `DELETE /api/v1/admin/personal-blueprints`, `@PreAuthorize("hasRole('ADMIN')")`; a
  non-admin is rejected with 403. The service logs the purge at WARN.
- **Explicit-warning guard (wipe-reset-grade).** Because it removes data across all users, the control
  is a distinct danger zone (always visible, independent of the member picker) whose **type-to-confirm**
  modal keeps the submit disabled until the administrator types the confirmation token (`LOESCHEN`),
  mirroring the Kartellbank wipe-reset hurdle. On confirm the form write goes through
  `krtFetch.submitForm` (the form-write standard, REQ-FE-002) with a
  "{n} Blueprints entfernt" toast; if a member's owned list is on screen it re-renders that fragment in
  place (REQ-FE-002) — no full-page reload; the classic `POST → redirect` is the no-JS fallback.
- **Not audited** (see REQ-INV-023).

**Acceptance criteria:**

- [ ] Given several users own removable blueprints plus defaults, when an admin confirms the purge,
  then every user's removable rows are gone, all defaults remain, and the returned/toasted count
  equals the total removed.
- [ ] The purge submit stays disabled until the exact token is typed; a non-admin call returns 403.
- [ ] After the purge, a member's owned-list fragment re-renders in place (no full-page reload); the
  no-JS path redirects with a success toast.

**Code links:** [`AdminPersonalBlueprintController#deleteAllForAllUsers`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/controller/AdminPersonalBlueprintController.java),
[`PersonalBlueprintService#deleteAllForAllUsers`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/PersonalBlueprintService.java),
[`PersonalBlueprintRepository#deleteAllRemovable`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/repository/PersonalBlueprintRepository.java),
[`AdminPersonalBlueprintsPageController#deleteAllUsers`](../../frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/controller/AdminPersonalBlueprintsPageController.java),
[`admin-personal-blueprints-purge.js`](../../frontend/src/main/resources/static/js/admin-personal-blueprints-purge.js).

### REQ-INV-038 — SC Extractor release link in the add bar

The JSON import (REQ-INV-021) consumes an export the member has to produce with the desktop
**Basetool SC Extractor** first, so the add bar carries the way to obtain that tool directly next
to the import trigger it feeds — a member who has no extractor yet no longer has to leave the tool
and search for it.

- Renders as `<a id="krt-bp-extractor-link" class="btn btn-ghost">` with the `#krt-icon-download`
  glyph, placed **immediately after** the "JSON importieren" button and before the delete-all
  danger control, so import and "get the importer" read as one pair.
- Targets GitHub's canonical latest-release redirect
  `https://github.com/krt-profit/basetool-sc-extractor/releases/latest`, so it never has to be
  bumped per release. The URL is hardcoded in the template, matching the existing external-link
  pattern of [`fragments/footer.html`](../../frontend/src/main/resources/templates/fragments/footer.html)
  (handbook / repository links) — no configuration property, no backend round-trip.
- Opens in a new tab (`target="_blank"` with `rel="noopener noreferrer"`), so the collection, the
  staged selection and any open detail pane survive the click.
- Lives in the page chrome **outside** the swapped `#krt-bp-list` fragment, so it is unaffected by
  every batch-add / import / remove re-render (REQ-FE-005). It carries no state and triggers no
  mutation, so there is nothing to live-update and nothing to audit.
- Label and tooltip come from the **shared** bundle keys `scExtractor.download.button` /
  `.title` (DE + EN) rather than a page-scoped key: the refinery create page carries the same
  control next to its screenshot import (`REQ-REFINERY-019`,
  [`refinery-screenshot-import.md`](refinery-screenshot-import.md)), and the two must not
  drift apart in wording or target URL.

**Acceptance criteria:**

- [ ] The Blueprints page renders the link in the add bar directly after the JSON import button,
  pointing at the extractor repository's `releases/latest` URL.
- [ ] The link opens in a new tab and carries `rel="noopener noreferrer"`.
- [ ] After an in-place list re-render the link is still present and unchanged.

**Code links:** [`personal-inventory-blueprints.html`](../../frontend/src/main/resources/templates/personal-inventory-blueprints.html),
[`PersonalInventoryBlueprintsPageControllerMvcTest`](../../frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/controller/PersonalInventoryBlueprintsPageControllerMvcTest.java).

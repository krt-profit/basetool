> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-21.
> **Owner area:** INV/UI · **Related ADRs:** none

# Blueprint availability overview — list & drill-down contract

## Context & goal

The leadership oversight page "Blueprint-Verfügbarkeit" (#364) lists, per product, how
many members of the caller's oversight org units own the crafting blueprint, with a lazy
per-row drill-down that fetches the owning members' display names. Expanding a row is the
page's hot path: it is clicked repeatedly while browsing, so it must stay responsive
regardless of how many blueprints the table lists and how many users exist in the system.
This spec pins the performance contract of that drill-down after a regression where every
expand briefly froze the UI (a `tr:has(details[open])` sibling CSS rule re-evaluated style
across the whole table per toggle) and the admin-scoped owners fetch ran a redundant
all-owners table scan whose result was echoed back as an unbounded SQL `IN` list.

The functional contract of the page (who may see it, what the list aggregates, that owner
identity is exposed as display name only) is documented on
`PersonalBlueprintOverviewService` / `PersonalBlueprintOverviewController` and in
[`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md).

## Requirements

### REQ-INV-012 — Owner drill-down stays responsive

The list aggregates one row per **variant family** (`REQ-INV-015`): a base item and its cosmetic
variants collapse onto one row whose owner count spans the whole family (magazines stay atomic), and
the row's key is the family key. Expanding or collapsing a row must not perform work that scales with
the size of the table, and the owners fetch must not perform work that scales with the number of
users outside the requested family.

**Acceptance**

- [ ] Toggling a row's details shows/hides only that row's companion details-row, via a
  class toggled on the element itself by `blueprint-overview.js`; no CSS rule may
  derive the companion row's visibility from a `:has()`/sibling selector that the
  browser has to re-evaluate across the table on every toggle.
- [ ] The drill-down expands the row's family key to its concrete product keys **once** via the
  cached `BlueprintVariantFamilyCatalog` (a base plus its cosmetic variants — usually a handful),
  rebuilt only on the periodic blueprint sync, so an expand click never rescans the blueprint master.
- [ ] For the admin "all org units" scope, the owners lookup queries by that **family product-key
  set alone** (`findAllByProductKeyIn`); it must not enumerate all distinct owner subs first nor pass
  them back as an `IN` restriction.
- [ ] Non-admin scopes keep the owner-restricted lookup (family product keys + in-scope member subs
  — **unioned with the subs of users who opted into global blueprint sharing, REQ-INV-018** —
  `findAllByProductKeyInAndOwnerSubIn`), resolved server-side: the client cannot widen the scope,
  the multi-user data-isolation rule is unaffected, and the opt-in widens only *whose* blueprints
  are counted, never *who may open* the overview.
- [ ] The list count aggregation loads only the `(ownerUserId, productName)` pair it groups on, via the
  `findOwnerProductByOwnerSubIn` projection — never the full `PersonalBlueprint` rows — so an admin
  all-scope view does not hydrate the entire `personal_blueprint` table (every column of every
  owner's blueprints) just to count owners per family (REQ-DATA-003). The item-order owner
  drill-down (`JobOrderItemBlueprintOwnersService`) uses the same projection.

**Enforced by:** `PersonalBlueprintOverviewServiceTest`, `JobOrderItemBlueprintOwnersServiceTest`,
`PersonalBlueprintRepositoryTest`, `BlueprintVariantFamilyCatalogTest` · **Code:**
`PersonalBlueprintOverviewService`, `JobOrderItemBlueprintOwnersService`,
`BlueprintVariantFamilyCatalog`, `frontend/src/main/resources/static/js/blueprint-overview.js` ·
**Issues:** #364

### REQ-INV-013 — True server-side pagination with selectable page size

The availability list paginates server-side over **all** matching entries — the page never
fetches more rows than it displays, and no entry is silently unreachable (the previous
single `size=1000` fetch truncated anything beyond the first thousand products). The user
chooses between 10, 50 and 100 entries per page.

**Acceptance**

- [ ] The frontend page relays `page`/`size` to the backend overview endpoint and renders
  exactly one page; `size` is restricted to the whitelist {10, 50, 100} (fallback 50),
  so a crafted query string cannot restore the unbounded fetch.
- [ ] The page-nav (first/previous/next/last + "x / y" readout) and a 10/50/100 page-size
  picker render from the shared design-system pagination component (`.pagination`,
  square `.page-btn` link group — no native `<select>`); choosing a size jumps back to
  page 0; the picker is hidden while the total fits the smallest size.
- [ ] The product search executes server-side **before** pagination, so it spans every
  entry (not just the visible page), and the reported page count describes the
  filtered set.
- [ ] Paging and re-sizing never drop an active search: every generated page-nav and
  size-picker link carries the `search` parameter along.

**Enforced by:** `PersonalBlueprintOverviewServiceTest`,
`BlueprintOverviewPageControllerTest`, `BlueprintOverviewPageControllerMvcTest` ·
**Code:** `PersonalBlueprintOverviewService`, `BlueprintOverviewPageController`,
`fragments/pagination.html` · **Issues:** #364

## Out of scope

- The availability list aggregation itself (page load, one query per view) and its
  scope-gate — see `PersonalBlueprintOverviewService` and `OwnerScopeService`.
- The identically-structured expandable rows on other pages (e.g. the squadron hangar);
  they have their own row counts and are governed by their own specs.

## Open questions

None.

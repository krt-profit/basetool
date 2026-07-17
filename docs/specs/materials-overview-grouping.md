> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-07.
> **Owner area:** UI · **Related ADRs:** none

# Materials / trade pages — category-grouping view toggle

## Context & goal

The two trade-browsing pages — the material list (`GET /materials`) and the price matrix
(`GET /materials/overview`) — group their entries under the **material categories that
admins maintain** (`material.category.name`, with uncategorised items collected under
"Unsortiert"). The grouping is useful for some users and noise for others, who just want
the page's plain sort. This spec adds a per-user control to turn the category grouping off
and fall back to the page's normal ordering, and to switch back at will. It governs only
the *presentation* of already-fetched data; it changes no API, no scope, and no category
data.

## Requirements

### REQ-UI-010 — Category grouping is a per-user toggle on the trade pages

Both `GET /materials` and `GET /materials/overview` expose a control labelled
`filter.groupByCategory` ("Nach Kategorie gruppieren" / "Group by category") that switches
the listing between two views:

- **Grouped (default):** entries are grouped under the admin-defined material categories —
  the collapsible accordion on `/materials`, the category header rows on the matrix. This
  is the historical behaviour and the initial state for a user who has never toggled it.
- **Flat:** the category grouping (and its headers) is hidden and every entry is shown in
  one list in the page's **normal sort** — alphabetical by material name, case-insensitive
  — exactly as if no categories existed.

The choice is a pure client-side presentation preference: it is applied without a server
round-trip and **persisted per browser** in `localStorage` (guarded so a storage-denying
privacy mode degrades to the default grouped view rather than breaking the page), mirroring
the existing sidebar-section and inventory-row persistence. It is **not** stored
server-side and is independent of any data scope, role, or org-unit. Text filters and the
matrix's material/system/loading-dock/auto-load filters keep working in both views.

**Acceptance**

- [ ] On both pages the toggle defaults to grouped; unchecking it hides the category
  headers and renders one flat, name-sorted list; re-checking restores the grouping.
- [ ] The preference survives navigation and reload within the same browser, and the two
  pages keep their own independent preference.
- [ ] With `localStorage` unavailable the page still renders and behaves as grouped.
- [ ] Both views render the same material card / row (no data difference between views).

**Enforced by:** `MaterialsPageControllerMvcTest` (toggle + both views render; shared
`fragments/material-card.html` resolves) · **Code:** `materials.html`,
`materials-overview.html`, `static/js/materials-matrix.js`,
`fragments/material-card.html` · **Issues:** —

## Out of scope

- **Data completeness of the two pages** (the detail price list showing every terminal; the matrix
  filtering server-side over a complete page-walk) — governed by
  [`materials-pages-completeness.md`](materials-pages-completeness.md) (REQ-UI-014/015, ADR-0105).
  Both specs apply to the same pages and are orthogonal.
- **Managing the categories themselves** (admin create/rename/delete) — unchanged; see the
  admin materials page. This spec only hides/shows the grouping at read time.
- **Look & feel** of the toggle and cards — governed by the design system
  ([`ui-design-system.md`](ui-design-system.md)).
- **What data each user may see** (scope, redaction) — unchanged; see
  [`security-and-access.md`](security-and-access.md) and
  [`org-unit-tenancy.md`](org-unit-tenancy.md).

## Open questions

- Should the preference move to a server-side user setting so it follows the user across
  devices? Deferred — `localStorage` matches every other UI-state preference in the app
  today. Promote to an ADR if cross-device sync is ever requested.


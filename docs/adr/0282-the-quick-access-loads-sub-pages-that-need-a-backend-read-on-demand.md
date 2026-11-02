# ADR-0282 — The quick access loads sub-pages that need a backend read on demand

- **Status:** Accepted — amended by
  [ADR-0297](0297-the-navigation-is-one-task-grouped-default-with-desktop-modes-a-configurable-tab-bar-and-a-scoped-entity-search.md):
  entity search runs through a query-taking sibling seam, `QuickAccessSearchContributor`, behind
  `GET /quick-access/search`; the on-demand page entries of this ADR stand. Amended in place
  (Amendment 1, 2026-11-02): the tab decisions come from five capability flags of the layout
  read.
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** spec `REQ-UI-026` ([`ui-design-system.md`](../specs/ui-design-system.md)) ·
  spec `REQ-SUP-001`, `REQ-PUR-001` ·
  [ADR-0165](0165-the-frontend-layout-model-is-opt-in-not-every-handler.md) ·
  `frontend/…/shell/web/QuickAccessController.java`,
  `frontend/…/kernel/layout/QuickAccessContributor.java`,
  `static/js/kernel/krt-palette.js`

## Context

The quick access (`Ctrl+K`) offers exactly the pages a member may open, because it indexes the
links the server rendered into the drawer. Some pages are reachable only as tabs of a page in the
menu, so the quick access could not find them. Most of those tabs are shown to every member or
depend on a role, which a template can test while it renders the page.

The tabs of „Bedarf & Spenden" (incoming donations, statistics) and „Ankauf" (partners, trade
delegates, report) are different: whether a member sees them depends on the supply units they
manage and on their purchasing capabilities, which only the backend knows. Rendering them into
every page would add two backend reads to every page the layout renders, for a dialog most page
views never open.

## Decision

**Sub-pages whose visibility needs a backend read are loaded by the quick access when it is first
opened on a page, from `GET /quick-access/entries`; everything a template can decide stays
server-rendered into the page.**

1. **Rendered sources.** The palette fragment carries hidden lists of links — sub-pages and
   navigating actions — each under the same `sec:authorize` / `th:if` as the tab or button it
   mirrors. The quick access indexes them like the drawer's links.
2. **On-demand sources.** A domain that owns such tabs contributes a `QuickAccessContributor`
   (kernel interface, implemented in the domain's `web` package). It derives its entries with the
   same function the domain's pages use for their tabs (`SupplyTabs.of`, `PurchasingTabs.of`),
   returns nothing while its surface is off, and fails closed through `SectionFallback`.
3. **The endpoint.** `QuickAccessController` in `shell.web` asks every contributor, resolves the
   message keys in the caller's locale and answers JSON. It needs an authenticated session and the
   background marker; it renders no layout.
4. **The client.** `krt-palette.js` reads the endpoint once per page, on the first opening, adds
   the entries it does not have yet, and re-renders if the dialog is still open. A failed read
   leaves the rendered entries alone.

## Consequences

- No page render pays for the quick access; opening it costs at most one request per page view,
  and the supply and purchasing reads it triggers are the ones those pages already make.
- The tab bars and the quick access cannot disagree, because both call the same derivation.
- A new tab whose visibility needs the backend is offered in the quick access by adding a
  contributor, not by touching the kernel or the palette script.
- On-demand entries appear a moment after the dialog opens and are not available before the first
  opening, so a visit to such a page enters „Zuletzt besucht" only when it is opened from the quick
  access.

## Alternatives considered

- **Render every sub-page into every page.** Rejected: two extra backend reads per rendered page.
- **Leave those tabs out of the quick access.** Rejected: the tabs are exactly the pages members
  cannot find through the menu.
- **Cache the tab decisions in the session.** Rejected: unit management and purchasing roles
  change at run time, and a stale cache would offer a page the member can no longer open.

## Amendment 1 (2026-11-02) — the tab decisions are capability flags of the layout read

The navigation of ADR-0297 registers the five tabs as entries (`supply.incoming`, `supply.stats`,
`purchasing.partners`, `purchasing.delegates`, `purchasing.report`) that can be starred, placed in
the tab bar and chosen as a start page. Gated by the surface switches alone, they were offered to
every member, although only some may open them.

**Decision.** `GET /api/v1/me/layout` (and `/me/capabilities`) carries five booleans in its
capabilities — `canViewSupplyIncoming`, `canViewSupplyStats`, `canViewPurchasingPartners`,
`canViewPurchasingDelegates`, `canViewPurchasingReport` — each false while its surface is off and
otherwise decided by the module that owns the page, through its `api` seam (`SupplyTabAccess`,
`PurchasingTabAccess`) and the same access policy the reads behind the tab enforce:

| Flag | True when |
|---|---|
| `canViewSupplyIncoming` | the caller manages (lead or Logistician), or as an admin reaches, an active unit that accepts donations |
| `canViewSupplyStats` | `SupplyAccessPolicy#canReadStats`: the governing leadership or the manager of at least one unit |
| `canViewPurchasingPartners` | `PurchasingAccessPolicy#canMaintainPartners`: an active trade delegate or the governing leadership |
| `canViewPurchasingDelegates` | `PurchasingAccessPolicy#canAppointDelegates`: the governing leadership |
| `canViewPurchasingReport` | `PurchasingAccessPolicy#canReadReport`: a delegate, the governing leadership, bank staff or a unit manager |

The frontend maps them to the `NavigationFlag`s `SUPPLY_INCOMING`, `SUPPLY_STATS`,
`PURCHASING_PARTNERS`, `PURCHASING_DELEGATES` and `PURCHASING_REPORT`; each entry requires its
surface flag and its own flag. The tab bars (`SupplyTabs.of`, `PurchasingTabs.of`) and both
`QuickAccessContributor`s read the same flags, so the supply and purchasing reads they made for
this are gone. None of the flags depends on the pinned unit; a flag counts as lost user-wide only
while its surface is on, so switching a surface off deletes no stored favourite or start page.

**Consequences.** The registry, the tab bars and the quick access decide from one answer and
cannot disagree. The „Statistik" tab now follows the statistics gate exactly, so the
Organisationsleitung and the governing Bereichsleitung see it without managing a unit. The layout
read gains a few small statements, none per unit (`MeLayoutSingleTransactionTest`). The on-demand endpoint stays: the five entries keep
`NOT_INDEXED`, so retiring `GET /quick-access/entries` in favour of the rendered index is a
separate change.

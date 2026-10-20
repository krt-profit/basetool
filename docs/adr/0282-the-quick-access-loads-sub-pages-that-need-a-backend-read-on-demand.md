# ADR-0282 — The quick access loads sub-pages that need a backend read on demand

- **Status:** Accepted — amended by
  [ADR-0297](0297-the-navigation-is-one-task-grouped-default-with-desktop-modes-a-configurable-tab-bar-and-a-scoped-entity-search.md):
  entity search runs through a query-taking sibling seam, `QuickAccessSearchContributor`, behind
  `GET /quick-access/search`; the on-demand page entries of this ADR stand.
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

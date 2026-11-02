# ADR-0329 — The supply and purchasing tabs are capability flags of the layout read

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** @greluc
- **Supersedes:** [ADR-0282](0282-the-quick-access-loads-sub-pages-that-need-a-backend-read-on-demand.md)
- **Related:** REQ-UI-026 · REQ-UI-032 · REQ-SUP-013 · REQ-PUR-021 · REQ-PUR-023 ·
  [ADR-0151](0151-clients-are-told-their-authorisation.md) ·
  [ADR-0297](0297-the-navigation-is-one-task-grouped-default-with-desktop-modes-a-configurable-tab-bar-and-a-scoped-entity-search.md) ·
  spec [`ui-design-system.md`](../specs/ui-design-system.md),
  [`security-and-access.md`](../specs/security-and-access.md)

## Context

Five tabs of „Bedarf & Spenden" and „Ankauf" — „Eingehende Spenden", „Statistik", „Partner",
„Handelsbeauftragte" and „Bericht" — are shown only to some members: whether a member may open
them depends on the units they manage and on their purchasing standing, which only the backend
knows. ADR-0282 therefore kept them out of the rendered quick-access index and loaded them on the
first opening of the quick access from `GET /quick-access/entries`, through one
`QuickAccessContributor` per domain, because rendering them into every page would have cost two
extra backend reads per page.

ADR-0297 then registered the five tabs as navigation entries (`supply.incoming`, `supply.stats`,
`purchasing.partners`, `purchasing.delegates`, `purchasing.report`) that can be starred, placed in
the phone tab bar and chosen as a start page. Their gates knew only the surface switches, so the
favourites, the tab-bar pickers, the order lists and the start-page select offered all five to
every member once a surface was on.

## Decision

1. **The layout read carries the five decisions.** The `capabilities` of
   `GET /api/v1/me/layout` (and `/me/capabilities`) gain five booleans. Each is `false` while its
   surface is off; otherwise the module that owns the page decides it through its `api` seam
   (`SupplyTabAccess`, `PurchasingTabAccess`) with the access policy the read behind the tab
   enforces:

   | Flag | True when |
   |---|---|
   | `canViewSupplyIncoming` | the caller manages (lead or Logistician), or as an admin reaches, an active unit that accepts donations |
   | `canViewSupplyStats` | `SupplyAccessPolicy#canReadStats`: the governing leadership or the manager of at least one unit |
   | `canViewPurchasingPartners` | `PurchasingAccessPolicy#canMaintainPartners`: an active trade delegate or the governing leadership |
   | `canViewPurchasingDelegates` | `PurchasingAccessPolicy#canAppointDelegates`: the governing leadership |
   | `canViewPurchasingReport` | `PurchasingAccessPolicy#canReadReport`: a delegate, the governing leadership, bank staff or a unit manager |

   The flags read a few rows and no row per org unit, so the one layout read per page stays cheap.
2. **Each entry requires its surface flag and its own flag.** The frontend maps the booleans to the
   `NavigationFlag`s `SUPPLY_INCOMING`, `SUPPLY_STATS`, `PURCHASING_PARTNERS`,
   `PURCHASING_DELEGATES` and `PURCHASING_REPORT`. None depends on the pinned unit; a flag counts
   as lost user-wide only while its surface is on, so a stored favourite, phone slot or start page
   of a caller who lost the right is deleted on the next load, and switching a surface off deletes
   nothing.
3. **One answer for every surface.** The navigation, the page tab bars (`SupplyTabs.of`,
   `PurchasingTabs.of`) and the quick access all read these flags; nothing derives the tabs a
   second time.
4. **The quick access renders them like every other tab.** The five entries are ordinary
   quick-access-only entries of the rendered index. `GET /quick-access/entries`,
   `QuickAccessContributor`, `QuickAccessEntry`, `QuickAccessHiddenEntries` and the palette's
   on-demand load are removed. Entity search stays the query-taking seam of ADR-0297
   (`QuickAccessSearchContributor`, `GET /quick-access/search`).

## Consequences

- The registry, the tab bars and the quick access cannot disagree, and a tab is offered as a
  favourite, a slot or a start page only to a member who may open it.
- The five tabs are in the quick access from the first keystroke on every page, and a visit to
  one enters „Zuletzt besucht" however it was opened; no page view pays a second request for them.
- The supply-units and purchasing-state reads the tab bars and the contributors made for this are
  gone; the layout read gains a few small statements, none per unit
  (`MeLayoutSingleTransactionTest`).
- „Statistik" follows the statistics gate exactly, so the Organisationsleitung and the governing
  Bereichsleitung see the tab without managing a unit.
- The decisions are only as fresh as the page: a right gained or lost shows on the next page load,
  as for every other capability flag. Every read behind a tab still re-checks.
- A new tab whose visibility needs a backend decision gets a capability flag and a registry gate,
  not a new endpoint.

## Alternatives considered

- **Keep the on-demand endpoint beside the flags.** Rejected: once the layout read carries the
  decisions, the endpoint only repeats them through a second request and a second code path.
- **Cache the tab decisions in the session.** Rejected for the reason ADR-0282 gave: unit
  management and purchasing roles change at run time, and a stale cache would offer a page the
  member can no longer open.
- **Gate the entries on roles.** Rejected: neither unit management nor a delegate appointment is a
  role the token carries.

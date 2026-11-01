# ADR-0297 — The navigation is one task-grouped default with desktop modes, a configurable tab bar and a scoped entity search

- **Status:** Accepted — implementation in progress: decision 7 ships for missions, operations
  and materials with the prefixes, and REQ-UI-033 is shipped in full; the rest is pending, its
  requirements marked planned.
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Supersedes:** [ADR-0240](0240-navigation-is-a-structured-drawer-a-quick-access-and-a-phone-tab-bar.md)
  decisions 2, 3, 4 (for entities) and 5 in the parts named below, and its rejection of a fixed
  sidebar and an icon rail. ADR-0240 decision 1 (one header fragment) and decision 4 for pages
  (the page index is read from the rendered menu) stand.
- **Amends:** [ADR-0282](0282-the-quick-access-loads-sub-pages-that-need-a-backend-read-on-demand.md)
  — the quick access gains a query-taking sibling seam, `QuickAccessSearchContributor`.
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-026, REQ-UI-031,
  REQ-UI-032, REQ-UI-033, REQ-UI-035) · [ADR-0298](0298-navigation-preferences-live-server-side-in-a-navigation-module.md)
  (where the preferences live) · [ADR-0299](0299-the-home-page-is-a-personalisable-widget-dashboard.md)
  (home dashboard) · [ADR-0300](0300-officer-reach-is-derived-per-org-unit-and-the-keycloak-officer-role-is-retired.md)
  (per-unit capabilities) · [ADR-0176](0176-layout-floors-are-defaults-that-controls-opt-out-of.md)
  · [ADR-0177](0177-the-app-has-exactly-one-dialog-shape.md) · map #2800

## Context

A member still sees far more than ten links, because the drawer lists pages, not tasks, and many
pages are siblings of one another. Members never customise in large numbers, so a short view has to
come from the default, not from personalisation. Comparable products keep a curated default, put
favourites at the top, park hidden items under "More" instead of removing them, and never reorder
the sidebar by usage; usage ranking appears only inside the command palette.

Officers, logisticians and per-unit feature switches depend on the active org-unit context, so a
navigation that ignores the context offers pages the member cannot use there. The quick access
finds pages only; missions, orders, materials and members are not reachable by name, and their
permission rules live only in the backend. On wide screens a permanently visible navigation is a
legitimate wish; on phones the fixed tab bar shows areas a member may not use most.

## Decision

1. **One default for everyone, cut by task.** Members see 10 links in four task groups — Einsatz &
   Flotte, Logistik, Handel, Staffel — plus a Kartellbank group for bank staff; former sibling pages
   become tabs of one page. The storage page has only the tabs „Lager · Mindestbestände"; „Mein
   Lager" and „Globales Lager" become a switch inside the „Lager" tab, and their quick-access
   entries open that tab with the switch set, keeping their own entry keys. Home is the logo, the bell sits next to it, the personal pages stay in
   the user menu, the Star Citizen links move to the footer. Roles and the active context decide
   only **whether** an entry shows, never **where**: no per-role order, no pre-seeded favourites.
2. **The navigation follows the active org-unit context.** Context-bound entries use the
   capabilities of the pinned unit, or the union of the member's units when nothing is pinned. A
   plain link unavailable in the context is not rendered, in the menu and in the quick access.
3. **Administration stays a mode**, now a hub start page plus 13 links in three collapsed groups
   (Mitglieder & Organisation, Stammdaten, Betrieb). Every old URL redirects to its new page or tab.
4. **Desktop has three modes:** drawer (default), icon rail from 1025 px, pinned labelled sidebar
   from 1280 px; between 1025 and 1279 px a stored "pinned" renders as the rail, below 1025 px the
   drawer and the tab bar apply. The rail shows one icon per group; its flyouts are disclosure
   buttons opening on hover and on click or keyboard, not dialogs and not ARIA menus.
5. **Personalisation sits on top of the default:** favourites (at most 12, a copy at the top, pages
   and tabs), hiding pages into a collapsed „Mehr" area (personal pages cannot be hidden), reorder
   of groups and of links within their group without a drag (REQ-UI-031), per-section and full
   resets, and an „Anpassen" edit mode. A favourite unavailable in the context renders greyed out.
   Edit controls are at least 44 × 44 px up to 1024 px and 32 px above.
6. **The phone tab bar has five slots:** three chosen by the member, „Meldungen" and „Menü" fixed.
   The three fill automatically from the favourites, then Missionen → Lager → Aufträge, until the
   first edit stores all three; an unavailable slot shows a stand-in. One bar per mode; the admin
   default is Admin-Dashboard, Mitgliederverwaltung, Audit-Logs.
7. **The quick access searches entities through the backend.** `GET /quick-access/search?q=` asks
   one `QuickAccessSearchContributor` per entity type (mission, operation, material, blueprint,
   ship, org unit, job order by number, member) in parallel, each scoped like the page it opens and
   failing closed on its own; a member search never reveals more than the org chart. Pages keep
   coming from the rendered menu. Symbol and DE/EN word prefixes narrow the kind; a browser-side
   „Häufig" ranking over entry keys ranks pages; entity hits never enter „Häufig" or „Zuletzt".
   Palette actions only navigate or open an existing dialog.
8. **Alt+1 … Alt+9 open favourites 1–9 and Alt+0 the dashboard**, member mode only, inactive while
   a dialog is open, switchable off.

## Consequences

- REQ-UI-026 is amended and REQ-UI-032, REQ-UI-033 and REQ-UI-035 are added, all planned; the
  current navigation stays authoritative until the implementation ships.
- The frontend needs per-context capability flags from the backend (`/me/capabilities` pin-aware,
  ADR-0300) to gate links, favourites and tab slots.
- The search term is a query parameter only, never a path, MDC field or metric label;
  `basetool_quick_access_search_total{kind, outcome}` and a p95 alert on `/quick-access/search`
  cover the new endpoint.
- Every new entry gets a stable entry key and appears at its default position for everyone who may
  see it, so new features never stay invisible behind someone's customisation.
- The rail and the pinned sidebar reserve their width through `scroll-padding-inline-start`, so
  fixed chrome never hides focus.

## Alternatives considered

- **Per-role defaults** (own order or expanded groups per role): a member with several roles has
  no single answer, and a role change would move entries. Rejected for visibility-only gating.
- **A sidebar that reorders itself by usage:** static menus are faster than adaptive ones, and no
  reviewed product does it. Rejected; usage ranks only inside the quick access.
- **Personalisation instead of a shorter default:** most members never customise. Rejected.
- **Keeping the drawer as the only desktop mode** (ADR-0240): rejected; the drawer stays the
  default, the rail and the pinned sidebar are opt-in.
- **A backend page index:** still rejected; the templates decide page visibility.
- **Entity search in the browser:** the permission rules for entities exist only in the backend,
  and loading every entity per page is too heavy. Rejected.
- **Entity names in the browser's recents:** puts mission and member names in `localStorage`.
  Rejected.
- **Single-key shortcut sequences:** need an off/remap switch under WCAG 2.1.4 and do not reach the
  page in screen-reader browse mode. Rejected for Alt + digit.

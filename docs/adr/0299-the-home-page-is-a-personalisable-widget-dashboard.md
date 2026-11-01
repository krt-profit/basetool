# ADR-0299 — The home page is a personalisable widget dashboard

- **Status:** Accepted — partially implemented (the member dashboard with its framework; the remaining widgets, the admin dashboard and the start page are planned).
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-034, REQ-UI-032) ·
  REQ-BANK-064 · REQ-ORDERS-047 · REQ-INV-068 ·
  [ADR-0297](0297-the-navigation-is-one-task-grouped-default-with-desktop-modes-a-configurable-tab-bar-and-a-scoped-entity-search.md)
  · [ADR-0298](0298-navigation-preferences-live-server-side-in-a-navigation-module.md) ·
  [ADR-0301](0301-being-a-job-order-assignee-grants-read-visibility-of-the-order.md) ·
  [ADR-0302](0302-minimum-stock-levels-allocate-free-stock-exclusively-top-tier-down.md) ·
  [ADR-0039](0039-bank-holder-ledger-decoupled-from-accounts.md) · map #2800

## Context

The home page shows three fixed cards. The new navigation (ADR-0297) moves Home to the logo and
gives admin mode a hub start page, and members ask for their own entry point: their orders, low
stock, the next mission, the balances they can see. Interests differ by role and unit, so one fixed
set of cards serves nobody well, and a member who never customises must still get a calm page.
Several widgets would need a whole list to compute one number, and some areas already have a live
sync topic while others do not.

## Decision

1. **The home page is a widget dashboard the member composes, in both modes,** each mode with its
   own layout. Member default: Nächste 7 Tage and Information in the wide column, Favoriten and
   Benachrichtigungen in the narrow one. Admin default: the hub (13 links in 3 groups) as an
   ordinary, removable widget plus four admin widgets. An empty dashboard offers
   „Widget hinzufügen".
2. **Layout:** two columns, 2 : 1; each instance belongs to one column and is ordered with up/down
   buttons and Alt+Up/Down (REQ-UI-031). Phones show one column, the wide one first; one stored
   order serves every device.
3. **Instances:** a widget may be added more than once, at most 12 instances per mode. Options come
   from fixed sets — org unit (follows the active context by default, can be pinned), time range,
   row count 3 / 5 / 10, status filter — in the per-widget matrix of REQ-UI-034. Information,
   Favoriten and Hub have none. A new option later is a spec change, not a new concept.
4. **The catalogue** lists only widgets whose data the member may read. It includes „Ich
   bearbeite" (ADR-0301), „Bestand niedrig" (ADR-0302) and „Bank", which shows the balances of the
   org-unit bank accounts the member may view through one collective read (REQ-BANK-064); there are
   no personal accounts. The read is paged with the standard `Pageable` / `PageResponse` and sorted
   in the backend — the KRT account first, then the accounts of the active org-unit context, then
   by account number; the widget requests page 0 with its row count as size.
5. **Freshness:** each widget loads and fails on its own, never blocking the page. A widget whose
   area has a live sync topic updates live; the others poll every 10 minutes while the tab is
   visible, staggered, and reload when it becomes visible again.
6. **Storage** in the `navigation` module (ADR-0298): the layout is one section per mode with its
   own version, and each instance's options carry their own version. A pinned instance whose unit
   is unavailable in the context renders greyed out; losing the role a widget needs deletes it.
   The dashboards join the data export and the account merge with the other preferences
   (ADR-0298); in a merge the target account's dashboards win.
7. **Start page per mode:** any page or tab that can be a favourite, the personal pages included.
   It applies after a plain login, on launching the installed app and on entering admin mode; a
   deep link that triggered the login wins, the logo always leads to the dashboard, and an
   unavailable start page falls back to it.
8. **Editing** through „Anpassen" in the page head, a catalogue dialog and an options dialog per
   instance; the dashboard has its own „Standard wiederherstellen" besides the full reset. A
   one-time, dismissible hint points to „Anpassen".

## Consequences

- REQ-UI-034 is added (planned), with the option matrix and the catalogue as tables.
- Widgets build on the existing read endpoints, a page total serving as a count; summary endpoints
  are added only where a widget would otherwise load a whole list, and for the new data sources
  (REQ-BANK-064, REQ-ORDERS-047, REQ-INV-068).
- Changing one widget's options never collides with a reorder elsewhere.
- Polling adds load proportional to open dashboards; the 5xx alert on the widget endpoints and the
  „Navigation & Dashboard" panel watch it.

## Alternatives considered

- **A fixed launchpad of links:** duplicates the navigation and shows no state. Rejected.
- **A free grid with drag-placed tiles:** needs a drag path and a layout per breakpoint. Rejected
  for two columns ordered by buttons.
- **One version for layout and options:** an options change would 409 a reorder. Rejected.
- **An "own bank balance" widget:** the bank has no per-player account type. Rejected for the
  „Bank" widget over the viewable accounts.
- **Live push for every widget:** most areas have no topic, and inventing one per widget is out of
  proportion. Rejected for the 10-minute poll.

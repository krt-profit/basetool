# ADR-0298 — Navigation preferences live server-side in a navigation module

- **Status:** Accepted — partly implemented: the module, its root table and the favourites section
  the hidden-pages section and the phone tab-bar sections exist; the other sections and the dashboards follow.
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-032, REQ-UI-034,
  REQ-UI-035, REQ-UI-017) · [`module-boundaries.md`](../specs/module-boundaries.md) ·
  [`security-and-access.md`](../specs/security-and-access.md) (REQ-SEC-046, REQ-SEC-058) ·
  REQ-DATA-021 ·
  [ADR-0297](0297-the-navigation-is-one-task-grouped-default-with-desktop-modes-a-configurable-tab-bar-and-a-scoped-entity-search.md)
  · [ADR-0299](0299-the-home-page-is-a-personalisable-widget-dashboard.md) ·
  [ADR-0120](0120-per-browser-filter-selection-persistence.md) · map #2800

## Context

The navigation of ADR-0297 lets a member keep favourites, hidden pages, an order, a tab bar and a
display mode. These are deliberate choices a member expects on every device. The app already keeps
incidental UI state in the browser (group collapse `krt.sidebar.*`, recents `krt.recent`), which is
lost on another device and which nobody misses. A member edits on the phone and the desktop at once,
and the project's locking rule forbids a screen-wide lock that makes unrelated edits collide.
Which entries a member may see is decided in the frontend templates; the backend has no map from a
page to its permission.

## Decision

1. **Split store.** Deliberate choices live server-side per user; incidental UI state (group
   collapse, recents, „Häufig", the dismissed dashboard hint) stays in the browser.
2. **A new backend module `navigation`** (`internal` / `web`, a leaf without an `api` package) owns
   the store and the dashboard data of ADR-0299. Its tables are Flyway-owned: one root row per
   member references `app_user (id)` with `ON DELETE CASCADE` and carries the section version
   counters, and every further table references that root, so account deletion removes them
   without an event or an `identity` dependency and the data export names one member column.
3. **Five sections, each with its own version counter:** favourites with their order, hidden
   entries, the order of groups and links (per mode), the phone tab bar (per mode; no rows means
   automatic), and „Darstellung" (desktop mode, start page per mode, `shortcutsEnabled`).
   Favourite and hidden are exclusive per key; a write that touches both checks both versions in
   one request. The backend enforces the caps (12 favourites, 3 tab slots).
4. **Entries are stable entry keys** from a frontend registry (e.g. `missions.list`,
   `orders.demand`), not URL paths. The backend validates the format only; a renamed page keeps its key or gets an
   explicit remap. A key the member did not order is placed after its default predecessor, so a
   release needs no data migration.
5. **Entries the member may no longer see are deleted on the next load.** The frontend detects them
   while it builds the navigation and asks the backend to delete them; an entry that is only
   unavailable in the active context is kept.
6. **Owner-only:** only the member reads, edits and resets their own preferences; there is no admin
   view or admin reset. **Not audited:** personal UI preferences are outside the `AuditDomain`
   areas, and the requirement says so.
7. **No live push.** Every page load renders the stored state; an open dashboard picks it up with
   its 10-minute poll. A 409 reloads only the affected section and shows „In einem anderen Fenster
   geändert – bitte erneut versuchen"; the change is dropped, the other sections stay.
8. **The full reset in the profile clears everything in both modes** — the five sections, the
   dashboard and the start page — behind a confirmation.
9. **The preferences and the dashboards are member data for the privacy flows.** They are part of
   the Art. 15 / Art. 20 export in their own section (REQ-SEC-058) and of the admin account merge
   (REQ-SEC-046); the module implements the `platform.api` participants `PersonalDataExportSection`
   and `UserReassignmentParticipant`, which its allowed `platform::api` dependency already covers.
   In a merge the target account's sections win: favourites are unioned (the target's first, then
   the other account's, deduplicated, cut at 12), hidden entries are unioned minus every entry that
   is a favourite, and order, tab bar, „Darstellung", the start pages and the dashboards are the
   target's.

## Consequences

- Preferences follow the member across devices, and a tab-bar edit on the phone never 409s a
  favourite edit on the desktop.
- The backend stays free of page-permission knowledge, and no role-revocation path has to remember
  the preferences; the price is that stale rows live until the member's next page load.
- Preferences are an explicit exemption from the peer live-update rule of REQ-FE-010, stated in
  REQ-UI-032.
- REQ-SEC-058, REQ-SEC-046 and REQ-DATA-021 gain planned amendments for the export section and
  the merge rule.
- `module-boundaries.md` and arc42 §5 gain the module; `basetool_navigation_preference_writes_total`
  `{section, outcome}`, `basetool_navigation_personalised_accounts{section}` and a 5xx alert on the
  preference endpoints cover it.

## Alternatives considered

- **Everything in `localStorage`:** lost on every other device and browser; no comparable product
  relies on it for deliberate choices. Rejected.
- **One JSON document with one version:** a coarse lock that makes unrelated edits collide, which
  the locking rule forbids. Rejected for relational sections with own counters.
- **URL paths as keys:** a moved or renamed page would orphan every stored entry. Rejected.
- **Backend-side key-to-permission mapping** to delete entries on role loss: duplicates the
  template rules and needs a hook on every revocation path. Rejected.
- **Keeping revoked entries dormant:** a regained role would resurrect stale choices. Rejected.
- **Live push across tabs:** preferences are personal, not a shared surface. Rejected.
- **Auditing preference writes:** no business state changes. Rejected.

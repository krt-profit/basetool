# ADR-0120 — Per-browser filter-selection persistence as an app-wide convention

- **Status:** Accepted
- **Date:** 2026-07-23
- **Deciders:** @greluc
- **Related:** REQ-UI-017 (`docs/specs/ui-design-system.md`) · REQ-UI-016
  (`docs/specs/materials-pages-completeness.md`) · REQ-ORDERS-027 · ADR-0105 (server-side matrix
  filtering) · ADR-0093 (CSP class-based visibility, unaffected)

## Context

A July-2026 audit of every frontend filter surface found the persistence story wildly
inconsistent. A handful of surfaces persisted their filters per browser (orders queue
REQ-ORDERS-027, bank request-queue/dashboard/org-layout modules, the grouping toggles, and — as of
REQ-UI-016 — the price matrix), while the majority did not: the Lager views, Materialbörse,
Raffinerie queue, profit calculation, mission/operation lists, promotion surfaces and the admin
filter pages all reset on reload. Three failure classes existed: state lost even on F5
(Materialbörse's `history:false` swaps, the bank chart range, client-only blueprint toggles),
state that dies with the tab (promotion-manage's sessionStorage), and URL-only state that survives
F5 but not a fresh navigation the next day. The Raffinerie queue was the starkest inconsistency —
the sibling job-orders queue persists the exact same status-filter family.

Alternatives considered: **(B)** server-side per-user preferences (DB or Redis) — follows the user
across devices, but adds a write per filter click, a schema/session surface, and a sync question
for every future filter; no user has asked for cross-device filters (recorded as an open question
on the grouping spec already). **(C)** relying on URL parameters + bookmarks — keeps deeplinks
shareable but demonstrably fails the "next day" expectation and covers none of the
`history:false` surfaces. **(D)** a single shared filter-store JS module — DRYer, but every page
already hand-rolls the small guarded read/write idiom (orders, bank, materials), a shared module
would touch every template's script tags for marginal gain, and the idiom is ~15 lines.

## Decision

Filter **selections** persist per browser in `localStorage` as an app-wide convention
(REQ-UI-017): one JSON object per page under a single key (bank surfaces key per user, matching
their existing modules), written immediately on every change, restored at init through the page's
existing update path exactly once, with explicit URL filter parameters winning over stored state
and being re-persisted. Multi-selects store `null` for "all" so later-added options stay included.
Free-text search fields and date-range inputs are deliberately excluded — a silently restored
stale search term or old date window hides data in a way users read as loss. The idiom stays
per-page (no shared module), mirroring the file it lives in; `sessionStorage` uses for filter
state are migrated to `localStorage`.

## Consequences

- Users keep their working filter context across reloads, sessions and days, on every covered
  surface; behaviour is uniform instead of per-page folklore.
- The preference is per browser, not per account across devices — accepted; promoting to a
  server-side user setting remains an open question and would supersede this ADR's storage choice
  (the widget semantics would stand).
- Two users sharing a browser profile share non-bank filter prefs (bank modules already key per
  user); accepted as the existing app-wide trade-off.
- Every future filter widget must ship with persistence wiring (REQ-UI-017 makes a missing one an
  incomplete change), and its restore path must reuse the page's existing fetch/swap — the
  convention adds no new network mechanisms.
- Stored keys are additive and unversioned; a page that changes its filter shape must tolerate an
  older stored object (the guarded-read + shape-check idiom already does).


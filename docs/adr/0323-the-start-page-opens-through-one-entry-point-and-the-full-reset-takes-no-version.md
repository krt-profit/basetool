# ADR-0323 — The start page opens through one entry point, and the full reset takes no version

- **Status:** Accepted
- **Date:** 2026-11-01
- **Deciders:** @greluc
- **Related:** REQ-UI-032 · REQ-UI-034 · REQ-UI-020 · [ADR-0298](0298-navigation-preferences-live-server-side-in-a-navigation-module.md) ·
  [ADR-0299](0299-the-home-page-is-a-personalisable-widget-dashboard.md) · spec
  [`ui-design-system.md`](../specs/ui-design-system.md)

## Context

ADR-0299 gives each mode a start page that opens after a plain login, when the installed app
launches and when administration mode is entered, and lets a deep link win. ADR-0298 promises a
confirmed full reset. Three questions stayed open: where the redirect lives, where the start pages
are stored, and how the reset meets the optimistic locks.

## Decision

1. **One entry point, `GET /start[?mode=admin]`.** The login success handler's default target, the
   `start_url` of the web app manifest and the sidebar's switch into administration mode lead
   there. It resolves the stored entry key against the entry registry and the caller's access and
   redirects to the entry's registry path, or to the mode's dashboard (`/`, `/admin`) when there is
   no start page, the key belongs to the other mode, or the entry is not open to the caller here.
   Nothing the request carries becomes the target. The dashboard itself never redirects, so the
   logo, `/` and Alt+0 always open it.
2. **A saved deep link wins; the bare root and assets do not count as one.** A visitor who opens
   `/` and is sent to sign in did not follow a deep link. The success handler drops such a saved
   request and goes to `/start`; any other saved request is followed as before.
3. **The start pages live in the „Darstellung" section** (two nullable columns of
   `navigation_appearance`) and share its version. A start-page write has its own endpoint, so the
   desktop-mode and shortcut writes need not echo the pages. Deleting a key the member may no
   longer see clears a start page that names it and bumps the section version.
4. **The full reset takes no version and moves every version on.** It is the member's explicit
   wish to start over, so it does not echo the sections' versions; it clears favourites, hidden
   pages, both orders, both tab bars, „Darstellung" with both start pages and both dashboards in
   one transaction and bumps every counter, a dashboard layout's included. A window that still
   holds an older version conflicts on its next write instead of restoring the cleared state.

## Consequences

- The installed app's `start_url` is `/start` instead of `/`; `scope` and `id` stay the root.
- Switching into administration mode navigates only when an administration start page is set.
- A reset on a member without any stored row creates the root row and the two dashboard rows so
  that their counters can move on.

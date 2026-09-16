# ADR-0013 — Refresh on bfcache history-restore with a global `pageshow` reload

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** @greluc
- **Related:** spec REQ-FE-008, REQ-FE-001 ([`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) · ADR-0012 · epic #571

## Context

Epic #571 (ADR-0012) made every in-page mutation update the DOM in place — no full-page reload on a
successful edit. That contract holds for the **active** document. It says nothing about a document the
browser later replays from its **back/forward cache (bfcache)**.

A bfcache restore reinstates the in-memory DOM and JS heap captured when the user navigated away. It
does **not** re-run the GET. So a server-rendered aggregate on an overview page keeps its pre-edit
value after the user edits the entity elsewhere and navigates back. Reported symptom: a deposit on the
bank account-detail page updates that page's balance in place (correct), but pressing the browser back
button shows the dashboard account card with the **old** balance until a manual reload.

Three forces constrain the fix:

- **It is cross-cutting, not bank-specific.** Every server-rendered overview (missions, orders,
  hangar, inventory, refinery, bank) reached by back-navigation after an edit has the same latent
  staleness. A bank-only fix would leave the class of bug everywhere else.
- **HTTP cache headers do not suppress bfcache reliably.** Spring Security already sends
  `Cache-Control: no-store, …` on every response, yet modern Chromium / Firefox / WebKit still restore
  no-store documents from bfcache. The HTTP cache and the bfcache are different mechanisms.
- **The restored document has no single swap target.** It is an arbitrary page; there is no
  `data-refresh` fragment the in-place foundation could re-render. The dashboard exposes no fragment
  endpoint, and building per-page restore handlers would re-implement the whole page anyway.

## Decision

We will add **one global `pageshow` listener** in `common-handlers.js` (loaded on every page via
`fragments/head.html`) that calls `window.location.reload()` exactly when `event.persisted` is true —
the precise signal of a bfcache restore. The handler is registered independently of the `krtEvents`
delegation registry so a missing registry cannot drop it.

This is the **second sanctioned reload** of REQ-FE-001, beside the optimistic-lock conflict confirm.
It does not contradict the no-reload posture: `event.persisted` never fires on an ordinary load or any
in-page interaction, only on a genuine history restore, where a reload is the *expected* behaviour for
a data-driven view. It cannot loop — the fresh document the reload produces fires `pageshow` with
`persisted === false`.

## Consequences

- Every server-rendered page is fresh after a back/forward restore, app-wide, from a single ~5-line
  handler — no per-page work, no fragment endpoints.
- A back-navigation into a bfcache-eligible page costs one extra GET. This is a correctness-over-
  micro-optimisation trade we accept; the alternative is showing stale money/inventory figures.
- A page the user was mid-scroll/mid-filter on, when restored from bfcache, resets to its fresh
  server render (scroll/top, default filter unless encoded in the URL). Acceptable: the restored
  snapshot was stale anyway, and list filters are already URL-encoded (REQ-FE-005) so they survive.
- Enforced by `BfcacheRefreshE2eTest`, which drives a synthetic `pageshow{persisted:true}` and asserts
  the reload discards a live-document marker — deterministic across all three engines because
  `PageTransitionEvent.persisted` is settable from the constructor.

## Alternatives considered

- **Do nothing / document the manual reload** — leaves a real correctness bug (stale balances) on
  every overview reached by back-navigation. Rejected.
- **Bank-dashboard-only handler** — smaller blast radius, but the same latent staleness remains on
  every other overview page; the fix is identical in cost whether scoped or global. Rejected in favour
  of the global handler (explicit owner decision, 2026-06-15).
- **`Cache-Control: no-store` / bfcache opt-out headers** — does not reliably evict the page from
  bfcache on current Chromium / Firefox / WebKit, and fully disabling bfcache would also lose its
  legitimate instant-back benefit on pages that have no staleness. Rejected.
- **Targeted fragment re-render on `pageshow`** — would honour the no-reload spirit, but the restored
  document is an arbitrary page with no single swap target; it would mean a fragment endpoint and a
  bespoke restore handler per page, re-rendering the whole page anyway. Disproportionate. Rejected.

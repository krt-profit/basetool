# ADR-0110 — The one-click ingest handoff is consumed off the navigational GET (prefetch-safe)

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** @greluc
- **Related:** refines [ADR-0018](0018-desktop-ingest-gateway-device-grant.md) (desktop ingest
  gateway + device grant) · spec REQ-INGEST-003/-004 ([desktop-ingest.md](../specs/desktop-ingest.md))
  · builds on ADR-0012/0013 (krtFetch fragment-swap, `REQ-FE-005`)

## Context

The desktop extractor's one-click send stages the matched draft in Redis under
`ingest:handoff:<sub>:<handoffId>` and opens the matching basetool page with `?handoff=<id>`. The
staged entry is **single-use**: the first successful read for the correct `sub` performs an atomic
`GETDEL` (REQ-INGEST-003).

Until now the **refinery** surface consumed the handoff **directly on the navigational page GET**
(`GET /refinery-orders/create?handoff=<id>` → `applyRefineryHandoff` → `GETDEL`), pre-filling the
form server-side in the first render. That makes a **GET destructively single-use** — a violation of
the HTTP "safe method" contract that any well-behaved prefetcher, link-scanner, or duplicate-load
path is entitled to break.

On **2026-07-19** a member reported the persistent `ingest.handoff.notFound` notice
("Import-Link abgelaufen oder ungültig") on **every** send, while the manual JSON upload always
worked and a re-login changed nothing. Prod logs settled it:

- The gateway's stage line and the frontend's consume-miss line carried the **same** masked `sub`
  hash (`u-9d10b624`) — **not** a subject mismatch — and the miss landed **~2 s** after staging —
  **not** the 30-minute TTL (REQ-INGEST-003, the previous fix). Both prior hypotheses ruled out.
- The edge access log showed **two identical top-level GETs** of `…/create?handoff=<id>` ~250 ms
  apart, same client IP and Firefox UA, empty referer: the **first** rendered the pre-filled form
  (larger body), the **second** rendered the empty form with the notice (smaller body). A different
  member on Chrome issued a **single** GET and succeeded.

The token was therefore burned by the **first** of two client-side navigations (a speculative
prefetch / duplicate load — a browser-side behaviour, hence deterministic per user, invisible to
single-GET browsers, and immune to re-login), and the user only ever saw the second (error) render.
The manual upload survives because it is a `POST` and is never duplicated by prefetch.

The **blueprint** surface was already structurally safe — its page GET never consumes; a page-module
`fetch` to `/staged` does — but that `fetch` was itself a side-effecting `GET`.

## Decision

**Consume the handoff off the navigational GET; perform the single-use consume only on an explicit,
script-initiated request that a page prefetch never issues.**

1. **Refinery.** `GET /refinery-orders/create?handoff=<id>` no longer consumes. It renders the empty
   owner-prefilled form and carries the pending id to the page module (`pendingHandoffId` →
   `REFINERY_HANDOFF_ID`). A new **`POST /refinery-orders/import-handoff`** (X-Requested-With,
   `@PreAuthorize("isAuthenticated()")`) performs the one-time `consume` and returns the existing
   `refinery-orders-create :: refineryImportFormBody` fragment — the exact machinery the manual
   screenshot-import already uses. `refinery-orders-create.js#_loadRefineryHandoff` issues that
   `POST` once on load, strips `?handoff=` from the address bar first (so a reload does not re-POST a
   consumed id), and swaps the fragment in place (krtFetch/`krt:swapped`, no reload — REQ-FE-005). A
   miss swaps in the fragment carrying the `ingest.handoff.notFound` notice; a transport failure
   shows an inline toast over the intact empty form.

2. **Blueprints.** `/staged` changes from `@GetMapping` to **`@PostMapping`**;
   `personal-inventory-blueprints-import.js#loadHandoff` calls it with `method: 'POST'` + CSRF
   headers. The page GET already never consumed; this removes the last side-effecting GET so the
   consume is uniformly a `POST` across both surfaces.

Single-use, per-`sub` scoping, the TTL, and the masked stage/consume correlator (REQ-INGEST-003) are
**unchanged** — only the *trigger* for the consume moves off the navigational GET.

## Consequences

- A speculative prefetch or a duplicate top-level load of the pre-fill URL fetches inert HTML, runs
  no script, and cannot consume. Only the real navigation's single `POST` burns the token, so the
  reported class of failure is closed for the observed mechanism.
- The pre-fill now arrives via an in-place fragment swap (a few hundred ms after load) instead of the
  first server render — consistent with the manual-import UX and the live-update standard
  (REQ-FE-001/-005). No full-page reload on success.
- **Residual, accepted:** if a browser performs two *full* renders that both execute the page script
  (e.g. certain container-tab extensions, not the observed prefetch), the second `POST` still misses
  and shows the notice in the surviving tab. Making that fully idempotent would require a short
  grace-window replay cache — a (bounded) relaxation of strict single-use — which was **not** taken
  here: the evidence (a single notifications SSE per send → a single full script execution) points to
  prefetch, which this decision fixes without weakening the single-use guarantee.
- No new role, metric, migration, or audited mutation — the consume deletes a transient Redis pickup
  and persists nothing (ROLES_AND_PERMISSIONS.md, monitoring, and the audit log are untouched).


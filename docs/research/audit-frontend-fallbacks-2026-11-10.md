# Frontend reauthentication fallback inventory

This source audit covers all 24 top-level frontend areas and the shared kernel. It checks broad
catch clauses and `CompletableFuture.exceptionally` callbacks against REQ-FE-036 and REQ-SEC-012.
The owning backend seam identifies session loss; local fallback code must propagate that signal
before publishing an empty section, unavailable counter or failed-mutation toast.

| Domain | Confirmed backend fallback clauses guarded |
| --- | ---: |
| Bank | 1 |
| Blueprints | 7 |
| Catalogue | 21 |
| Hangar | 4 |
| Identity | 21 |
| Inventory | 2 |
| Job orders | 12 |
| Mission finance | 3 |
| Notification preferences | 1 |
| Operations | 3 |
| Org units | 9 |
| Personal inventory | 6 |
| Refinery | 7 |
| Settings | 1 |
| Supply | 1 |
| Total | 99 |

The shared `MutationResponseHelper` and `BackendErrorResponses` guard their callbacks too.
Additional guards cover `QualityTierCatalog`, `LayoutContextLoader`, `OrgUnitContextAdvice`, nine
parallel fallback callbacks across Hangar, member Hangar and mission-data administration, the
mission option-list join, job-order page joins and quick-access search. The guard preserves the
original reauthentication exception through nested wrappers and converts raw OAuth authorization
failures into that same public contract. `BackendRoleSyncFilter` maps session loss before MVC via
the handoff shared with the terms gate; HTML, AJAX and SSE retain their respective contracts.

The domain audit distinguished 113 broad domain clauses and 31 kernel clauses. The remaining
clauses handle pure UUID/time/payload parsing, JSON decoding, SSL initialization, message resolution,
deferred CSRF access, or Redis/session/socket cleanup. Audit and purchasing use no broad fallback
catches. Dashboard, navigation, leadership and material exchange use typed backend handling or
explicit session-loss propagation; exchange's broad clauses decode JSON only.

Live-sync probes use captured bearer tokens without an OAuth refresh filter: backend rejection
maps to `DENY_TOKEN_REJECTED` and closes the socket with 4401. Initial notification/SSE and WebSocket
token capture deliberately fail soft while browser polling or live-sync reconnect performs the
normal reauthentication flow. Send/close/disposal failures remain transport lifecycle handling.
Logout, OAuth callbacks and error handling remain exempt from the role-sync handoff.

`BackendFallbackReauthenticationTest` exercises actual fallback controllers/services from every
affected domain plus the catalogue and layout kernel. Direct and nested session loss must escape
without flash feedback; an ordinary backend 503 must retain the local fallback. Shared mutation,
parallel Hangar/member/catalogue, mission option-list and quick-access tests cover the common and
asynchronous seams. Existing controller tests retain known 403/409 and classic form behavior; the
per-file broad-catch ratchet prevents new catch-all handling.

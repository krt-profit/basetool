# ADR-0078 — Mission-page fragment-gated reads + multi-user scale hardening

- **Status:** Accepted
- **Date:** 2026-07-06
- **Deciders:** @greluc
- **Related:** ADR-0031 (live mission sync over the presence WebSocket) · ADR-0077 (breaker ignores
  4xx) · ADR-0071 (client-side per-section write serialization) · REQ-SEC-011 (per-IP rate-limit
  attribution) · `MissionPageController` · `mission-detail.js` · `mission-presence.js`

## Context

The mission detail page must stay error-free for two workloads: a single user editing rapidly
(crew job-types, check-in/out, reassignment) and **200 concurrent viewers with 20 concurrent
editors**. Live update is a **refetch fan-out** (ADR-0031): when any user mutates, every peer
re-GETs the changed section fragment (`GET /missions/{id}?fragment=X`), coalesced 400 ms and
deferred while editing.

A multi-agent audit found the dominant defect: `MissionPageController.missionDetail()` **ignored the
`fragment` parameter when building the model** — it ran the full page's ~6–9 uncached backend reads
(mission aggregate, `/users/lookup`, `/users/me/pickable-org-units`, `/unit-ship-options`, and the
finance trio incl. `finance-entries?size=1000`) for *every* fragment refetch, then used `fragment`
only to pick which Thymeleaf fragment to return. So one peer's crew-board refetch still pulled the
finance ledger and the manager pickers. At 200 viewers a single edit fanned out to ~2,600 backend
DB-touching GETs in one coalesce window, starving the prod Hikari pool (15) → 500s → the **shared**
`backendApi` circuit breaker opened → a fleet-wide 503 outage repeating every 5 s. The same
amplification could 429 a single active viewer against the per-IP global bucket, and a mass presence
reconnect (frontend redeploy / blip) made all 200 clients resync-refetch every section in one
un-jittered burst. Two narrower self-409 races were also found: a rapid edit of a participant right
after check-in, and back-to-back owning-org-unit reassignments, both echoing a stale `data-version`
captured before the async refetch repainted it.

## Decision

Cut the amplification at the source and widen the funnel behind it; make rapid same-item edits
version-safe.

1. **Fragment-gate the controller reads.** `missionDetail()` computes `fullRender`/`needMgmt`/
   `needCrewBoard`/`needFinance` up front and issues each expensive backend read only when the
   requested fragment renders it: the finance trio + rounding-mode only for `finance`/full;
   `/users/lookup` + owner picker only for `mgmt`/full; `/unit-ship-options` only for
   `crew-board`/full. The single mission-aggregate read stays unconditional. The attribute→fragment
   mapping is verified against `mission-detail.html`; skipped reads default to empty so no fragment
   dereferences a missing attribute. A section refetch drops from ~6–9 uncached backend GETs to ~1–2.
2. **Widen the DB funnel.** Prod Hikari `maximum-pool-size` 15 → 40 (well under the db-backend
   Postgres cap of 100) so a residual burst queues briefly instead of exhausting the pool. This is
   defence-in-depth *behind* (1), not a substitute — the amplification is O(peers × sections ×
   reads) and out-scales any pool bump.
3. **Jitter the presence reconnect.** `mission-presence.js._scheduleReconnect` uses full jitter
   (`Math.random() * backoff`) so a mass socket drop no longer makes all viewers reconnect — and
   resync-refetch — in the same instant.
4. **Version-writeback on rapid edits.** On check-in/out success the fresh `@Version` from the slim
   response is synced to every `[data-participant-id]` container synchronously (before the async
   refetch), closing the self-409 window on a user's own sequential edits. The owning-org-unit
   reassignment now does the same: `changeMissionOwningOrgUnit`'s `krtMissionWrite` payload reads
   `owningOrgUnitVersion` lazily in a thunk at send time (mirroring the steps/objectives
   `stepsVersion()` pattern) and its `onSuccess` writes the bumped version back to
   `#owning-org-unit-row` before the serialized `section:owningOrgUnit` chain releases the next
   queued write, so back-to-back reassignments no longer self-409.
5. **Dedicated SSE connection pool.** The notification SSE relay's streaming `WebClient` shared the
   request path's `WebClientConfig.connector(...)` sizing — a 100-connection Netty
   `ConnectionProvider`. But each viewing browser holds one long-lived (~30 min) frontend→backend
   stream for its whole page lifetime, and a streaming connection is never returned to the pool until
   the stream ends, so 100 was a hard ceiling on *concurrent live viewers*: the 101st blocked on the
   10 s `pendingAcquireTimeout` then failed, silently dropping that user's live notification push. The
   streaming connector now builds a separate `frontend-sse-pool` (`maxConnections=1000`, no
   `maxLifeTime` so a long stream is never evicted as "too old"), sized for a full mission audience on
   the 16 GB host, while the request path keeps its own 100-slot `frontend-pool`.

## Consequences

- A 200-viewer / 20-editor mission stays within the DB pool and never trips the shared breaker under
  legitimate live-update fan-out; a single active viewer stays well under the per-IP 5000/min bucket.
- Rapid check-in-then-edit on one participant no longer self-409s.
- The fragment path is now the lean path — future fragments must fetch only what they render; a new
  heavy read added unconditionally would reintroduce the amplification. A MockMvc guard asserting a
  non-finance fragment issues no finance backend calls is the regression fence (follow-up).
- Existing `MissionPageControllerMvcTest` / `MissionWriteControllerMvcTest` pass unchanged — the
  full-page render is byte-identical; only fragment renders skip reads their template never used.
- Follow-ups landed: the finance-ledger pagination + SQL-aggregate summary (a new
  `GET /finance-entries/summary` endpoint computes the strip's totals in one grouped query instead of
  the `size=1000` load-all; the entries table is bounded to a page and the list endpoint is capped
  server-side at 500); the prod Hikari pool raised 40 → 100 backed by the matching infra change
  (Postgres `max_connections=150` and a 1536M `db-backend` RAM limit, so 100 app connections plus the
  exporter, Flyway and admin fit with headroom); the owning-org-unit self-409 writeback (decision 4);
  and the dedicated `frontend-sse-pool` SSE connection-pool sizing pass (decision 5).
- Still open: a receive-side refetch token bucket as defence-in-depth.

## Alternatives considered

- **Only raise Hikari / the bulkhead.** Rejected as the primary fix: it raises the threshold but
  leaves the O(peers × sections × reads) amplification, which out-scales any reasonable pool.
- **Push rendered fragments over the presence socket instead of re-pulling.** Rejected: every peer
  re-pulls through its own authorized, redaction-applying fragment endpoint (ADR-0031); pushing
  rendered HTML would bypass per-viewer guest redaction and the member-only finance gate.
- **A short-TTL backend cache on the mission read.** Deferred: viable and complementary (collapses
  199 identical concurrent reads), but the redaction contract requires caching the un-redacted
  aggregate and redacting per-viewer after the cache; fragment-gating removes the dominant cost
  first with no redaction risk. Reconsider if a single mission's read rate still bites post-gating.


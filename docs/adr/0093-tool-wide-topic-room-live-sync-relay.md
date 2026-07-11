# ADR-0093 — Tool-wide topic-room live sync over one multiplexed WebSocket with Redis pub/sub fan-out

- **Status:** Accepted
- **Date:** 2026-07-10
- **Deciders:** @greluc
- **Related:** REQ-FE-015 · REQ-FE-010 · REQ-NOTIF-006/-010 · REQ-OBS-011 · ADR-0031 (mission relay — generalized here) · ADR-0016 (notification SSE — its Redis deferral is discharged here) · ADR-0079 (Redis AOF/noeviction) · ADR-0084 (readiness excludes optional externals) · ADR-0085 (5000-account capacity) · #1102 · #1115 · #1120

## Context

REQ-FE-010 states the live multi-user sync standard broadly — *"on any surface where several
users can see the same state, a peer's change propagates to the others without a manual
reload"* — but the implementation covers exactly one surface fully (mission detail, ADR-0031)
plus a hand-forked copy for the Materialbörse board. Three registries of the same
single-instance shape exist side by side:

1. `MissionPresenceWebSocketHandler` / `MissionPresenceService` — per-mission rooms, presence
   dots + `changed` relay, in-memory `sessionsByMission` (ADR-0031 names this the Redis
   swap-out point and knowingly defers it).
2. `MaterialboardPresenceWebSocketHandler` — a fork of (1): one global room, relay-only.
3. `NotificationStreamService` (backend) — per-user SSE emitter queues; its Javadoc carries
   the "multi-instance fan-out via Redis pub/sub is a noted follow-up (ADR-0016)" caveat.

Issue #1102 (owner-confirmed scope) closes the coverage gap tool-wide — operations (#1115),
the mission Verwaltung Organisation panel (#1120), the Aufträge queue and the Kartellbank —
and explicitly forbids a bespoke second sync stack per surface. Two sizing constraints are
binding: **5000 registered accounts and ≥200 concurrently active users** (extending
ADR-0085), and no regression of the shipped mission behaviour (its two-browser-context e2e
tests are the anchor).

Exploration surfaced one requirement that dominates the transport choice: **cross-topic
publishes**. A requester (or an anonymous guest) creating an order on `/orders/create` must
make the staff queue re-fetch, although requesters are *excluded* from viewing that queue; a
bank employee confirming a booking request must reach the staff room, the affected account
room(s) and the org-unit member room in one action; an org-unit responsible holder's owner
approval must reach the staff rooms they cannot join.

## Decision

### One multiplexed socket per tab — `/ws/sync` with topic rooms

Replace the per-resource socket model with **one WebSocket per tab** carrying
`subscribe` / `changed` / presence frames that name a **topic** (`mission:{uuid}`,
`operation:{uuid}`, `order:{uuid}`, `orders`, `bank:{accountId}`, `bank`, `orgunit-bank`,
`materialboard`). A server-side **topic registry** (`LiveSyncTopicClass`) is the single
source of truth for each class: section whitelist, scope (global vs per-resource),
presence flag, and the authorizing check. A parity test derives the client seam maps from
the shipped JS and asserts set-equality with the registry, turning the REQ-FE-010
"three mirror points" convention into a build gate.

Per-page sockets (the straight generalization) were rejected because publish and
subscription would stay welded together: a page that must notify a room it may not join has
no channel at all, and the bank surfaces would need 3–4 parallel sockets per action page.

### Authorization: subscribe strictly, publish loosely, both bounded

- **Subscribe** requires the per-topic authorizing check (the same read the page performs:
  `GET /api/v1/missions/{id}`, `GET /api/v1/operations/{id}`, `GET /api/v1/orders/{id}`,
  capabilities `canViewJobOrders` for the queue, the bank staff/org-unit account reads with
  a try-both fallback, local role checks for the global staff/member rooms). Checks run
  **asynchronously** on a dedicated bounded executor (8 threads, queue 500) — never on the
  WS container thread — answering `subscribed` / `denied`. Explicit 403/404 denies;
  transient errors and executor saturation **fail open** (ADR-0031 semantics: only opaque
  section keys cross the socket, every fragment re-fetch re-authorizes per viewer).
- **Publish** (`changed`) requires only an authenticated socket, a known topic, the topic
  class's section whitelist and the per-session token bucket (burst 20, refill 10/s) — **no
  subscription**. This widens ADR-0031's trust delta from "viewers of the resource can emit
  spoofed signals" to "any authenticated member can emit them for any topic". We accept it:
  the signal still carries no data and can only trigger re-fetches each receiver is
  authorized for; receivers clamp to ≤ ~2.5 re-fetches/s/section via coalescing regardless
  of publish rate; and the population able to abuse it (invite-gated realm accounts) is the
  population ADR-0031 already trusts. The spoof-proof server-side trigger interceptor stays
  the not-taken heavier option, unchanged from ADR-0031.
- **Server-side publish hook** (`LiveSyncLocalBus.publish`, delegating to
  `LiveSyncWebSocketHandler.publishFromServer`) exists for mutations that reach the frontend
  without a client socket: first consumer is the job-order create path, whose anonymous-guest
  submissions must still refresh the staff queue.

### Redis pub/sub fan-out — frontend relay and backend SSE

- **Frontend relay:** on an accepted `changed` frame the handler relays locally first
  (today's exact fan-out, origin session excluded), then publishes
  `{"v":1,"topic":…,"sections":[…],"origin":"<instanceUuid>"}` to `basetool:livesync:changed`
  fire-and-forget. Instances skip their own messages on consume and relay the rest to their
  local rooms. Redis being down therefore degrades to exactly today's single-instance
  behaviour — never worse.
- **Backend SSE:** `NotificationEventListener` publishes through a `NotificationFanout` seam.
  The default bean delivers locally (byte-identical to today); the Redis bean delivers
  locally first, then publishes the recipient subs to `basetool:notify:published` with the
  same origin-skip consume. The polling fallback (REQ-NOTIF-006) remains the correctness
  guarantee. This discharges the ADR-0016 deferral. The backend gains its first Redis
  dependency: `spring-boot-starter-data-redis`, a new `net-redis-backend` compose network,
  and property-gated config (`app.notifications.redis-fanout.enabled`, default **off**, prod
  **on**). Redis is deliberately **not** added to the backend readiness group and the Redis
  health indicator is disabled wherever Redis is absent — the ADR-0084 lesson: an optional
  enhancement must not flip a container unhealthy or trigger deploy-gate rollbacks.
- **Presence dots stay per-instance.** Only `changed` frames cross Redis. Cross-instance
  editing-awareness dots would need shared TTL state or presence-frame mirroring for a
  cosmetic feature; multi-instance *change propagation* is the goal. Consequence: viewers on
  different replicas see different dot sets. Tracked as a follow-up issue.

### Migration and compatibility

- The generic handler ports the mission handler 1:1 including all #1149/#1150 hardening
  (`ConcurrentWebSocketSessionDecorator(5 s, 512 KB, TERMINATE)`, bin-lock room registries,
  reaper, `sendSafe` exception set). The presence store is renamed
  `LiveSyncPresenceService` with `String topic` keys; meter names stay unchanged
  (REQ-OBS-011 treats a rename that breaks a dashboard as incomplete) and gain a bounded
  `topic_class` label.
- The legacy paths `/ws/missions/{id}/presence` and `/ws/materialboerse/board` stay
  registered on the generic handler for **one release** (handshake stuffs an implicit topic;
  frames without a `topic` field resolve against it) so tabs opened before the deploy keep
  their live sync after reconnect instead of going silently stale. Removal is a tracked
  follow-up.
- The per-frame section count is bounded by two independent gates: each class's section
  whitelist (a `changed` frame keeps only keys in `LiveSyncTopicClass.allowedSections()`, so
  the effective ceiling is that class's size — the order topic has the largest at 9) and a
  defensive raw count cap `MAX_CHANGED_SECTIONS = 16` that also bounds the parse of an
  oversized array. The count cap sits above every class whitelist, so it never clips a
  legitimate frame; it only backstops a crafted one.

### Capacity model (5000 accounts / ≥200 concurrent, extends ADR-0085)

Assume ~300 open tabs per instance (200 users × ~1.5 tabs), 1–3 subscriptions each.
Sockets: ~300 — trivial for Tomcat NIO; decorator buffers only under backpressure
(worst case 150 MB, TERMINATE evicts). Relay fan-out: largest (global) room ≤ 200
subscribers → ≤ 200 sends of ~60 B per frame; a hostile publisher is bucket-capped at
10 frames/s → ≤ 2000 sends/s. The binding path is the **re-fetch herd**: one frame into a
200-viewer global room triggers per-viewer fragment GETs, so global-room receivers use a
**1500 ms coalesce window** (detail topics keep 400 ms), both full-jittered (#1125), bounding
the worst case at ~130 GETs/s and the realistic case at ≤ 30/s against reads already
slimmed by ADR-0081/#1170; `FrontendBackendFanoutHigh` stays the amplification watchdog.
Deploy reconnect storms (~600 subscribe auths spread over the 1–30 s jitter ≈ ≤ 20/s) sit
well under the 8-thread authorize executor's capacity; saturation fails open. SSE stays
within the existing 1000-slot relay pool (~300 emitters expected). Redis pub/sub adds no
keys, so the ADR-0079 384 MB/noeviction budget is untouched; the worst-case recipient
payload (all 5000 subs) is ~180 KB.

## Consequences

- **One stack instead of three.** The mission and Materialbörse forks are deleted; new
  surfaces are a registry row + a page seam map + broadcasts, not new transports. The
  notification SSE rides the same pattern on the backend.
- **Multi-instance correctness for change propagation and SSE** the moment a second replica
  ships; a Redis outage degrades to single-instance behaviour with polling as the SSE
  fallback. Delivery stays best-effort (reconnect resync recovers; no ordering guarantees).
- **Peers stop 409ing on stale versions** on every covered surface: a peer's fragment
  re-render ships fresh `data-version`/`data-field-version` attributes (the ADR-0031 benefit,
  now tool-wide — the #1120 party-lead stale-version defect class disappears structurally).
- **Wider publish trust surface, quantified and bounded** (see Decision). Reviewers should
  treat the token bucket, per-class whitelists and receiver coalescing as the load-bearing
  bounds.
- **The backend joins a Redis network for the first time** — compose topology changes, which
  makes this release's deployment a manual, runbook-driven one
  (`docs/LIVESYNC_ROLLOUT_RUNBOOK.md`: clean `down` + redeploy because the in-place `up`
  strands containers on network changes, plus an ACL pre-check that the `default` user's
  explicit `users.acl` entry retains `&*` channel permissions for the two channels).
- **Presence dots are per-instance** until the tracked follow-up lands; the remaining
  Phase-3 surfaces (Lager, Raffinerie, Rollen/Org-Struktur, `/missions` list), the legacy
  alias removal and a baselined drop-rate alert are tracked follow-up issues as well.

## Alternatives considered

- **Per-resource sockets (straight generalization of ADR-0031).** Rejected: cannot express
  publish-without-membership (requester/guest order creates, org-unit owner approvals into
  staff rooms) — no number of parallel sockets fixes an authorization-refused handshake —
  and multiplies connections 3–4× on bank action pages. Grafting a `topic` field onto an
  arbitrary open socket is this decision with extra connection overhead.
- **Server-side triggers on every write route (interceptor + endpoint→section map).**
  Cleanest trust model, but duplicates the mutation→section knowledge that already lives in
  the page seam maps across ~dozens of routes, and diverges from the shipped chokepoint
  pattern. The `LiveSyncLocalBus` seam keeps this door open per-endpoint (used for the
  anonymous order-create hook) without adopting it wholesale.
- **Uniform Redis path (origin instance consumes its own message instead of local-first).**
  Rejected: makes local delivery depend on Redis health, so a Redis outage would break
  single-instance live sync — a regression the local-first + origin-skip flow avoids at the
  cost of one UUID comparison.
- **Cross-instance presence dots now (shared Redis state or mirrored presence frames).**
  Deferred: meaningful new surface (TTL coordination, snapshot reconciliation) for a
  cosmetic awareness feature; the store is already the single seam, so it retrofits cleanly.
- **STOMP/SockJS or a message broker for the client channel.** Rejected as in ADR-0031: the
  hand-rolled frame protocol is tiny, shipped and hardened; a broker adds dependency and
  operational surface without changing any bound that matters at 200 concurrent users.


# ADR-0031 — Live multi-user mission sync over the presence WebSocket

- **Status:** Accepted — **generalized by [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md)** (2026-07-10): the per-mission relay became one tool-wide topic-room WebSocket with Redis fan-out; the "only opaque section keys cross the socket" rule stands
- **Date:** 2026-06-21
- **Deciders:** @greluc
- **Related:** spec REQ-FE-010 · REQ-FE-001…008 · ADR-0012 (krtFetch) · ADR-0013 (bfcache) · ADR-0016 (notification SSE)

## Context

When several users have the same mission detail page open — a common case during mission
preparation — a change one of them makes (a participant joining, a crew move, a finance entry, a
manager/owner change, a core/schedule/status/party-lead/frequency edit) is invisible to the others
until they manually reload. The in-place mutation foundation (REQ-FE-001…007) only keeps the
**acting** user's own document fresh; it has no way to reach a second user's already-rendered page.
This is the in-place sibling of the bfcache staleness handled in ADR-0013 / REQ-FE-008.

Three pieces of infrastructure already exist and bear on the choice:

1. **A per-mission WebSocket** — `mission-presence.js` ↔ `MissionPresenceWebSocketHandler` at
   `/ws/missions/{id}/presence`, which already fans a message out to every socket on a mission via an
   in-memory `sessionsByMission` map (currently used only for focus/blur/heartbeat awareness).
2. **`krtRefreshMissionSection(keys)`** — the single client chokepoint that, after every successful
   mutation, re-fetches a section's server-rendered fragment and swaps it in place (REQ-FE-001/005).
3. **The notification pipeline** (ADR-0014/0016) — a per-user inbox fed by after-commit domain
   events, delivered by polling + SSE.

The frontend serves HTML and proxies every mission mutation to the backend, so the **frontend
process already sees both every mutation and every open socket** for a mission. The deployment is a
single frontend replica.

## Decision

We will broadcast a lightweight **"section changed" signal over the existing presence WebSocket** and
let each peer re-render the affected section through the mechanism it already uses for its own edits.

- The acting client's `krtRefreshMissionSection(keys)` additionally calls
  `missionPresence.sendChanged(keys)`. The handler relays a `{type:"changed","sections":[…]}` frame to
  every **other** socket on the mission (the originator is excluded — it already applied its change).
- A receiving client turns the frame into `krtRefreshMissionSection(keys, {broadcast: false})` —
  `broadcast:false` suppresses re-emission so an applied change cannot echo back into a loop.
- **Only opaque section keys cross the socket — never mission data.** Each peer re-pulls the affected
  fragment through its own authenticated `GET /missions/{id}?fragment=…`, so authorization
  (guest field-redaction, the member-only finance gate) is re-applied per viewer. The relay sanitises
  the inbound `sections` array (keys restricted to `crew`/`finance`/`mgmt`/`overview`, count capped).
- We add an `overview` fragment (Tab-1 plus an `#overview-head-meta` carrier that patches the sticky
  header title / status pill / facts) so core/schedule/status edits propagate, not just the
  crew/finance/mgmt sections that already had swap fragments.
- A refresh is **deferred behind a DS-styled "updates available" pill** when the local user is
  mid-edit (a modal is open, or focus is inside the target section), rather than yanking the DOM.
  The busy test is re-applied at flush time, so a modal opened during the coalesce window is also
  respected. Bursts are coalesced; a reconnect resyncs every visible section to recover missed
  signals.
- The socket is **authorized against mission access at the handshake**
  (`MissionPresenceHandshakeAuthInterceptor` issues the same authenticated `GET /api/v1/missions/{id}`
  the mission-detail page does), so an authenticated user cannot join the presence room of a mission
  they may not see — closing the pre-existing auth-only-not-membership handshake gap. Inbound
  `changed` frames are additionally **rate-limited per session** (a token bucket, far above any human
  edit cadence) so a crafted client cannot drive unbounded re-fetch amplification.

## Consequences

- **Reuses everything, adds almost nothing.** No new transport, no new backend module, no Flyway
  migration, no new endpoint — one `changed` branch in the handler, a `sendChanged` method, a
  receiver block, and one fragment. The data path is the existing, already-authorized fragment GET.
- **Authorization is structurally preserved**: because no data rides the socket, a viewer can only
  ever see what their own fragment request returns. A push cannot leak privileged fields.
- **Fewer optimistic-lock 409s for collaborators**: a peer's section swap re-renders fresh
  `data-version` attributes, so a second user's next edit ships a current version instead of a stale
  one (the recurring 409 pain class).
- **Single-instance only** — the relay reuses the in-memory `sessionsByMission` map, so it is correct
  for one frontend replica (the current deployment). A peer connected to a different replica than the
  one the mutation passed through would not be notified. Scaling the frontend out horizontally
  requires moving both presence and this relay behind a Redis pub/sub fan-out; the swap-out point is
  `MissionPresenceService` / `MissionPresenceWebSocketHandler` (Redis already backs Spring Session, so
  the dependency is in place). We knowingly defer this until horizontal scale is actually needed.
- **Best-effort, not guaranteed**: a dropped frame (offline socket) is recovered by the reconnect
  resync or a manual reload; we do not add delivery guarantees or message ordering.
- The client `changed` signal is trusted only to make peers **re-fetch authenticated fragments**, so
  the worst a malicious client could achieve is a bounded, same-mission re-fetch amplification —
  capped per frame by the server-side key allow-list + count cap and per receiver by the 400 ms
  debounce. Two server-side guards close the rest of that window: the **handshake membership gate**
  stops a client joining a mission room it has no access to (so amplification cannot target
  arbitrary missions), and the **per-session token bucket** caps the inbound `changed` frame rate (so
  a tight-loop client cannot sustain a flood against a mission it *can* see). The gate **fails open**
  on transient backend errors — only an explicit 403/404 refuses the handshake — so a backend blip
  never silently kills presence for a legitimate viewer; this is safe because no data rides the socket
  and every re-fetch re-authorizes per viewer. Residual: the gate is spoof-adjacent only in that the
  `changed` trigger is still client-emitted (a connected, authorized viewer can emit a `changed` for a
  section nothing actually changed) — harmless (peers just re-pull current data) and the throttle
  bounds it; the spoof-proof server-side `HandlerInterceptor` trigger (below) remains the heavier
  option not taken.

## Alternatives considered

- **Drive it through the notification pipeline (ADR-0014/0016).** Rejected: notifications are
  per-user inbox items targeted by role/org-unit and persisted as rows — the wrong semantics for
  "everyone currently *viewing* mission X gets a transient live refresh." It would create durable
  rows for ephemeral UI updates and cannot express "all current viewers."
- **A second, dedicated WebSocket (or STOMP/SockJS) for data sync.** Rejected: a per-mission channel
  with fan-out and reconnect already exists; a parallel channel duplicates connection management,
  auth, and the single-instance limitation for no gain.
- **Server-side trigger via a `HandlerInterceptor` on `/missions/{id}/**` writes.** More
  authoritative (spoof-proof, fires only on real success) but needs an endpoint→section map across
  ~24 routes. Rejected in favour of the one-line client chokepoint, which degrades gracefully and
  carries no per-endpoint mapping; the signal only ever triggers authenticated re-fetches, so the
  trust delta is small. The amplification concern it would also eliminate is instead handled by the
  handshake membership gate + per-session throttle (see Decision/Consequences); the spoof-proof
  trigger remains the heavier option to revisit only if a missed-trigger or abuse gap actually
  appears.
- **Short-interval polling of the mission.** Rejected: wasteful (most missions are idle), laggy, and
  multiplies backend load per open tab — the presence socket already gives us push for free.

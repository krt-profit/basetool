# ADR-0143 — The app's live sync: a backend SSE stream and a client-published `changed` relay

- **Status:** Accepted
- **Date:** 2026-08-23
- **Deciders:** @greluc
- **Related:** ADR-0094 (the tool-wide topic-room relay this bridges into) · ADR-0126 (presence gossip, deliberately not bridged) · ADR-0031 (the mission relay both generalize) · ADR-0016 (notification SSE, whose Redis deferral ADR-0094 discharged) · REQ-FE-010 · REQ-API-009 · REQ-SEC-037 · REQ-OBS-011 · basetool-android phase 4

## Context

The tool's live sync is complete on the web and invisible to the app.

ADR-0094 put one multiplexed WebSocket (`/ws/sync`) in the **frontend** module: a browser tab
subscribes to topic rooms, publishes `changed` after its own mutations, and every instance relays
what it accepts to `basetool:livesync:changed` so peer replicas can reach their own rooms. The
Android app never sees any of it. It speaks only to the **backend**, over a bearer token, and the
backend has no live-sync surface at all — it holds one Redis fan-out, for notification SSE
(`basetool:notify:published`), and nothing that carries a `changed` frame.

Two gaps follow, and they are not symmetric in cost but they are symmetric in kind:

- **Web → app.** A member is looking at an Einsatz in the app while an officer edits it in a
  browser. The browser peers refresh; the app shows yesterday until the member pulls to refresh.
- **App → web.** The same member books stock out in the app. Every open Lager tab in the
  organisation keeps showing the stock that is no longer there — the app's write reaches the
  database and nothing else.

The second gap is the one REQ-FE-010 names directly: *"on any surface where several users can see
the same state, a peer's change propagates to the others without a manual reload."* An app that
mutates shared state without emitting a signal does not merely fail to receive live sync; it
**breaks** live sync for everyone else, on surfaces where it currently works. Phase 3 shipped seven
write slices, so the gap is already open.

Three constraints shaped the answer:

1. **The backend has no WebSocket infrastructure.** No handler, no handshake interceptor, no topic
   registry, no per-session buckets, no `SecurityConfig` matcher. The frontend's stack is the
   hardened result of an epic (#1102/#1115/#1120/#1236/#1241/#1243).
2. **The backend already has the SSE half.** `NotificationStreamService` is an emitter registry with
   a per-recipient cap, eviction, heartbeats, timeout handling and metrics; `RedisNotificationFanout`
   is a local-first, origin-skipping Redis publisher/consumer. Both are in production.
3. **The app's publish need is tiny.** A phone shows one screen. After a successful write it must
   emit one frame. It never needs a socket kept open to speak.

## Decision

### One SSE stream, topics named at connect time — `GET /api/v1/live-sync/stream?topics=…`

The app opens **one** SSE stream and names its topics in the query string. The topic set is fixed
for the life of the stream; when the member navigates to a different screen the app closes the
stream and opens a new one. There is no `subscribe` frame, no server-held subscription protocol and
no client state to reconcile after a reconnect — the URL *is* the subscription.

This is the whole reason SSE wins here. The frontend needed a bidirectional socket because a browser
tab holds several rooms at once and changes them without a navigation; a phone shows one screen, so
"resubscribe" and "navigate" are the same event, and re-issuing an HTTP GET costs one round trip on
a transition the user is already waiting through.

The stream emits three event kinds:

- `subscribed` — once, immediately, naming the topics that were **accepted**. The app must not
  assume it got what it asked for (see authorization below); a topic missing from this list is a
  topic that will never deliver, and the app treats that screen as poll-only rather than silently
  believing it is live.
- `changed` — `{"topic":"…","sections":["…"]}`, one per accepted frame.
- `heartbeat` — the same keep-alive `NotificationStreamService` already sends, for the same reason
  (an idle SSE body through nginx and a mobile NAT).

### Publishing: `POST /api/v1/live-sync/changed`

A body of `{"topic":"…","sections":["…"]}`. The backend validates the topic against its registry,
clips the sections to the class whitelist, relays to its own local subscribers and publishes to
`basetool:livesync:changed` with this instance's origin id — **the same channel and the same payload
shape the frontend uses**, `{"v":1,"topic":…,"sections":[…],"origin":…}`. That single fact is what
makes the bridge cheap in both directions: the backend consuming that channel gives the app every
web change for free, and the backend publishing to it gives every browser the app's changes for
free. Neither the frontend's handler nor its JS is touched by this ADR.

We accept the frontend's trust delta verbatim rather than inventing a stricter one for the app: a
`changed` frame carries **no data**, only an opaque topic and section keys, and every receiver
re-fetches through its own authorized read. An app client can therefore make other members re-fetch
things they are already allowed to fetch, and nothing else. This is precisely the trade ADR-0094
weighed and took for browser tabs, and the app population is the same invite-gated realm accounts.

The spoof-proof alternative — a server-side trigger interceptor that publishes from the mutation
itself, needing no client cooperation — stays the not-taken heavier option it has been since
ADR-0031. It is strictly better and strictly more expensive: every mutating service path would have
to name its topic and sections. Nothing here forecloses it; a later interceptor would make this
endpoint redundant rather than wrong.

### Authorization: the backend checks for real, because it can

The frontend's authorizer works with a captured token, a bounded executor and a try-both fallback,
and it resolves an indeterminate verdict in the class's fail direction. The backend needs none of
that apparatus: it *is* the authority, and `OwnerScopeService` already holds the exact predicates
its own `@PreAuthorize` expressions use. A subscribe therefore runs the real check, synchronously,
in the request thread:

|                              Topic class                              |                             Check                              |
|-----------------------------------------------------------------------|----------------------------------------------------------------|
| `mission:{id}`, `operation:{id}`, `order:{id}`, `refinery-order:{id}` | the matching `ownerScopeService.canSee…(id)`                   |
| `bank:{accountId}`                                                    | the org-unit account read the app's Bank screen performs       |
| `orders` (queue)                                                      | `ownerScopeService.canViewJobOrders()`                         |
| `missions`, `inventory`, `materialboard`, `refinery`, `orgunit-bank`  | member role, matching the page gate ADR-0094 gives these rooms |

A refused topic is **dropped from the set**, not made fatal: a stream asking for three topics and
allowed two delivers two and says so in `subscribed`. A stream where nothing is allowed is `403`.
There is no fail-open branch, because there is no indeterminate verdict to resolve.

**`mission:{id}` is subscribed for `changed` only.** ADR-0094 fails that class *closed* because an
allowed subscribe there immediately emits an editor-presence snapshot — pseudonymous ids and
callsigns, cross-user identity data. This bridge never emits presence: no presence frames, no
`basetool:livesync:presence` subscription, and no way to ask for one. The app has no editor dots,
which is also why ADR-0126's gossip channel is deliberately not bridged.

### Bounds

The publish endpoint carries **the same two buckets ADR-0094 sized**, for the same reason and with
the same numbers: per-subject (burst 40, refill 20/s) so one client cannot flood, and per-topic
(burst 200, refill 100/s) so no set of clients can amplify one global room's re-fetch fan-out.
Consumed frames from Redis bypass the per-topic bucket — they were already accepted on their
originating instance, exactly as the frontend's `deliverFromFanout` does. Both bounds degrade to a
bounded re-fetch rate, never to data loss.

Receivers coalesce: **400 ms** for a per-resource topic, **1500 ms** for a global one, full-jittered.
These are ADR-0094's windows unchanged, and they are what actually clamps the re-fetch herd — the
relay rate is not the binding path, the GET herd is. The app implements them client-side
(`REQ-APP-SYNC-*`).

The emitter registry inherits `NotificationStreamService`'s shape: a per-subject cap on open streams
so a crafted client cannot hold thousands, oldest-evicted-first, and the eviction counted.

### Redis stays optional, exactly as it is for notifications

The fan-out is property-gated (`app.live-sync.redis-fanout.enabled`, default **off**, prod **on**)
and Redis is **not** in the backend readiness group. Local delivery happens before the publish, so a
Redis outage degrades to single-instance behaviour and never worse — the ADR-0084 lesson, applied
the same way ADR-0094 applied it. With the fan-out off, the app still receives every change the
backend itself relays and browsers still receive every change the frontend relays; only the crossing
between the two stops.

## Consequences

- **REQ-FE-010 holds across the app.** An app write refreshes browsers; a browser write refreshes
  the app. The requirement stops being a web-only promise.
- **Two topic registries now exist**, one per module, and they can drift: a renamed topic or section
  in the frontend would leave the app silently stale, which is the worst failure shape available
  here — nothing is broken, nothing is logged, the screen is just old. A **parity test in the
  backend reads the frontend's `LiveSyncTopicClass` source** and asserts the backend's registry is a
  subset of it, prefix for prefix and section for section. Parsing a sibling module's source as a
  build gate is the established move in this repo (`LiveSyncSectionMapParityTest` derives the client
  seam maps from the shipped JS for the same reason).
- **The app's stream reconnects on navigation.** Accepted: one GET per screen change, against reads
  already slimmed by ADR-0081. The alternative — a subscribe protocol over a long-lived stream —
  buys a round trip and costs server-held per-stream state and a resubscribe path after every
  network blip.
- **Presence is web-only, on purpose.** App members do not appear as editor dots and do not see
  them. Making them appear would mean bridging ADR-0126's snapshot gossip and emitting cross-user
  identity data to a client that has no place to show it.
- **A new anonymous-surface entry.** Both paths are `isAuthenticated()`; REQ-SEC-037's enumeration
  and the API vhost's allow-list gain them, and the nightly deny probe gains a row each.
- **New metrics** (`basetool_livesync_*`, REQ-OBS-011): stream gauge, accepted/refused subscribes by
  topic class, published/consumed/dropped frames, and the two bucket rejection counters.

## Addendum — 2026-09-02: "Redis stays optional" now holds at startup too

This ADR's posture is that a Redis outage degrades cross-instance sync to single-instance behaviour
and **never worse**, which is ADR-0084's rule applied to the fan-out. That held for a Redis lost
while running, and silently did not hold while *starting*.

Both fan-out containers subscribe during context refresh. A plain `RedisMessageListenerContainer`
rethrows that first connection failure out of `SmartLifecycle#start()` — its `lazyListen()`
`InitialBackoffExecution` branch does not back off, it propagates — and Spring turns any exception
out of a lifecycle start into a cancelled refresh. On **2026-09-02 07:07:09Z** that crash-looped the
production backend (`ApplicationContextException: Failed to start bean
'liveSyncRedisMessageListenerContainer'`) at roughly one boot per minute, with no API for the
frontend at all, because Redis had been recreated during a deploy.

No decision here is reversed; the decision is restored. `ResilientRedisMessageListenerContainer`
swallows that first failure, resets the container's `started` flag (upstream sets it *before* the
throw, so a bare retry would be a permanent no-op) and retries every five seconds in the background.
Both containers use it — hardening only the live-sync one would have renamed the outage rather than
ended it, since both sit at lifecycle phase `Integer.MAX_VALUE` and whichever the lifecycle
processor reaches first is the one that aborts the refresh.

The trade this makes is loud-to-quiet: an unreachable Redis used to announce itself by taking the
backend down. `basetool_redis_fanout_subscribed{fanout}` and the `RedisFanoutUnsubscribed` alert
(0 for 10m, warning) are what replace that announcement, bound to `isListening()` rather than
`isRunning()` for the reason above.

Deliberately **not** extended to the frontend: Redis is genuinely mandatory there
(`@EnableRedisIndexedHttpSession` — no session store, no login), and its context dies one bean
earlier in the keyspace-notification initializer, before any `SmartLifecycle` runs. And deliberately
not solved with a compose `depends_on` gate: `depends_on` is not re-evaluated on a restart-policy
restart, on `docker restart backend`, on `up -d --no-deps backend` (which `scripts/deploy.sh` itself
runs on health recovery), or when redis is recreated under a running backend — which are exactly the
paths this happened on.

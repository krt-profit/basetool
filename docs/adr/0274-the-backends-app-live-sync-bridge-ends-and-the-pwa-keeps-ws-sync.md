# ADR-0274 — The backend's app live-sync bridge ends; the PWA keeps `/ws/sync`

- **Status:** Accepted — decided ahead of the change; implemented in #2673. Supersedes
  [ADR-0143](0143-app-live-sync-bridge-over-backend-sse.md), its 2026-09-02 addendum excepted (see
  *Consequences*).
- **Date:** 2026-10-17
- **Deciders:** @greluc (issue #2638)
- **Related:** [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md) (the relay that stays) ·
  [ADR-0031](0031-live-mission-sync-over-presence-websocket.md) ·
  [ADR-0126](0126-cross-instance-editor-presence-via-snapshot-gossip.md) (never bridged) ·
  [ADR-0164](0164-an-installable-web-app-without-a-service-worker.md) (why the PWA needs nothing
  here) · [ADR-0260](0260-the-unit-hangar-overview-listens-to-one-room-per-unit-and-one-for-all.md) ·
  [ADR-0266](0266-the-org-unit-bank-seam-and-the-live-sync-relay-split-by-responsibility.md) ·
  [ADR-0268](0268-the-backend-is-internal-only-and-ingest-is-the-single-public-api-entry-point.md) ·
  specs `REQ-FE-010`, `REQ-OBS-011`, `REQ-SEC-037`

## Amendment 1 — the backend keeps publishing (2026-10-17, #2673)

**Two bullets of the decision below were wrong about the backend's own writes, and the
implementation corrects them rather than following them.** They are kept as written, with this
amendment above them, because the mistake is instructive: the ADR reasoned from *who subscribes* and
concluded that nothing on this side needed the channel.

*What was overlooked.* `ExchangeLiveSync` (REQ-XCH-013) and `HangarUnitLiveSync` (ADR-0260,
ADR-0261) publish into the frontend's rooms after a committed backend write. Those writes arrive
over the exchange API through the ingest gateway, or from an admin bulk change — **no browser makes
them**, so the frontend cannot know they happened. Removing the backend's publish side would have
left a member's open Lager, hangar and Materialbörse pages stale after every exchange write, with
nothing broken and nothing logged: exactly the failure shape REQ-FE-010 exists to prevent, and
exactly the shape the *Alternatives considered* section below warns about in a different guise.

*What the implementation does instead.*

1. **The client-facing bridge is removed as decided**: `GET /api/v1/live-sync/stream`, `POST
   /api/v1/live-sync/changed`, `LiveSyncController`, `LiveSyncStreamService`,
   `LiveSyncSubscriptionAuthorizer`, `LiveSyncAuthorization`, `LiveSyncChangedRequest`, the
   `LiveSyncTopicAuthorizer` SPI with its seven per-domain implementations, the per-client rate
   limiting of `LiveSyncRelayService` with both token buckets, and the subscriber-side metrics and
   alerts.
2. **The publish side stays**, as a seam that does one thing: `LiveSyncPublisher.publish(topic,
   sections)` clips the sections, hands the frame to the fan-out and counts it. There is no bucket,
   because the write that raised the frame is bounded already.
3. **`RedisLiveSyncFanout` becomes publish-only.** It no longer implements `MessageListener`, and
   `LiveSyncRedisConfig` registers no listener container and no executor. The sentence *"the backend
   stops doing either"* is therefore half right: it stops **consuming** `basetool:livesync:changed`
   and keeps **publishing** on it.
4. **`LiveSyncTopicClass` shrinks to the six rooms the backend actually publishes into** —
   `inventory`, `hangar:{id}`, `hangar-unit:{id}`, `hangar-units`, `blueprints:{id}`,
   `materialboard` — and loses its `authorization` column and its wire parser with the subscribe
   path. The other nine classes existed only so an app could subscribe to them.
5. **`LiveSyncTopicRegistryParityTest` stays, against the decision below.** With a publish-only
   registry the drift risk it guards is *larger*, not smaller: a prefix or section key the frontend
   renames is a frame the browser clips away on arrival. The test was already one-directional — every
   backend class must exist in the frontend with the same arity and sections — so a smaller backend
   registry only makes it stricter.

*What this costs.* The backend still holds a live-sync vocabulary that must stay in step with the
frontend's, which is most of what the decision below wanted to be rid of. The honest version of
that wish is the alternative the ADR already names — move the relay into the backend and have the
frontend consume it — and it is still not foreclosed.

## Context

ADR-0143 built a live-sync surface in the **backend** — `GET /api/v1/live-sync/stream?topics=…` and
`POST /api/v1/live-sync/changed` — because the Android app spoke only to the backend and the relay of
ADR-0094 lives in the frontend. Its sharpest argument was not the missing refresh but the damage:

> An app that mutates shared state without emitting a signal does not merely fail to receive live
> sync; it **breaks** live sync for everyone else, on surfaces where it currently works.

Delivering that cost an emitter registry with a per-subject cap, a topic registry, a synchronous
subscription authorizer over `OwnerScopeService`, two token buckets sized from ADR-0094, a
per-stream bounded queue of 64 frames drained on virtual threads (the 2026-09-23 amendment), a
publish/consume pair on `basetool:livesync:changed` sharing the frontend's payload shape, a parity
test that reads the frontend's `LiveSyncTopicClass` source to keep two registries from drifting, the
`basetool_livesync_*` series, and the `AppLiveSyncBridgeSilent` / `AppLiveSyncFramesDropped` alerts.

The client is gone, and the replacement is not a second client. The installable web app of ADR-0164
is the Thymeleaf frontend: a PWA tab opens `/ws/sync` like any other tab and has had live sync since
ADR-0094. Nothing else calls the bridge — the frontend's own subscription authorizer probes ordinary
read endpoints (`GET /api/v1/missions/{id}`, `GET /api/v1/orders/{id}`, `GET /api/v1/me/capabilities`
and the rest of `LiveSyncTopicClass`), never `/api/v1/live-sync/*`.

## Decision

**The backend's live-sync bridge is removed. Live sync is the frontend's `/ws/sync` alone.**

- `/api/v1/live-sync/stream` and `/api/v1/live-sync/changed` go, with the controller, the stream
  service, the relay, the subscription authorizer, the backend's live-sync fan-out and properties,
  the `basetool_livesync_*` metrics and the two alerts.
- **The `LiveSyncTopicAuthorizer` SPI goes with them.** It is a backend platform SPI whose startup
  check refuses to boot a room kind without exactly one authorizer; with no rooms on this side there
  is nothing for it to authorise, and the rule in `backend/CLAUDE.md` loses that clause.
- **The parity test goes, and the drift risk it guarded goes with it.** ADR-0143 named two topic
  registries as its own worst failure shape — a renamed section leaving a client silently stale,
  nothing broken, nothing logged, the screen just old. One registry remains, in the module that owns
  the rooms.
- **REQ-FE-010 is not weakened.** *On any surface where several users can see the same state, a
  peer's change propagates without a manual reload* becomes a single-implementation promise again,
  which is what it was before the app and what the frontend's relay has always delivered. ADR-0266's
  split of `LiveSyncWebSocketHandler` and the room design of ADR-0260/ADR-0261 are untouched.
- **`basetool:livesync:changed` stays a frontend channel.** Frontend replicas keep publishing and
  consuming it; the backend stops doing either.

## Consequences

**ADR-0143's 2026-09-02 addendum survives its ADR.** That addendum is not about the bridge: it
records why `ResilientRedisMessageListenerContainer` exists after both fan-out containers
crash-looped the production backend at one boot per minute when Redis was recreated during a deploy,
and the notification fan-out still subscribes through it. The container, the
`basetool_redis_fanout_subscribed` gauge and the `RedisFanoutUnsubscribed` alert stay, with one
subscriber on the backend instead of two. Nothing in that record is reversed here, and the reason the
outage happened — a lifecycle start that propagates its first connection failure — is exactly the
sort of fact a reader will need again.

**Presence stays web-only, as it already was.** ADR-0126's gossip was deliberately never bridged, so
nothing changes there; the bridge's careful `mission:{id}`-is-`changed`-only carve-out disappears
with the thing it constrained.

**The backend's anonymous and authenticated surface shrinks by two operations**, which REQ-SEC-037's
enumeration and the nightly deny probe both reflect, and the `/api/v1` SSE family reduces to the
notification stream.

**A future native client would have to rebuild this.** That is the honest cost, and it is the right
trade: the backend's `livesync` package is some 2 200 lines of infrastructure mirroring the
frontend's, and its value was entirely in there being a second client. ADR-0143 is the record of how
to build it again, down to the bucket sizes and the reason SSE beat a socket for a phone.

**The backend keeps no WebSocket infrastructure**, which was ADR-0143's first constraint and is now
simply true again.

## Alternatives considered

*Keep the bridge for a future app.* Infrastructure for a client that does not exist: two registries
that can drift, two buckets to re-tune, a queue whose drop counter has no reader, and two alerts that
can only fire about an absence of traffic. `AppLiveSyncBridgeSilent` would fire permanently, and
ADR-0135 already recorded what a permanently-firing channel does to an operator.

*Move the relay into the backend and have the frontend consume it*, so that one implementation serves
both. A real design — ADR-0143 itself notes that a server-side trigger interceptor publishing from
the mutation is strictly better and strictly more expensive, because every mutating service path
would have to name its topic and sections. It is not foreclosed by this decision, and it is not a
reason to keep an SSE bridge with no subscriber in the meantime.

*Keep `POST /live-sync/changed` alone, as a generic publish endpoint.* A write endpoint any
authenticated member may call, which makes other members re-fetch, kept for no caller. The trust
delta ADR-0094 weighed was taken for browser tabs that needed it.

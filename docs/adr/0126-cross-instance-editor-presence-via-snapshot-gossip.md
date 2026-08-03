# ADR-0126 — Make editor-presence dots cross-instance by gossiping per-origin snapshots

- **Status:** Accepted
- **Date:** 2026-08-03
- **Deciders:** Repository owner (@greluc)
- **Related:** ADR-0094 (tool-wide topic-room live-sync relay — supersedes its "presence dots stay per-instance" consequence) · spec REQ-FE-010/-015 ([`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) · REQ-OBS-011 · epic #1102 · issue #1237

## Context

ADR-0094 made the live-sync **`changed` relay** correct across frontend replicas: an accepted
frame is relayed to the publishing instance's own rooms first, then published on the Redis
channel `basetool:livesync:changed`, and every other replica relays it to its local rooms.
Multi-instance *change propagation* was the goal, and it is met.

**Editor-presence dots were explicitly left out.** They live in the in-memory
`LiveSyncPresenceService` and never cross an instance boundary. The consequence, recorded in
ADR-0094 and tracked as #1237: two people editing the same mission from **different** replicas do
not see each other's dots. They still both re-fetch correctly on a `changed` signal — only the
"someone else is in this panel" cue is missing, which is precisely the moment the cue exists for.

The deferral reasoning was that cross-replica dots "would need shared TTL state or presence-frame
mirroring for a cosmetic feature". That framing is what this ADR revisits: the cost was assumed to
be TTL coordination, and it is not — a periodically re-gossiped full snapshot needs no coordination
at all.

Two properties of presence make it a different problem from the `changed` relay, and they drive
the design:

1. **Presence is state, not an event.** A `changed` frame is a fire-and-forget signal whose whole
   meaning is consumed on arrival; losing one costs a stale panel until the next change. A
   presence dot is a *level* that must stay true for as long as someone is focused and must
   disappear when they leave — including when their replica dies without a goodbye.
2. **Presence is cosmetic; `changed` is load-bearing.** A missed presence message costs a dot. A
   missed `changed` message costs a user acting on stale data. The two must therefore not share a
   failure domain, a throttle, or a metric series that would let one mask the other.

## Decision

**We will mirror editor presence across replicas by gossiping each instance's complete
per-topic snapshot on a second Redis channel, and merging the received snapshots into a
read-only partition of the presence store.**

- **A second channel, `basetool:livesync:presence`.** Payload
  `{"v":1,"topic":…,"origin":"<instanceUuid>","sections":{"<key>":[{"userId","displayName"}]}}`.
  `RedisLiveSyncFanout` publishes and subscribes on both channels and dispatches inbound messages
  by the channel they arrived on, skipping its own `origin` exactly as the `changed` path does.

- **Full snapshots, never deltas.** Every message carries an instance's *entire* presence state for
  one topic, and replaces that `(topic, origin)` partition wholesale on the receiver. This is the
  load-bearing choice: it makes the mirror converge with no delete frames, no acknowledgements, no
  sequence numbers and no assumption that messages arrive in order. An **empty** `sections` map is
  a real message — "nobody is editing here on this replica any more" — which drops the partition
  immediately rather than waiting out its TTL.

- **Published on every local change, re-gossiped every reaper tick (10 s).** The change-driven
  publish makes a dot appear and disappear on every replica at the same moment as locally; the
  periodic re-gossip is what heals a dropped message and seeds a replica that started *after* the
  focus happened. It costs one small message per **actively edited** topic per tick per replica —
  the tracked-topic set is empty whenever nobody has a panel focused, which is the ordinary case.

- **Freshness by arrival, never by the peer's clock.** A partition carries the local instant it was
  applied. It expires after `REMOTE_PARTITION_TTL` = 30 s — three missed gossips — so a replica
  that crashed, was scaled away or lost Redis stops showing phantom dots within half a minute. No
  timestamp crosses the wire, so no two hosts' clocks ever have to agree.

- **The local half is untouched and authoritative.** Local entries keep their 120 s
  heartbeat TTL, their `focus`/`blur` semantics and their per-topic section cap. Remote partitions
  are read-only: never heartbeat-decayed, never blurred, never reaped entry by entry.
  `snapshot()` merges the two and collapses a user present in both halves (two tabs, two replicas)
  to one dot, so the count stays a count of *people*.

- **Consume never re-publishes.** Applying a peer's snapshot broadcasts to local sockets and stops
  there; re-publishing would make two replicas echo each other indefinitely.

- **Bounded on ingest like every other peer input.** The topic must parse to a *presence-enabled*
  class (a peer cannot open a presence surface on a class that has none), section keys are held to
  the same shape bound as an inbound client frame, and the store caps origins per topic (16) and
  editors per section (32). The publishing replica already applied all of this — this is
  defense-in-depth against a malformed, older-version or tampered payload, the same posture
  `deliverFromFanout` takes.

- **Separate metric series** (REQ-OBS-011): `basetool_livesync_presence_published_total` /
  `_consumed_total` (tag `topic_class`) and the unlabelled gauge
  `basetool_livesync_presence_remote_partitions`. Gossip failures count under distinct
  `op=presence_publish` / `presence_consume` values on the existing errors counter, deliberately
  **outside** the `LiveSyncRedisFanoutBroken` alert expression: a failed `changed` publish degrades
  correctness and is worth a page, a failed gossip costs a dot and is worth a dashboard row.

## Consequences

- **Two editors on different replicas now see each other's dots**, within the gossip tick at worst
  and instantly for a focus/blur. This closes the last known cross-replica gap in the live-sync
  epic (#1102).
- **Losing Redis degrades presence to exactly the pre-#1237 behaviour** — per-instance dots, local
  half fully working — for the same reason the `changed` relay does: the local broadcast happens
  before the publish, and the publish is swallow-and-count.
- **The frontend gains a second pub/sub channel.** The Redis `default` user's existing `&*` grant
  already covers it, so no ACL change is needed; the restricted `monitoring` user must still not
  gain `&*`. Pub/sub adds no keys, so the 384 MB `noeviction` budget (ADR-0079) is unaffected.
- **The presence gossip shares the bounded listener executor with the `changed` relay.** That pool
  was sized for the changed relay at ≥200 concurrent users and absorbs the far smaller presence
  stream without retuning; both keep the `CallerRunsPolicy` backpressure rather than dropping.
- **On today's single-replica deployment this changes nothing observable.** The `NoopLiveSyncFanout`
  path is a no-op, the Redis path skips its own origin, and the remote-partitions gauge reads a flat
  zero. This is deliberate: the point is that scaling the frontend out no longer ships a known-broken
  cosmetic, and the gauge is the signal that tells the two situations apart.
- **Presence identity crosses an instance boundary for the first time.** Only the fields that were
  already on the browser-facing wire travel — the public callsign (`preferred_username`) and the
  `sub` — over the internal Redis network, and the payload carries no entity data whatsoever.

## Alternatives considered

- **Shared presence state in Redis (a hash or ZSET per topic, written on every touch).** Rejected:
  it puts Redis on the read path of every presence broadcast, so a Redis blip would break presence
  *within* an instance too — the exact regression the local-first `changed` design was chosen to
  avoid (ADR-0094). It also converts a 10 s gossip into a write per heartbeat per editor.
- **Deltas with explicit `join`/`leave` operations.** Rejected: correct only under reliable,
  ordered delivery. Redis pub/sub is fire-and-forget, so a dropped `leave` leaves a ghost dot
  forever and a dropped `join` hides a real editor until they blur. Recovering that needs exactly
  the reconciliation the full-snapshot design gets for free.
- **A snapshot request/response handshake on instance start ("who is editing what?").** Rejected as
  redundant once gossip is periodic: the cold-start gap it would close is already bounded by one
  tick, at the cost of a second message type, a fan-in burst on every deploy, and a failure mode
  (nobody answers) that the periodic path does not have.
- **Reusing the `changed` channel with a discriminator field.** Rejected: the periodic gossip would
  ride the same series as the event-driven relay, so a steady gossip floor would mask a changed-relay
  outage on the fan-out panel and in the alert; and a presence burst would compete for the same
  throttle budget as a correctness-carrying stream.
- **Doing nothing (keep ADR-0094's deferral).** Considered seriously, since the deployment runs one
  frontend replica today. Rejected because the follow-up is cheap at the seam the ADR itself
  predicted ("the store is already the single seam, so it retrofits cleanly"), and leaving it undone
  means the first horizontal scale-out ships a silently wrong UI cue with no signal that it is wrong.


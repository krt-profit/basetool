# ADR-0074 — Cache invalidation stays per-instance Caffeine + eviction-on-mutation; distributed eviction deferred

- **Status:** Accepted
- **Date:** 2026-07-05
- **Deciders:** @greluc
- **Related:** spec REQ-DATA-007 · REQ-DATA-011 · REQ-OBS-005 · ADR-0012 · ADR-0013 · ADR-0031 · issue #1002

## Context

Both modules cache slow-changing data in **Caffeine, which is per-JVM**:

- the frontend `STATIC_DATA_CACHE` (URI-keyed, 10 min), evicted app-wide by `clearStaticDataCache()`
  on admin mutations (REQ-DATA-007);
- the backend master-data caches (30 min), evicted by each service's `@CacheEvict` on writes and by the
  sync sweeps on completion (REQ-DATA-011).

REQ-DATA-007's guarantee — *"no user sees a list more stale than the last mutation"* — is airtight
**only because there is exactly one frontend JVM and one backend JVM**. An eviction on replica A does
not reach replica B, so with a second replica a peer would serve cached catalogues (and roles, settings,
age thresholds) up to the TTL stale after another replica's mutation, with no test or alert catching it.

The current deployment is single-instance **by construction**, not by luck: `docker-compose.yml` pins
`container_name: backend` / `container_name: frontend` (which makes `docker compose --scale` fail),
`scripts/deploy.sh` does an in-place `up -d` recreate rather than a multi-container rolling swap, there
is no `deploy.replicas` / load balancer anywhere, and the host is documented as a single-purpose box.
Redis is present but used **only** for Spring Session, not as a cache backend.

The caching audit (#1002) raised the obvious "distributed correctness" fixes — move the eviction-sensitive
caches onto Redis, or add a Redis pub/sub evict-broadcast — and asked whether to build one now.

## Decision

**Keep per-instance Caffeine + eviction-on-mutation. Do not build Redis-backed caching or a pub/sub
evict-broadcast at this time.** Against the actual single-instance topology both are pure downside: a
Redis-backed cache adds a network hop + serialization to every reference-data read that is currently an
in-JVM hit and puts the read path behind Redis availability; a pub/sub broadcast adds a subscriber, a
delivery-failure mode, and monitoring surface to solve a problem that cannot occur at one replica.

The single-instance precondition is made **explicit and binding** in REQ-DATA-007 (CACHE-DIST-01) so it
can no longer be relied on silently. Scaling either module beyond one replica is gated on first
replacing the eviction-sensitive caches with a shared/broadcast eviction scheme.

## Consequences

- No code, dependency, or infrastructure change now — the deliverable is this decision record plus the
  REQ-DATA-007 invariant bullet. The fast in-JVM read path and the simple `@CacheEvict` model stay.
- The revisit **trigger** is a concrete decision to run **>1 frontend or backend replica** (nothing
  short of that — a mere traffic increase does not qualify while `container_name` pins the topology).
- The **preferred future design**, if the precondition ever changes, is a **Redis pub/sub
  evict-broadcast** for the coarse caches that keeps Caffeine as the local read path and fans out only
  the *eviction* — explicitly **not** full Redis-backed caching. That future work must also carry: the
  REQ-DATA-007 per-principal / guest-redaction leak review for anything that becomes shared, wiring the
  broadcast into the existing `@CacheEvict(allEntries=true)` path (the caches are `expireAfterWrite`-only),
  and REQ-OBS-005…011 alert/dashboard additions for the new subscriber and its at-least-once-delivery
  failure mode.

## Alternatives considered

- **Move the eviction-sensitive caches onto Redis now.** Rejected: net-negative at one replica — a
  network hop + serialization on every currently-in-JVM read and a new availability dependency, to fix a
  staleness class that cannot occur at the current topology.
- **Add a Redis pub/sub evict-broadcast now.** Rejected for the same reason — it is the right design
  *when* there is more than one replica, but pure added surface (subscriber, delivery-failure handling,
  monitoring) until then.
- **Shorten the TTLs to paper over multi-instance staleness.** Rejected: it does not make eviction reach
  peers, re-incurs the DB-hit cost the 30 min TTL was chosen to avoid (REQ-DATA-011 context), and would
  be solving the wrong problem — freshness at single instance is a TTL/eviction-hook question, not a
  distribution one.
- **Leave the single-instance assumption undocumented.** Rejected: every other place with the same
  hazard already documents it (the live-sync relay, token single-flight, ADR-0019); REQ-DATA-007 was the
  lone silent carrier, so CACHE-DIST-01 closes a consistency gap.


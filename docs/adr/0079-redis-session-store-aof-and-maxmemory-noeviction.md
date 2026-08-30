# ADR-0079 — Redis session store: RDB+AOF hybrid persistence and an explicit maxmemory / noeviction ceiling

- **Status:** Accepted
- **Date:** 2026-07-07
- **Deciders:** @greluc
- **Related:** spec REQ-OPS-018 · REQ-OPS-010 · REQ-OBS-005…011 · ADR-0072 · ADR-0074 (Redis is session-store only, not a cache)

## Context

Redis holds exactly two things, both with a low-but-non-zero cost on loss:

- the **Spring Session** store (frontend): `OidcUser` + `OAuth2AuthorizedClient` (incl. the OAuth2
  **refresh token**) + flash attributes, behind a rolling 30-day login;
- the **ingest handoff** staging (ingest): a single-use draft, TTL **30 minutes** since ADR-0110.
  This line said "5-minute-TTL draft **pointer**" until 2026-08-30; it is neither. The entry holds
  the **full draft body**, which is why the sizing argument below needed the per-subject quota
  REQ-INGEST-003 now mandates (`max-handoffs-per-subject`, `max-handoff-bytes`) — without it one
  caller could hold 900 entries of up to the 2 MiB ingress cap in a `noeviction` Redis shared
  with the session store, which refuses writes at the ceiling rather than evicting.

Two aspects of the runtime config were suboptimal:

**Persistence.** History: pre-M-7 ran `--appendonly yes` with `appendfsync always` — one fsync per
session write (~200/s under load), pure I/O with little payoff, so M-7 dropped **all** persistence.
2026-06 re-enabled **RDB only** for the 30-day session goal, keeping AOF off on the stated grounds that
"the ~200 fsync/s AOF cost stays off". That rationale conflates AOF with `appendfsync always`: AOF with
`appendfsync everysec` costs **~1 fsync/s regardless of write volume** and caps the worst-case loss at
~1 s, versus RDB's ≤5-min window. For a store now carrying refresh tokens (whose loss forces a re-auth
round-trip — silent only while a live Keycloak SSO cookie exists), the `everysec` middle ground was
wrongly excluded by a rationale that only applied to `always`.

**Memory.** The `redis-server` command set **no `--maxmemory`**, so Redis ran unbounded (`maxmemory=0`)
inside a 256 MB cgroup. Consequences: (1) under memory growth the **kernel OOM-killer** drops the whole
process (brief outage + AOF/RDB reload) instead of Redis managing the boundary; (2) the
`RedisMemoryHigh` leading-indicator alert self-guards on `redis_memory_max_bytes > 0` and was therefore
**permanently inert**; (3) `RedisEvictions` likewise cannot fire without a limit. The alerts described a
memory-management model the config never actually enabled.

## Decision

**Run RDB + AOF (hybrid) and pin an explicit memory ceiling with a session-safe eviction policy**, in
both the `redis` (prod) and `redis-dev` command lines:

- `--appendonly yes --appendfsync everysec` — AOF as the primary durability layer (~1 fsync/s, ~1 s
  worst-case loss). On restart Redis prefers the AOF.
- `--save "60 1"` — a compact, always-fresh RDB snapshot for fast restart and to keep the
  `RedisRdbStale` probe green (replaces `--save "300 1 60 1000"`).
- `--maxmemory 192mb` — below the 256 MB cgroup, leaving copy-on-write headroom for the RDB/AOF-rewrite
  forks and fragmentation.
- `--maxmemory-policy noeviction` — **mandatory** for a session store: evicting a session key is a
  silent logout, so at the ceiling Redis rejects **new** writes (failed logins) rather than dropping
  live sessions.

The two Redis memory alerts are reconciled to the new reality: `RedisMemoryHigh` is now the real leading
indicator (it becomes functional once `maxmemory > 0`), and `RedisEvictions` is re-framed as a
**misconfiguration tripwire** (under noeviction any eviction means the policy was wrongly changed).

## Consequences

- Worst-case session-loss window on a crash drops from ≤5 min to ~1 s at ~1 fsync/s — negligible I/O for
  this tiny data set. AOF and RDB both land on the `/var/iri/redis` bind mount (unchanged; still excluded
  from off-site backups per REQ-OPS-010 — sessions transparently re-login).
- Memory behaviour is now defined and observable: the kernel OOM-kill path is replaced by a graceful
  write-refusal at 192 mb, and `RedisMemoryHigh` warns 10 min before that boundary.
- Takes effect when the `redis` container is recreated on the next deploy (a redis command change is a
  config-only diff, auto-applied per REQ-OPS deployment rules).
- The refresh token remains at rest in plaintext on the host bind mount (now in the AOF as well as the
  RDB) — no change to the existing accepted exposure; the dir never leaves the host (REQ-OPS-010).

## Alternatives considered

- **AOF `appendfsync always`.** Rejected: the pre-M-7 pathology (~one fsync per write); its ~0-loss
  guarantee is not worth the per-write fsync for a session store where the loss is "a few users
  re-login".
- **Keep RDB-only, no AOF.** Rejected: the ≤5-min loss window is avoidable at ~1 fsync/s, and the
  original reason to exclude AOF (`always`-mode cost) does not apply to `everysec`.
- **`maxmemory` with an evicting policy (`allkeys-lru`).** Rejected outright: LRU eviction of a session
  key is a silent, unpredictable logout — `noeviction` is the only correct policy here.
- **Leave `maxmemory` unset.** Rejected: cedes the memory boundary to the kernel OOM-killer and leaves
  `RedisMemoryHigh` permanently inert, i.e. no warning before the cliff.
- **Redis replication / HA for durability.** Out of scope: single-instance by construction (ADR-0074);
  session loss is recoverable by re-login, so HA is unjustified here.


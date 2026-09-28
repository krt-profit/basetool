# ADR-0221 — Redis grows to 768 MB and holds a bounded exchange partition

- **Status:** Accepted — owner gate G0 of epic [#2078](https://github.com/krt-profit/basetool/issues/2078),
  taken with the merge of #2111 and #2112 (2026-09-26); implemented on main by 2026-09-28 (the Redis size #2115, the partition and the
  ACL rows with epic #2078); the production ACL render precedes the go-live release and the resize
  arrives with it ([#2092](https://github.com/krt-profit/basetool/issues/2092), amendment 2). Amends [ADR-0085](0085-scale-user-sync-and-stack-capacity-for-5000-accounts.md) (the
  Redis ceiling) and [ADR-0207](0207-each-service-reaches-redis-as-its-own-acl-user.md) (two new key
  families in the ACL).
- **Date:** 2026-09-26
- **Deciders:** @greluc
- **Related:** `REQ-OPS-018`, `REQ-SEC-068` · [ADR-0079](0079-redis-session-store-aof-and-maxmemory-noeviction.md) ·
  [ADR-0217](0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md) ·
  [ADR-0218](0218-exchange-sync-semantics.md)

## Context

Redis is the session store (`noeviction`, AOF) and the live-sync fan-out. ADR-0085 set
`maxmemory 384mb` in a 512 MB container. The exchange adds data the gateway must share across
restarts: the registry mirror, revocations and the `jkt` deny list (written by the backend), and
daily quotas and idempotency results (written by the gateway). A full Redis under `noeviction`
refuses writes — logins would fail first. Today the backend's ACL user has no key access at all,
and the ingest user has `SET` and `EXPIRE` on `ingest:*` but no `GET` or `INCR`.

*Note 2026-09-28: the last sentence above is the ACL when the decision was taken (2026-09-26). The
template on main, `scripts/redis-users.acl.tmpl`, gives the backend `GET` and `SET` on `exchange:*`,
and the ingest user `GET`, `INCR`, the sorted-set commands, `EVAL` and `EVALSHA` on `ingest:*` plus
read-only access to `exchange:*`; production renders it with the go-live (#2092).*

## Decision

1. **Size.** `maxmemory` rises to a **fixed 768 MB** and the container limit to **1024 MB**
   (`maxmemory` + 256 MB AOF-rewrite headroom), before the first exchange release, checked against
   the host's RAM (amends ADR-0085; owner decision 2026-09-26, chosen over a formula from the
   measured peak).
2. **Hard exchange budget.** Gateway-written exchange data is bounded to **1 MiB per client and
   member, 16 MB per client and 64 MB in total**, counted exactly (every stored value registers its
   size and expiry in a per-scope sorted set, pruned on write). Above a limit the gateway answers
   `503 EXCHANGE_BUDGET_EXHAUSTED` instead of writing. An alert fires at 80 %.
3. **Two key families.** The backend writes `exchange:*` (registry mirror, global switch,
   revocations, deny list); the gateway reads it. The gateway writes `ingest:xch:*` (quotas,
   idempotency, locks) with `GET`, `SET`, `INCR`, `EXPIRE` and the sorted-set commands the budget
   needs. The mirror never sits under `ingest:*`, where the gateway could rewrite its own registry
   (amends ADR-0207).

## Consequences

- Sessions keep their room: the whole exchange budget is under a tenth of the new ceiling.
- The Redis memory alerts and the container sizing in the compose files and Quadlet units move with
  the change; the enlargement is a production write and goes through the owner's approval.
- The sum of all container memory limits rises from 14 000 MiB to 14 512 MiB, above ADR-0085's
  ~14 GB review trigger on the 16 GB host. The owner accepted that on 2026-09-26: limits are
  ceilings, and Redis's measured resident set is a few dozen megabytes; the price is less reserve
  in the case where every container peaks at once.
- A client that tries to fill Redis is refused, not other members' logins.

## Alternatives considered

- **max(512 MB, measured peak + budget + 50 %).** Proposed, rejected by the owner for a fixed,
  plannable value.
- **A separate Redis for the exchange.** Rejected: one more service to run, secure and back up for
  a few megabytes.
- **Soft limits with eviction.** Rejected: `noeviction` protects the sessions, and an evicted
  revocation would re-open access.

## Amendment 1 (2026-09-27) — the budget is checked and recorded atomically

**Status:** accepted · **Approved by:** @greluc (security-review findings L2 and L4) · **Spec:**
`REQ-XCH-020`, `REQ-XCH-023`

Decision 2 counted with a separate check and record, which let parallel writes overshoot a limit,
read a whole sorted set on every write and left the sets' own memory uncounted. Each budget step is
now one Lua script: it prunes a bounded batch of expired entries, checks all three limits against
running totals kept beside the sets and records the entry, charging each entry a fixed 512 bytes on
top of its value. The idempotency lock is taken with a per-request token and released by a
compare-and-delete script. Decision 3's command list therefore grows by `EVAL`, `EVALSHA`, `ZREM`
and `ZSCORE` for the ingest user; Redis checks every command a script issues against the same user's
rules, so the key families stay as decided. The ACL change is rendered and loaded on the host by the
owner, like every ACL change (ADR-0207).

Decision 2's "counted exactly" overstated it: the per-entry charge for the sets' own memory is a
fixed estimate, so the budget bounds the exchange's data within a known margin rather than
measuring Redis's memory byte for byte. A quota counter is created with its expiry by `SET NX EX`
before its `INCR`, so it can no longer lose its expiry (second review, L8).

## Amendment 2 (2026-09-28) — the resize arrives with the release, and leaves with a rollback

**Status:** accepted · **Spec:** `REQ-OPS-018` · **Runbook:** `docs/EXCHANGE_GO_LIVE_RUNBOOK.md`

Decision 1 placed the resize „before the first exchange release". It cannot be a step of its own:
`--maxmemory 768mb` and `Memory=1024M` are lines of the generated `redis.container` unit, and units
reach production only with a release. The release that carries them is the one that carries the
exchange, so the resize lands in its deploy — Redis restarts once, in the release's restart window,
with the exchange still switched off and its registry empty. The ACL render (decision 3) is what has
to come first: from that release on the gateway reads `exchange:registry` every 30 s.

The go-live plan also said Redis's size „stays" on an application rollback. It does not: a promotion
of an earlier release restores that release's units, so Redis returns to `384mb` in 512 MB and
restarts again. The rendered ACL does stay; the earlier releases run unchanged on it.

The container-limit sum in the consequences is the compose files' figure, 14 512 MiB. The production
host runs Quadlet units, and there `alloy` (512 MiB in the compose file) and `node-exporter` (32 MiB)
are host services without a container limit, so the 18 units' `Memory=` lines sum to **13 968 MiB**
with Redis at 1024 MB (13 456 MiB before; read on production 2026-09-28). Both figures are right for
what they count; the host's own ceiling is the unit sum, and alloy and node-exporter still use host
memory outside it.

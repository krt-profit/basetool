# ADR-0221 — Redis grows to 768 MB and holds a bounded exchange partition

- **Status:** Proposed — epic [#2078](https://github.com/krt-profit/basetool/issues/2078); nothing
  built yet. Amends [ADR-0085](0085-scale-user-sync-and-stack-capacity-for-5000-accounts.md) (the
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
- A client that tries to fill Redis is refused, not other members' logins.

## Alternatives considered

- **max(512 MB, measured peak + budget + 50 %).** Proposed, rejected by the owner for a fixed,
  plannable value.
- **A separate Redis for the exchange.** Rejected: one more service to run, secure and back up for
  a few megabytes.
- **Soft limits with eviction.** Rejected: `noeviction` protects the sessions, and an evicted
  revocation would re-open access.

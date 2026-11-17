# ADR-0389 — The ingest gateway runs no Lua in Redis

- **Status:** Accepted — the scripts are replaced; the ACL keeps `EVAL`/`EVALSHA` until production
  runs this release, and their removal is the follow-up step of decision 4
- **Date:** 2026-11-16
- **Deciders:** @greluc
- **Related:** [REQ-SEC-068](../specs/security-and-access.md), [REQ-XCH-020](../specs/external-exchange.md),
  [REQ-XCH-023](../specs/external-exchange.md) ·
  [ADR-0207](0207-each-service-reaches-redis-as-its-own-acl-user.md),
  [ADR-0221](0221-redis-grows-to-768-mb-and-holds-a-bounded-exchange-partition.md),
  [ADR-0388](0388-the-session-store-gets-a-redis-the-ingest-gateway-cannot-reach.md) ·
  amends ADR-0221 amendment 1 and ADR-0207 · follow-up
  [#3494](https://github.com/krt-profit/basetool/issues/3494)

## Context

`basetool-ingest` is the only Redis user that may run scripts. The gateway sends five through
`EVAL`/`EVALSHA`: the byte budget's check-and-record step and its `Retry-After` walk, the quota's
add and refund, and the release of an idempotency claim (ADR-0221 amendment 1). Each needs a
read, a decision and a write over up to six keys as one step.

Redis checks every command a script issues against the user's ACL, so a script reaches only the
user's keys. The Lua interpreter, however, runs inside the server, and its faults reach past the
ACL: CVE-2025-46818 lets an authenticated user run code in another user's context, and its advisory
names denying `EVAL` and `FUNCTION` as the workaround; CVE-2025-49844, rated critical, was published
with it. The gateway is the internet-facing service (ADR-0268), the one whose compromise ADR-0207's
ACL design assumes, and today it shares the server with every session token (ADR-0388).

Redis offers an atomic read-check-write without scripting: an optimistic transaction. The client
watches the keys it reads, decides, and queues its writes between `MULTI` and `EXEC`; when another
client changed a watched key in between, Redis discards the writes and the client tries again.

## Decision

1. **No script.** The budget step, the quota's add and refund and the claim release run as
   optimistic transactions through one helper, `OptimisticTransaction`: it watches the keys a step
   reads, lets the step read and decide, and queues the step's writes between `MULTI` and `EXEC`.
   An aborted `EXEC` runs the step again on the new state, up to **32 attempts** with a random pause
   of up to **8 ms** between them; when every attempt is aborted, the step fails as an unreachable
   Redis does (`503` with `Retry-After`, REQ-XCH-023). The `Retry-After` walk reads without a
   transaction: its answer is a hint and was never written.
2. **The rules stay.** The budget step keeps every rule of ADR-0221 amendment 1 — up to 1000
   expired entries pruned per set, three limits, 512 bytes charged per entry, a missing total
   rebuilt from its set, an entry's expiry only ever raised, and the sets and totals kept at least as
   long as their longest entry (`SET … KEEPTTL`, `PEXPIRE … NX` and `GT`). The quota's add never
   creates a counter and its refund never goes below zero; a claim is released only while it holds
   the caller's token.
3. **A pooled connection per transaction.** `WATCH` and `MULTI` need a connection of their own, so
   the gateway gets the Lettuce connection pool (`commons-pool2`, version from the Spring Boot BOM):
   at most 8 connections, waiting at most 2 s for one. Every other command keeps the shared
   connection, which Lettuce takes from the same pool and holds for good, so 7 are left for
   transactions; a step that waits longer for one fails like an unreachable Redis.
4. **The ACL changes in two steps.** This change grants `basetool-ingest` `WATCH`, `UNWATCH`,
   `MULTI`, `EXEC`, `DISCARD`, `INCRBY`, `DECRBY` and `ZCARD` and keeps `EVAL`/`EVALSHA`, so one
   rendered ACL serves both the running release and this one. Once production runs this release, a
   follow-up removes `EVAL`/`EVALSHA` from the template (#3494); from then on the gateway's user can
   run no script at all.

## Consequences

- After step 4 a code execution in the gateway cannot reach the Lua interpreter through its own
  Redis user.
- A step costs a few reads per scope and one transaction instead of one `EVALSHA`. At the gateway's
  write rate that is a few milliseconds per write.
- Every write touches the total scope, so parallel writes conflict and retry. The budget still
  never overshoots — the parallel tests of `ExchangeStoreRedisIntegrationTest` run under the ingest
  user — and sustained contention beyond 32 attempts turns into `503`s, never into an overshoot. An
  exhausted step logs a warning naming `OptimisticLockingFailureException`.
- The owner renders and loads the expanded ACL before this release, and the reduced one after the
  follow-up (ADR-0207: an ACL change is a render and an `ACL LOAD` on the host).
- REQ-SEC-068's command list and REQ-XCH-023's description of the budget step move with this
  change; `docs/deployment.md` names the new commands.

## Alternatives considered

- **Keep the scripts.** Rejected: the Lua interpreter stays reachable from the internet-facing
  service.
- **Redis Functions (`FUNCTION LOAD`, `FCALL`).** Rejected: the same Lua interpreter.
- **A lock per scope** (`SET NX` with a token). Rejected: releasing it safely is the
  compare-and-delete the claim release already needs, a lock on the total scope serializes every
  write, and a holder that dies blocks the scope until the lock expires.
- **Keep the budget in the gateway's memory.** Rejected: it is lost on every deploy and wrong with a
  second gateway (REQ-XCH-023, *Per instance, not per deployment*).
- **Remove `EVAL`/`EVALSHA` in the same step.** Rejected: whichever comes first, the ACL render or
  the deploy, would refuse every exchange write until the other follows.

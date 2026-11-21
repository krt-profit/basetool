# ADR-0389 — The ingest gateway runs no Lua in Redis

- **Status:** Accepted — implemented: the scripts are replaced and the ACL grants no `EVAL`/`EVALSHA`
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
   optimistic transactions through one component, `OptimisticTransactions`: it watches the keys a
   step reads, lets the step read and decide, and queues the step's writes between `MULTI` and
   `EXEC`. An aborted `EXEC` — recognised by the connection's own `null` reply, not by an empty list
   — runs the step again on the new state, up to **32 attempts** and **2 s**, with a random pause
   whose bound doubles from 2 ms up to **32 ms**; when every attempt is aborted, the step fails as an
   unreachable Redis does (`503` with `Retry-After`, REQ-XCH-023) and is counted in
   `basetool_ingest_exchange_store_conflicts_total`, which `ExchangeStoreStepsExhausted` watches. A
   budget step that leaves every scope as it found it writes nothing, so it never aborts another.
   The `Retry-After` walk reads without a transaction: its answer is a hint and was never written.
2. **The rules stay.** The budget step keeps every rule of ADR-0221 amendment 1 — up to 1000
   expired entries pruned per set, three limits, 512 bytes charged per entry, a missing total
   rebuilt from its set, an entry's expiry only ever raised, and the sets and totals kept at least as
   long as their longest entry (`SET … KEEPTTL`, `PEXPIRE … NX` and `GT`). The quota's add never
   creates a counter and its refund never goes below zero; a claim is released only while it holds
   the caller's token.
3. **Every command at most once, on a pooled connection.** `WATCH` and `MULTI` need a connection of
   their own, and Lettuce's defaults would re-establish a dropped connection and replay what it had
   not answered — the queued writes of a transaction outside it, a refund twice. So the gateway has
   one explicit connection factory, `RedisClientConfig`: a pool (`commons-pool2`, version from the
   Spring Boot BOM) of at most 16 connections that tests each before lending it, waiting at most 2 s
   for one; no shared connection, so a step's reads, watch and writes run on the connection it
   borrowed; no reconnect, so a dropped connection fails its commands and is replaced on the next
   borrow; and a 2 s command and connect timeout. An attempt that fails for any reason, an `Error`
   included, closes its connection, so no watch, open transaction or unanswered command reaches the
   next borrower.
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
- Every write that changes the budget touches the total scope, so concurrent writes conflict and
  retry: the gateway carries a few hundred committing budget steps per second, far above the
  exchange's write rate. The budget still never overshoots — the parallel tests of
  `ExchangeStoreRedisIntegrationTest` run under the ingest user on the production connection
  settings — and sustained contention beyond 32 attempts or 2 s turns into `503`s, never into an
  overshoot; `ExchangeStoreStepsExhausted` reports it.
- A dropped Redis connection costs the requests in flight on it a `503` instead of a silent
  replay; the next request gets a fresh connection.
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

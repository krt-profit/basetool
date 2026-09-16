# ADR-0186 — An unmappable session hash reads as no session

- **Status:** Accepted
- **Date:** 2026-09-16
- **Deciders:** @greluc
- **Requirement:** [REQ-SEC-063](../specs/security-and-access.md)
- **Related:** [ADR-0157](0157-a-dropped-session-value-is-repaired-on-the-request-that-found-it.md)
  (why the repair does not live on the read path),
  [ADR-0154](0154-a-container-written-final-session-value-gets-a-forced-type-id.md) (the
  *hypothetical* this exception was known as until now),
  [ADR-0088](0088-two-tier-session-idle-timeout.md) (the 720-hour window the half-written hash
  inherits), [ADR-0079](0079-redis-session-store-aof-and-maxmemory-noeviction.md) (the store and its
  AOF settings), REQ-OBS-006 (the bounded tag)
- **Source:** read-only production log triage, 2026-09-16 — `/var/iri/frontend/log`, and
  `docker inspect redis`

## Context

The frontend has been answering `IllegalStateException: creationTime key must not be null` in
production for months, and every document that named that exception treated it as something that
*would* happen if a design mistake were made.

Counted in the production frontend error log on 2026-09-16:

|    Day     |                                            Occurrences                                            |
|------------|---------------------------------------------------------------------------------------------------|
| 2026-09-14 | 90 in the 11:00 hour, 14 in 12:00, 4 in 16:00, **163 in 17:00**, 7 in 18:00, 8 in 21:00 — **286** |
| 2026-09-16 | 6 in 07:00, 8 in 11:00, 4 in 14:00                                                                |

plus 8–10 a day on 2026-07-24, -29, -30, 2026-08-05, -14, -16, -17, -21 and 2026-09-02. The
2026-09-14 burst opens in the hour Redis restarted (`docker inspect redis` →
`StartedAt = 2026-09-14T10:56:28Z`).

**Every one of those is an HTTP 500 for a member.** `RedisSessionMapper#getRequired` throws when the
hash it is handed is non-empty and missing `creationTime`, `lastAccessedTime` or
`maxInactiveInterval`. Nothing on Spring Session's read path catches it, so it leaves
`SessionRepositoryFilter` exactly the way the 2026-09-02 `SerializationException` did — and the
cookie is **not** cleared afterwards (`commitSession` expires it only after an explicit
`invalidate()`), so the browser keeps presenting the same session id and keeps getting 500s. For up
to the 720-hour authenticated window, on every page and every asset that passes through the security
filter chain. The bursts of four to eight in the log are somebody giving up, not somebody
recovering.

### It is not REQ-SEC-049's failure mode

The obvious reading — a poisoned value nulled by our own `FaultTolerantSessionSerializer` — is
wrong, and the log rules it out: there is **not one** `Dropped an unreadable session value` line
anywhere in the period. Nothing of ours nulled the key. `creationTime` is written as a bare `Long`,
carries no `@class`, and cannot fail deserialization (measured 2026-09-02, pinned by
`SessionSerializerRoundTripTest`). The field was never in the hash.

### Spring Session produces the hash itself

`RedisIndexedSessionRepository.RedisSession#saveDelta` writes the session back with a plain `HSET`
of the changed fields only:

```java
getSessionBoundHashOperations(sessionId).putAll(this.delta);
```

and `creationTime` / `maxInactiveInterval` are put into that delta solely `if (this.isNew)`. So a
request that read a live session and commits *after* the hash has gone — a Redis restart, an AOF
truncation under `appendfsync everysec`, the hash's own TTL, a purge run against live traffic —
re-creates the key holding `lastAccessedTime` alone. `saveDelta` then sets
`expire(maxInactiveInterval + 5 min)` on it, so the half-written state inherits the **full session
TTL** rather than being a blip.

This was inferred from the 4.1.1 sources during the triage and is now reproduced in CI against a
real Redis: `HalfWrittenSessionHashIntegrationTest#aDeltaWriteAfterTheHashVanishesReCreatesItHalfWritten`
deletes the hash between the read and the commit and asserts both halves — the key comes back with
`lastAccessedTime` and no `creationTime`, and it carries a TTL again.

### Nothing alerted, and that is arithmetic

`LogbackErrorSpike` is `rate(logback_events_total{level="error"}[5m]) > 0.2` held for 10 minutes.
163 errors spread over an hour is ~0.045/s. The worst day this fault has had does not reach a
quarter of the threshold, so it has always been delivered to members silently.

## Decision

**A session hash the mapper cannot map reads as *no session*, and the give-up is counted.**

`SessionAttributeDiagnosticMapper` — already installed in place of the default mapper, and already
the layer that sees the whole hash — catches `IllegalStateException` from its delegate, resolves
which required field was absent *from the hash itself*, bumps
`basetool_session_unmappable_total{missing_key}`, logs one WARN per distinct missing key, and
returns `null`.

`null` is not an improvisation: it is a contract both upstream call sites already honour.

```java
MapSession loaded = this.redisSessionMapper.apply(id, entries);
if (loaded == null || (!allowExpired && loaded.isExpired())) {
    return null;
}
```

`#getSession` returns `null` to `SessionRepositoryFilter`, which mints a fresh session and a fresh
cookie; `#onMessage` guards with `if (loaded != null)` and skips the `SessionCreatedEvent`. The
non-indexed `RedisSessionRepository#findById` takes the same branch and even deletes the key there.
The member is **signed out and can log back in**, which is the same bargain
`FaultTolerantSessionSerializer` struck for an unreadable value in ADR-0154's predecessor.

Three boundaries the decision deliberately keeps:

- **The catch is narrow.** `IllegalStateException` only — the single exception `getRequired` raises.
  A broader catch would swallow faults this mapper knows nothing about and turn them into silent
  sign-outs.
- **Nothing is written.** The half-written hash is left exactly as found. Repairing or deleting it
  would put a Redis write on the session *read* path, which ADR-0157 rules out for the subsystem
  that took the whole application down twice inside two releases. The orphan expires with its TTL
  and nobody reads it again, because that browser now carries a different session id.
- **The tag is closed.** `missing_key` can only take the three required-field literals plus `other`,
  and is resolved by inspecting the map rather than by parsing the upstream exception's message
  (REQ-OBS-006). A fourth required field added upstream still degrades correctly and reports
  `other`.

`SessionUnmappableSustained` (> 20 per 15 m held 30 m, warning) backs the counter and sums **across**
`missing_key`, so a wire-format break that loses all three fields at once cannot hide below a
per-series threshold.

## Alternatives considered

- **Leave it, and fix whatever loses the hashes.** Rejected: the producers are Redis restarts, AOF
  truncation and TTL expiry — ordinary operational events that will keep happening. A read path
  whose correctness depends on the store never losing a key is the same assumption that made
  2026-09-02 a total outage.
- **Repair the hash — delete the orphan, or write the missing fields back.** Rejected on ADR-0157's
  standing ground: a Redis write on the session read path. It also buys nothing the null does not,
  since the browser is about to stop referring to that key anyway.
- **Catch it one layer out, in a servlet filter.** Rejected: a filter cannot distinguish this
  `IllegalStateException` from any other without matching on upstream's message text, and it would
  have to re-enter the session machinery to recover. The mapper is the layer that already holds the
  hash and the reason.
- **Return an empty `MapSession` instead of `null`.** Rejected: that is a *valid* session with
  invented timestamps, which would be written back on commit and make the corrupted state
  self-perpetuating. `null` is the upstream-sanctioned "there is nothing here".
- **Widen the catch to `RuntimeException`.** Rejected: it would also swallow a Redis connection
  failure and a serializer fault, silently signing members out instead of failing loudly — the
  opposite of what REQ-SEC-049 asks for on the write side.
- **Clear the session cookie on the give-up.** Rejected as unnecessary: `SessionRepositoryFilter`
  already issues a new session id and a new cookie once `findById` answers `null`, so an explicit
  expiry would only duplicate it — and doing it from the mapper would mean reaching for the response
  from a layer that has none.
- **Raise no counter and rely on the WARN.** Rejected outright: an unmeasured silent degradation is
  how this fault stayed invisible for months in the first place, and a format break would sign the
  whole organisation out with nothing to see.

## Consequences

- A member whose session hash is half-written is signed out once and logs back in, instead of being
  locked out of the application until they clear their cookie by hand.
- Production stops serving those 500s. On the observed history that is ~286 fewer on a day like
  2026-09-14 and a handful on an ordinary day.
- `basetool_session_unmappable_total` becomes a standing obligation: the degradation is only allowed
  to be quiet because the counter is loud, so removing or unlabelling it re-opens the hole. A rate
  that covers the whole active population means the session wire format broke.
- The counter also gives Redis a member-visible consequence it did not have: restarts, AOF
  truncation and purges now show up as a measurable number of sign-outs rather than as nothing.
- The half-written hashes already in production are not cleaned up by this change. They expire with
  their own TTL, and until then each affected browser gets one sign-out instead of a lockout.
- `RedisSessionConfig#sessionRepositoryCustomizer` now takes an `ObjectProvider<MeterRegistry>`, for
  the same bean-ordering reason `springSessionDefaultRedisSerializer` does.


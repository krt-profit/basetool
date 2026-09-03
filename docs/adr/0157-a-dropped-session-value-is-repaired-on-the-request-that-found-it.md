# ADR-0157 — A dropped session value is repaired on the request that found it

- **Status:** Accepted
- **Date:** 2026-09-03
- **Deciders:** @greluc
- **Requirement:** [REQ-SEC-050](../specs/security-and-access.md), amends
  [REQ-SEC-049](../specs/security-and-access.md)
- **Related:** [ADR-0154](0154-a-container-written-final-session-value-gets-a-forced-type-id.md)
  (the forced type id, and the premise this ADR corrects),
  [ADR-0088](0088-two-tier-session-idle-timeout.md) (the 720-hour window a poisoned session survives
  in), [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md) (`/ws/sync`, and the lazy connect
  that breaks ADR-0154's healing argument),
  [ADR-0079](0079-redis-session-store-aof-and-maxmemory-noeviction.md) (the store),
  REQ-OBS-011 (`basetool_session_value_dropped_total`)
- **Source:** production alert `SessionValueDropsSustained`, 2026-09-03 13:33Z — the **second**
  firing, 3 h 24 min after the ADR-0154 fix was live

## Context

ADR-0154 shipped at 09:39:26Z on 2026-09-03 (frontend image revision `0b626588c`, v1.6.18). At
13:33Z `SessionValueDropsSustained` fired again, at 47 drops per 15 min. Alertmanager runs
`repeat_interval: 720h`, so this was not a repeat mail: the alert had resolved and re-fired.

Read-only production inspection, under the approval gate, found the deployed frontend healthy and
the drops unchanged in kind:

```
attribute='org.apache.tomcat.websocket.server.WsHttpSessionBindingListener'
cause=InvalidTypeIdException typeId=absent baseType=java.lang.Object
```

one WARN line, 12 s after start, and then silence — the mapper's repetition guard. The counter told
the real story: 1 417 drops at 4 h 34 min uptime, 1 438 three minutes later, 1 474 at 4 h 52 min.
Flat to rising, 2–6 per minute, hours after the fix.

**The write path was not the problem.** Two independent checks say ADR-0154 works:

- `tomcat-embed-websocket:11.0.25` is on the frontend's resolved runtime classpath, so
  `CONTAINER_WRITTEN_FINAL_SESSION_TYPES`' `Class.forName` lookup resolves and the mix-in is
  registered;
- against a real Redis and the real `RedisIndexedSessionRepository`, the attribute is written as
  `{"@class":"org.apache.tomcat.websocket.server.WsHttpSessionBindingListener","key":"…"}` and reads
  back. `SessionSerializerRoundTripTest` had only ever proved the serializer in isolation, which is
  why this could not be answered from CI the first time.

**What was still broken is that nothing ever clears a value that has already been poisoned.**
`FaultTolerantSessionSerializer` turns the unreadable bytes into a marker,
`SessionAttributeDiagnosticMapper` names the attribute and nulls it *in memory*, and both are
read-only by design — so the bytes stay in Redis and are re-read, re-dropped and re-counted on every
subsequent request that session makes, for up to its 720-hour window (REQ-SEC-025).

ADR-0154 accepted that, on an explicit argument:

> **Existing poisoned sessions heal themselves.** The drop leaves the attribute unset, so the next
> handshake writes a readable value over it.

with the supporting claim that the handshake happens on "every logged-in page, because live sync
opens `/ws/sync`". **That claim is false.** `krt-live-sync.js` opens the socket *lazily*:
`ensureSocket()` is reached only from `subscribe()`, `sendChanged()` and `sendPresence()`. A page
that subscribes to no live-sync room — and most do not — never handshakes, so
`WsServerContainer#registerAuthenticatedSession` never runs and nothing rewrites the attribute.
Meanwhile `notifications.js` polls every 60 s (300 s while SSE is healthy) from *every* page, and
each poll reads the session and drops the value again.

So the residue could not drain, the alert could not clear, and the meter stayed blind to the next
real poisoning — the same outcome ADR-0154 set out to prevent, reached by a different route.

## Decision

**An unreadable session value is removed from the session on the request that discovered it, so it
is dropped once rather than for the life of the session.**

- `SessionAttributeDiagnosticMapper` hands the attribute name to `SessionAttributeRepairQueue`, a
  bounded thread-local. It still writes nothing itself.
- `SessionAttributeRepairFilter` — ordered `SessionRepositoryFilter.DEFAULT_ORDER + 10`, i.e.
  immediately inside Spring Session's own filter — drains the queue in a `finally` and calls
  `HttpSession#removeAttribute` for each name. That is the same public API
  `BackendRoleSyncFilter` and `TermsAcceptanceGateFilter` already use on every request; it goes
  through the ordinary delta-and-flush machinery instead of reaching into the serializer, so no
  Redis write is added to the session **read** path.
- The removal writes an empty value into the hash field (Spring Session's ordinary removal shape,
  not an `HDEL`), which deserialises to `null`. The next read therefore costs nothing and counts
  nothing.
- The queue is cleared on the way *into* the chain as well as drained on the way out, because
  Tomcat pools request threads and a leftover name would remove an attribute from a different
  member's session. The filter also runs on async dispatches, so the notification SSE stream cannot
  strand one.
- Separately, `RedisSessionConfig` now logs the allow-list outcome at `INFO`, and an unresolved name
  at `WARN`. The mechanism degrades silently — an unresolved class simply means no mix-in — and on
  2026-09-03 ruling that out cost a full investigation with nothing in the log either way.

## Alternatives considered

**Leave it and let the sessions expire.** The residue drains on the 720-hour authenticated window,
during which the alert fires continuously and the meter cannot see a new poisoning. This is exactly
the trap ADR-0154 rejected under "accept it and raise the alert threshold", reached by waiting
instead of by editing a threshold.

**Purge the poisoned field in Redis once, by hand.** The precedent exists — the `ActiveSessionsRunaway`
backlog was purged rather than coded around. It works, it is a production write and therefore the
owner's to run, and it fixes this instance only: the next value that is ever unreadable starts a new
720-hour tail. Not mutually exclusive with this ADR, and unnecessary once it ships, because every
poisoned session repairs itself on its very next request.

**Repair inside the mapper or the serializer.** Shortest diff, worst place: it puts a Redis write on
the session read path, which is the subsystem that took the whole application down twice inside two
releases. Rejected for the same reason ADR-0154 rejected stripping the attribute on the write path.

**Open `/ws/sync` eagerly on every authenticated page**, making ADR-0154's premise true. It would
heal each session on its next page view, at the cost of a WebSocket per member per page that nothing
else needs — and it makes session integrity depend on an unrelated feature's connection policy.

**Count repairs in a new metric.** Rejected as noise: a repair follows every drop exactly once, so
the series would carry no information `basetool_session_value_dropped_total` does not already carry.
The signal that matters is that the drop counter now *can* reach zero.

## Consequences

- `basetool_session_value_dropped_total` becomes a **step**, not a plateau: one drop per poisoned
  session, then nothing. The alert's own reading changes with it — a rate that does not fall to zero
  now means a value is still being *written* unreadably, which is the only case worth waking someone
  for. The alert annotation and `monitoring/README.md` are corrected to say so.
- The production backlog drains within minutes of this deploying — one request per session — with no
  Redis write by hand and nothing handed to the owner.
- ADR-0154's self-healing claim is corrected in place, dated, rather than quietly dropped. Its
  decision (the forced type id) stands and is unaffected; only its argument about what happens to
  already-poisoned sessions was wrong.
- `SessionAttributeRepairIntegrationTest` pins the repository write path against a real Redis — the
  gap that let the first fix ship unverifiable. `SessionSerializerRoundTripTest` keeps its narrower
  job; the two are not redundant.
- If some future value really is re-written unreadably on every request, the repair costs one extra
  write per drop and the rate is unchanged. That was the case `SessionAttributeDiagnosticMapper`
  named when it deferred this decision, and it is a bounded cost rather than a new failure mode.


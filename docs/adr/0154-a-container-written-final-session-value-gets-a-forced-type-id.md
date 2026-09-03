# ADR-0154 — A container-written final session value gets a forced type id

- **Status:** Accepted
- **Date:** 2026-09-03
- **Deciders:** @greluc
- **Requirement:** [REQ-SEC-049](../specs/security-and-access.md)
- **Related:** [ADR-0088](0088-two-tier-session-idle-timeout.md) (the 720-hour window that lets a
  poisoned session outlive several deploys), [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md)
  (`/ws/sync`, the handshake that triggers this on every logged-in page),
  [ADR-0079](0079-redis-session-store-aof-and-maxmemory-noeviction.md) (the store),
  REQ-OBS-011 (`basetool_session_value_dropped_total`, the meter that caught it)
- **Source:** production alert `SessionValueDropsSustained`, 2026-09-03 05:23Z, ~70 drops per 15 min

## Context

`SessionValueDropsSustained` fired against a healthy application. The WARN that
`SessionAttributeDiagnosticMapper` was built to produce named the value on the first read:

```
attribute='org.apache.tomcat.websocket.server.WsHttpSessionBindingListener'
cause=InvalidTypeIdException typeId=absent baseType=java.lang.Object
```

That class is **new in Tomcat 11.0.25** — absent from 11.0.22 and 11.0.24 — and it is a Java
`record`, hence implicitly **final**. `WsServerContainer#registerAuthenticatedSession` puts it into
the `HttpSession` whenever a WebSocket session is open, carries a user principal and has an HTTP
session behind it. For this application that is every logged-in page, because live sync opens
`/ws/sync`.

The `NON_FINAL` default typing that `SecurityJacksonModules` activates writes a final type as a JSON
object with **no** `@class`, and the reader then demands the type id it was never given. This is
precisely the trap the 2026-09-02 incident review wrote down as "a record in a session is a time
bomb" and pinned with a test — walked into eight days later by a dependency rather than by our own
code.

Two properties made it worse than a one-off poisoning:

- **It never decays.** A dropped value is not written back, so the attribute reads as unset — and an
  unset attribute is exactly the condition under which Tomcat writes it again on the next handshake.
  The residue-drains-after-a-deploy reasoning that `basetool_session_value_dropped_total` was built
  for does not apply; the rate is flat by construction.
- **It destroys the signal.** The meter and its alert exist to detect a genuine session poisoning.
  A permanent background rate of ~70 per 15 min means the next real one arrives inside the noise.

Member-visible impact was nil, and that is worth stating plainly rather than inflating: only the
failing attribute is nulled, the security context is untouched, nobody was signed out, and Tomcat's
own in-request read-back check passes because Spring Session answers `getAttribute` from memory. The
defect was in the diagnostics, and the diagnostics are the thing the 2026-09-02 outage was paid for.

Reverting Tomcat was never available: 11.0.25 carries ten CVE fixes and is the reason the pin exists
([`build.gradle.kts`](../../build.gradle.kts)).

## Decision

**A final class that a dependency writes into the session is named in an allow-list and given a
forced `@class` type id through a Jackson mix-in. Our own code never appears on that list.**

- `RedisSessionConfig.CONTAINER_WRITTEN_FINAL_SESSION_TYPES` holds the fully-qualified names, and
  `ForcedTypeIdMixin` carries
  `@JsonTypeInfo(use = Id.CLASS, include = As.PROPERTY, property = "@class")`. An explicit
  `@JsonTypeInfo` is found by `createTypeSerializer` **before** the default typing is consulted, so
  the value is written in the same shape as every non-final value in the same hash and reads back.
- The names are resolved with `Class.forName(…, false, loader)` and skipped when absent. An
  internal is exactly the class that appears in one patch release and moves in the next; a
  compile-time reference would make an emergency Tomcat downgrade fail to build.
- ~~**Existing poisoned sessions heal themselves.** The drop leaves the attribute unset, so the next
  handshake writes a readable value over it. No Redis write, no purge, nothing handed to the
  owner.~~

  > [!warning] **Wrong, corrected 2026-09-03** — see
  > [ADR-0157](0157-a-dropped-session-value-is-repaired-on-the-request-that-found-it.md).
  > They do not heal themselves. The claim rests on the handshake happening on every logged-in page,
  > and it does not: `krt-live-sync.js` opens `/ws/sync` **lazily** — `ensureSocket()` is reached
  > only from `subscribe()`, `sendChanged()` and `sendPresence()` — so a page that subscribes to no
  > live-sync room never handshakes and nothing rewrites the attribute, while the notification poll
  > keeps reading the session from every page. Production kept dropping at 2–6 per minute for hours
  > after this fix was live, and `SessionValueDropsSustained` fired a second time at 13:33Z. The
  > decision below (the forced type id) is unaffected and correct; only this argument about
  > already-poisoned sessions was wrong. ADR-0157 repairs them instead of waiting for a handshake.

- A value *we* write is never fixed here. It is fixed by not writing a final type — wrap it, as
  `BackendRoleSyncFilter`'s `new ArrayList<>(…)` already does.

## Alternatives considered

**Widen the default typing to cover final types.** The general cure, and the dangerous one:
`creationTime`, `lastAccessedTime` and `maxInactiveInterval` are `Long`/`Integer` and are read as
bare scalars today. Giving them type ids changes the wire format of the three keys whose loss throws
`IllegalStateException: creationTime key must not be null` — for every live session at once, on the
subsystem that already took the whole application down twice inside two releases. Rejected as a
blast radius out of all proportion to one attribute.

**Strip the attribute on the session write path.** Would silence the meter without a Jackson
annotation, and would also disable Tomcat's bookkeeping while pretending nothing had changed. It
also means new interception code on the read/write path — the change with the worst track record in
this codebase.

**Pin Tomcat back to 11.0.24.** Trades ten CVEs for a log line, and only defers the problem: the
class stays in every later release.

**Accept it and raise the alert threshold.** The proposal that ends with the meter watching nothing.
A threshold tuned above a known-permanent rate cannot see the fault it was built for.

## Consequences

- ~~`basetool_session_value_dropped_total` returns to zero once the deployed sessions have re-written
  the attribute, so a non-zero rate means what it says again. A residual decaying tail is expected
  for sessions that never open `/ws/sync`.~~

  > [!warning] **Wrong, corrected 2026-09-03.** It did not return to zero and the tail did not decay,
  > for the reason recorded against the Decision above. "Sessions that never open `/ws/sync`" turned
  > out to be most of them, and for those the tail is flat rather than decaying — it ends only when
  > the session does, up to 720 h later. Made true by
  > [ADR-0157](0157-a-dropped-session-value-is-repaired-on-the-request-that-found-it.md), which
  > removes the value on the request that dropped it.

- The allow-list is a standing obligation: an entry that no longer resolves is dead weight and the
  round-trip test is the alarm for it, which is why the test references the Tomcat class **by type**
  while production resolves it **by name**.
- `SessionSerializerRoundTripTest` now pins both halves — the named class round-trips, and a plain
  record still does not. If the second ever starts passing, the allow-list has become a policy
  change and the required session keys are the next thing to check.
- Spring Session fires `valueUnbound` only from `setAttribute`/`removeAttribute`, not from
  `invalidate()` or Redis expiry, so Tomcat's close-the-WebSocket-on-logout path was never wired
  here and this does not wire it. That is a separate question about `/ws/sync` lifecycle, not a
  serialization one.


# ADR-0206 — A session value may name only an allow-listed class

- **Status:** Accepted — shipped in `report` mode; production runs `enforce` since 2026-09-25 (owner's switch after ~5 h of `report` with zero refusals, not a week — see `deployment.md`)
- **Date:** 2026-09-23
- **Deciders:** @greluc
- **Requirement:** [REQ-SEC-067](../specs/security-and-access.md)
- **Related:** [ADR-0154](0154-a-container-written-final-session-value-gets-a-forced-type-id.md)
  (the one container class that needed a forced type id, now an allow-list entry too),
  [ADR-0157](0157-a-dropped-session-value-is-repaired-on-the-request-that-found-it.md) (what happens
  to a refused value), [ADR-0079](0079-redis-session-store-aof-and-maxmemory-noeviction.md) (the
  store), REQ-SEC-049, REQ-OBS-011
- **Source:** improvement audit 2026-09-22, finding APPSEC-05

## Context

The frontend keeps its HTTP sessions in Redis as JSON, written by a Jackson mapper on which
`SecurityJacksonModules` activates default typing (`NON_FINAL`, `@class` as a property). Every
non-final session value therefore carries the fully-qualified name of the class it is read back as,
and reading it makes Jackson load that class, construct it and call its setters.

What decides whether a named class may be built is the polymorphic type validator, and ours was
`BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class)`: every class is a subtype of
`Object`, so every class on the frontend's classpath was allowed. Its Javadoc justified that with
"session data originates only from our own application and Keycloak". That is a statement about the
**writer**. The trust boundary is the **store**: one Redis that backend, frontend and ingest reach
with one shared password (APPSEC-04), whose ACL once left `default` open to anything on the Redis
networks (the 2026-07-10 incident). Anybody who can write one field of one `basetool:session:*` hash
could name any class the frontend can load, and have it instantiated on the next request carrying
that cookie — the shape of every Jackson deserialization-gadget CVE. The same Javadoc also claimed
Jackson 3's `allowIfSubType(String)` could not match by prefix; it does (`String#startsWith`, read
from `jackson-databind` 3.1.5 — Spring Security's own module relies on it for `java.util.ArrayList`
and friends).

The obstacle to simply narrowing it was never technical, it was proof: a validator that refuses a
class some session legitimately holds drops that attribute for every member who has it, and the
session carries the login itself.

## Decision

**A session value may name only a class on `SessionTypeAllowList`**, and the list is proven against
production's real sessions before it refuses anything.

1. **The list, matched by name before the class is loaded.** `java.util.*` and `java.time.*` (direct
   members only — `java.util.logging`, `java.util.concurrent` are out), `org.springframework.security.*`,
   `FlashMap` and `LinkedMultiValueMap` by exact name, the direct members of
   `org.springframework.validation` (`beanvalidation` is out), the application's own
   `de.greluc.krt.profit.basetool.frontend.model.*`, and `CONTAINER_WRITTEN_FINAL_SESSION_TYPES`. The
   builder is handed to `SecurityJacksonModules`, which adds Spring Security's exact types on top.
2. **Three modes**, `app.session.type-allow-list` / `APP_SESSION_TYPE_ALLOW_LIST`: `off` (the old
   validator byte for byte), `report` (**default** — everything is read as before, a class outside the
   list is counted on `basetool_session_type_refused_total{mode="report"}` and named once in a `WARN`)
   and `enforce` (such a class is refused, the attribute is dropped by `FaultTolerantSessionSerializer`
   and repaired on the same request, ADR-0157). The mode is parsed leniently and a typo falls back to
   `report`.
3. **The hook is one method.** `AllowListBuilder` subclasses Jackson's builder so that the `build()`
   `SecurityJacksonModules` calls itself produces `AllowListValidator`, which overrides only
   `validateSubType` — the step where everything the matchers did not allow ends up. The builder is
   the only object this configuration hands over, so it is the only place a different validator can
   come from.
4. **The E2E stack runs `enforce`**, so the whole Playwright suite — every login, refresh, flash
   redirect and live-sync handshake — reads real sessions under the strictest mode, while production
   ships `report`.
5. **`SessionTypeOutsideAllowList`** alerts on any increment within an hour, labelled by mode, with no
   `for`: under `report` Jackson caches the resolved deserializer, so the counter rises once per class
   and slot per frontend lifetime and a rate rule would never see it.

## Consequences

- **Merging changes no read.** `report` accepts exactly what `off` accepts; the only new effects are
  a counter, a `WARN` and a startup line naming the mode.
- **The switch to `enforce` is a one-line `.env` change and a frontend restart**, reversible the same
  way, and needs no migration: the validator governs reading, and what is written is identical in all
  three modes ([`deployment.md` → *Session type allow-list*](../deployment.md#session-type-allow-list-report-then-enforce)).
- **Under `enforce`, what a refusal costs is one attribute**, never the session — unless the refused
  value *is* the security context, which a class outside `org.springframework.security` cannot be.
  The parity test proves the security context, both tokens, the authorization request, the CSRF
  token, the saved request and the flash maps read identically.
- **A new session attribute of a new type is a list change.** A form or DTO under `frontend.model`
  needs none; anything else does, in the same PR, and `report` mode names it in production first.
- **A class the name matchers leave undecided is still loaded** before the class-level check refuses
  it — that is how Jackson's two-step validation works and how Spring Security's class matchers are
  consulted. Loading runs a static initializer, not a constructor or a setter; the gadget risk is the
  latter, and the negative test proves the setter never runs.
- Found in passing, and **not** this decision's business: a flash map carrying a form's
  `BeanPropertyBindingResult` is unreadable under every validator, because that class has no creator
  Jackson can use — the flash list is dropped on the redirect's GET. The parity test keeps it in its
  sample so whoever fixes it finds the list already covering it.

## Amendment 1 — 2026-09-23: final types in containers

The decision assumed a final type never carries a type id, so `java.lang`, `java.math` and
`java.net` were left off the list. That holds for a session attribute at the top level and not
inside a container: in an `Object`-typed slot of a map or list, Jackson writes a final type as
`["java.lang.Long", 1788…]`. A real login produces exactly that — the ID token's claims map holds
`iss` as `java.net.URL` (Spring's OIDC claim conversion), numeric claims as `java.lang.Long` and a
nested claim as Nimbus's `com.nimbusds.jose.shaded.gson.internal.LinkedTreeMap`; Spring Session's
session-created payload holds `Long` timestamps. Under `enforce` every member's security context
would have been unreadable.

Production was never exposed: it ships `report`, which would have named each class on the first
read — the mode did its job. The E2E stack runs `enforce` and was exposed from the merge of #2018.
The list gains the boxed scalars of `java.lang` (by exact name, never the package), `BigDecimal`,
`BigInteger`, `URL`, `URI` and the Nimbus map, and the parity test now builds its ID token with the
real decoder instead of a hand-written claims map, which is what hid the gap. `URL`'s `hashCode`
resolves its host; a writer able to plant that could forge a security context outright, so the DNS
query is accepted rather than refusing every ID token.

## Alternatives rejected

- **Enforce on merge.** One wrong entry would sign members out on the next deploy, and the only way
  to find the wrong entry would be production. `report` finds it without costing anything.
- **An allow-list by base type** (`allowIfBaseType(Serializable.class)` or similar). Almost every
  gadget class is `Serializable`; it narrows nothing.
- **Class-hierarchy matchers for our own entries** (`allowIfSubType(Map.class)`). They match only
  after loading, and they allow every map implementation on the classpath, known gadget maps
  included.
- **Dropping default typing for a typed session schema.** The right end state, and a rewrite of how
  Spring Session and Spring Security persist their own objects; out of proportion to the finding.
- **Leaving it, because APPSEC-04 closes the store.** Per-service Redis users narrow who can write a
  session hash; they do not make a write harmless. The two are layers, and this one costs nothing to
  run.

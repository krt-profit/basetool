# ADR-0114 — Bound the frontend's reactive Redis health check so it cannot hang the readiness probe

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** @greluc
- **Related:** `frontend/src/main/resources/application.yml` (`spring.data.redis.timeout` / `connect-timeout`) · `frontend/Dockerfile` (HEALTHCHECK) · REQ-OPS-003 (`docs/specs/deployment-delivery.md`) · ADR-0084 (readiness health-group composition) · ADR-0088 (Redis-backed two-tier session) · the 2026-07-20 edge / reactive-degradation incident

## Context

The frontend container `HEALTHCHECK` probes `/actuator/health/readiness` (`--timeout=5s --retries=3`),
and docker-compose gates `service_healthy` on it. The `readiness` group includes `redis` (ADR-0084):
a Redis outage means every request fails, because the Redis-backed Spring Session owns the auth
context, so `redis` DOWN legitimately means "this instance cannot serve."

Two classpath facts turn that indicator into a liability when Redis is *slow* rather than *down*:

- The frontend carries `spring-boot-starter-webflux` (for the reactor-netty `WebClient` to the
  backend), so `reactor.core.publisher.Flux` is present and Boot's `RedisReactiveHealthContributor`
  auto-configuration wins over the blocking one — the health indicator is
  `RedisReactiveHealthIndicator`, which runs a reactive `PING`.
- `spring.data.redis.timeout` was **unset**, so Lettuce's default **60 s** command timeout governed
  every command — including that health `PING`. Connection acquisition on a broken/reconnecting
  Lettuce channel can outlast even that.

On 2026-07-20, during the edge incident, the frontend's readiness probe was observed hanging for
**hundreds of seconds** on the reactive Redis `PING`. Because the health endpoint aggregates its
group synchronously, an indicator that never returns hangs the whole `/actuator/health/readiness`
response; every 5 s Docker probe then timed out, the container flipped `unhealthy` after three
misses, and the frontend degraded into the maintenance page — a failure indistinguishable, from the
outside, from a real outage, and one a container restart only *temporarily* cleared (the restart
reset the stale Lettuce channel).

This is the same failure shape ADR-0084 fixed for the *external* Keycloak probe (an unhealthy flip
that cycles the container), but reached through an **unbounded** local indicator rather than a
mis-scoped remote one. ADR-0084 removed Keycloak from the group; Redis must **stay** in the group
(its DOWN is a genuine local-serve signal), so the fix here is to bound it, not remove it.

## Decision

Set an explicit, short Lettuce timeout on the frontend so the reactive Redis `PING` — and every other
Redis command — fails fast instead of hanging:

```yaml
spring:
  data:
    redis:
      timeout: ${SPRING_DATA_REDIS_TIMEOUT:2s}          # command timeout (incl. the health PING)
      connect-timeout: ${SPRING_DATA_REDIS_CONNECT_TIMEOUT:2s}
```

`2 s` is more than a thousand times the sub-millisecond round-trip of the co-located Redis, so it
never trips on healthy traffic, yet it is comfortably inside the 5 s Docker probe window — so a
stalled Redis yields a **truthful, fast `DOWN`** within the probe timeout instead of an indefinite
hang. `redis` stays in the readiness group unchanged: a genuine Redis outage still fails readiness,
which is correct because the session store is load-bearing for every request. Both values are
env-overridable so an operator can retune during an incident without a rebuild.

The knob is set in the base `application.yml` (profile-independent, alongside the existing
`app.http.*` timeouts). It is inert in the `test` profile, which runs no Redis
(`spring.session.store-type: none`, `RedisSessionConfig` is `@Profile("!test")`, and the readiness
group there is narrowed to `readinessState, diskSpace`).

## Consequences

- A slow/stalled Redis can no longer hang the readiness probe: the health endpoint returns a fast
  `DOWN`, so the container's `unhealthy` state (if any) reflects a real, timely signal rather than a
  probe that never completed. The maintenance-page flap from an unbounded health `PING` is closed.
- The 2 s command timeout now also bounds **application** Redis operations (session reads/writes).
  This is intended: against a co-located Redis these complete in well under a millisecond, and a
  session op that genuinely cannot finish in 2 s is a failure the request should surface, not wait
  on. Operators who run Redis across a slower link can raise `SPRING_DATA_REDIS_TIMEOUT`.
- Complements, and does not change, ADR-0084: the readiness group composition is untouched
  (`readinessState, backend, redis, diskSpace, ssl`); only the Redis client's timeout posture
  changes. Redis remains a local-serve gate; Keycloak remains excluded.
- This addresses the readiness-hang strand of the 2026-07-20 reactive degradation. It is independent
  of the SSE relay fix (ADR-0113) and the Keycloak-backchannel connection-pool hardening tracked
  separately — those close different reactive failure modes on the same serving path.


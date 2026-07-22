# ADR-0118 — Bound the Redis health check at the health layer (wrapper indicator, not only the Lettuce timeout)

- **Status:** Accepted
- **Date:** 2026-07-22
- **Deciders:** @greluc
- **Related:** ADR-0114 (2 s Lettuce command/connect timeout) ·
  `frontend/.../health/BoundedRedisHealthIndicator.java` · the 2026-07-22 incident

## Context

ADR-0114 bounded the frontend's reactive Redis health `PING` with
`spring.data.redis.timeout=2s`, on the diagnosis that Lettuce's 60 s default command timeout
governed the hang. The 2026-07-22 incident **falsified the sufficiency of that bound on a live
build that already carried it** (v1.5.11): six health checks queued for 161–836 *seconds*, growing
linearly — the signature of checks serialising behind a wedged shared-connection
acquisition/reconnect, which is monitor-synchronised inside `LettuceConnectionFactory` and **not
covered by any Lettuce command timeout** (bytecode-verified: Boot 4.1 does enable
`TimeoutOptions`, so command *dispatch* is bounded at 2 s — the hang lived a layer below).
Readiness hung with it; the Docker `HEALTHCHECK` (5 s budget) failed silently for ~15 minutes.

## Decision

Bound the check **at the health layer**, where no Lettuce-internal layering can bypass it: a
`BoundedRedisHealthIndicator` (bean name `redisHealthIndicator`, so Boot's
`DataRedisReactiveHealthContributorAutoConfiguration` backs off and the contributor key stays
`redis`) wraps the stock `DataRedisReactiveHealthIndicator` with a hard `Mono.timeout` of **3 s**
falling back to a truthful `DOWN` (`error: health check timed out after 3000 ms`). 3 s sits above
the 2 s command timeout (a regular slow-command failure keeps its more specific detail) and below
the 5 s `HEALTHCHECK` budget (a wedged connection now surfaces as a deterministic `DOWN`, not an
infrastructure-level probe timeout).

ADR-0114's property stays: it remains the *first* bound (fast, detailed failures for the dispatch
layer) and it also governs Spring Session's sync path. This ADR adds the *last-resort* bound the
incident proved missing. `redis` stays in the readiness group (unchanged ADR-0114 position — a
Redis that cannot `PING` cannot serve sessions).

## Consequences

- A health check can never again exceed ~3 s wall-clock, whatever wedges inside Lettuce; queued
  checks cannot accumulate multi-minute durations, and the `HealthContributorHanging` log alert
  (added with this change) fires on any residual >10 s contributor line.
- The `.timeout` operator cancels the delegate subscription, so an abandoned check does not keep a
  slot in later checks' wall time.
- Unit-guarded by `BoundedRedisHealthIndicatorTest` (never-completing delegate → `DOWN` within the
  bound; healthy delegate passes through untouched).

## Alternatives considered

- **A `LettuceClientConfigurationBuilderCustomizer` enabling `TimeoutOptions`** — already the
  effective state: Boot 4.1's `LettuceConnectionConfiguration` enables `TimeoutOptions` when a
  command timeout is set (bytecode-verified), and the incident happened anyway. Redundant, and it
  cannot reach the acquisition layer.
- **Removing `redis` from the readiness group** — rejected in ADR-0114 already; its `DOWN` is a
  genuine local-serve signal (sessions live in Redis).
- **Backend counterpart** — the backend's prod-enabled Redis indicator has no Lettuce timeout at
  all, but sits outside its readiness group (ADR-0084) and only slows the visibility aggregate;
  covered by the new `HealthContributorHanging` alert and deferred until it misbehaves in practice.


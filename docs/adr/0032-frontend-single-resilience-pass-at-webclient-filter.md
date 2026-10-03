# ADR-0032 — Single frontend resilience pass at the WebClient filter

- **Status:** Accepted — amended 2026-10-02 (one filter chain under per-domain typed clients, see below)
- **Date:** 2026-06-21
- **Deciders:** @greluc
- **Related:** ADR-0019 (frontend reauth + single-flight refresh) · spec [`security-and-access.md`](../specs/security-and-access.md) (REQ-SEC-012) · `WebClientConfig` · `BackendApiClient` · PR #766

## Context

Every call the frontend made to the backend went through **two** Resilience4j passes:

1. A reactive **WebClient exchange filter** (`WebClientConfig#resilienceFilter`) bound to the
   `backendApi` instance, applying bulkhead → time limiter → retry (idempotent verbs only) → circuit
   breaker **inside** the reactive chain.
2. Method-level `@Retry` / `@CircuitBreaker` AOP annotations on `BackendApiClient`, bound to a
   **separate** `backend` instance, wrapping the blocking call **outside** the reactive chain.

Only the filter carries a `TimeLimiter` — the one operator that actually bounds a hung upstream
thread (the AOP layer had no `@TimeLimiter`, and the `backend` instance's `timelimiter` config was
never reachable without one). So the second pass added a parallel circuit-breaker window and a second
retry multiplier (up to 2×2 attempts on a GET) without contributing the guarantee that matters. The
class Javadoc already described the double-wrap as redundant.

There was also a correctness wart: a circuit-breaker-open on a **write** threw
`CallNotPermittedException` from the AOP proxy *outside* the reactive chain, so it escaped the
per-verb exception mapping in `executePost` / `executePut` / … and surfaced unmapped.

## Decision

We will make the **WebClient exchange filter the single resilience pass** for the backend hop.

- Remove the method-level `@Retry` / `@CircuitBreaker` annotations from `BackendApiClient` and delete
  the now-unused `backend` Resilience4j instance (circuitbreaker / timelimiter / retry / bulkhead)
  from `application.yml`.
- All resilience — bulkhead, time limiter, retry (idempotent verbs only), circuit breaker — lives in
  `resilienceFilter` on the `backendApi` instance, applied uniformly to every verb inside the
  reactive chain.

## Consequences

- **One window, one retry budget.** A GET is retried once per its configured budget, not multiplied;
  the breaker tracks a single failure window. Worst-case load amplification against a struggling
  backend drops.
- **Timeout coverage is unchanged and uniform** — the filter's `TimeLimiter` already covered
  POST/PUT/PATCH/DELETE the same way it covers GET, so removing the (timeout-less) AOP layer loses no
  protection.
- **Cleaner failures on writes.** Because the breaker now trips inside the reactive chain, a write
  hitting an open breaker is mapped to a clean `503` by `executePost` et al.
  (`CallNotPermittedException` → `BackendServiceException.CODE_SERVICE_UNAVAILABLE`) instead of
  escaping the AOP proxy unmapped. `WebClientResilienceTest` pins the filter-level behaviour (CB open
  short-circuits; time limiter fires on every verb); the `BackendApiClient` tests pin the 503/Bulkhead
  classification.
- **The `backend` Resilience4j instance is gone** — future tuning happens on `backendApi` only,
  removing the trap of editing the wrong (dead) instance.
- Orthogonal to the single-flight refresh of ADR-0019 / REQ-SEC-012: `ClientAuthorizationException`
  stays on the filter's `ignoreExceptions`, so reauth is still neither retried nor counted toward the
  breaker.

## Alternatives considered

- **Keep both layers.** Rejected: the AOP layer carries no `TimeLimiter`, so it adds a redundant
  breaker + retry multiplier without the one guarantee that matters, and it mis-handles a write
  breaker-open by throwing outside the mapped reactive chain.
- **Keep the AOP layer, drop the filter.** Rejected: the filter is the only pass that runs *inside*
  the reactive chain (its operators compose with the WebClient pipeline and its
  `CallNotPermittedException` is mapped), the only one with a `TimeLimiter`, and the one that already
  special-cases idempotent-only retry and `ignoreExceptions` for reauth.

## Amendment — 2026-10-02: one filter chain under per-domain typed clients

Domain modularisation plan §5.9, track step F3, guard G-17. The frontend gets one typed backend
client per domain — first a thin class over `BackendApiClient`, then, with the domain's REST cut, a
Spring HTTP interface created by `HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient))`
over the **same** `webClient` bean. The decision above holds unchanged for every one of them: the
resilience filter on that bean stays the single pass, together with the OAuth2 bearer relay and
the correlation, org-unit, locale and client-IP relays, and `BackendApiClient`'s error mapping
stays the one mapping. What changes is only that more than one class calls through the chain.

Rules (G-17): only the backend kernel builds or injects a `WebClient` (named exceptions: the SSE
relay and the live-sync probe); an `@ImportHttpServices` group is allowed only with a
highest-precedence configurer that supplies `webClient.mutate()`, because a default group builds a
fresh client without the filters; client interfaces take no `java.net.URI`, `UriBuilderFactory` or
`@CookieValue` parameter, carry no `@Cacheable` and use only relative `/api/` paths; and a filter
on the `webClient` bean refuses any request not addressed to the backend's own host, because the
OAuth2 filter attaches the member's token without a host check.

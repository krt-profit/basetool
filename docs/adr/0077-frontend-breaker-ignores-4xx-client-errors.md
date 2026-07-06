# ADR-0077 — Frontend circuit breaker + retry ignore 4xx client errors

- **Status:** Accepted
- **Date:** 2026-07-06
- **Deciders:** @greluc
- **Related:** ADR-0032 (single resilience pass at the WebClient filter — this refines its
  status-to-error mapping) · ADR-0019 / REQ-SEC-012 (reauth `ignoreExceptions`) · REQ-SEC-023
  (edge/per-IP rate limiting) · `WebClientConfig#resilienceFilter` · `BackendApiClient` · the ingest
  gateway breaker (`ingest` `application.yml`)

## Context

On 2026-07-06 a mission manager assembling a large operation exhausted the backend's **global
per-IP** rate-limit bucket (300/min at the time). The backend correctly answered `429 Too Many
Requests` for the mission page's background reads (`/users/me`, `/me/active-org-unit`,
`/me/capabilities`, `/notifications/unread-count`, `/missions/{id}`, …).

The frontend's single resilience pass (ADR-0032, `WebClientConfig#resilienceFilter`) mapped **both
4xx and 5xx** responses to errors *before* the retry and circuit-breaker operators, "so
Retry/CircuitBreaker can react properly". The effect on a 429:

1. **Retried** on idempotent GETs — piling more load onto an already-throttled backend and ignoring
   `Retry-After`.
2. **Recorded as a failure** in the shared `backendApi` circuit-breaker window.

The breaker tripped **OPEN** and then short-circuited *every* backend call with
`CallNotPermittedException` — turning a partial, per-IP throttle into a **total frontend outage**.
The logs show the cascade: 35× `Error loading mission details`, 159× `CircuitBreaker[backendApi]
call not permitted`, with the breaker flapping `OPEN → HALF_OPEN → CLOSED` repeatedly.

A 4xx is a **client-side** signal — a rate limit, a not-found, a conflict, a bad request — **not a
backend-health fault**. Recording it as one is the defect. The desktop-ingest gateway's breaker
already gets this right: "HTTP response errors are ignored … only transport failures open the
breaker."

## Decision

The frontend resilience filter surfaces **only 5xx server errors** (and transport failures, which
already arrive as errors) to the retry + circuit-breaker operators. **4xx client responses pass
through the filter untouched**; `retrieve()` maps them to a `WebClientResponseException` downstream,
where `BackendApiClient.handleWebClientException` renders the proper per-call user-facing result
(e.g. the localized "Ratenlimit überschritten. Bitte in N Sekunden erneut versuchen." for a 429).

## Consequences

- **No cascade.** A 429 — or any 4xx — is never retried and never counts toward the breaker's
  failure window, so a per-IP throttle can no longer escalate into an org-wide frontend blackout.
  Each throttled call degrades gracefully on its own.
- **The breaker still guards genuine ill-health.** Sustained 5xx and transport failures (timeout,
  connection reset) still open it, preserving the load-shedding purpose that matters.
- **The retry budget is spent where it helps.** Idempotent-GET retry now retries only 5xx/transport,
  not deterministic client errors (a 404/409 will not change on retry; a 429 must not be hammered).
- **Metric shift, not a threshold change.** Fewer `reason=circuit_open` backend-client-error events,
  more `reason=backend_4xx` — the correct classification. The aggregate
  `basetool_backend_client_errors_total` alert is reason-agnostic, so no monitoring rule changes.
- **Complements the rate-limit ceiling increase** shipped in the same change (REQ-SEC-023 tuning:
  global 300→1000/min, participant 30→500, the anonymous create endpoints 10/10/20→100/100/200):
  higher limits reduce how *often* 429s occur; this ADR ensures that when they do, they degrade
  per-call instead of cascading.
- `ClientAuthorizationException` stays on the filter's `ignoreExceptions` (ADR-0019 / REQ-SEC-012) —
  orthogonal and still neither retried nor counted.
- `WebClientResilienceTest` pins the new behaviour (a 429 is neither retried nor trips the breaker)
  alongside the existing 5xx-opens-breaker / time-limiter assertions.

## Alternatives considered

- **Add only `WebClientResponseException.TooManyRequests` to `ignoreExceptions`.** Rejected: it
  fixes only the 429 instance while leaving every other 4xx (a 409-conflict storm during heavy
  concurrent editing, a 404 burst) free to trip the shared breaker, and it is fragile — the reactive
  `WebClientResponseException` has dedicated status subclasses only for well-known codes.
- **Ignore all HTTP status errors (4xx *and* 5xx), like the ingest gateway.** Rejected for the
  frontend: a sustained 5xx genuinely signals backend ill-health, where shedding load via the
  breaker is protective; keeping 5xx as a breaker signal preserves that guard. The ingest gateway
  serves a single trusted extractor, so it can afford the stricter transport-only rule.


# ADR-0113 — Frontend notification SSE relay self-commits its response on the request thread

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** @greluc
- **Related:** spec [REQ-NOTIF-010](../specs/notifications.md) · [ADR-0016](0016-notification-transport-polling-sse.md) (polling baseline + SSE push) · `NotificationPageController#stream` · spring-ai #6169 · the 2026-07-20 100%-dead-SSE incident

## Context

The frontend relays the backend notification SSE (`GET /api/v1/notifications/stream`) to the browser
(REQ-NOTIF-010): `NotificationPageController.stream()` returns a Spring MVC `SseEmitter` and, from a
reactor-netty `sseWebClient` subscribe callback, `forward()`s each backend event via `emitter.send()`.
It sent **nothing of its own** — its first write was the first forwarded backend event, so its HTTP
response committed (status line + headers) only when that arrived.

On 2026-07-20 the relay was **100% broken** in prod: every `/notifications/stream` header-timed-out at
the edge (`upstream timed out while reading response header`), **0** successful streams. The browser
fell back to the unread-count poll (REQ-NOTIF-006), so the user impact was a dead live-push, not an
outage — which is exactly why it went unnoticed. The backend was healthy: it received every relay
request (`200 in 0 ms`) and its `subscribe()` sent a `connected` event immediately. Only the
**frontend** never sent its response headers.

Root cause: on **Spring Boot 4.1 / Spring Framework 7 / embedded Tomcat 11.0.24 / Java 25 with
virtual-thread-per-request**, an async `SseEmitter` response whose **first write arrives on a
non-container thread** is not committed — the write/flush completes with no exception but the status
line + headers never reach the socket (open issue spring-ai #6169; identical symptom, and moving that
transport to WebFlux fixes it on the same versions). The relay's first write comes from a
reactor-netty event-loop thread (`forward()`), so it hit the defect. The **backend** SSE endpoint is
unaffected because its first write — `NotificationStreamService.subscribe()`'s `connected` — is a
pre-initialize send that Spring replays on the **request** thread.

## Decision

We will have `NotificationPageController.stream()` **commit its own response on the request thread**:
immediately after resolving the bearer, and before wiring the reactor relay, it sends an initial SSE
**comment** (`SseEmitter.event().comment("ready")`). Spring MVC replays a pre-initialize send on the
request (dispatch) thread when it initializes the emitter, so the response commits there — on a
container thread — sidestepping the Tomcat 11 non-container-thread commit defect. A comment (not a
named event) is invisible to `EventSource`, so it only flushes the headers; the forwarded backend
events (including the backend's own `connected`) follow normally. This mirrors the backend's
already-working request-thread-first-write pattern. Emitter creation is behind a `newEmitter()` seam
so a unit test can assert the initial commit on a mock emitter.

## Consequences

- Live notification push works again: the response commits in ~0 ms instead of never, so the edge
  stops header-timing-out and the reconnect storm ends.
- The initial comment **looks** redundant next to the forwarded backend `connected` event — it is
  not: removing it re-breaks SSE 100%. The load-bearing reason is the **thread of the first write**,
  not the event content. Documented at the call site and here so it is not "cleaned up".
- Purely additive and best-effort: if the browser is already gone when we try to commit, the initial
  send fails soft (complete + return, no relay), consistent with the existing REQ-NOTIF-010 fail-soft
  contract.
- Revisit if spring-ai #6169 is fixed upstream or the transport moves to WebFlux; the workaround
  stays correct regardless, so removal is optional, not required.

## Alternatives considered

- **Drop to raw `HttpServletResponse.getOutputStream()` + `flushBuffer()`** (the #6169 workaround) —
  rejected: it bypasses the `SseEmitter` lifecycle (30-min timeout, clean completion, the relay
  connection gauge, the clean best-effort error handling) for a heavier low-level rewrite; the
  initial request-thread commit fixes the same root cause with a two-line, lifecycle-preserving
  change.
- **Disable virtual threads for this endpoint / globally** — rejected: broad blast radius for a
  targeted SSE issue, and #6169 reproduces on the servlet SSE write path; the fix is the writer
  thread of the first commit, not virtual threads per se.
- **Switch the frontend relay to WebFlux** — rejected as disproportionate: it would fork the
  frontend's servlet stack for one endpoint. Kept as the long-term option if the workaround ever
  stops holding.


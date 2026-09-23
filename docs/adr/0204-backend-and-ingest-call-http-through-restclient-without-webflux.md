# ADR-0204 — The backend and ingest call HTTP through `RestClient` on the JDK client; WebFlux is removed

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc ("WebFlux raus aus Backend und Ingest", with a new ADR — decided with the
  improvement audit's open questions on 2026-09-22)
- **Related:** improvement audit 2026-09 findings BE-MOD-01, BE-MOD-02, ING-MOD-01, ING-PERF-01 ·
  [`docs/specs/observability.md`](../specs/observability.md) (`REQ-OBS-001`, `REQ-OBS-009`,
  `REQ-OBS-011`) · [`docs/specs/desktop-ingest.md`](../specs/desktop-ingest.md) (`REQ-INGEST-001`) ·
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) ·
  [ADR-0161](0161-rest-json-over-http-stays-the-wire-format.md) (frontend hop, unaffected)

## Context

Three of the four modules made outbound HTTP calls through Spring's reactive `WebClient` on Reactor
Netty, and two of them never used it reactively:

- **Backend.** `UexClient` and `ScWikiClient` built a `Mono` per catalogue fetch and collapsed it with
  `blockOptional()` on a scheduler thread. `KeycloakService` already used `RestClient` — but built a
  new one per Admin API call from `RestClient.builder()`, with no observation registry, so none of
  those calls appeared in `http_client_requests_seconds` or in a trace (BE-MOD-02).
- **Ingest.** The relay to the backend and the gateway's own client-credentials grant each ended in
  `block()` on the servlet request thread, behind a Resilience4j breaker applied through
  `resilience4j-reactor`. `micrometer-context-propagation` was declared "so the correlation id
  survives the hop onto the WebClient worker thread", yet no code used it: the correlation id was
  read from the MDC on the request thread before the call was assembled.

The price of that stack was a second HTTP client — `spring-webflux`, Reactor Netty and, with it,
Netty's HTTP/2, HTTP/3, QUIC, SOCKS-proxy and native-epoll artefacts — on the runtime classpath of
the internet-facing gateway and of the backend, plus a pool with no idle eviction in the gateway
(ING-PERF-01). The frontend is different: it streams SSE, relays on a worker thread with context
propagation, and its WebClient is load-bearing (ADR-0032, ADR-0161). It is not part of this
decision.

## Decision

1. **The backend and the ingest gateway make every outbound HTTP call through `RestClient` on the
   JDK `java.net.http.HttpClient`** (Spring's `JdkClientHttpRequestFactory`), and neither module
   depends on `spring-boot-starter-webflux` any more. The ingest also drops `resilience4j-reactor`
   and its explicit `micrometer-context-propagation` declaration.
2. **Timeouts stay what they were.** Backend: 5 s connect, 30 s read (the old per-call timeout).
   Ingest relay: 5 s connect, 15 s read. Ingest token grant: 5 s connect, read bounded by the smaller
   of 10 s and `app.ingest.service-account.timeout-millis` — what the old netty timeouts and
   `block(timeout)` amounted to together. The JDK client has no separate write timeout; its request
   timeout runs from the send, so it covers the upload as well.
3. **Both JDK clients are pinned to HTTP/1.1.** The JDK defaults to HTTP/2: over TLS it would
   negotiate h2 with the backend through ALPN, over plain HTTP it would offer `Upgrade: h2c` on every
   request. Reactor Netty did neither, so the wire behaviour stays identical.
4. **Observation is wired by hand, as before.** Boot 4 auto-configures a `RestClient.Builder` only
   in its separate `spring-boot-restclient` module, which neither application ships. Each module
   therefore builds its clients from `RestClient.builder()` with the `ObservationRegistry` set,
   exactly as the `WebClient.Builder`s were. The observation is Spring's `http.client.requests` with
   the same default key values (`method`, `uri`, `status`, `outcome`, `exception`, `client.name`), so
   the Prometheus series `http_client_requests_seconds` and every dashboard and alert on it are
   unchanged; `ObservationPrivacyFilter` sits on the registry and keeps scrubbing. In the backend the
   builder is a prototype-scoped bean over one shared JDK client (`RestClientConfig`), and
   `KeycloakService` builds its admin client from it once, at construction.
5. **Response bodies stay capped.** `RestClient` streams a body into the converter and has no
   `maxInMemorySize`. A `ResponseSizeLimitInterceptor` restores the cap — 16 MiB for UEX and SC Wiki,
   `app.ingest.max-payload-bytes` for the relay — and fails the read instead of truncating, so an
   oversized catalogue page still lands in the counted fallback (`basetool_external_fetch_errors_total`)
   and an oversized relay answer is a failed relay.
6. **The ingest's TLS guarantees stay exactly as they were** (M-13; changing them is ING-SEC-04).
   `dev`/`test` trust everything without a hostname check; elsewhere `backend-trust` is the relay's
   only anchor, without a hostname check; with no bundle the relay uses the JVM trust store with the
   hostname verified; the token client trusts the JVM anchors plus `keycloak-trust` and always
   verifies the hostname. The JDK client cannot switch hostname verification off per client through
   its API, so the relay installs an `X509ExtendedTrustManager` that validates the chain and ignores
   the TLS engine — JSSE does endpoint identification inside the trust manager, so the check goes
   away for that client only. The token client's additive trust manager is a plain
   `X509TrustManager`, which JSSE wraps in one that verifies the name.
7. **The breaker is the same breaker.** `CircuitBreaker.executeSupplier` replaces the reactive
   operator; the instance is still `backend`, so `resilience4j_circuitbreaker_*` series and their
   alerts are unchanged. Its `ignore-exceptions` entry moves from `WebClientResponseException` to
   `RestClientResponseException`.

## Consequences

- **Smaller runtime classpath.** 21 components leave the backend SBOM and 22 the ingest SBOM:
  `spring-webflux`, `spring-boot-starter-webflux`, `spring-boot-webflux`, `spring-boot-reactor`,
  `spring-boot-reactor-netty`, `spring-boot-starter-reactor-netty`, `spring-boot-http-codec`,
  `reactor-netty-core`, `reactor-netty-http`, and the Netty HTTP, HTTP/2, HTTP/3, QUIC (with its
  native library), SOCKS, proxy, compression, macOS-DNS and native-epoll artefacts — plus
  `resilience4j-reactor` in the ingest. `reactor-core` and the core Netty transport stay, because
  Lettuce (Redis) needs them.
- **ING-PERF-01 is moot.** It asked for a named Netty connection provider with idle eviction. The JDK
  client closes idle HTTP/1.1 connections after `jdk.httpclient.keepalive.timeout`, 30 s by default,
  and the backend's embedded Tomcat keeps an idle HTTP/1.1 connection for 60 s (its default; the
  backend sets no `keep-alive-timeout`). The client therefore always drops a connection well before
  the server can, so a stale pooled connection cannot be reused and no eviction setting is needed.
  `RelayIdleConnectionBoundTest` reads Tomcat's default off the classpath and fails if the margin
  falls below a factor of two — the same rule the frontend's `WebClientBackendPoolIdleBoundTest`
  applies to its HTTP/2 pool.
- **Keycloak Admin API calls are now observed.** They appear in `http_client_requests_seconds` for
  `basetool-backend` and, with tracing on, as client spans. The dashboard panels that aggregate the
  backend's client calls (p95, fan-out ratio) now include them; `FrontendBackendFanoutHigh` is
  frontend-scoped and unaffected. In production the admin client keeps its truststore-pinned JDK
  factory and, as before, no read timeout of its own.
- **A relay whose body cannot be read is a 502.** `RestClient` raises a plain `RestClientException`
  when a response body fails mid-read (torn connection, payload cap); the gateway's exception handler
  maps it with the transport failures to `502 BACKEND_RELAY_FAILED`. On the reactive path the
  equivalent of a body past the cap (`DataBufferLimitException`) had no handler and fell through to
  the generic `500`.
- **What stays reactive:** the frontend's WebClient, its `ReactorContextPropagationConfig` and its
  pools. No ADR prescribed WebClient for the backend or the ingest, so none is superseded; ADR-0161's
  HTTP/1.1 observation is about the frontend's hop and still stands.

## Alternatives considered

- **Keep WebFlux and fix the pool (ING-PERF-01 as written).** Rejected by the owner's decision: it
  keeps a whole HTTP stack on the internet-facing module for calls that block anyway.
- **Add Boot's `spring-boot-starter-restclient` and use its auto-configured builder.** It would bring
  `spring.http.clients.*` properties and the observation customizer for free, but also a new module
  and a second place where client behaviour is configured, while both modules need hand-built
  clients anyway (the ingest's per-client trust, the Keycloak truststore). Wiring the registry by
  hand is one line and is what the WebClient builders already did.
- **Apache HttpClient 5 or Jetty as the request factory.** Either would add a dependency the JDK
  client makes unnecessary; neither is on the classpath today.

# ADR-0115 — Harden the OAuth2 token-endpoint connection pool against the Keycloak-hairpin PrematureClose

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** @greluc
- **Related:** `frontend/.../config/WebClientConfig.java` (`oauthTokenPool` + the two token-response clients) · `frontend/.../config/SecurityConfig.java` (`oauth2Login().tokenEndpoint()`) · REQ-SEC-012 (refresh-token rotation / reuse detection) · ADR-0019 (single-flight authorized-client manager) · ADR-0078 (WebClient pool scale-hardening) · the 2026-07-20 reactive-degradation incident

## Context

The frontend is an OAuth2 *client*: it exchanges the `authorization_code` for tokens at login and
runs a `refresh_token` grant whenever an access token expires. Both call Keycloak's token endpoint,
whose URL is derived from the public `issuer-uri` (`https://keycloak.profit-base.online/...`), so the
call **hairpins out through the public NPM edge** rather than staying inside the Docker network.

On Spring Security 7 the default token-response clients are
`RestClientAuthorizationCodeTokenResponseClient` and `RestClientRefreshTokenTokenResponseClient`,
each backed by a `RestClient`. On this classpath (reactor-netty present, no Apache HttpComponents /
Jetty client) `RestClient` auto-selects `ReactorClientHttpRequestFactory` with its no-arg transport —
reactor-netty's **global** `HttpClient` connection pool, which has **no idle eviction** (no
`maxIdleTime` / `maxLifeTime` / `evictInBackground`). Verified from the shipped bytecode.

The app's own backend traffic does **not** use that pool: `WebClientConfig.connector(...)` builds
named, idle-evicting pools (`frontend-pool` / `frontend-sse-pool`, ADR-0078). The OAuth backchannel
is the one hot path left on the un-evicting global pool. NPM reaps idle upstream keep-alive sockets
after ~60–75 s; a refresh grant that reuses a socket the edge has already closed fails with
reactor-netty's `PrematureCloseException`, surfacing as an intermittent auth-path 5xx / forced
re-login. The `refresh_token` grant is the exposed path — every active session refreshes on
access-token expiry — so this recurred in normal use and was one strand of the 2026-07-20 reactive
degradation.

## Decision

Give both token-response clients a **dedicated reactor-netty connection pool** (`frontend-oauth-pool`)
whose idle eviction sits **below** the upstream keep-alive, so a stale socket is discarded before it
can be handed to a token call:

- `maxIdleTime` 20 s (< the ~60–75 s edge keep-alive), `maxLifeTime` 60 s, `evictInBackground` 10 s,
  `pendingAcquireTimeout` 5 s, `maxConnections` 20, `metrics(true)`.
- **One shared** `ConnectionProvider` for both clients, so pool metrics stay a single
  `reactor.netty.connection.provider.*` series tagged `frontend-oauth-pool`, mirroring the
  `frontend-pool` / `frontend-sse-pool` request pools.

The transport is swapped by replacing the token client's whole `RestClient` (via `setRestClient`) —
the only seam Spring Security exposes — while **faithfully replicating its default HTTP stack**: a
`FormHttpMessageConverter` for the `application/x-www-form-urlencoded` grant request, an
`OAuth2AccessTokenResponseHttpMessageConverter` for the token JSON, and an
`OAuth2ErrorResponseErrorHandler`; only the request factory changes, to a
`ReactorClientHttpRequestFactory` bound to `frontend-oauth-pool`. The token client's own
parameters/headers converters are untouched. No explicit `secure(...)` call: reactor-netty
negotiates TLS per request from the URL scheme, so the `https` Keycloak endpoint uses the default
system trust store (its edge cert is publicly trusted).

The refresh-token client is wired into `authorizedClientManager()`'s `refreshToken` provider; the
authorization-code client is wired into `SecurityConfig`'s `oauth2Login().tokenEndpoint()`. So **both**
the recurring refresh and the interactive login exchange leave the global pool.

**This is a transport-only change, neutral to REQ-SEC-012.** No retry is added (a retry would replay
the refresh token and trip Keycloak's reuse detection into revoking the whole token family). The
single-flight manager (ADR-0019) and the no-request-scope context mapper are unchanged; refresh-token
rotation semantics are identical — only the socket the request travels on changes.

## Consequences

- The refresh / login backchannel no longer reuses upstream-reaped keep-alive sockets, so the
  `PrematureClose` auth-path failures close. Pool health is observable and alertable via
  `reactor.netty.connection.provider.*{name="frontend-oauth-pool"}`.
- One extra small pool (≤ 20 connections, mostly idle). Negligible resource cost.
- Independent of ADR-0113 (SSE relay commit) and ADR-0114 (reactive Redis health timeout): three
  distinct reactive failure modes on the same serving path, all surfaced by the 2026-07-20 incident.
- A regression that clears the token client's converter list without re-adding both converters can no
  longer ship silently: `WebClientConfigOauthTokenPoolTest` round-trips a real `refresh_token` grant
  through the hardened client against a `MockWebServer`.

## Alternatives considered

- **JVM property `-Dreactor.netty.pool.maxIdleTime=20000` on the frontend container.** Evicts idle
  connections in the reactor-netty **global** pool — which is exactly the OAuth backchannel, since
  every other reactor-netty client here uses a named pool. Kept as the documented **zero-rebuild
  mitigation / rollback**, but rejected as the primary fix: it is action-at-a-distance (invisible in
  the code that owns the token clients), yields no pool-specific metrics, and silently relies on
  "nothing else uses the global pool" — fragile to any future reactor-netty client added without a
  named pool.
- **Split-horizon internal Keycloak backchannel** (token / JWK / userinfo to an internal
  `keycloak:port`, bypassing the edge). Reduces but does **not** eliminate the `PrematureClose`: an
  internal idle socket reaped by Keycloak's own keep-alive would still `PrematureClose` without
  eviction. It also introduces a second issuer/host topology with its own failure modes. Deferred;
  the pool fix is the direct cause fix and composes with a split-horizon later if wanted.
- **Retry on `PrematureClose`.** Rejected: replaying a `refresh_token` grant risks Keycloak's
  reuse-detection revoking the whole token family (REQ-SEC-012), converting a transient socket error
  into a forced-logout storm. Eviction prevents the stale reuse in the first place; it does not retry.
- **Apache HttpComponents / JDK `HttpClient` request factory for the token client.** Would move the
  token calls onto a separate HTTP stack with its own pool, but adds a second client dependency and
  diverges from the app's reactor-netty-everywhere transport. The reactor-netty pool with eviction is
  the smaller, consistent change.


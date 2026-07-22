# ADR-0117 — Bound every phase of the OAuth2 token exchange (write/read handlers on the token client)

- **Status:** Accepted
- **Date:** 2026-07-22
- **Deciders:** @greluc
- **Related:** ADR-0115 (dedicated `frontend-oauth-pool`) · `frontend/.../config/WebClientConfig.java`
  (`oauthTokenRestClient()`) · REQ-SEC-012 (refresh-token rotation / reuse detection) · the
  2026-07-22 Keycloak-backchannel incident

## Context

ADR-0115 moved both token-response clients onto the dedicated, idle-evicting
`frontend-oauth-pool` and closed the stale-keep-alive `PrematureClose` failure mode. The pool's
`HttpClient` carried a connect timeout and a `responseTimeout` — which looked like a fully bounded
exchange, but is not: **reactor-netty's `responseTimeout` only arms once the request has been fully
written.** A token `POST` whose body write stalls (the peer stops reading mid-request) is invisible
to it, and no other client-side bound existed for that phase.

On 2026-07-22 (14:54–15:34 UTC) a transient L4 fault on the public Keycloak hairpin produced exactly
that shape: every token grant opened a **fresh** connection (the ADR-0115 eviction worked — zero
pool reuse in the logs), completed the TLS handshake, then stalled while sending the request body.
Client-side the exchange hung unbounded; the edge reaped each attempt after ~60 s
(`PrematureCloseException: ... while sending request body`), the caller retried, and the retry train
locked onto a :58-per-minute kill cadence for 41 minutes. Each kill was one genuinely lost refresh
grant (not retried by design — REQ-SEC-012), and the login `authorization_code` exchange shares the
same transport. Zero alerts fired.

## Decision

Mirror the backend WebClient transport's established idiom onto `oauthTokenRestClient()`: a
`ReadTimeoutHandler` + `WriteTimeoutHandler` pair (`app.http.read-timeout` / `write-timeout`, 3 s
each) via `doOnConnected`, alongside the existing connect timeout and `responseTimeout`. Any read
silence or unfinished write beyond the bound now fails the exchange in seconds; the failure
surfaces through the unchanged `ClientAuthorizationException` → silent SSO re-login path instead of
hanging a virtual thread until the edge's reaper acts.

Still **no retry** (unchanged ADR-0115 rejection: replaying a refresh grant trips Keycloak's
reuse detection) and no converter/semantics change — this is transport-only, REQ-SEC-012-neutral.

## Consequences

- A stalled token exchange fails in ≤ ~3 s instead of ~60 s: the user-facing request behind it gets
  its re-login redirect promptly, and the self-sustaining kill-retry cadence of the incident cannot
  form.
- A genuinely slow (> 3 s) Keycloak token response now fails the grant. Accepted: the token endpoint
  answers in tens of milliseconds normally; 3 s is far outside healthy behaviour, and the same
  read-timeout bound has governed every backend call since ADR-0078 without incident.
- Regression-guarded by `WebClientConfigOauthTokenPoolTest.stalledTokenEndpointFailsWithinTheClientSideBound`
  (stalled endpoint + 500 ms read timeout + deliberately long 30 s `responseTimeout`: only the
  handler pair can cut the exchange short).

## Alternatives considered

- **`ReactorClientHttpRequestFactory#setExchangeTimeout` as a single umbrella bound** — coarser and
  redundant next to the per-phase handlers, and it diverges from the transport idiom the backend
  WebClient already uses; rejected for consistency.
- **Retry on transport failure** — still rejected (REQ-SEC-012, refresh-token replay).


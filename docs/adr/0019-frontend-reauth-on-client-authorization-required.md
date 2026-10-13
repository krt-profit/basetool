# ADR-0019 — Frontend recovery from `client_authorization_required` + single-flight token refresh

- **Status:** Accepted
- **Date:** 2026-06-17
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-012 · ADR-0001 (frontend OAuth2 client) · runbook `docs/archive/INGEST_KEYCLOAK_SETUP.md` (step 4)

## Context

The frontend is an OAuth2 login client for the `keycloak` registration and relays the user's
bearer token to the backend via WebClient. It deliberately does **not** request `offline_access`
(audit finding L-4), so it holds only an online refresh token bound to the Keycloak SSO session,
and the access-token lifespan is 5 minutes.

As part of the desktop-ingest rollout, Keycloak realm-wide refresh-token rotation with reuse
detection was enabled (`Revoke Refresh Token = on`, `Refresh Token Max Reuse = 0`; see
`INGEST_KEYCLOAK_SETUP.md` step 4). A logged-in page fans out several backend-bound requests that
share one Spring session but run on separate servlet threads — the page render, the notification
SSE relay (`/notifications/stream`) and the periodic unread-count poll. When the 5-minute access
token expires, two or more of those requests independently ask
`DefaultOAuth2AuthorizedClientManager` to refresh and each replays the **same** stored refresh
token. The first refresh rotates it; the racing second replays the now-consumed token; reuse
detection revokes the whole token family. From then on every call throws
`ClientAuthorizationException [client_authorization_required]` until the user logs in again.

Two failures compound the user impact:

1. The exception is a `RuntimeException`, not an `AuthenticationException`, so it never reaches
   `SsoReAuthenticationEntryPoint` — the silent-reauth entry point does not fire. Pages render
   empty (or a 500) with no redirect.
2. `BackendApiClient` mapped the unrecognised exception through its catch-all, logging a full
   stack trace per failed call (a log flood) and surfacing a generic 500.

## Decision

We will make the frontend **recover gracefully** from `client_authorization_required` and
**prevent the in-session refresh race** that causes it, without re-adding `offline_access`:

1. **Single-flight refresh.** Wrap the `OAuth2AuthorizedClientManager` in a
   `SingleFlightAuthorizedClientManager` that serialises refreshes per session (striped lock,
   keyed by session id) and serves a short-lived freshness cache, so the parallel fan-out issues
   exactly **one** refresh-token grant per expiry window.
2. **Typed re-auth signal.** `BackendApiClient` detects a `ClientAuthorizationException` in the
   cause chain and rethrows a `ReauthenticationRequiredException` (logged at DEBUG, no stack trace)
   instead of a generic 500.
3. **Central recovery.** `GlobalExceptionHandler` maps that exception to an interactive re-login:
   a `302` to `/oauth2/authorization/keycloak` for HTML navigations, or a `401` carrying the
   `X-Reauthenticate` header for AJAX callers so the shared `krtFetch`/`krtReauth` client redirects
   the window. The notification SSE relay pushes a `reauth` event for the same purpose.
4. **Resilience tuning.** `ClientAuthorizationException` is added to the Resilience4j
   `ignoreExceptions` so it is neither retried nor counted toward the circuit breaker (it is
   per-session auth state, not backend health).

The operator-side mitigation — setting `Refresh Token Max Reuse` to a small value > 0 — remains the
recommended robust fix for multi-instance deployments and is documented in the runbook; the code
changes here make a single frontend instance correct on their own and degrade safely to that
operator setting when scaled horizontally.

## Consequences

- The `client_authorization_required` flood stops: at most one refresh per session per expiry
  window, no retried auth failures, no stack-trace spam, and a self-healing redirect to a fresh
  (typically silent) Keycloak login.
- The single-flight cache holds tokens in JVM memory (already present in Redis) for the access-token
  lifetime; it is bounded and session-keyed. Cache-hit requests rely on Spring Session delta-saves
  not rewriting the authorized-client attribute, so they cannot clobber the refreshed token in
  Redis.
- Single-flight is JVM-local: with the frontend scaled to multiple instances, a cross-instance race
  is still possible and is covered by `Refresh Token Max Reuse > 0` on Keycloak.
- A client-side `sessionStorage` guard rate-limits re-auth redirects to avoid a loop if a freshly
  minted session were revoked again immediately.

## Alternatives considered

- **Re-add `offline_access`** — rejected: audit finding L-4 removed it deliberately; a long-lived
  offline token broadens the blast radius of a leaked session and does not address the rotation
  race.
- **Disable refresh-token rotation entirely** — rejected as the *code* fix: rotation/reuse
  detection is a genuine security control (valuable for the persisted desktop-extractor token). The
  runbook keeps turning it off / raising max-reuse as a reversible operator lever, but the frontend
  should not depend on it.
- **Naive per-session lock with no cache** — rejected: a lock alone does not help, because the
  second request re-reads its own request-scoped (stale) session snapshot and refreshes again. The
  freshness cache is what actually collapses the refreshes.
- **Treat it as an `AuthenticationException` to reuse `SsoReAuthenticationEntryPoint`** — rejected:
  it would require wrapping/translating inside the security filter chain for an error that is raised
  deep in the WebClient exchange on a Reactor worker thread; handling it at the MVC boundary is
  simpler and also covers the AJAX/SSE transports the entry point does not.

## Amendment — 2026-06-18: single-instance correctness was incomplete

A production deployment (single frontend instance, Keycloak `Refresh Token Max Reuse = 5`,
`ssoSessionIdleTimeout` = 30 days, `ssoSessionMaxLifespan` = 180 days) still forced an
**interactive** re-login every ~30–60 minutes — i.e. Keycloak was revoking the whole SSO session,
which at those settings can only be refresh-token **reuse detection** firing (no timeout fits that
cadence, and a small in-session race stays under `Max Reuse = 5`). Two gaps in the original fix were
responsible; neither is the multi-instance race this ADR anticipated:

1. **The single-flight key was not stable per session.** `cacheKey()` keyed by session id only when
   the servlet request was attached to the `OAuth2AuthorizeRequest`, otherwise by principal name,
   otherwise `null`. A caller that authorized without the attribute and a sibling that had it used
   different stripe locks / cache buckets for the *same* session, so their refreshes were not
   serialised and each issued a grant off the same stored token. **Fix:** recover the session id
   from `RequestContextHolder` when the attribute is missing, so the precise session key is used
   consistently and the principal fallback is reserved for genuinely request-less calls.

2. **The notification SSE relay drove token refresh on a long-lived request.** `/notifications/stream`
   is an async `SseEmitter` capped at 30 minutes; it used the default-authorized-client filter, so
   its `authorize()` could refresh and write the rotated client into the session. A long-lived
   request that loaded the session early and persisted it late can write a now-many-rotations-stale
   refresh token back to Redis, clobbering the current one — the next refresh then replays a dead
   token and reuse detection revokes the session. The 30-minute stream timeout lines this up with
   the observed re-login cadence. **Fix:** the relay resolves the access token **read-only** on the
   servlet thread (`OAuth2AuthorizedClientRepository.loadAuthorizedClient`) and attaches it
   explicitly to the upstream call, so it never refreshes or rewrites the session; it fails soft when
   no token is bound, and the always-on 60 s unread-count poll keeps the token fresh and drives any
   re-authentication.

The original claim that single-flight "makes a single frontend instance correct on its own" holds
only with these two corrections. `Refresh Token Max Reuse > 0` remains the operator-side backstop;
it is not a substitute for them. A sanitized snapshot of the prod realm's token settings now lives
at `docs/keycloak/realm-config.reference.json` (see `docs/keycloak/README.md`).

## Amendment — 2026-06-18 (follow-up): the read-only load did not make the relay refresh-free

The first amendment's fix #2 claimed the relay's **read-only** `loadAuthorizedClient` + explicit
attach stops it from refreshing. That is **not true at the resolved Spring Security version** (7.1.0,
via Boot 4.1.0; the mechanism is identical in 7.0.x). Attaching an authorized client via
`oauth2AuthorizedClient(...)` routes the upstream call into
`ServletOAuth2AuthorizedClientExchangeFilterFunction.reauthorizeClient`, which calls
`OAuth2AuthorizedClientManager.authorize(...)` **unconditionally**. Its only short-circuit
(`servletRequest`/`servletResponse == null`) never fires, because `oauth2Configuration()` →
`populateDefaultRequestResponse` bakes both in from `RequestContextHolder` on the servlet thread at
build time. So `RefreshTokenOAuth2AuthorizedClientProvider` still refreshes whenever the attached
token is within its 60s clock skew of expiry, and `DefaultOAuth2AuthorizedClientManager` writes the
rotated client back to the Redis session. The read-only load only stopped the relay from *selecting
and persisting a self-chosen* stale client; the **only** thing that suppressed an actual refresh was
the JVM-local single-flight freshness cache being warm at reconnect.

A production incident confirmed this: the homepage briefly showed "Fehler beim Laden der Einsätze"
(and the periodic forced re-login recurred) when an SSE reconnect on the 5-minute access-token
boundary found a stale/empty cache, drove a refresh against the snapshot it had captured read-only at
stream-open (the deferred `authorize()` runs later on `boundedElastic`), and replayed a refresh token
already rotated past — Keycloak logged `REFRESH_TOKEN_ERROR reason="Stale token"`, reuse detection
revoked the client session, and subsequent reconnects logged `Session doesn't have required client`
until the 60s poll bounced the user through a (usually silent) re-login. The single-flight key split
this amendment's fix #1 addressed was **ruled out** for this incident: the relay carries the servlet
request in the `OAuth2AuthorizeRequest` attribute, so `cacheKey()` derives the session key directly
and the relay + poll share one stripe.

**Fix:** make the relay **structurally** refresh-incapable instead of relying on a warm cache. The
`sseWebClient` bean is built **without** the `oauth2Configuration()` exchange filter, and
`NotificationPageController.stream` sets the read-only bearer as a plain `Authorization` header. With
no OAuth2 filter on that client the upstream call can never reach `reauthorizeClient` → `authorize`,
so the relay cannot refresh or write back under any cache state. The snapshot token is relayed
verbatim even when expired (the backend rejects it, the stream fails soft, and the always-on poll
drives re-auth); the relay still fails soft when no token is bound. Additionally,
`SingleFlightAuthorizedClientManager.EXPIRY_SKEW` is raised from 30s to **60s** to match the refresh
provider's clock skew, removing a 30–60s asymmetry window in which the cache and the provider
disagreed on whether a refresh was due. A new `NotificationPageControllerStreamTest` case pins the
exact production condition — a present-but-**expired** client relays its bearer verbatim and issues
**zero** refresh grants.

Note: the runbook prescribes `Refresh Token Max Reuse = 0`, but the prod realm runs `5` (confirmed by
an Admin Console read on 2026-06-18); it is an operator backstop and does not change this code defect.
The "never issues a refresh-token grant" guarantee in REQ-SEC-012 is now delivered by construction
(no filter), not by cache warmth.

## Amendment — 2026-06-18 (amendment #4): root mitigation moved to Keycloak (rotation off)

Despite the three amendments above, production still showed `Fehler beim Laden der Einsätze` on the
homepage and recurring forced re-logins. Three log sources, captured together, settle the cause:

- **Backend:** every `GET /api/v1/missions/next` that reaches it returns `200` — the backend is
  healthy and is not the source.
- **Frontend:** the homepage fails in `HomeController.home` with
  `ReauthenticationRequiredException: ... GET /api/v1/missions/next`, caused by
  `ClientAuthorizationException [client_authorization_required]` thrown from
  `SingleFlightAuthorizedClientManager.authorize` → `DefaultOAuth2AuthorizedClientManager.authorize`.
  The failing refresh is on the **ordinary page-render / unread-count-poll path**, driven by the
  `ServletOAuth2AuthorizedClientExchangeFilterFunction` for a normal authenticated call — **not** the
  SSE relay (which amendment #3 already made refresh-incapable). The relay was never the only trigger.
- **Keycloak:** one SSO session logged the full reuse-detection cascade —
  `REFRESH_TOKEN_ERROR reason="Stale token"` (an already-rotated refresh token replayed → reuse
  detection revokes the client session) → `"Session doesn't have required client"` (the dead token
  replayed against the revoked session) → `"refresh token issued before the client session started"`
  (after a silent re-login created a new client session, a stale authorized client persisted in Redis
  replayed its old token). The event field `client_auth_method="client-secret"` is **not** evidence of
  a confidential client: it reflects the client's default `clientAuthenticatorType` attribute (which
  is `client-secret` even on public clients), not secret-based authentication. `basetool-frontend` is
  a **public** client (`publicClient: true`, as the sanitized realm reference records); the
  public→confidential migration is ADR-0001, implementation pending.

**Decision.** Disable refresh-token rotation / reuse detection realm-wide
(`Revoke Refresh Token = Off`; realm-export `"revokeRefreshToken": false`). This reverses the
"rejected as the *code* fix" alternative above, on new evidence: although `basetool-frontend` is a
public Keycloak client, it is driven by a **server-rendered Spring BFF** — the refresh token is held
only in the Redis-backed Spring Session and never reaches the browser. Reuse detection — designed to
limit the blast radius of a token leaked from an *untrusted* client environment (browser / SPA /
native) — therefore buys little here, while it is the *direct* mechanism revoking the session under
the unavoidable concurrent-refresh / stale-session-write race of a session-backed BFF. With rotation off, a replayed
or duplicate online refresh token is simply accepted, the cascade cannot start, and the access token
is refreshed normally. `Revoke Refresh Token` is a realm-level control with no per-client override, so
the change is realm-wide.

**Consequences.**

- The homepage `Fehler beim Laden der Einsätze` and the periodic forced re-logins stop, because the
  only thing that was revoking live SSO sessions is gone.
- The `SingleFlightAuthorizedClientManager`, the filter-free `sseWebClient` relay and the
  `EXPIRY_SKEW ≥ 60s` invariant are **kept as defense-in-depth** (harmless, tested) but are no longer
  load-bearing — with rotation off there is no reuse window for them to protect.
- The persisted desktop-extractor refresh token (`basetool-sc-extractor`,
  `INGEST_KEYCLOAK_SETUP.md`) loses rotation-based theft detection. The runbook already documents
  turning rotation off as a reversible lever that is **independent of ingest**, so this is an accepted
  trade, not a regression of a hard requirement. If stricter desktop-token protection is later
  required, the robust options are a shorter SSO/offline session lifetime or a dedicated realm for the
  desktop client rather than realm-wide reuse detection.

This is an operator-applied Keycloak setting (the owner runs prod Keycloak directly); this PR carries
the matching spec/runbook/reference updates so code-as-docs and the live config stay in step.

## Amendment — 2026-06-20 (amendment #5): a second, rotation-independent cause — the `scope` request param leaked into the grant

Even with rotation off (amendment #4), production still showed `Fehler beim Laden` — but the Keycloak
event was a *different* one: `REFRESH_TOKEN_ERROR error="invalid_request" reason="Invalid scopes: all"`
(also `"Invalid scopes: mine"`), surfacing as `ClientAuthorizationException [invalid_scope]` from
`RefreshTokenOAuth2AuthorizedClientProvider`. `all` / `mine` are not OAuth scopes — they are the
job-orders **"Staffel" filter** (`orders-index.html`, radio `name="scope"` `value="mine|all"`,
"Eigene Staffel" / "Alle Staffeln"). The bug is a Spring footgun independent of rotation/reuse and of
single-flight: `DefaultOAuth2AuthorizedClientManager`'s default `contextAttributesMapper` copies an
HTTP request parameter literally named `scope` into
`OAuth2AuthorizationContext.REQUEST_SCOPE_ATTRIBUTE_NAME`, and the refresh provider then forwards it to
Keycloak as the requested scope of the **refresh-token grant**. So any token refresh that coincides
with a request carrying `?scope=all|mine` is rejected `invalid_scope` and the SSO session is bounced
into re-authentication. It is intermittent because only a refresh landing on such a request is
poisoned — a `scope`-free background poll (`/notifications/unread-count`) refreshes cleanly, which is
why a reload "after a bit" recovers. This is precisely why the four prior rotation-/reuse-focused
iterations never closed it.

**Fix.** The frontend never requests scopes dynamically — the `keycloak` client registration's scopes
are fixed — so the `DefaultOAuth2AuthorizedClientManager`'s `contextAttributesMapper` is overridden to
return an empty map (`WebClientConfig.NO_REQUEST_DERIVED_ATTRIBUTES`), severing the request-parameter →
OAuth-scope path entirely. This is a code change (unlike amendment #4's operator toggle) and is
orthogonal to every mitigation above; it holds regardless of the rotation setting. Guarded by
`OAuth2ScopeRequestParamLeakTest` (baseline proves the default mapper leaks; fix proves the override
drops `scope=all` and `scope=mine`).

## Amendment — 2026-09-27 (amendment #6): the relay refreshes once, at stream open, through the manager

Amendment #3 left the notification SSE relay reading its bearer straight from the
`OAuth2AuthorizedClientRepository`, which never refreshes. With a 5-minute access-token lifespan, a
stream opened after the member had been idle carried an expired bearer, the backend answered `401`,
and the stream failed soft until a page request or the unread-count poll refreshed the session.

**Decision.** `NotificationPageController.stream` obtains the client through the single-flight
`OAuth2AuthorizedClientManager`, synchronously on the servlet thread at stream open, with the
principal and the servlet request and response as `OAuth2AuthorizeRequest` attributes. The bearer
is still sent as a plain `Authorization` header over the filter-free `sseWebClient`, so nothing
refreshes later in the stream's life. A manager that returns nothing or throws completes the stream
without calling the backend, as before.

The hazards amendments #1 and #3 closed do not return. The refresh is not deferred onto
`boundedElastic` against a snapshot; it is the same synchronous, session-keyed, single-flight call a
page render makes. It does not write a stale client back late in a 30-minute request either: the
Redis session runs `FlushMode.IMMEDIATE` and saves only changed attributes, so the rotated client is
stored when it is obtained and the relay writes nothing afterwards. Rotation has been off since
amendment #4. The `/ws/sync` handshake took the same step for the same reason.

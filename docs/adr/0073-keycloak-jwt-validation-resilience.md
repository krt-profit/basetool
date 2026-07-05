# ADR-0073 — Keycloak JWT-validation resilience: internal JWKS + retryable 503 on IdP outage

- **Status:** Accepted
- **Date:** 2026-07-05
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-024 · REQ-SEC-014 · REQ-OBS-011 · ADR-0072

## Context

The backend is a JWT resource server: it validates every incoming access token against Keycloak's
JWKS (public signing keys). Spring Boot's auto-configured decoder derives the JWKS URL from
`spring.security.oauth2.resourceserver.jwt.issuer-uri`, which is the **public** issuer
(`https://keycloak.profit-base.online/realms/iri`). Internal, service-to-service token validation
therefore **hairpins out through the public edge** (nginx-proxy-manager) and back to Keycloak.

A production incident made the cost concrete. When Keycloak was briefly slow / unreachable (JWKS
`SocketTimeoutException: Read timed out`, HTTP `503`, and — during a Docker-network churn — an
`UnresolvedAddressException` resolving the internal `keycloak` host), `NimbusJwtDecoder` failed the
key fetch with a `JwtException`. `JwtAuthenticationProvider` wraps that into an
`AuthenticationServiceException`, and Spring Security's `AuthenticationEntryPointFailureHandler`
deliberately **re-throws** it (it denotes a server-side error, not a credential failure). So it
escaped the bearer-token filter unhandled and Tomcat's error dispatch rendered it as `500
INTERNAL_ERROR` — recorded by Micrometer as `uri="UNKNOWN"` — on **every** authenticated endpoint
(`/notifications/unread-count`, `/me/active-org-unit`, `/me/capabilities`, `/users/me`). The
frontend's poll calls surfaced those as 5xx, and the `Http5xxRateHigh` alert fired: a transient
identity-provider blip looked like an application outage.

Two structural weaknesses: (1) internal auth depends on the public edge being healthy; (2) an
identity-provider availability problem is mis-typed as an unretryable server fault.

## Decision

We will harden the resource server on two axes, both with a **safe-by-default** rollout so existing
deployments and the test profile are byte-for-byte unchanged until opted in:

1. **Optional internal JWKS (split-horizon).** A new `app.security.jwt.jwk-set-uri` (env
   `KEYCLOAK_JWK_SET_URI`), when set, points key retrieval at the internal Keycloak connector
   (`https://keycloak:18443/.../certs`) over a `keycloak-trust`-pinned client (the same SSL bundle
   `KeycloakService` uses, factored into `KeycloakTrustSupport`). The `iss` claim is still validated
   against the public issuer Keycloak stamps into tokens, so the split-horizon is transparent.
   Because `NimbusJwtDecoder.withJwkSetUri` defaults to RS256-only (unlike issuer-location discovery,
   which derives the accepted algorithms from the live JWKS), the internal path explicitly restores
   the full asymmetric `SignatureAlgorithm` set (no HMAC, so no algorithm confusion) — otherwise
   enabling internal JWKS would 401 every token on a realm that signs with PS\*/ES\*. Empty (default)
   keeps the auto-configured, issuer-derived, lazily-fetching decoder.

2. **Identity-provider-unavailable → retryable 503.** A dedicated `IdentityProviderUnavailableFilter`
   installed before the bearer-token filter catches a re-thrown `AuthenticationServiceException`
   whose cause chain shows a transport / upstream-5xx failure and re-maps it to `503 Service
   Unavailable` (RFC-7807 problem+json, `Retry-After`, code `SERVICE_UNAVAILABLE`), logged at WARN
   and counted on `basetool_http_error_total{code="SERVICE_UNAVAILABLE"}`. Every other exception —
   and any `AuthenticationServiceException` without a transport cause — propagates unchanged, so
   genuine `401`/`403`/`500` semantics are preserved.

## Consequences

- A Keycloak/edge blip now degrades to a retryable `503` the frontend already handles gracefully
  (its `SERVICE_UNAVAILABLE` path renders a "temporarily unavailable, retry" page and the poll keeps
  driving re-auth), instead of a `500` that reads as a crash and trips `Http5xxRateHigh`.
- The 503 is logged at WARN and counted, not logged at ERROR — an IdP outage no longer inflates the
  `LogbackErrorSpike` error-rate signal, and operators gain a first-class "IdP unavailable" counter.
- Enabling internal JWKS removes the public-edge dependency for internal auth. Cost: the pinned
  internal Keycloak certificate must carry `dns:keycloak` in its SAN (already required for the admin
  client, REQ-SEC-014), and the split-horizon means `iss` (public) and the key URL (internal) differ
  — intentional and validated.
- The internal-JWKS knob does **not** by itself defeat a full Docker-network strand (during the
  incident the internal `keycloak` host also failed to resolve); the 503 re-map is the layer that
  helps regardless of which Keycloak path fails.
- **Applied to both resource servers.** The `ingest` gateway is a second JWT resource server with the
  same decoder pattern; the same hardening (internal-JWKS decoder + the 503 filter) ships for it in
  the same change. Because the modules are independent Gradle projects, ingest carries its own
  package-local `KeycloakTrustSupport` / `IdentityProviderUnavailableFilter` copies (using its own
  `ProblemResponseWriter`, as it has no i18n message bundle) rather than depending on backend classes.

## Alternatives considered

- **Leave the failure as 500.** Rejected: an availability event mis-typed as a server fault trips the
  5xx alert, spams ERROR logs, and gives the frontend no retryable signal.
- **Make `AuthenticationServiceException` reach the entry point (`rethrowAuthenticationServiceException=false`).**
  Rejected: the entry point renders `401`, telling the client to re-authenticate when the token is
  actually fine and the server is the problem — wrong semantics.
- **Map internally via a custom `JwtDecoder` exception.** Rejected: the provider wraps any
  `JwtException` into `AuthenticationServiceException` before it leaves the auth layer, so the
  classification must happen above the provider — a filter is the correct seam.
- **Compose `extra_hosts` to resolve the public hostname internally.** Rejected: the internal
  self-signed cert's SAN is `dns:keycloak`, not `keycloak.profit-base.online`, so hostname
  verification would fail; the `jwk-set-uri` split-horizon is the clean form.
- **Always-on internal JWKS (no opt-in).** Rejected: a high-blast-radius change to token validation
  (a misconfiguration locks everyone out) and it would require the `keycloak-trust` bundle in every
  environment; env-gating makes the rollout controlled and reversible.


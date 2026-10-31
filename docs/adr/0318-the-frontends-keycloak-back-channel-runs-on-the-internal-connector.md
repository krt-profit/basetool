# ADR-0318 — The frontend's Keycloak back-channel runs on the internal connector

- **Status:** Accepted
- **Date:** 2026-10-24
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-SEC-087, REQ-SEC-024, REQ-SEC-014
  ([`security-and-access.md`](../specs/security-and-access.md)) ·
  [ADR-0196](0196-a-rootless-host-aliases-its-own-public-names-to-the-container-gateway.md) (the
  aliases this makes unnecessary for two services) ·
  [ADR-0115](0115-frontend-oauth-token-pool-hardening.md) (the token pool) ·
  [ADR-0167](0167-the-keycloak-hostname-is-the-issuers-single-source.md) (the public issuer) ·
  runbook [`deployment.md` → *Internal Keycloak back-channel*](../deployment.md#internal-keycloak-back-channel)

## Context

Every server-to-server call from the frontend to Keycloak derived its URL from the **public** issuer
and therefore left the container networks: container → `host-gateway` → haproxy → the edge nginx →
Keycloak. On the rootless host that path works only because ADR-0196 aliases the public names to
the container gateway.

The calls on that path were:

| caller | call | when |
| --- | --- | --- |
| frontend | OIDC discovery | every start |
| frontend | token endpoint (`authorization_code`, `refresh_token`) | every login, every refresh |
| frontend | JWKS (ID-token signature) | first login after a start, key rotation |
| frontend | userinfo | every login |
| frontend, backend | `KeycloakHealthIndicator` (discovery document) | every health check |

None of them needs the edge. The frontend already sits on `net-backend-keycloak` and can reach
`keycloak:18443`, whose certificate carries `dns:keycloak` and which the backend already uses for
the Admin API and, since 2026-09-25, for its JWKS (REQ-SEC-024). The detour has costs that have
already been paid once: an edge-reaped keep-alive socket under the refresh grant (ADR-0115), a login
that depends on the edge being up and configured, and an edge rate limit applied to traffic that
is not a client. Measured on production on 2026-10-24, the hairpinned calls reach the edge with the
host's own public address as their source, so the edge cannot tell them apart from each other.

## Decision

**One optional setting, `app.security.keycloak-backchannel.uri`, moves the frontend's
server-to-server Keycloak calls and both Keycloak health probes onto the internal connector.** It
holds the realm base on that connector (`https://keycloak:18443/auth/realms/basetool`), reaches both
applications as `KEYCLOAK_BACKCHANNEL_URI` from the host's `BASETOOL_KEYCLOAK_BACKCHANNEL_URI`, and is
empty by default.

When it is set:

1. **The frontend builds its `keycloak` registration without discovery.** Issuer, authorization
   endpoint and end-session endpoint name the public issuer, because the browser follows them and
   the ID token's `iss` is the public issuer (ADR-0167); token, JWKS and userinfo name the internal
   realm base. Keycloak's endpoint paths below a realm are fixed, so nothing is looked up.
2. **Every back-channel client trusts the `keycloak-trust` bundle**, the same internal truststore
   the backend's Keycloak clients use, with hostname verification on: the token client on its
   existing `frontend-oauth-pool` (ADR-0115), the ID-token decoder and the userinfo call.
3. **The ID-token decoder keeps Spring Security's defaults** — RS256, timestamp and OIDC ID-token
   validation, the standard claim conversion — and differs only in the client that fetches the
   JWKS.
4. **Both health indicators probe the internal discovery document** over the same trust.

Empty leaves every behaviour as before: discovery against the public issuer, the JVM trust store,
the public probe.

## Consequences

- With the setting on, the frontend no longer dials a public name at all, and the backend — whose
  JWKS is already internal — no longer does either. Their `10-host-alias.conf` drop-ins
  (ADR-0196) become unnecessary; removing them is a separate host change once production runs
  with the setting, because the alias is what a rollback to an empty value needs.
- The frontend starts without contacting Keycloak. A Keycloak outage at start-up no longer fails
  the start; it shows on the health indicator and on the first login instead.
- The `iss` check is unchanged: the registration's issuer is the public one, and Keycloak stamps
  `KC_HOSTNAME` into every token whichever connector issued it.
- The edge's token rate limit (`krt_token_perip`) now sees browser traffic only. The frontend's
  token calls are bounded by its own pool (20 connections) instead.
- A new realm path would have to change the derived endpoints as well as `BASETOOL_KEYCLOAK_HOSTNAME`;
  that is the price of not running discovery, and the realm name is already a constant of this
  deployment.

## Alternatives considered

- **Discovery against the internal URL.** Keycloak answers it with the public endpoints (its
  hostname is strict), and Spring Security rejects a discovery document whose issuer differs from
  the URL it was fetched from. It would need a hand-written discovery client to rewrite three of
  the five endpoints it returns — more code than the fixed paths, and a start-up dependency on
  Keycloak that this decision removes.
- **Overriding `token-uri`, `jwk-set-uri` and `user-info-uri` in the Spring properties.** Spring
  Boot still runs discovery whenever `issuer-uri` is set, so the start-up call stays on the edge,
  and dropping `issuer-uri` drops the `iss` check with it.
- **Moving the ingest gateway and Grafana as well.** Both reach Keycloak only through the edge
  today, by design: the ingest gateway is the internet-facing service and has no network to
  Keycloak, and Grafana has none either. Giving them one would open Keycloak's whole internal
  connector to them in exchange for leaving the edge; through the edge they see only what the
  internet sees. They stay.

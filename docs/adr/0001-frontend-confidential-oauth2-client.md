# ADR-0001 — Frontend as a confidential OAuth2 client (PKCE + client secret)

- **Status:** Accepted — implementation pending
- **Date:** 2026-05-20
- **Deciders:** Repository owner (security-audit follow-up)
- **Related:** security-audit finding **M-6** (2026-05-20) · implementation runbook
  [`docs/OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](../OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md)

## Context

The frontend Spring Boot server authenticates against Keycloak as a **public** OAuth2
client (`client-authentication-method: none`, PKCE-only). A public client sends no client
secret at the token endpoint — correct for SPAs and mobile apps, where a secret cannot be
stored safely.

But this frontend is **not** a SPA: it is a server-side Thymeleaf application, a
*confidential* component that can hold a secret in a server-only environment variable.
Relying on PKCE alone means an attacker who intercepts the authorization code — via a
compromised TLS-terminating reverse proxy, an open-redirect misconfiguration, SSRF against
the access logs, or browser-side malware — and obtains the PKCE verifier can redeem it.
This was raised as audit finding **M-6** (Medium — defense-in-depth, no acutely
exploitable vector).

## Decision

We will register the frontend as a **confidential** client and authenticate at the token
endpoint with **PKCE *plus* a client secret** (`client_secret_basic`). PKCE stays active;
the secret is an additional layer, not a replacement. An intercepted authorization code is
then useless without the server-only secret.

## Consequences

- **Easier:** a single compromised layer (proxy, redirect, logs) no longer suffices to
  redeem a code — the attacker would also need the production `.env` secret.
- **Harder:** the migration is **not atomic**. It spans a repo PR (`application.yml`,
  `docker-compose.yml`, `.env.example`, `application-test.yml`), Keycloak admin-console
  changes (public → confidential, generate secret), a prod `.env` update, and a container
  restart. Logins fail between flipping the client and deploying the secret, so it needs a
  maintenance window or a carefully sequenced deploy. The step-by-step runbook is the
  linked migration doc.
- The secret must be rotated like any other credential (Keycloak → `basetool-frontend` →
  Credentials → Regenerate).

## Alternatives considered

- **Keep PKCE-only (status quo).** Rejected: leaves the defense-in-depth gap for a
  component that can safely hold a secret.
- **`client_secret_post` instead of `client_secret_basic`.** Both work;
  `client_secret_basic` is Keycloak's default and was chosen for that reason.

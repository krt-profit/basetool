# Keycloak realm configuration

This directory documents the production Keycloak realm (`iri`) that backs the Profit Basetool
deployment, **in sanitized form**, so the realm's configuration is versioned and reviewable
without ever committing secrets or PII.

## Files

- [`realm-config.reference.json`](realm-config.reference.json) — a **sanitized reference** of the
  prod `iri` realm. It is **not** an importable realm dump and **must not** be used to provision a
  realm directly. It captures the configuration that matters for this codebase (token/session
  lifetimes, the application clients, the `extractor-ingest` audience scope and the
  extractor-exclusive `extractor-ingest-only` scope behind the ingest client-identity gate, roles,
  security headers) and nothing else.

  > **Do not merge the two ingest scopes.** `extractor-ingest` is a **default scope on both**
  > `basetool-frontend` and `basetool-sc-extractor` and carries `include.in.token.scope: false`;
  > `extractor-ingest-only` is assigned to the extractor **alone** and emits its name into the
  > `scope` claim. That separation is exactly what lets the gateway tell an extractor token from a
  > browser session token (`REQ-INGEST-011`) — putting the `basetool-ingest` audience on the shared
  > scope, or assigning the exclusive scope to the frontend, silently disables the gate. See
  > [`INGEST_KEYCLOAK_SETUP.md` step 7a](../INGEST_KEYCLOAK_SETUP.md).

The throwaway **test** realm used by the Playwright e2e suite lives elsewhere, at
[`frontend/src/e2e/resources/realm-export.e2e.json`](../../frontend/src/e2e/resources/realm-export.e2e.json),
and is deliberately different (10 h test token lifetimes, synthetic users, `directAccessGrants`
on for ROPC test logins). Do not cross-contaminate the two.

> **One thing the two realms deliberately agree on: the `basetool-backend` audience.** The e2e
> realm's `basetool-frontend` client carries an `aud-basetool-backend` audience mapper with the
> same config as the prod `extractor-ingest` scope's mapper — access token only, never the ID
> token — because the e2e backend runs with `app.security.jwt.expected-audiences` **enabled**
> (audit L-1, REQ-SEC-024). It sits directly on the client rather than on a client scope only
> because the e2e realm declares no `clientScopes` at all; the emitted claim is identical. Changing
> the mapper here breaks every e2e test with a 401 — `E2eAudienceEnforcementParityTest` fails first
> with an explanation.

## Provenance & sanitization

`realm-config.reference.json` is **generated**, not hand-edited. Refresh it with:

```bash
# 1. Export the iri realm from the Admin Console into a file OUTSIDE the repository
# 2. Sanitize — the script refuses to write when anything sensitive survives
python scripts/sanitize-realm-export.py RAW_EXPORT.json docs/keycloak/realm-config.reference.json
# 3. Delete the raw export
```

Automating this is the point. The previous file was a hand-curated subset from **2026-06-18**, and
by August it had drifted far enough that two security documents contradicted the code (2026-08-17
audit). A generated snapshot refreshes in seconds, so it stays true; it is also fuller than the old
curated subset — more noise in exchange for no drift.

Current snapshot: **2026-08-17**. Still a sanitized reference, still **not** importable.

The script strips or replaces the following — **never commit any of them**:

- **Client secrets** → `__SET_AT_DEPLOY__` (the source export already masked them as `**********`).
- **SMTP credentials & address** → `smtp.example.invalid` / `noreply@example.invalid` /
  `__SMTP_USER__`. The real SMTP host, account and reply-to address are operator secrets and live
  only in the deploy environment.
- **Real public URLs** (`profit-base.online`, `iri-base.org`) → `basetool.example.invalid` in all
  `redirectUris` / `webOrigins` / `post.logout.redirect.uris`. Internal Docker network aliases
  (`backend:11261`, `frontend:18081`) are kept because they document the service topology and are
  not secret.
- **Users** — removed entirely (the only export entry was the `backend-service` service account).
- **Realm signing keys / `components`** — not present in the masked export and never to be added.
- **`id` UUIDs**, Keycloak built-in clients (`account`, `broker`, `realm-management`, …) and the
  authentication flows — omitted as noise.
- **Discord IdP client id/secret** → `__SET_AT_DEPLOY__`. The `discord` identity provider and the
  `discord_user_id` attribute/protocol mappers **are** captured here (they are app-relevant, see
  ADR-0030), but the membership gate's guild id + KRT-Mitglied role id live on the custom
  first-broker-login flow's authenticator config — and flows stay omitted, so those are documented in
  [`DISCORD_KEYCLOAK_SETUP.md`](DISCORD_KEYCLOAK_SETUP.md) only, never here.

This file is **reference documentation**, not a credential store. Treat it as read-mostly: when the
prod realm config changes in a way that matters to the app (token settings, a new client/scope, a
mapper), update this file in the same PR — secrets stay redacted.

## `backend-service` service-account roles (Admin API)

The backend's user sync (`KeycloakService`, `UserSyncTask`) authenticates to the Keycloak Admin API
as the `backend-service` confidential client's service account. Because the built-in
`realm-management` client is omitted from the reference above, the required grants are documented
here instead. The service account MUST hold **both** `realm-management` client roles:

|     Role     |                                                                                                 Why                                                                                                 |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `view-users` | list users (`GET /users`) and read a user's federated identity (Discord back-fill).                                                                                                                 |
| `view-realm` | list realm roles (`GET /admin/realms/{realm}/roles`) and read their members (`GET /roles/{name}/users`) — the role-indexed resolution added by the 5000-account hardening (ADR-0085 / REQ-SEC-018). |

**`view-realm` is easy to miss:** before role-indexing the sync read roles per user and needed only
`view-users`, so an older deployment's service account may carry `view-users` alone. With only
`view-users` the nightly sync fails closed every run — a `403` on the `GET /roles` listing, which
`KeycloakService` skips (never a wipe) but which means roles/departures stop reconciling until the
next interactive login. The backend logs this as an explicit "missing `view-realm`" ERROR.

Grant it once, via the Admin Console (**Clients → `backend-service` → Service account roles → Assign
role → filter by `realm-management` → `view-realm`**) or with `kcadm.sh`:

```bash
# Authenticate kcadm against the running Keycloak first (adjust server/admin creds), then:
kcadm.sh add-roles -r iri \
  --uusername service-account-backend-service \
  --cclientid realm-management \
  --rolename view-realm
```

## Token & session settings (source of truth for session behaviour)

The realm-level token settings reproduced verbatim in the reference are what govern login
longevity and the refresh flow. As of 2026-06-18:

|                    Setting                    |   Value    |                         Meaning                          |
|-----------------------------------------------|------------|----------------------------------------------------------|
| `accessTokenLifespan`                         | 300        | 5 min                                                    |
| `revokeRefreshToken` / `refreshTokenMaxReuse` | false / 5  | rotation **off** realm-wide since 2026-06-18 — see below |
| `ssoSessionIdleTimeout`                       | 2 592 000  | **30 days**                                              |
| `ssoSessionMaxLifespan`                       | 15 552 000 | **180 days**                                             |
| `clientSessionIdleTimeout` / `…Max`           | 0 / 0      | inherit the realm SSO values                             |

These values mean **no session/idle timeout fires anywhere near 30–60 minutes** — relevant when
diagnosing forced re-logins (see [ADR-0019](../adr/0019-frontend-reauth-on-client-authorization-required.md)
and [`INGEST_KEYCLOAK_SETUP.md`](../INGEST_KEYCLOAK_SETUP.md) step 4).

**Refresh-token rotation is off, and that is deliberate.** `revokeRefreshToken` was turned off
realm-wide on 2026-06-18 (REQ-SEC-012 / [ADR-0019](../adr/0019-frontend-reauth-on-client-authorization-required.md)
amendment #4): the frontend is a server-rendered BFF whose refresh token never reaches the
browser, so rotation plus reuse detection bought little there while being the direct cause of
session revocations under the BFF's unavoidable concurrent-refresh race. `refreshTokenMaxReuse`
is therefore inert — it only has meaning while rotation is on. Two consequences worth knowing:
the persisted desktop-extractor refresh token is no longer protected by reuse detection (a
recorded, reversible operator lever — see [`INGEST_KEYCLOAK_SETUP.md`](../INGEST_KEYCLOAK_SETUP.md)),
and any future **public** client (native/mobile) must be sender-constrained via DPoP instead,
since RFC 9700 requires public-client refresh tokens to be either rotated or bound.

## Open findings (hardening, tracked separately)

- **`fullScopeAllowed: true`** on `basetool-frontend` and `basetool-sc-extractor` grants the full
  realm role set into tokens rather than a least-privilege subset. `INGEST_KEYCLOAK_SETUP.md`
  step 1 specifies `fullScopeAllowed: false` for the extractor; prod currently has it `true`.

## Resolved

- **ROPC disabled on `basetool-frontend` (2026-06-18).** `directAccessGrantsEnabled` is now `false`
  on the public frontend client (it used the browser authorization-code flow anyway), so the
  password can never traverse a direct-access (resource-owner-password) grant. The e2e test realm
  (`realm-export.e2e.json`) deliberately keeps it `true` for its ROPC test logins.


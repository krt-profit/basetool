# Desktop ingest — Keycloak setup runbook

> **Doc type:** Implementation runbook for [ADR-0018](adr/0018-desktop-ingest-gateway-device-grant.md)
> and [`docs/specs/desktop-ingest.md`](specs/desktop-ingest.md) (`REQ-INGEST-002`,
> `REQ-INGEST-007`, `REQ-INGEST-008`). The *decision* and the *requirements* live there; this
> document is the step-by-step *how* for the operator. Registered in
> [`docs/specs/INDEX.md`](specs/INDEX.md). Tracks GitHub issue #641 (epic #639).

**Status:** open — the live Keycloak changes and the validator enablement are an operator
deployment step that cannot be done by PR. The **prod realm dump (with secrets) is not in this
repository**: only a **sanitized reference** of the prod realm config lives at
[`docs/keycloak/realm-config.reference.json`](keycloak/realm-config.reference.json) (secrets,
SMTP and real URLs redacted — see [`docs/keycloak/README.md`](keycloak/README.md)), and the
throwaway `frontend/src/e2e/resources/realm-export.e2e.json` test artifact — do **not** copy its
`directAccessGrantsEnabled: true`.

> ## ⚠️ The ingest interface is restricted to approved clients
>
> **Only client software explicitly approved by the basetool developer (@greluc) may use the ingest
> interface.** Approving a client means doing **both** of the following — neither alone grants
> access:
>
> 1. registering a dedicated Keycloak client for it (steps 1–3 below), and
> 2. adding its client id to `APP_INGEST_CLIENT_IDENTITY_ALLOWED_CLIENT_IDS` on the gateway.
>
> Removing the allowlist entry revokes a client immediately (existing access tokens expire within the
> access-token lifespan, ~5 min) without needing a Keycloak change or a release. Do not add a client
> id here on anyone's request but the owner's.

## What this sets up

The desktop extractor (`basetool-bp-extractor`) must obtain a **minimal, per-user,
audience-restricted** Keycloak token **without shipping any secret**, and that token must be
accepted by the backend's import endpoints (reached through the ingest gateway, #642). Four
pieces, applied in a **strict order**:

1. a new **public** client `basetool-sc-extractor` (device-grant, PKCE, no secret);
2. an **audience mapper** that stamps `aud=basetool-backend` on its access tokens (via a
   dedicated `extractor-ingest` client scope);
3. **the same audience mapper** on the existing frontend client, so the frontend's relayed
   user token also carries `aud=basetool-backend`;
4. **only then** the backend's opt-in audience validator turned on.

> **Critical sequencing (REQ-INGEST-008).** Enabling the backend audience validator before
> **both** the extractor token **and** the frontend token already carry `aud=basetool-backend`
> will reject the frontend's tokens and **break the entire app** (every page is behind the
> frontend's user token). Apply steps 1–3, **verify both token sets carry the claim**, and
> only then do step 4. Do the whole sequence in a **staging realm first**.

## Prerequisites

- Admin access to the Keycloak realm that backs prod (the same realm the frontend client
  `basetool-frontend` and the backend resource server live in).
- A staging/replica realm to rehearse the sequence.
- The ability to restart the backend container (step 4 is an env change).

## Step 1 — New public client `basetool-sc-extractor`

Realm → Clients → Create client. Settings (Admin Console fields → the equivalent
realm-export JSON keys):

|           Console setting            |                                Value                                 |
|--------------------------------------|----------------------------------------------------------------------|
| Client ID                            | `basetool-sc-extractor`                                              |
| Client authentication                | **Off** (public client — no secret)                                  |
| Standard flow                        | **On** (RFC 8252 loopback auth-code fallback)                        |
| Direct access grants                 | **Off** (no ROPC — the desktop app must never see the password)      |
| Service accounts                     | **Off**                                                              |
| OAuth 2.0 Device Authorization Grant | **On**                                                               |
| PKCE Code Challenge Method           | `S256` (required)                                                    |
| Valid redirect URIs                  | `http://127.0.0.1/*`, `http://localhost/*` (loopback only, RFC 8252) |
| Web origins                          | *(empty — no browser CORS surface)*                                  |

Equivalent realm-export fragment (for reference / IaC):

```json
{
  "clientId": "basetool-sc-extractor",
  "name": "Basetool SC Extractor (desktop)",
  "enabled": true,
  "protocol": "openid-connect",
  "publicClient": true,
  "standardFlowEnabled": true,
  "directAccessGrantsEnabled": false,
  "serviceAccountsEnabled": false,
  "fullScopeAllowed": false,
  "redirectUris": ["http://127.0.0.1/*", "http://localhost/*"],
  "webOrigins": [],
  "attributes": {
    "oauth2.device.authorization.grant.enabled": "true",
    "pkce.code.challenge.method": "S256"
  }
}
```

Notes:

- `publicClient: true` + no secret is correct and RFC-conform for a native app (a desktop
  binary cannot keep a secret — REQ-INGEST-002). PKCE `S256` is the proof-of-possession that
  replaces the secret.
- `fullScopeAllowed: false` keeps the token's roles to what the client scope grants, not the
  whole realm — least privilege.
- The device grant has no redirect; the redirect URIs only serve the loopback auth-code
  fallback.

## Step 2 — Audience mapper via an `extractor-ingest` client scope

Create a client scope and attach the audience mapper, then assign the scope to the new
client as a **default** scope (so every token it issues carries the audience).

Realm → Client scopes → Create client scope:

| Setting  |       Value        |
|----------|--------------------|
| Name     | `extractor-ingest` |
| Type     | Default            |
| Protocol | `openid-connect`   |

Add mapper → **Audience**:

|      Mapper setting      |         Value          |
|--------------------------|------------------------|
| Name                     | `aud-basetool-backend` |
| Included Custom Audience | `basetool-backend`     |
| Add to access token      | **On**                 |
| Add to ID token          | Off                    |

> If a Keycloak **client** named `basetool-backend` exists for the backend resource server,
> use *Included Client Audience* = `basetool-backend` instead of *Included Custom Audience*;
> both emit the identical `aud` value. The backend only checks the string value
> (`app.security.jwt.expected-audiences=basetool-backend`).

Then: Clients → `basetool-sc-extractor` → Client scopes → Add client scope → `extractor-ingest`
as **Default**.

Equivalent realm-export fragment:

```json
{
  "name": "extractor-ingest",
  "protocol": "openid-connect",
  "attributes": { "include.in.token.scope": "true", "display.on.consent.screen": "false" },
  "protocolMappers": [
    {
      "name": "aud-basetool-backend",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-audience-mapper",
      "config": {
        "included.custom.audience": "basetool-backend",
        "access.token.claim": "true",
        "id.token.claim": "false"
      }
    }
  ]
}
```

## Step 3 — Same audience mapper on the frontend client

The frontend relays the **user's** access token to the backend (through the gateway for
ingest, directly for everything else). That token must also carry `aud=basetool-backend`, or
step 4 breaks it.

Either assign the same `extractor-ingest` scope to `basetool-frontend` as a default scope,
**or** (cleaner separation) add an identical Audience mapper to the frontend client's own
dedicated scope. Whichever you pick, the result must be: a fresh `basetool-frontend` login
token contains `"aud": [..., "basetool-backend"]`.

## Step 4 — Refresh-token rotation (realm-wide) — DISABLED 2026-06-18

Realm settings → Tokens (realm-level — note this affects the whole realm):

|         Setting         |         Value          |                             Why                             |
|-------------------------|------------------------|-------------------------------------------------------------|
| Revoke Refresh Token    | **Off**                | no rotation: a replayed online refresh token is not revoked |
| Refresh Token Max Reuse | `5` (inert)            | ignored by Keycloak while rotation is off                   |
| Access Token Lifespan   | realm default (~5 min) | keep short; clients refresh                                 |

Equivalent realm-export keys: `"revokeRefreshToken": false` (see
`docs/keycloak/realm-config.reference.json`).

> **Why this was turned off (2026-06-18).** Rotation + reuse-detection was originally enabled here
> to protect the persisted desktop-extractor refresh token (a public client storing its token in the
> OS keystore). But the same realm-wide control also governs `basetool-frontend`, a **server-rendered
> Spring BFF** — a public Keycloak client whose refresh token is nonetheless held only in the
> Redis-backed Spring Session and never reaches a browser. On that BFF, rotation buys little (the
> token never leaves the trusted server) and — under the unavoidable concurrent-refresh / stale-session
> race — was the *direct* cause of a production cascade that revoked live SSO sessions
> (`REFRESH_TOKEN_ERROR reason="Stale token"` → `"Session doesn't have required client"`), surfacing
> as `Fehler beim Laden der Einsätze` on the homepage and recurring forced re-logins (REQ-SEC-012,
> ADR-0019 amendment #4). Because `Revoke Refresh Token` is realm-level with no per-client override,
> it is turned off realm-wide. The trade is that the desktop-extractor token loses rotation-based
> theft detection — acceptable here and reversible; if stricter desktop protection is later needed,
> use a shorter SSO/offline session lifetime or a dedicated realm for the desktop client rather than
> re-enabling realm-wide reuse detection (which re-breaks the frontend).

## Step 5 — VERIFY both token sets carry the audience (gate for step 6)

Do **not** proceed to step 6 until both checks pass.

1. **Extractor token:** run the device flow against `basetool-sc-extractor` (or use the
   extractor's send action once #645 ships), then decode the access token (jwt.io / `jq`)
   and confirm `aud` contains `basetool-backend`.
2. **Frontend token:** log into the frontend normally, capture its access token (server log
   at debug, or a fresh login in staging), decode it, and confirm `aud` contains
   `basetool-backend`.

If either token is missing the claim, fix the mapper/scope assignment (steps 2–3) and
re-verify. Enabling step 4's validator while either token lacks the claim is the documented
break.

## Step 6 — Enable the backend audience validator

Only after step 5 passes on **both** tokens:

```properties
# backend env (docker-compose / .env)
APP_SECURITY_JWT_EXPECTED_AUDIENCES=basetool-backend
```

This sets `app.security.jwt.expected-audiences`, which the backend's already-present
`@ConditionalOnProperty` decoder (`SecurityConfig.audienceValidatingJwtDecoder` /
`audienceValidator`) activates — layering an `aud` check on top of the existing signature /
issuer / expiry validation. Restart the backend. Smoke-test: the frontend still works
(pages load, writes succeed) **and** an extractor ingest call still reaches the backend.

## Step 7 — Client-identity gate (REQ-INGEST-011)

Everything below is **inert until configured**, and each check is fail-closed once enabled. Do it in
this order; the audit-only pass is what keeps it from locking out the real extractor.

### 7a — ⚠️ First: `extractor-ingest` is currently shared with the frontend

**This is the trap, and in the deployed realm it is already sprung.** Step 3 above offered two ways
to give the frontend its `basetool-backend` audience, and the realm took the shared-scope route. Per
[`docs/keycloak/realm-config.reference.json`](keycloak/realm-config.reference.json), the
`extractor-ingest` scope is a **default scope on both** `basetool-frontend` **and**
`basetool-sc-extractor`.

Two consequences, and both silently defeat step 7 if ignored:

- `APP_INGEST_CLIENT_IDENTITY_REQUIRED_SCOPE=extractor-ingest` would be satisfied by a **frontend
  session token** — the scope check would not discriminate at all.
- An audience mapper added to `extractor-ingest` would stamp `aud=basetool-ingest` onto **frontend
  tokens too**, so the audience gate would pass for exactly the tokens it exists to refuse.

**Fix the scope topology before anything else.** Create a **new** client scope that only the
extractor ever gets, and put the ingest-specific mapper there:

|             Setting              |           Value           |
|----------------------------------|---------------------------|
| Setting                          | Value                     |
| -------------------------------- | ------------------------- |
| Name                             | `extractor-ingest-only`   |
| Type                             | Default                   |
| Protocol                         | `openid-connect`          |
| **Include in token scope**       | **On** ⚠️ see below       |

> **⚠️ `Include in token scope` must be On — the shared scope has it Off.** Spring Security derives
> the `SCOPE_…` authority from the token's `scope` claim, and the deployed `extractor-ingest` scope
> carries `include.in.token.scope: "false"` (see
> [`realm-config.reference.json`](keycloak/realm-config.reference.json)), so its name never reaches
> the claim. With that setting the gateway's `required-scope` check would reject **every** caller,
> the real extractor included — not merely fail to discriminate. Verify the flag on the new scope
> before enabling the check.

Add an **Audience** mapper to it — name `aud-basetool-ingest`, *Included Custom Audience* =
`basetool-ingest`, *Add to access token* **On** — and assign the scope as a **Default** scope to
`basetool-sc-extractor` **only**.

Equivalent realm-export fragment (already reflected in
[`realm-config.reference.json`](keycloak/realm-config.reference.json)):

```json
{
  "name": "extractor-ingest-only",
  "protocol": "openid-connect",
  "attributes": {
    "include.in.token.scope": "true",
    "display.on.consent.screen": "false"
  },
  "protocolMappers": [
    {
      "name": "aud-basetool-ingest",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-audience-mapper",
      "config": {
        "included.custom.audience": "basetool-ingest",
        "access.token.claim": "true",
        "id.token.claim": "false"
      }
    }
  ]
}
```

Leave the existing shared `extractor-ingest` scope untouched: it
still stamps `aud=basetool-backend` for both clients, which is what step 6 depends on.

Verify on **both** live tokens before continuing:

- extractor token → `aud` contains **both** `basetool-backend` and `basetool-ingest`; `scope`
  contains `extractor-ingest-only`
- frontend token → `aud` contains `basetool-backend` but **not** `basetool-ingest`; `scope` does
  **not** contain `extractor-ingest-only`

Only then, on the **gateway** (not the backend):

```properties
APP_SECURITY_JWT_EXPECTED_AUDIENCES=basetool-ingest
```

> **Do not point the gateway at `basetool-backend`.** That is the backend's audience and every
> frontend token carries it — the check would pass for tokens this interface must refuse.

### 7b — Use the exclusive scope for the scope check

Because of 7a, the value below is the **new** scope, not the shared one:

```properties
APP_INGEST_CLIENT_IDENTITY_REQUIRED_SCOPE=extractor-ingest-only
```

Setting it to `extractor-ingest` would look configured and enforce nothing.

### 7c — Configure, run in audit-only, then enforce

```properties
# gateway env
APP_INGEST_CLIENT_IDENTITY_ALLOWED_CLIENT_IDS=basetool-sc-extractor
# NOTE: the exclusive scope from 7a, NOT the shared `extractor-ingest`.
APP_INGEST_CLIENT_IDENTITY_REQUIRED_SCOPE=extractor-ingest-only
APP_INGEST_CLIENT_IDENTITY_ALLOWED_TOOLS=basetool-sc-extractor
APP_INGEST_CLIENT_IDENTITY_AUDIT_ONLY=true
```

Restart, then watch for at least one full scrape interval:

- `basetool_ingest_client_rejected_total` must stay at **zero**. Any value means a legitimate caller
  would have been locked out — read the `reason` label before proceeding.
- `basetool_ingest_client_total{client_id="basetool-sc-extractor"}` should carry the traffic. If it
  lands on `client_id="other"` instead, the `azp` is not what the allowlist expects.

Only when both hold, set `APP_INGEST_CLIENT_IDENTITY_AUDIT_ONLY=false` and restart. The
`IngestUnknownClient` alert fires on the same counter afterwards.

> Multiple client ids are supported (comma-separated), which is what makes a client-id **rotation**
> possible without downtime: ship the new extractor with a new id, run both, drop the old id once the
> per-`client_id` counter shows no traffic on it.

## Step 8 — DPoP (REQ-INGEST-012) — BLOCKED on the extractor

Keycloak has supported DPoP as a non-preview feature since 26.4, and the gateway validates DPoP-bound
tokens already. **`APP_INGEST_CLIENT_IDENTITY_DPOP_REQUIRED` must stay `false`** until the desktop
extractor sends DPoP proofs — it sends `Authorization: Bearer` today, so enabling this breaks every
send on deploy. Validation of a presented DPoP token is active regardless, so there is nothing to
enable for the migration to begin.

Two things are worth doing on the Keycloak side **now**, independently of the extractor:

- **Do not grant `offline_access`** to `basetool-sc-extractor`, so no long-lived offline token can be
  minted for a client whose token is persisted on a user machine.
- **Shorten the client session / access-token lifespans** for that client (Client → Advanced). This
  is a *per-client* override and therefore free of the problem that forced realm-wide refresh-token
  rotation off in step 4 — it does not touch the frontend BFF.

## Rollback

- **Step 6:** unset `APP_SECURITY_JWT_EXPECTED_AUDIENCES` and restart the backend — the
  validator becomes inert (the decoder bean is no longer created); all previously-valid
  tokens are accepted again. This is the fast rollback if anything 401s after step 6.
- **Steps 1–3:** remove the `extractor-ingest` scope assignment / the `basetool-sc-extractor`
  client. Harmless to leave in place even if the gateway is not yet deployed — the client
  issues tokens nobody consumes until #642 is live.
- **Step 4:** refresh-token rotation is **off** as of 2026-06-18 (it broke the server-rendered
  frontend BFF — REQ-SEC-012 / ADR-0019 amendment #4). Re-enabling it (`Revoke Refresh Token = On`)
  restores desktop-token rotation but re-introduces the frontend session-revocation cascade, so do
  not re-enable it realm-wide without a per-client / per-realm scoping plan for the frontend.

## Security checklist (REQ-INGEST-002 / -007 / -008)

- [ ] `basetool-sc-extractor` is **public**, has **no secret**, ROPC **off**, service
  accounts **off**, web origins **empty**.
- [ ] PKCE `S256` required; redirect URIs are loopback only.
- [ ] `aud=basetool-backend` verified on **both** the extractor token and the frontend token
  **before** the validator is enabled.
- [ ] Refresh-token rotation + reuse-detection **off** realm-wide (`"revokeRefreshToken": false`) —
  disabled 2026-06-18 because it revoked the server-rendered frontend BFF's sessions (REQ-SEC-012,
  ADR-0019 amendment #4).
- [ ] No client secret, refresh token, or user name/email is written to any config file or
  log (project-wide logging rule).


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
> 2. adding its client id to `IRI_INGEST_ALLOWED_CLIENT_IDS` on the gateway.
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

> **The mechanism itself no longer needs proving here.** Since #1247 the E2E realm stamps
> `aud=basetool-backend` on its `basetool-frontend` client and the E2E backend runs with
> `IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend`, so every e2e-labelled PR re-proves that a
> stamped mapper plus an armed validator accepts real Keycloak tokens. What no CI run can tell you
> is whether the **deployed** realm stamps the claim — that, and only that, is what this step
> checks. Note the E2E stack covers the backend only; the gateway is not part of it, so
> `IRI_INGEST_EXPECTED_AUDIENCES` has unit coverage alone.

**5a — Config check (preferred; handles no live token).** In the Admin Console, for **both**
`basetool-frontend` and `basetool-sc-extractor`: *Clients → \<client\> → Client scopes* must list
`extractor-ingest` as a **Default** scope, and *Client scopes → extractor-ingest → Mappers* must
contain an `oidc-audience-mapper` with `Included Custom Audience = basetool-backend` and *Add to
access token* ON. Both clients showing that is the condition step 6 depends on. The committed
[`realm-config.reference.json`](keycloak/realm-config.reference.json) records this shape, but it
is a sanitized snapshot (2026-06-18), **not** live state — read it from the running realm.

**5b — Token check (confirmation).** Obtain an access token per client (device flow for the
extractor; a normal frontend login for the browser token) and decode the payload **locally**:

```bash
P=$(cut -d. -f2 <<<"$TOKEN" | tr '_-' '/+'); while (( ${#P} % 4 )); do P+='='; done; base64 -d <<<"$P" | jq .aud
```

(The `while` loop restores the base64 padding Keycloak strips; without it `base64 -d` reports
`invalid input`. Expected output: an array containing `basetool-backend`.)

> ⚠️ **Never paste a live access token into jwt.io or any other online decoder.** It is a bearer
> credential: whoever holds it is the user until it expires. Decode it locally, and treat any token
> that has been pasted into a third-party page as compromised. (This runbook previously suggested
> jwt.io — it should not have.)

If either token is missing the claim, fix the mapper/scope assignment (steps 2–3) and
re-verify. Enabling step 4's validator while either token lacks the claim is the documented
break.

## Step 6 — Enable the backend audience validator

Only after step 5 passes on **both** tokens:

```properties
# /var/iri/code/.env on the prod host
IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend
```

This sets `app.security.jwt.expected-audiences`, which activates the backend's already-present
`SecurityConfig#resourceServerJwtDecoder` (a `@ConditionalOnExpression` bean, shared with the
`jwk-set-uri` knob) and its `audienceValidator` — layering an `aud` check on top of the existing
signature / issuer / expiry validation. Restart the backend. Smoke-test: the frontend still works
(pages load, writes succeed) **and** an extractor ingest call still reaches the backend.

Rollback is instant and needs no release: blank the variable (or delete the line) and restart.

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

- `IRI_INGEST_REQUIRED_SCOPE=extractor-ingest` would be satisfied by a **frontend
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
IRI_INGEST_EXPECTED_AUDIENCES=basetool-ingest
```

> **Do not point the gateway at `basetool-backend`.** That is the backend's audience and every
> frontend token carries it — the check would pass for tokens this interface must refuse.
>
> ### ⚠️ `AUDIT_ONLY` does NOT cover this variable — set it LAST
>
> `IRI_INGEST_CLIENT_AUDIT_ONLY` only softens the three checks in `ClientIdentityProperties` (client
> id, scope, provenance). **The audience check is a different mechanism**: it lives in the
> resource server's `JwtDecoder`, so it starts refusing the moment it is set, regardless of
> audit-only — and it refuses with **`401`**, not the `403` the other gates use. A client will
> report "you must be signed in" rather than "not approved", which points at the wrong problem.
>
> This bit in production on **2026-08-03**: the audience was set alongside the audit-only
> variables, so it was live while the rollout was believed to be observe-only.
>
> Therefore: leave `IRI_INGEST_EXPECTED_AUDIENCES` **empty** until 7c has completed its audit-only
> pass and enforcement is on, then set it as the final step and re-test a real send immediately. If
> sends start failing with 401, this variable is the first thing to clear.

### 7b — Use the exclusive scope for the scope check

Because of 7a, the value below is the **new** scope, not the shared one:

```properties
IRI_INGEST_REQUIRED_SCOPE=extractor-ingest-only
```

Setting it to `extractor-ingest` would look configured and enforce nothing.

### 7c — Configure, run in audit-only, then enforce

> **Two names for one setting — use the `IRI_*` one on the host.** The application reads
> `APP_INGEST_CLIENT_IDENTITY_*` (that is what the spec and the `@ConfigurationProperties` class
> name), but `docker-compose.yml` maps those from `IRI_*` variables. Putting an `APP_*` name in
> `/var/iri/code/.env` sets a variable the container never receives — the gate would stay silently
> inert and look configured. Everything below is the host-side name.

```properties
# /var/iri/code/.env on the prod host
IRI_INGEST_ALLOWED_CLIENT_IDS=basetool-sc-extractor
# NOTE: the exclusive scope from 7a, NOT the shared `extractor-ingest`.
IRI_INGEST_REQUIRED_SCOPE=extractor-ingest-only
# BOTH spellings: the extractor emits the slug on the refinery path but the display
# name on the blueprint path. Only the slug = every blueprint send 403s (2026-08-03).
IRI_INGEST_ALLOWED_TOOLS=basetool-sc-extractor,Basetool SC Extractor
IRI_INGEST_CLIENT_AUDIT_ONLY=true
```

Restart, then watch for at least one full scrape interval:

- `basetool_ingest_client_rejected_total` must stay at **zero**. Any value means a legitimate caller
  would have been locked out — read the `reason` label before proceeding.
- `basetool_ingest_client_total{client_id="basetool-sc-extractor"}` should carry the traffic. If it
  lands on `client_id="other"` instead, the `azp` is not what the allowlist expects.

Only when both hold, set `IRI_INGEST_CLIENT_AUDIT_ONLY=false` and restart. The
`IngestUnknownClient` alert fires on the same counter afterwards.

> Multiple client ids are supported (comma-separated), which is what makes a client-id **rotation**
> possible without downtime: ship the new extractor with a new id, run both, drop the old id once the
> per-`client_id` counter shows no traffic on it.

## Step 8 — DPoP: both tokens are bound, and that is correct (REQ-INGEST-012)

DPoP protects two things here: the **refresh token** the extractor writes to disk — the credential
most worth binding — and the **access token** it presents to the gateway, which since ADR-0129 is
validated at the very hop that consumes it.

Since Keycloak 26.4 DPoP needs **no feature flag** and **no per-client switch**: it binds whenever a
client sends a proof, and the extractor always sends one. So the correct configuration is *no
configuration*.

> **This step used to say the opposite.** While the gateway relayed the caller's token, a bound
> access token could not survive the second hop, so the instruction was to narrow binding to the
> refresh token. ADR-0129 removed the relay and with it that constraint; the instruction survived
> until 2026-08-17 and would have degraded the deployment if followed.

### 8a — Nothing to configure

There is no step here any more, and that is the point: since Keycloak 26.4 DPoP binds whenever a
client presents a proof, the extractor always presents one, and since ADR-0129 **both** tokens being
bound is the wanted state. Verified against production on 2026-08-17: the realm carries zero client
profiles and zero policies, which is correct.

> **Do not create the `extractor-dpop` profile this step used to describe.** A
> `dpop-bind-enforcer` executor with `allow-only-refresh-token-binding = On` would narrow binding to
> the refresh token, which was right while the gateway relayed the access token and is wrong now.
> It would not break sending — the extractor follows the server and would fall back to the `Bearer`
> scheme, which the gateway still accepts — it would silently *remove* the sender-constraining from
> the one internet-facing hop, and `ClientIdentityFilter` would start logging the lapsed-protection
> canary on every request.
>
> The per-client **"Require DPoP bound tokens"** switch (*Settings → Capability config*, attribute
> `dpop.bound.access.tokens`) also stays **off**. It is an enforcement switch, and enforcement is
> deliberately absent: the gateway keeps `.jwt()` alongside `.dPoP()` so a client rollout needs no
> flag day (REQ-INGEST-012).
>
> The executor exists and its configuration keys are real
> (`DPoPBindEnforcerExecutorFactory`: `auto-configure`,
> `enforce-authorization-code-binding-to-dpop`, `allow-only-refresh-token-binding`) — it is simply
> the wrong tool for this deployment. It **is** the right tool for a client that talks to the
> backend directly, which is why the Android app plans to use it.

### 8b — Verify

Send once from the extractor, then decode the access token it received:

- `cnf.jkt` must be **present** — the access token is sender-constrained to the extractor's key.
- `token_type` must be `DPoP`, not `Bearer`.

If `cnf` is absent, the binding lapsed: the gateway logs a `WARN` naming REQ-INGEST-012, sends keep
working over the `Bearer` path, and the protection is gone without anything failing. Check that no
client policy narrowed the binding and that the extractor build still sends a proof at the token
endpoint.

### 8c — Two numbers that bite

Keycloak allows a proof lifetime of **10 seconds** and a clock skew of **15 seconds** (`DPoPUtil`). A
machine whose clock drifts beyond that fails authentication with no obvious cause — the extractor
detects and names this case, but if you see unexplained auth failures on one machine, check its clock
first.

## Step 9 — The gateway becomes a trusted subsystem (ADR-0129, REQ-INGEST-001/-012)

**Why this exists.** Until now the gateway forwarded the caller's own token to the backend. That
made sender-constrained tokens impossible: a DPoP-bound token presented as a plain bearer is
rejected outright by Spring Security 7.1, which is what broke every send from 2026-08-03. The
gateway now validates the extractor's proof itself and calls the backend under its **own** identity,
naming the member it acts for.

**Everything below is fail-closed.** With none of it applied, the deployed code behaves exactly as
before: the backend refuses every on-behalf-of header, and the gateway keeps accepting plain
bearers. So the code can ship first and this can be applied afterwards — but **the extractor will
not send until all four values are set**.

**Order matters:** create the client (9a), then set all four env values together (9b), then restart
(9c), then verify (9d). Setting the gateway's credentials without the backend allowlist gives you a
gateway that authenticates and a backend that refuses it.

### 9a — New confidential client `basetool-ingest-gateway`

Realm → Clients → Create client.

|  Admin Console field   |                    Value                    |
|------------------------|---------------------------------------------|
| Client type            | `OpenID Connect`                            |
| Client ID              | `basetool-ingest-gateway`                   |
| Name                   | `Basetool Ingest Gateway`                   |
| Client authentication  | **On** (this is what makes it confidential) |
| Authorization          | Off                                         |
| Standard flow          | **Off**                                     |
| Direct access grants   | **Off**                                     |
| Implicit flow          | Off                                         |
| Service accounts roles | **On**                                      |
| Valid redirect URIs    | *(leave empty — no browser flow)*           |
| Web origins            | *(leave empty)*                             |

Every flow except service accounts is off on purpose: this client never represents a person and
never sees a browser. It exists solely to obtain a client-credentials token for one internal hop.

Then Clients → `basetool-ingest-gateway` → **Credentials** → copy the **Client secret**. You will
need it in 9b.

> **Treat this secret like the database password.** It is the credential that lets the gateway act
> for *any* member. It goes only into the prod `.env`, never into the repository, never into a
> screenshot, and never into a chat message.

**No role assignment is needed.** The backend authorises this caller by its `azp`, not by a role,
and the two endpoints it reaches require only `isAuthenticated()`.

### 9b — Four values in the prod `.env`

All four, together. Each is inert on its own.

```
IRI_INGEST_PUBLIC_BASE_URL=https://ingest.profit-base.online
IRI_INGEST_SERVICE_ACCOUNT_TOKEN_URI=https://keycloak.profit-base.online/realms/iri/protocol/openid-connect/token
IRI_INGEST_SERVICE_ACCOUNT_CLIENT_ID=basetool-ingest-gateway
IRI_INGEST_SERVICE_ACCOUNT_CLIENT_SECRET=<the secret from 9a>
IRI_INGEST_GATEWAY_CLIENT_IDS=basetool-ingest-gateway
```

> **`IRI_INGEST_PUBLIC_BASE_URL` is the one that will bite you.** It is the DPoP `htu` comparison
> target. Spring compares `htu` with a bare `String.equals` against a URL Tomcat assembles from the
> reverse proxy's forwarded headers — so if nginx-proxy-manager omits `X-Forwarded-Port`, the server
> expects `…:11262/v1/…` while the extractor signed the public URL, and **every** send fails with
> `invalid_dpop_proof`. Setting this pins the origin to a value that is identical everywhere.
>
> Write it exactly as the extractor signs it: lower-case scheme and host, **no trailing slash**, and
> **no port** when it is the scheme default. `https://ingest.profit-base.online` — not
> `https://ingest.profit-base.online/`, not `…:443`.
>
> **The token URI must be the PUBLIC Keycloak host.** The ingest gateway shares no application
> network with the `keycloak` container — only the monitoring plane — so the internal
> `https://keycloak:18443` is not reachable from it. Its trust set is chosen to match: it uses the
> JVM's default anchors (and keeps hostname verification) unless a `keycloak-trust` bundle is
> configured. An earlier build pinned this client to the **backend's** truststore by mistake, which
> made every grant fail the TLS handshake and surface as a bare 500 — fixed, but worth knowing if
> you ever repoint the URI.

Note the last one is on the **backend**, not the gateway: it is the only `azp` the backend will
accept an `X-Ingest-On-Behalf-Of` header from.

### 9c — Restart

Both services read these at startup:

```bash
docker compose --profile prod up -d --force-recreate ingest backend
```

### 9d — Verify, in this order

1. **The gateway can obtain its own token.** Watch for the counter to show a mint rather than a
   failure:
   `sum by (outcome) (increase(basetool_ingest_service_account_token_total[15m]))`
   A non-zero `failed` means the gateway cannot obtain its identity — check that before anything
   else, because nothing else can work while it fails. Since v1.5.34 the sender sees a named
   `GATEWAY_IDENTITY_UNAVAILABLE` 503 rather than a bare "unexpected error", and the gateway log
   names the exception class: `WebClientResponseException` is Keycloak refusing (wrong secret or
   client id), `WebClientRequestException` is not reaching it at all (wrong host, DNS, or TLS
   trust).
2. **A real send succeeds.** Run the extractor (v2.7.2 or newer) and send one blueprint export.
   Success is the pre-filled basetool page opening.
3. **The upload is attributed to the member, not the service account.** Open the staged draft in the
   browser and confirm it belongs to the member who sent it. If it belongs to nobody or to the
   service account, `IRI_INGEST_GATEWAY_CLIENT_IDS` does not match the client id from 9a.
4. **Nothing is being refused.** `basetool_on_behalf_of_refused_total{reason="not_a_gateway"}` must
   stay at zero. A non-zero value here means the same mismatch as (3), or somebody else is sending
   the header.
5. **No proof failures.** `basetool_ingest_auth_failures_total{reason="invalid_dpop_proof"}` stays
   at zero. A non-zero value is almost certainly the `htu` mismatch from 9b — compare the value you
   set against what the extractor signs.

### 9e — What an older extractor does

Nothing changes for it. The gateway keeps accepting plain unbound bearers alongside DPoP, so a
pre-2.7 client keeps working and there is no flag day. **A 2.7.0–2.7.1 client stays broken** — that
is the defect being fixed, and those installs must update.

## Rollback

- **Step 9:** unset the five values from 9b and restart. The backend stops honouring the
  on-behalf-of header and the gateway stops trying to obtain its own token — ingest writes then fail
  with a named configuration error rather than misbehaving. The Keycloak client can be left in
  place; it issues tokens nobody consumes. Note this does **not** restore sends for a 2.7.x
  extractor, which was already broken before this change.
- **Step 6:** unset `IRI_BACKEND_EXPECTED_AUDIENCES` and restart the backend — the
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


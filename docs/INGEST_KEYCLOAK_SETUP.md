# Desktop ingest — Keycloak setup runbook

> **Doc type:** Living runbook for [ADR-0018](adr/0018-desktop-ingest-gateway-device-grant.md),
> [ADR-0129](adr/0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) and
> [`docs/specs/desktop-ingest.md`](specs/desktop-ingest.md) (`REQ-INGEST-002`, `-007`, `-008`,
> `-011`, `-012`). The *decision* and the *requirements* live there; this document is the operator's
> *how*. Registered in [`docs/specs/INDEX.md`](specs/INDEX.md). Last reviewed: 2026-09-22.

**Status: implemented.** The setup this runbook describes is live: epic #639 and its issues #641
(Keycloak client + audience scope) and #642 (the gateway) closed on 2026-06-17, and #1247 (backend
audience enforcement) on 2026-08-28. What the document is for now:

1. **the record of the configured state** — [*Configured state*](#configured-state) below;
2. **the repeatable procedure for onboarding a new approved ingest client** —
   [*Onboarding a new approved client*](#onboarding-a-new-approved-client); and
3. **how a new or out-of-date realm gets the same shape** —
   [*New or out-of-date realm: run the provisioner*](#new-or-out-of-date-realm-run-the-provisioner).

Steps 1–9 further down are the original setup sequence, kept as the record of *why* each value is
what it is. They are corrected to what is deployed, not to what was first written. **Since
2026-09-22 they are no longer the procedure for building a realm:** their Keycloak half is code in
`scripts/provision-keycloak-realm.py`, and replaying them by hand is how the testing realm fell four
months behind production without anybody deciding it should.

The **prod realm dump (with secrets) is not in this repository**: only a **sanitized reference**
lives at [`docs/keycloak/realm-config.reference.json`](keycloak/realm-config.reference.json)
(secrets, SMTP and real URLs redacted — see [`docs/keycloak/README.md`](keycloak/README.md)), and
the throwaway `frontend/src/e2e/resources/realm-export.e2e.json` test artifact — do **not** copy its
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

## Configured state

Keycloak side, read from the realm export of **2026-09-09** (the reference above) and re-confirmed
row by row against production's configuration snapshot of **2026-09-22**
(`scripts/keycloak-config-snapshot.sql`), which also added the `basetool-android` row:

|         Object          |                                                                                              State                                                                                               |
|-------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `basetool-sc-extractor` | public, no secret, device grant on, direct access grants off, service accounts off, `fullScopeAllowed: false`; default scopes include `extractor-ingest` **and** `extractor-ingest-only`. Still carries the unused standard flow with `http://127.0.0.1/*` + `http://localhost/*` — **retired by owner decision 2026-09-22** (step 1), removed on the provisioner's next production apply |
| `basetool-ingest-gateway` | confidential, service account only (standard flow and direct access grants off), empty redirect/origin lists — the gateway's own identity for the hop to the backend (step 9); still carries both ingest scopes, inherited from the realm defaults at creation (hardening step 9b leaves that to its own audience needs) |
| `basetool-frontend`     | carries `extractor-ingest` (so its relayed token has `aud=basetool-backend`), **not** `extractor-ingest-only`                                                                                    |
| `basetool-android`      | carries **both** ingest scopes as defaults, inherited from the realm defaults when it was provisioned — so an app token has `aud=basetool-ingest` and `extractor-ingest-only` in `scope`, and only the gateway's `azp` allowlist (step 7c) keeps it out of ingest. **Retired by owner decision 2026-09-22** (`REQ-INGEST-011`): the app requests neither scope and never calls ingest; removed on the provisioner's next production apply. Its own `aud=basetool-backend` mapper stays and is what the backend checks |
| `extractor-ingest`      | audience mapper `aud-basetool-backend` → `basetool-backend`; `include.in.token.scope: false`; no longer a realm default scope (hardening step 9, 2026-09-09)                                     |
| `extractor-ingest-only` | audience mapper `aud-basetool-ingest` → `basetool-ingest`; `include.in.token.scope: true`; no longer a realm default scope                                                                       |
| Realm                   | `revokeRefreshToken: false` (step 4); client policies: only `krt-mobile-dpop`, scoped to `basetool-android` by its marker role — none applies to the extractor (step 8)                         |

Environment side (host `.env` → `env.d`), as last recorded — the values live only on the host and
this repository cannot see them:

|             Variable             |               Service               |                                                               State                                                                |
|----------------------------------|-------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| `IRI_BACKEND_EXPECTED_AUDIENCES` | backend                             | `basetool-backend` — enforcing since #1247 (2026-08-28); **required** since 2026-09-22 — blank refuses the prod start (APPSEC-08) |
| `IRI_INGEST_ALLOWED_CLIENT_IDS`  | ingest                              | `basetool-sc-extractor`                                                                                                            |
| `IRI_INGEST_CLIENT_AUDIT_ONLY`   | ingest                              | `false` since 2026-08-30 — the `azp` allowlist enforces                                                                            |
| `IRI_INGEST_EXPECTED_AUDIENCES`  | ingest                              | `basetool-ingest` since **2026-09-22 21:37 UTC** (host `.env` set, `env.d` re-rendered, `ingest.service` restarted; the container's environment read back as `APP_SECURITY_JWT_EXPECTED_AUDIENCES=basetool-ingest`, container healthy). **Corrected 2026-09-23:** this row said it was read on 2026-08-28 as the backend's value and was open |
| `IRI_INGEST_REQUIRED_SCOPE`, `IRI_INGEST_ALLOWED_TOOLS` | ingest       | not present in the environment read on 2026-08-28, so inert — **open** (7b, 7c)                                                    |
| `IRI_INGEST_SERVICE_ACCOUNT_*`, `IRI_INGEST_PUBLIC_BASE_URL`, `IRI_INGEST_GATEWAY_CLIENT_IDS` | ingest / backend | set — the extractor's sends go through this path since v2.7.2, and it refuses by name when a value is missing (step 9) |

The **open** row is the remaining work of the client-identity gate: read the host's values (a
read, needing no approval under the production-host rule), then set them in the order 7b → 7c.
The audience that 7a says to set last went in first, on 2026-09-22: it refuses with `401` rather
than `403` and is not softened by `AUDIT_ONLY`, so a send that fails with `401` after a realm
change points here before anywhere else.

### Applying an `.env` change on the production host

Since the rootless-Podman cutover (2026-09-22) a container does not read `/var/iri/code/.env`
directly: each unit reads its own `/var/iri/code/env.d/<service>.env`, which `deploy.sh` renders
from `.env` — but only when a new config bundle arrives. After editing `.env`, render and restart
by hand:

```bash
cd /    # sudo -u keeps the working directory, and neither deploy nor iri can enter /root
sudo -u deploy /var/iri/code/scripts/render-env-d.py \
  --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) systemctl --user restart ingest.service   # and/or backend.service
```

The renderer refuses, naming every missing variable, rather than writing a half-rendered file.

## New or out-of-date realm: run the provisioner

`scripts/provision-keycloak-realm.py` ([ADR-0202](adr/0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md),
`REQ-OPS-033`) brings a realm to the **production shape** of everything the Basetool owns: the five
clients (`basetool-frontend`, `backend-service`, `basetool-ingest-gateway`, `basetool-sc-extractor`,
`basetool-android`; `grafana` with `--grafana-origin`), both ingest scopes and their audience mappers,
every client's scope assignments, the Android client's role scope and marker role, the DPoP profile
and policy, the service-account roles, and the realm's token and session settings. The values are
production's, read on 2026-09-22 with `scripts/keycloak-config-snapshot.sql`. Steps 1–9 below stay
as the explanation of those values; they are not the procedure any more.

What to know before running it:

- **Dry run by default.** It prints every planned change and writes nothing (exit `2` when there is
  something to do, `0` when the realm is already in shape). `--apply` writes, then re-plans and fails
  unless the second plan is empty.
- **It never deletes what only the target has.** An extra client, mapper, redirect URI or scope
  assignment is listed under *only on this realm* and left alone. The exceptions are existing
  decisions: the Android client's realm-role scope is converged both ways (`REQ-SEC-035`),
  `offline_access` is withheld from it (ADR-0131), and a client the run *creates* gets exactly its
  production scope lists.
- **The frontend's client type is left alone unless you name it** (ADR-0202 amendment 2):
  `--frontend-client confidential` switches `basetool-frontend` to confidential and sets Keycloak's
  secret from `$KEYCLOAK_FRONTEND_CLIENT_SECRET` in the same update (refused without it);
  `--frontend-client public` is the rollback. Without the flag a run never changes the type, in
  either direction ([`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md)).
- **Origins are arguments.** `--public-origin https://<the environment's host>` feeds the frontend's
  and the app's redirect URIs, web origins and post-logout list; nothing production-specific is
  hard-coded.
- **It never prints a secret.** A confidential client it creates gets a secret Keycloak generates;
  the output names the Admin Console path (*Clients → the client → Credentials*) and the `.env`
  variable(s) that need it — for `basetool-ingest-gateway` all five values of
  [step 9b](#9b--five-values-in-the-prod-env).
- **The identity** is the short-lived `basetool-provisioner` of
  [`docs/keycloak/README.md`](keycloak/README.md#why-a-service-account-and-not-the-admin-user)
  (`manage-clients` + `manage-realm`). Granting service-account roles needs `manage-users`, which that
  identity deliberately lacks: the script then exits `3` and prints the roles to assign by hand in
  the Admin Console. Everything else is applied.
- **It reproduces production as it is**, marking what looks unintended `PROD-AS-IS` — except three
  entries the owner retired on 2026-09-22 (ADR-0202 amendment 1): the extractor's unused
  authorization-code flow and its loopback redirect URIs, both ingest scopes on `basetool-android`,
  and the frontend's compose-internal `http://frontend:18081` pair. Those are **removed** wherever
  the script finds them, production included on its next apply. The gateway keeps both ingest
  scopes.
- **It does not do** the realm-wide hardening (Require SSL, events, OTP, default roles —
  [`KEYCLOAK_HARDENING_RUNBOOK.md`](KEYCLOAK_HARDENING_RUNBOOK.md)), the Discord identity provider
  ([`DISCORD_KEYCLOAK_SETUP.md`](keycloak/DISCORD_KEYCLOAK_SETUP.md)), or the realm's default client
  scopes; an ingest scope that is a realm default is reported, because every client created later
  would inherit its audience.

The sequence, on the host that runs the realm (root, from `/`). Every `apply` is a write: on
production it needs the owner's per-action approval, on testing it is the owner's call.

```bash
cd /
install -d -m 0700 /root/kc-realm
# 1. copy BOTH scripts next to each other — the realm provisioner imports the mobile one
install -m 0700 provision-keycloak-realm.py provision-keycloak-mobile-client.py /root/kc-realm/
# 2. open a kcadm session: the KCCFG/kc/KCADM definitions and the truststore + credentials
#    commands of docs/keycloak/README.md, "Runbook — provisioning the mobile client", steps 1-2
# 3. the rollback basis
kc get clients -r iri                   > /root/kc-realm/clients.before.json
kc get client-scopes -r iri             > /root/kc-realm/client-scopes.before.json
kc get client-policies/profiles -r iri  > /root/kc-realm/profiles.before.json
kc get client-policies/policies -r iri  > /root/kc-realm/policies.before.json
kc get realms/iri                       > /root/kc-realm/realm.before.json
# 4. dry run, read it
python3 /root/kc-realm/provision-keycloak-realm.py --realm iri \
  --public-origin https://<the environment's host> --kcadm-command "$KCADM"
# 5. apply — a second run must then report "No changes"
python3 /root/kc-realm/provision-keycloak-realm.py --realm iri \
  --public-origin https://<the environment's host> --kcadm-command "$KCADM" --apply
# 6. clean up the session file (it holds a token and the truststore password in cleartext)
sudo -u iri podman exec keycloak rm -f "$KCCFG"
```

Then, in this order:

1. **Secrets and `.env`.** For every client the run created confidential, copy its secret from the
   Admin Console into the variables the output names. Set `IRI_BACKEND_EXPECTED_AUDIENCES=basetool-backend`
   — only now, because only now does the frontend's token carry that audience (`REQ-INGEST-008`; an
   access token issued before the apply lacks it until its 300 s lifetime runs out, so wait five
   minutes or expect one re-login). Apply the `.env` change
   ([above](#applying-an-env-change-on-the-production-host)) or let the next deploy render it.
2. **Verify the tokens**, not only the config: [step 5b](#step-5--verify-both-token-sets-carry-the-audience-gate-for-step-6)
   on a fresh frontend login.
3. **Verify the shape**: run `scripts/keycloak-config-snapshot.sql` on this host and on production
   (the recipe is in the file's header) and `diff` the two. What may still differ is listed there —
   the origins, Keycloak-version artefacts on built-ins, and what the provisioner reported as *only
   on this realm*. Any other line is drift.

**Rollback.** The five `*.before.json` files. The client-policy lists are replaced wholesale on
write, so restore them with `kc update client-policies/policies -r iri -f - < …policies.before.json`
(and the same for profiles); a client or scope the run created can be deleted by id. Detaching the
DPoP policy alone is the safe partial rollback for the Android client.

## Onboarding a new approved client

Only for client software the owner has explicitly approved (box above). The gateway's gates are
built for more than one client: the allowlist and the tool list are comma-separated, the required
scope is one shared scope, and the per-client counter labels every allowlisted id separately.

1. **Register a dedicated Keycloak client** for it — never reuse `basetool-sc-extractor`'s id.
   A desktop or other native app is **public** (no secret, which it could not keep), uses the
   **device authorization grant**, and has direct access grants, service accounts, the standard flow
   and web origins **off** — the step 1 table, with its own client id. Set `fullScopeAllowed: false`
   and map only the realm roles its members need (hardening runbook step 8 shows how).
2. **Give it the ingest scope, and nothing broader.** Assign `extractor-ingest-only` as a
   **Default** client scope — that is what puts `basetool-ingest` in its `aud` and
   `extractor-ingest-only` in its `scope`, the two things the gateway checks. It does **not** need
   `extractor-ingest` (the backend audience): since ADR-0129 its token is consumed at the gateway,
   which calls the backend under its own identity. Never give `extractor-ingest-only` to a browser
   client.
3. **Verify a real token** from the new client, decoded locally (5b): `aud` contains
   `basetool-ingest`, `scope` contains `extractor-ingest-only`, `azp` is the new id.
4. **Allowlist it on the gateway**: append the id to `IRI_INGEST_ALLOWED_CLIENT_IDS` and, when
   `IRI_INGEST_ALLOWED_TOOLS` is set, the `tool` value(s) the client writes into its payload; apply
   ([above](#applying-an-env-change-on-the-production-host)).
5. **Watch** `basetool_ingest_client_total{client_id="<new id>"}` carry its traffic and
   `basetool_ingest_client_rejected_total` stay flat. Traffic landing on `client_id="other"` means
   the `azp` is not the id you allowlisted.
6. **Record it**: the client in this document's *Configured state*, the realm reference on its next
   regeneration ([`docs/keycloak/README.md`](keycloak/README.md)), and the vault.

**Revoking** a client is step 4 in reverse — remove its id from the allowlist and apply. Tokens it
already holds stop working at once at the gateway; disabling the Keycloak client additionally stops
new ones being issued.

## What this sets up

The desktop extractor (the Basetool SC Extractor, repository `basetool-sc-extractor`) must obtain a
**minimal, per-user, audience-restricted** Keycloak token **without shipping any secret**, and that
token must be accepted by the ingest gateway (#642), which since ADR-0129 calls the backend's import
endpoints under its own identity (step 9). The original four pieces, applied in a **strict order**:

1. a new **public** client `basetool-sc-extractor` (device grant, no secret);
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
- The ability to apply an `.env` change and restart the backend and the gateway
  ([*Applying an `.env` change*](#applying-an-env-change-on-the-production-host)).

## Step 1 — New public client `basetool-sc-extractor`

Realm → Clients → Create client. Settings (Admin Console fields → the equivalent
realm-export JSON keys):

|           Console setting            |                                Value                                 |
|--------------------------------------|----------------------------------------------------------------------|
| Client ID                            | `basetool-sc-extractor`                                              |
| Client authentication                | **Off** (public client — no secret)                                  |
| Standard flow                        | **Off** — see the note below                                         |
| Direct access grants                 | **Off** (no ROPC — the desktop app must never see the password)      |
| Service accounts                     | **Off**                                                              |
| OAuth 2.0 Device Authorization Grant | **On**                                                               |
| PKCE Code Challenge Method           | *(none — not used by the device grant)*                              |
| Valid redirect URIs                  | *(empty — the device grant has no redirect)*                         |
| Web origins                          | *(empty — no browser CORS surface)*                                  |

Equivalent realm-export fragment (for reference / IaC):

```json
{
  "clientId": "basetool-sc-extractor",
  "name": "Basetool SC Extractor (desktop)",
  "enabled": true,
  "protocol": "openid-connect",
  "publicClient": true,
  "standardFlowEnabled": false,
  "directAccessGrantsEnabled": false,
  "serviceAccountsEnabled": false,
  "fullScopeAllowed": false,
  "redirectUris": [],
  "webOrigins": [],
  "attributes": {
    "oauth2.device.authorization.grant.enabled": "true"
  }
}
```

Notes:

- `publicClient: true` + no secret is correct and RFC-conform for a native app (a desktop
  binary cannot keep a secret — REQ-INGEST-002). The extractor uses **only** the device grant
  (`DeviceGrantClient` in the extractor repository), where the device code itself is the
  proof-of-possession, so it sends no PKCE; DPoP binds its tokens (step 8).
- **Correction (2026-09-22).** This step used to enable the standard flow with loopback redirect
  URIs and PKCE `S256` "for an RFC 8252 loopback auth-code fallback". That fallback was never built:
  the extractor contains no authorization-code client. The production client still carries
  `standardFlowEnabled: true` with the two loopback wildcards and no PKCE — an unused flow, recorded
  as the thirteenth finding of the
  [Keycloak hardening runbook](KEYCLOAK_HARDENING_RUNBOOK.md#step-6--basetool-frontend-require-pkce-with-s256--version-sensitive).
  **Decided 2026-09-22: off, redirect URIs removed** (owner; ADR-0202 amendment 1). The table above
  is the target, `scripts/provision-keycloak-realm.py` applies it, and production follows on the
  owner's apply.
- `fullScopeAllowed: false` keeps the token's roles to what the client's scope mappings grant, not
  every realm role the member holds — least privilege. Production carries it since 2026-09-09
  (hardening step 8); before that it was `true`.

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
as **Default**. (The deployed scope has `include.in.token.scope: false` — its name never appears in
the token's `scope` claim, which is why 7a needed a second scope for the gateway's scope check.)

Equivalent realm-export fragment:

```json
{
  "name": "extractor-ingest",
  "protocol": "openid-connect",
  "attributes": { "include.in.token.scope": "false" },
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
is a sanitized snapshot (2026-09-09), **not** live state — read it from the running realm.

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
signature / issuer / expiry validation. Apply it and restart the backend
([*Applying an `.env` change*](#applying-an-env-change-on-the-production-host)). Smoke-test: the frontend still works
(pages load, writes succeed) **and** an extractor ingest call still reaches the backend.

~~Rollback is instant and needs no release: blank the variable (or delete the line), re-render and
restart.~~ **No longer true since 2026-09-22 (APPSEC-08):** the backend refuses to start under the
`prod` profile while the variable is blank, so blanking it is an outage, not a rollback. If a token
population turns out to lack the audience, fix its mapper or scope assignment (steps 2–3); as a
stop-gap, add the audience that population does carry to the comma list.

**Done 2026-08-28** (#1247).

## Step 7 — Client-identity gate (REQ-INGEST-011)

Everything below is **inert until configured**, and each check is fail-closed once enabled. Do it in
this order; the audit-only pass is what keeps it from locking out the real extractor.

### 7a — ⚠️ First: `extractor-ingest` is shared with the frontend

**This is the trap.** Step 3 above offered two ways to give the frontend its `basetool-backend`
audience, and the realm took the shared-scope route: the `extractor-ingest` scope is a **default
scope on both** `basetool-frontend` **and** `basetool-sc-extractor`. The fix below — a second,
extractor-only scope — is in place in the deployed realm (2026-09-09 export), and the audience
variable at the end of this section is set in production since 2026-09-22 (*Configured state*).

Two consequences, and both silently defeat step 7 if ignored:

- `IRI_INGEST_REQUIRED_SCOPE=extractor-ingest` would be satisfied by a **frontend
  session token** — the scope check would not discriminate at all.
- An audience mapper added to `extractor-ingest` would stamp `aud=basetool-ingest` onto **frontend
  tokens too**, so the audience gate would pass for exactly the tokens it exists to refuse.

**Fix the scope topology before anything else.** Create a **new** client scope that only the
extractor ever gets, and put the ingest-specific mapper there:

|          Setting           |          Value          |
|----------------------------|-------------------------|
| Name                       | `extractor-ingest-only` |
| Type                       | Default                 |
| Protocol                   | `openid-connect`        |
| **Include in token scope** | **On** ⚠️ see below     |

> **⚠️ `Include in token scope` must be On — the shared scope has it Off.** Spring Security derives
> the `SCOPE_…` authority from the token's `scope` claim, and the deployed `extractor-ingest` scope
> carries `include.in.token.scope: "false"` (see
> [`realm-config.reference.json`](keycloak/realm-config.reference.json)), so its name never reaches
> the claim. With that setting the gateway's `required-scope` check would reject **every** caller,
> the real extractor included — not merely fail to discriminate. Verify the flag on the new scope
> before enabling the check.

Add an **Audience** mapper to it — name `aud-basetool-ingest`, *Included Custom Audience* =
`basetool-ingest`, *Add to access token* **On** — and assign the scope as a **Default** scope to
`basetool-sc-extractor` **only** (and to any later approved ingest client — never to a browser
client). Set its type to **None** rather than *Default* at realm level, or every client created
afterwards inherits it; hardening step 9 removed both extractor scopes from the realm defaults for
exactly that reason.

Equivalent realm-export fragment (already reflected in
[`realm-config.reference.json`](keycloak/realm-config.reference.json)):

```json
{
  "name": "extractor-ingest-only",
  "protocol": "openid-connect",
  "attributes": {
    "include.in.token.scope": "true"
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
> frontend token carries it — the check would pass for tokens this interface must refuse. Since
> 2026-09-22 the repo-lint check `ingest-audience` (job *Repository gates*) (`scripts/check-ingest-audience.py`) fails a PR
> that pairs the gateway's audience with that value in any config, env template or runbook.
>
> **Reading the result without the host** (2026-09-22): the gauge
> `basetool_ingest_gate_enforcing{gate="audience"}` is `1` once this variable holds a value and `0`
> while it is empty; `IngestAudienceGateOff` fires while it is `0`, and the gateway's startup log
> prints the whole posture on its `Client gates` line (booleans and counts only). The same gauge
> reports `azp` / `scope` / `tool`, which read `0` while `AUDIT_ONLY` is on.
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
> name), but the service's environment map — `docker-compose.yml`, generated into
> `quadlet/env.d/ingest.env.tmpl` — maps those from `IRI_*` variables. Putting an `APP_*` name in
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

Apply and restart the gateway, then watch for at least one full scrape interval:

- `basetool_ingest_client_rejected_total` must stay at **zero**. Any value means a legitimate caller
  would have been locked out — read the `reason` label before proceeding.
- `basetool_ingest_client_total{client_id="basetool-sc-extractor"}` should carry the traffic. If it
  lands on `client_id="other"` instead, the `azp` is not what the allowlist expects.

Only when both hold, set `IRI_INGEST_CLIENT_AUDIT_ONLY=false` and apply again. The
`IngestUnknownClient` alert fires on the same counter afterwards. (Audit-only went to `false` on
2026-08-30, with only the client-id allowlist configured.)

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
bound is the wanted state. Verified against production on 2026-08-17: the realm then carried zero
client profiles and zero policies. It now carries exactly one of each, `krt-mobile-dpop` /
`krt-mobile-dpop-policy` (ADR-0131), whose condition is the `dpop-refresh-only` client role that
only `basetool-android` holds — so it does not apply to the extractor, and nothing here changes.

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
> backend directly, which is why the Android app uses it (`krt-mobile-dpop`, provisioned by
> `scripts/provision-keycloak-mobile-client.py`).

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
not send until all five values are set**.

**Order matters:** create the client (9a), then set all five env values together (9b), then restart
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

### 9b — Five values in the prod `.env`

All five, together. Each is inert on its own.

```
IRI_INGEST_PUBLIC_BASE_URL=https://ingest.profit-base.online
IRI_INGEST_SERVICE_ACCOUNT_TOKEN_URI=https://profit-base.online/auth/realms/iri/protocol/openid-connect/token
IRI_INGEST_SERVICE_ACCOUNT_CLIENT_ID=basetool-ingest-gateway
IRI_INGEST_SERVICE_ACCOUNT_CLIENT_SECRET=<the secret from 9a>
IRI_INGEST_GATEWAY_CLIENT_IDS=basetool-ingest-gateway
```

> **`IRI_INGEST_PUBLIC_BASE_URL` is the one that will bite you.** It is the DPoP `htu` comparison
> target. Spring compares `htu` with a bare `String.equals` against a URL Tomcat assembles from the
> reverse proxy's forwarded headers — so if the edge proxy (`docker/edge`) omits `X-Forwarded-Port`, the server
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

Both services read these at startup — render `env.d` and restart both, per
[*Applying an `.env` change*](#applying-an-env-change-on-the-production-host):

```bash
sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) systemctl --user restart ingest.service backend.service
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

- **Step 9:** unset the five values from 9b, re-render `env.d` and restart. The backend stops honouring the
  on-behalf-of header and the gateway stops trying to obtain its own token — ingest writes then fail
  with a named configuration error rather than misbehaving. The Keycloak client can be left in
  place; it issues tokens nobody consumes. Note this does **not** restore sends for a 2.7.x
  extractor, which was already broken before this change.
- **Step 6:** ~~unset `IRI_BACKEND_EXPECTED_AUDIENCES`, re-render and restart the backend — the
  validator becomes inert~~. **Corrected 2026-09-22 (APPSEC-08):** a blank value now stops the prod
  backend from starting at all (`JwtAudienceStartupCheck`), so this is not a rollback any more. If
  anything 401s, fix the realm side (steps 2–3) or temporarily add the audience the failing tokens
  carry to the comma list.
- **Steps 1–3:** removing the `extractor-ingest` scope assignment or the `basetool-sc-extractor`
  client stops every extractor send, and removing the scope from `basetool-frontend` breaks the web
  app while step 6 is enforcing. There is no reason to roll these back short of retiring ingest.
- **Step 4:** refresh-token rotation is **off** as of 2026-06-18 (it broke the server-rendered
  frontend BFF — REQ-SEC-012 / ADR-0019 amendment #4). Re-enabling it (`Revoke Refresh Token = On`)
  restores desktop-token rotation but re-introduces the frontend session-revocation cascade, so do
  not re-enable it realm-wide without a per-client / per-realm scoping plan for the frontend.

## Security checklist (REQ-INGEST-002 / -007 / -008)

- [ ] `basetool-sc-extractor` is **public**, has **no secret**, ROPC **off**, service
  accounts **off**, web origins **empty**.
- [ ] Device grant only: standard flow off and no redirect URIs (decided 2026-09-22; production
  until the provisioner is applied there: still on, see step 1).
- [ ] `extractor-ingest-only` is on the extractor (and any later approved ingest client) only —
  never on `basetool-frontend` or another browser client, and not on `basetool-android` (decided
  2026-09-22, same caveat). The gateway carries it too, inherited at its creation.
- [ ] `aud=basetool-backend` verified on **both** the extractor token and the frontend token
  **before** the validator is enabled.
- [ ] Refresh-token rotation + reuse-detection **off** realm-wide (`"revokeRefreshToken": false`) —
  disabled 2026-06-18 because it revoked the server-rendered frontend BFF's sessions (REQ-SEC-012,
  ADR-0019 amendment #4).
- [ ] No client secret, refresh token, or user name/email is written to any config file or
  log (project-wide logging rule).

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

## Verifying the binding actually holds

`scripts/verify-dpop-binding.py` is the companion to the provisioning script, and the split between
them matters: the provisioning script asserts the client's **configuration** — the profile exists,
the executor is right, `dpop.bound.access.tokens` is false — while this one measures the realm's
**behaviour**. A correct configuration that does not produce a bound refresh token is exactly the
failure nobody would notice, because everything keeps working.

It performs a real login and four token calls, and expects: a grant with the key, a grant on
refresh with the same key, a **refusal** with no proof at all, and a **refusal** with a different
key. It also checks the split the backend depends on — `cnf.jkt` present on the refresh token,
absent on the access token, because Spring Security's bearer filter rejects a bound access token
outright.

```bash
python scripts/verify-dpop-binding.py --issuer http://127.0.0.1:18080/realms/iri --username test-member --password test-member-pw
```

Exit code 0 means all four matched. Measured on the test realm 2026-08-19:

```
ok   1. code exchange, with the key      HTTP 200  GRANTED  Bearer
     access token  cnf.jkt = <none>
     refresh token cnf.jkt = ZqFS5YCd…
ok   2. refresh, with the same key       HTTP 200  GRANTED  Bearer
ok   3. refresh, NO proof at all         HTTP 400  REFUSED  invalid_dpop_proof: DPoP proof is missing
ok   4. refresh, with a DIFFERENT key    HTTP 400  REFUSED  invalid_grant: DPoP confirmation doesn't match DPoP proof
```

Run it against a **test** realm — it needs a password and creates a session. Requires `requests`
and `cryptography`.

The issuer above assumes the test stack was started with `docker-compose.android.yml` layered on
top, which pins `KC_HOSTNAME` to `127.0.0.1`. Without it the test override sets the hostname to
`host.docker.internal`, Keycloak emits its endpoints under that name, and the login redirect
leaves for a host the script never asked for:

```bash
docker compose --env-file .env.test -f docker-compose.yml -f docker-compose.test.yml -f docker-compose.android.yml --profile dev up -d keycloak-dev
```

## Runbook — provisioning the mobile client `basetool-android`

`scripts/provision-keycloak-mobile-client.py` creates the client and the refresh-token-only DPoP
policy of [ADR-0131](../adr/0131-mobile-auth-refresh-only-dpop-binding.md) / REQ-SEC-030. Run it on
a **test realm first**; production only after that reads clean.

**Two things about kcadm on the production container that cost a procedure attempt if assumed.**
Production Keycloak serves **HTTPS only on 18443** (`--http-enabled=false`), so the usual
`http://localhost:8080` answers `Connection refused` — there is no cleartext listener anywhere. And
because the connector uses the shared **self-signed** `keystore.p12`, kcadm rejects the connection
with a PKIX path error until a truststore is configured; the keystore itself serves as one. Both
were verified against a Keycloak 26.7 started with the production command line and a throwaway
keystore (2026-08-17). `KC_HOSTNAME_STRICT=true` does **not** interfere — `https://localhost:18443`
and `https://keycloak:18443` both authenticate.

```bash
# 1. trust the self-signed connector cert. `--trustpass -` prompts, which needs the TTY that
#    `-it` provides; without one kcadm refuses with "Console is not active". The password is
#    KC_HTTPS_KEY_STORE_PASSWORD from the deployment env.
docker exec -it keycloak /opt/keycloak/bin/kcadm.sh config truststore \
    --trustpass - /run/secrets/keystore.p12

# 2. authenticate as the provisioning service account (see the section below — an admin account
#    with OTP cannot authenticate here at all). This must come BEFORE any read: kcadm refuses
#    every command without a stored credential, and says "No server specified. Use --server, or
#    'kcadm.sh config credentials'." rather than anything about being unauthenticated.
#    Omitting --secret makes it prompt, keeping the secret out of shell history.
docker exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
    --server https://localhost:18443 --realm iri --client basetool-provisioner

# 3. save the current lists — this is the rollback basis, and both are expected to be empty
docker exec keycloak /opt/keycloak/bin/kcadm.sh get client-policies/profiles -r iri \
    > kc-profiles.before.json
docker exec keycloak /opt/keycloak/bin/kcadm.sh get client-policies/policies -r iri \
    > kc-policies.before.json

# 4. see every payload without writing anything
scripts/provision-keycloak-mobile-client.py --realm iri --profile prod --dry-run

# 5. apply, then re-assert independently
scripts/provision-keycloak-mobile-client.py --realm iri --profile prod
scripts/provision-keycloak-mobile-client.py --realm iri --verify-only

# 6. clean up: kcadm.config stores the truststore password AND an admin refresh token in
#    cleartext (mode 0600, inside the container). Remove it when the procedure is done.
docker exec keycloak rm -f /opt/keycloak/.keycloak/kcadm.config
```

The kcadm session survives a container **restart** but not a **recreate**, so a deploy that replaces
the container clears it — which is also why step 6 costs nothing.

### Why a service account and not the admin user

**kcadm cannot log in as an admin account that has OTP enabled.** Its `config credentials` command
offers exactly three authentication modes — `--user/--password`, `--client/--secret` and
`--client/--keystore` — and none of them can carry a second factor. The direct-grant login simply
fails, and it fails as `invalid_grant` / **"Invalid user credentials"**, which reads as a wrong
password and sends you looking in the wrong place. The realm's own log is what disambiguates it:

```bash
docker logs keycloak --tail 300 2>&1 | grep LOGIN_ERROR \
    | grep -oE 'realmName="[^"]*"|error="[^"]*"' | tail -10
```

`error="invalid_user_credentials"` for an account whose password demonstrably works in the Admin
Console means the second factor, not the password. (`user_temporarily_disabled` would mean the
brute-force lockout instead — `iri` has `bruteForceProtected` on with `failureFactor: 5`, so retrying
a failing login is actively counterproductive.)

Create a short-lived provisioning identity in the Admin Console instead — **Clients → Create client**:

- Client ID `basetool-provisioner`, **Client authentication ON** (confidential).
- Authentication flow: **Service accounts roles ON**, everything else OFF — no standard flow, no
  direct access grants. It is not a login client.
- Then **Service accounts roles → Assign role → Filter by clients → `realm-management`** and assign
  **`manage-clients`** and **`manage-realm`**.

Both roles are required and neither is surplus, verified against Keycloak 26.7 in both directions:
with `manage-clients` alone the client-policy endpoints answer **403**, with `manage-realm` alone the
client endpoints answer **403**. The service account deliberately cannot edit its own role mappings
(that needs `manage-users`), so it cannot widen its own reach.

The credential is short-lived in use, too: the service-account token carries the realm's 300 s
access-token lifespan and there is no refresh token on the client-credentials grant, so a step run
after a long pause may need step 2 again.

**Remove it when done.** Disable or delete `basetool-provisioner` after the procedure; it exists to
be used for minutes, not to sit in the realm holding `manage-realm`. Re-create it the next time the
client needs an edit — which, per the frozen-client note below, is the only supported way to edit it
anyway.

Use `--profile test` on a test realm: it additionally registers the custom-scheme and loopback
redirect URIs the prod client deliberately does without.

**Expected output of a clean first run** — five steps, then the verification line. Session bounds
are written as 30 d / 180 d against the production realm; on a realm with tighter SSO settings the
script clamps and says so rather than failing.

```
[1/5] detaching 'krt-mobile-dpop-policy' so the client is editable
  policy not attached — nothing to detach
[2/5] client 'basetool-android' (prod redirect URIs)
  create clients — client created
[3/5] marker role, audience mapper, offline_access
  create clients/<uuid>/roles — marker role created
  create clients/<uuid>/protocol-mappers/models — audience mapper created
  delete clients/<uuid>/optional-client-scopes/<id> — offline_access withheld
[4/5] client profile 'krt-mobile-dpop'
  update client-policies/profiles — profile merged into 0 existing
[5/5] attaching policy 'krt-mobile-dpop-policy'
  update client-policies/policies — policy merged into 0 existing

[verify]
  the client and its refresh-only DPoP policy are in the intended state
```

**The trap you will hit later.** Once the policy is attached, Keycloak refuses *every* admin edit
to that client — including one made in the Admin Console, and including changes as harmless as the
description:

```
Invalid client metadata: DPoP token is disabled [invalid_client_metadata]
```

That message names DPoP for no apparent reason and does not mention the policy. The supported route
is detach → edit → re-attach, which is exactly what re-running the script does: it detaches first,
writes the client, and attaches again. Edit through the script rather than the console.

**Rollback.** Remove the two entries by name and, if the client itself should go, delete it. Both
client-policy endpoints replace the whole realm-global list, so read the current list first and
write it back **without** our entry — never post an empty list unless it is genuinely empty:

```bash
docker exec keycloak /opt/keycloak/bin/kcadm.sh get client-policies/policies -r iri   # keep a copy
docker exec keycloak /opt/keycloak/bin/kcadm.sh get client-policies/profiles -r iri   # keep a copy
# edit both copies to drop krt-mobile-dpop-policy / krt-mobile-dpop, then:
docker exec -i keycloak /opt/keycloak/bin/kcadm.sh update client-policies/policies -r iri -f - < policies.json
docker exec -i keycloak /opt/keycloak/bin/kcadm.sh update client-policies/profiles -r iri -f - < profiles.json
docker exec keycloak /opt/keycloak/bin/kcadm.sh delete clients/<uuid> -r iri          # only if removing the client
```

Detaching the policy alone is the safe partial rollback: the client keeps working and simply stops
having its refresh token bound.

## Open findings (hardening, tracked separately)

- **`fullScopeAllowed: true`** on `basetool-frontend` and `basetool-sc-extractor` grants the full
  realm role set into tokens rather than a least-privilege subset. `INGEST_KEYCLOAK_SETUP.md`
  step 1 specifies `fullScopeAllowed: false` for the extractor; prod currently has it `true`.

## Resolved

- **ROPC disabled on `basetool-frontend` (2026-06-18).** `directAccessGrantsEnabled` is now `false`
  on the public frontend client (it used the browser authorization-code flow anyway), so the
  password can never traverse a direct-access (resource-owner-password) grant. The e2e test realm
  (`realm-export.e2e.json`) deliberately keeps it `true` for its ROPC test logins.


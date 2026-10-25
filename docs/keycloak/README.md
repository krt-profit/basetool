# Keycloak realm configuration

This directory documents the production Keycloak realm (`basetool`) that backs the Profit Basetool
deployment, **in sanitized form**, so the realm's configuration is versioned and reviewable
without ever committing secrets or PII. Last reviewed: 2026-09-22.

## The realm at a glance

As of the 2026-09-09 snapshot (the version moved to Keycloak 26.8 on 2026-10-01; the realm values
below were not re-read). Keycloak 26.8 (`quay.io/keycloak/keycloak:26.8`, pinned by digest),
serving under `/auth` on the web origin since ADR-0166; in production it runs as the Quadlet unit
`quadlet/systemd/keycloak.container` (rootless Podman, **read-only root filesystem**, `start`
without `--import-realm` — the Keycloak database, not `realm-export.json`, is the source of truth).

|          Client           |              Kind               |                                   Used by                                    |                                 Canonical runbook                                 |
|---------------------------|---------------------------------|------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `basetool-frontend`       | public, authorization code, PKCE `S256` required — **confidential** (client secret + PKCE) once the owner's rollout is done | the web app's login (Spring OAuth2 client)                  | [`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](../OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md) (ADR-0001: code shipped 2026-09-23, rollout pending) |
| `backend-service`         | confidential, service account   | the backend's user sync and Discord linking (Admin API)                      | [below](#backend-service-service-account-roles-admin-api)                          |
| `basetool-sc-extractor`   | public, device grant            | the desktop SC Extractor (ingest)                                            | [`INGEST_KEYCLOAK_SETUP.md`](../archive/INGEST_KEYCLOAK_SETUP.md)                          |
| `basetool-ingest-gateway` | confidential, service account   | the ingest gateway's own identity towards the backend (ADR-0129)             | [`INGEST_KEYCLOAK_SETUP.md` step 9](../archive/INGEST_KEYCLOAK_SETUP.md)                   |
| `grafana`                 | confidential, authorization code | Grafana's OAuth login                                                        | set up once by the archived [`MONITORING_ROLLOUT_RUNBOOK.md`](../archive/MONITORING_ROLLOUT_RUNBOOK.md) |

The `discord` identity provider and the membership gate come from the `keycloak-spi` module
(provider JAR delivered by `deploy.sh`, ADR-0055) — set up per
[`DISCORD_KEYCLOAK_SETUP.md`](DISCORD_KEYCLOAK_SETUP.md). The same JAR replaces the realm's login
forms with `krt-freemarker`, which adds a device login's user code to the consent page
([ADR-0228](../adr/0228-a-login-forms-provider-shows-the-device-code-on-the-consent-page.md)). It
becomes the `login` SPI's default by its `order()`, so no `KC_SPI_LOGIN__PROVIDER` is set anywhere;
Keycloak logs `KC-SERVICES0047` for it at start, as for every provider of the JAR. On a Keycloak
upgrade, re-check the classes it extends and render one device consent page (the sandbox smoke test
does). The login and account theme is
`krt-theme` from `keycloak-theme/`. Realm hardening and its per-step status:
[`KEYCLOAK_HARDENING_RUNBOOK.md`](../archive/KEYCLOAK_HARDENING_RUNBOOK.md).

Three scripts read a realm without changing it: `scripts/keycloak-realm-fingerprint.sh` prints a
secret-free identity list of the realm's database (clients, roles, flows, mappers) to `diff` two
realms after a move — on the Podman host run it as the service user with `--runtime podman`;
`scripts/keycloak-config-snapshot.sql` prints the realm's secret-free **configuration** (client flags
and attributes, redirect URIs, scope assignments, mapper configs, service-account roles, client
policies, token settings) to `diff` two **environments**; and `scripts/check-keycloak-issuer.py`
asserts that the issuer the apps validate is the one Keycloak advertises (ADR-0166).

The snapshot opens a read-only session and prints a client secret only as `present`/`absent`. Feed
it on stdin, so `$POSTGRES_USER` / `$POSTGRES_DB` expand inside the container and no credential
reaches a command line; from PowerShell, strip carriage returns from the file first (a CRLF copy
makes psql read `\pset footer off\r`):

```sh
ssh <host> 'cd / && sudo -n -u basetool podman exec -i db-keycloak sh -c "psql -qAt -U \$POSTGRES_USER -d \$POSTGRES_DB -p 15433 -f -"' \
    < scripts/keycloak-config-snapshot.sql > realm-<env>.txt
diff realm-prod.txt realm-testing.txt
```

Lines that legitimately differ between two environments: the public origin in redirect URIs, web
origins and `post.logout.redirect.uris`; `realm_client` on every client and the `basic` / `acr` /
`service_account` scope mapper configs of Keycloak's built-ins (artefacts of the Keycloak version a
realm was created under); anything the provisioner reports as *only on this realm*. Every other line
is drift.

**One script writes the whole Basetool-owned part of a realm:** `scripts/provision-keycloak-realm.py`
brings a realm to the production shape of the table above (`grafana` only with `--grafana-origin`),
dry run by default, never deleting what only the target has except the entries its ADR names (the
withheld ones, and any client-level mapper on the extractor or a third-party client), and never
printing a secret
([ADR-0202](../adr/0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md),
`REQ-OPS-033`). It also deletes, as one named retirement, the discontinued Android app's client
`basetool-android` with its `krt-mobile-dpop-policy` policy and `krt-mobile-dpop` profile wherever
they remain (ADR-0202 amendment 7, ADR-0268); every other client policy and profile is kept. It also
keeps `offline_access` in `default-roles-basetool` and mapped on the
`offline_access` client scope, so every member may hold the extractor's and the third-party
clients' offline sessions (ADR-0202 amendment 5; without the composite their device login is
refused `400 not_allowed`), and it offers the scope to no other client: `offline_access` is
withheld from `basetool-frontend`, `backend-service`, `basetool-ingest-gateway` and `grafana`, and
it is no realm default client scope. Procedure:
[`INGEST_KEYCLOAK_SETUP.md` → *New or out-of-date realm*](../archive/INGEST_KEYCLOAK_SETUP.md#new-or-out-of-date-realm-run-the-provisioner);
the kcadm session it needs on production: [below](#runbook--a-kcadm-session-on-the-production-container).

## Files

- [`realm-config.reference.json`](realm-config.reference.json) — a **sanitized reference** of the
  prod `basetool` realm. It is **not** an importable realm dump and **must not** be used to provision a
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
  > [`INGEST_KEYCLOAK_SETUP.md` step 7a](../archive/INGEST_KEYCLOAK_SETUP.md).

The throwaway **test** realm used by the Playwright e2e suite lives elsewhere, at
[`frontend/src/e2e/resources/realm-export.e2e.json`](../../frontend/src/e2e/resources/realm-export.e2e.json).
It is **generated** by [`scripts/build-sandbox-realm.py`](../../scripts/build-sandbox-realm.py) from
the shared base [`scripts/keycloak/test-realm-base.json`](../../scripts/keycloak/test-realm-base.json)
and the production provisioner (ADR-0225): production's token and session settings, login theme,
client scopes, ingest gateway client and third-party template, plus synthetic users and
`directAccessGrants` on for ROPC test logins. Change the base or the provisioner and regenerate; do
not hand-edit the output, and do not cross-contaminate it with this reference.

> **One thing the two realms deliberately agree on: the `basetool-backend` audience.** The e2e
> realm's `basetool-frontend` client carries an `aud-basetool-backend` audience mapper with the
> same config as the prod `extractor-ingest` scope's mapper — access token only, never the ID
> token — because the e2e backend runs with `app.security.jwt.expected-audiences` **enabled**
> (audit L-1, REQ-SEC-024). It sits directly on the client rather than on a client scope, as it
> did before the e2e realm was generated with client scopes; the emitted claim is identical. Changing
> the mapper here breaks every e2e test with a 401 — `E2eAudienceEnforcementParityTest` fails first
> with an explanation.

## Provenance & sanitization

`realm-config.reference.json` is **generated**, not hand-edited. Refresh it with:

```bash
# 1. Export the basetool realm from the Admin Console into a file OUTSIDE the repository
# 2. Sanitize — the script refuses to write when anything sensitive survives
python scripts/sanitize-realm-export.py RAW_EXPORT.json docs/keycloak/realm-config.reference.json
# 3. Delete the raw export
```

Automating this is the point. The previous file was a hand-curated subset from **2026-06-18**, and
by August it had drifted far enough that two security documents contradicted the code (2026-08-17
audit). A generated snapshot refreshes in seconds, so it stays true; it is also fuller than the old
curated subset — more noise in exchange for no drift.

**What the snapshot deliberately does not carry.** `DROP_SECTIONS` removes `users`, `components`,
`keys`, `authenticationFlows`, `authenticatorConfig`, `requiredActions`, `clientScopeMappings`,
`groups` and `federatedUsers`. The three after `keys` matter to a reader of
the hardening runbook: **an authentication flow built by hand in the Console is not recorded here**,
so the conditional-OTP sub-flow of WP-K2 step 11 exists in exactly one place. Keeping them would
mean establishing first that `authenticatorConfig` carries nothing sensitive — this realm's
`discord-guild-role-gate` has configuration of its own — which is a decision, not a flag flip.

**`scopeMappings` are kept since 2026-09-28** (owner decision). They name only client scopes,
clients and realm roles — which client scope or client may put which realm role into a token — and
entries of Keycloak's built-in clients are dropped like those clients. Until then the sanitizer
dropped them, so the reference could not show whether an offline token was possible at all when SC
Extractor 2.10.0's sign-in was refused `400 not_allowed`: production's `offline_access` client
scope mapped the role all along, and what it lacked was the `offline_access` composite on
`default-roles-basetool` (ADR-0202 amendment 5). The current file carries that one mapping by hand; the
next regeneration brings every entry. `scripts/sanitize-realm-export.py --selftest` (in
`repo-lint.yml`) pins what is kept and dropped.

**Current snapshot: 2026-09-09**, still a sanitized reference and still **not** importable, taken
mid-procedure during WP-K2. It therefore records an
intermediate state: steps 1 and 3–10 of the hardening runbook are applied, step 11 is not, and steps
2 and 12 are open decisions. It also contains `basetool-provisioner` — the short-lived provisioning
client the procedure creates and deletes — which is an artifact of the export's timing and **not**
part of the realm's intended shape. Regenerate once the procedure is finished and that client is
gone.

The script strips or replaces the following — **never commit any of them**:

- **Client secrets** → `__SET_AT_DEPLOY__` (the source export already masked them as `**********`).
- **SMTP credentials & address** → `smtp.example.invalid` / `noreply@example.invalid` /
  `__SMTP_USER__`. The real SMTP host, account and reply-to address are operator secrets and live
  only in the deploy environment.
- **Real public URLs** (`profit-base.online`) → `basetool.example.invalid` in all
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
here instead. The service account MUST hold **all three** `realm-management` client roles:

|     Role     |                                                                                                 Why                                                                                                 |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `view-users` | list users (`GET /users`) and read a user's federated identity (Discord back-fill).                                                                                                                 |
| `view-realm` | list realm roles (`GET /admin/realms/{realm}/roles`) and read their members (`GET /roles/{name}/users`) — the role-indexed resolution added by the 5000-account hardening (ADR-0085 / REQ-SEC-043). |
| `manage-users` | the admin-side account writes: moving a Discord link during an account merge (`POST`/`DELETE /users/{id}/federated-identity/discord`) and removing the merged-away account (REQ-SEC-026); the exchange disconnect's consent removal, session deletion and the `basetool-exchange` extension below (REQ-XCH-008). |

**The `basetool-exchange` admin extension** comes with the `keycloak-spi` jar
(`ExchangeClientSessionResource`, ADR-0226).
`DELETE /admin/realms/{realm}/basetool-exchange/users/{id}/clients/{client}/sessions` detaches one
client from every online session of a member — its refresh tokens then fail with „Session doesn't
have required client" — and revokes its offline sessions, while the member's other clients (the web
login among them) stay signed in. It checks `manage-users` over that member, the right
`backend-service` already holds and that already lets a caller end a whole session, so it needs no
new grant and the provisioner changes nothing. A Keycloak running an older SPI jar answers `404`;
the backend then logs a warning and leaves the shared sessions to the gateway's `auth_time` check.

> **Corrected 2026-09-22.** This table listed only the two `view-` roles. `KeycloakService` names
> `manage-users` for the merge writes, and production's service account holds it (configuration
> snapshot of 2026-09-22); the testing realm's did not. `scripts/provision-keycloak-realm.py` grants
> all three — or, when its identity lacks `manage-users`, prints them for the Admin Console.

**`view-realm` is easy to miss:** before role-indexing the sync read roles per user and needed only
`view-users`, so an older deployment's service account may carry `view-users` alone. With only
`view-users` the nightly sync fails closed every run — a `403` on the `GET /roles` listing, which
`KeycloakService` skips (never a wipe) but which means roles/departures stop reconciling until the
next interactive login. The backend logs this as an explicit "missing `view-realm`" ERROR.

Grant it once, via the Admin Console (**Clients → `backend-service` → Service account roles → Assign
role → filter by `realm-management` → `view-realm`**) or with `kcadm.sh`:

```bash
# Authenticate kcadm against the running Keycloak first (adjust server/admin creds), then:
kcadm.sh add-roles -r basetool \
  --uusername service-account-backend-service \
  --cclientid realm-management \
  --rolename view-realm
```

## Token & session settings (source of truth for session behaviour)

The realm-level token settings reproduced verbatim in the reference are what govern login
longevity and the refresh flow. Unchanged from 2026-06-18 through the 2026-09-09 snapshot:

|                    Setting                    |   Value    |                         Meaning                          |
|-----------------------------------------------|------------|----------------------------------------------------------|
| `accessTokenLifespan`                         | 300        | 5 min                                                    |
| `revokeRefreshToken` / `refreshTokenMaxReuse` | false / 5  | rotation **off** realm-wide since 2026-06-18 — see below |
| `ssoSessionIdleTimeout`                       | 2 592 000  | **30 days**                                              |
| `ssoSessionMaxLifespan`                       | 15 552 000 | **180 days**                                             |
| `clientSessionIdleTimeout` / `…Max`           | 0 / 0      | inherit the realm SSO values                             |

These values mean **no session/idle timeout fires anywhere near 30–60 minutes** — relevant when
diagnosing forced re-logins (see [ADR-0019](../adr/0019-frontend-reauth-on-client-authorization-required.md)
and [`INGEST_KEYCLOAK_SETUP.md`](../archive/INGEST_KEYCLOAK_SETUP.md) step 4).

**Refresh-token rotation is off, and that is deliberate.** `revokeRefreshToken` was turned off
realm-wide on 2026-06-18 (REQ-SEC-012 / [ADR-0019](../adr/0019-frontend-reauth-on-client-authorization-required.md)
amendment #4): the frontend is a server-rendered BFF whose refresh token never reaches the
browser, so rotation plus reuse detection bought little there while being the direct cause of
session revocations under the BFF's unavoidable concurrent-refresh race. `refreshTokenMaxReuse`
is therefore inert — it only has meaning while rotation is on. Two consequences worth knowing:
the persisted desktop-extractor refresh token is no longer protected by reuse detection (a
recorded, reversible operator lever — see [`INGEST_KEYCLOAK_SETUP.md`](../archive/INGEST_KEYCLOAK_SETUP.md)),
and any future **public** client (native/mobile) must be sender-constrained via DPoP instead,
since RFC 9700 requires public-client refresh tokens to be either rotated or bound.

## Runbook — a kcadm session on the production container

`scripts/provision-keycloak-realm.py` and `scripts/harden-keycloak-realm.py` both drive `kcadm.sh`
inside the Keycloak container. Run them against a **test realm first**; production only after that
reads clean.

**Three things about kcadm on the production container that cost a procedure attempt if assumed.**
Production Keycloak serves **HTTPS only on 18443** (`--http-enabled=false`), so the usual
`http://localhost:8080` answers `Connection refused` — there is no cleartext listener anywhere. And
because the connector uses the shared **self-signed** `keystore.p12`, kcadm rejects the connection
with a PKIX path error until a truststore is configured; the keystore itself serves as one. Both
were verified against a Keycloak 26.7 started with the production command line and a throwaway
keystore (2026-08-17). `KC_HOSTNAME_STRICT=true` does **not** interfere — `https://localhost:18443`
and `https://keycloak:18443` both authenticate.

And since the rootless-Podman cutover (2026-09-22) the container belongs to the service user `basetool`
and runs with a **read-only root filesystem**, so kcadm cannot write its default session file
(`/opt/keycloak/.keycloak/kcadm.config`): every call goes through `sudo -u basetool podman exec` and
carries `--config` pointing at the unit's writable tmpfs. That was derived from the generated unit
and first exercised on the production host on 2026-09-25 (the owner's session for the confidential
frontend client, steps 1–2 below typed interactively); the helper and its reasoning are in
[`KEYCLOAK_HARDENING_RUNBOOK.md` § 0.4](../archive/KEYCLOAK_HARDENING_RUNBOOK.md#04-open-a-session). The
scripts are not deployed to the host (copy them over, with `keycloak_kcadm.py` and the
`keycloak/` directory beside them), and their default prefix is `docker exec`, so pass
`--kcadm-command` as below. Run everything as root, from `/`.

```bash
cd /
KCCFG=/opt/keycloak/data/tmp/kcadm.config
KCADM="sudo -u basetool podman exec -i keycloak sh -c 'exec /opt/keycloak/bin/kcadm.sh \"\$@\" --config $KCCFG' kcadm"

# 1. trust the self-signed connector cert. `--trustpass -` prompts, which needs the TTY that
#    `-it` provides; without one kcadm refuses with "Console is not active". The password is
#    KC_HTTPS_KEY_STORE_PASSWORD in the container's environment (rendered from
#    SERVER_SSL_KEY_STORE_PASSWORD in the host .env).
#    After the internal-TLS step-3 release (#2036) this path is Keycloak's OWN leaf keystore -- see
#    the note after this block.
sudo -u basetool podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config truststore \
    --trustpass - /run/secrets/keystore.p12 --config "$KCCFG"

# 2. authenticate as the provisioning service account (see the section below — an admin account
#    with OTP cannot authenticate here at all). This must come BEFORE any read: kcadm refuses
#    every command without a stored credential, and says "No server specified. Use --server, or
#    'kcadm.sh config credentials'." rather than anything about being unauthenticated.
#    Omitting --secret makes it prompt, keeping the secret out of shell history.
sudo -u basetool podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
    --server https://localhost:18443/auth --realm basetool --client basetool-provisioner --config "$KCCFG"

# 3. the script's dry run, then its apply — the full argument list is in the procedure linked
#    under "One script writes the whole Basetool-owned part of a realm" above
python3 provision-keycloak-realm.py --realm basetool --public-origin https://profit-base.online \
    --kcadm-command "$KCADM" --frontend-client confidential
python3 provision-keycloak-realm.py --realm basetool --public-origin https://profit-base.online \
    --kcadm-command "$KCADM" --frontend-client confidential --apply

# 4. clean up: kcadm.config stores the truststore password AND a token in cleartext.
sudo -u basetool podman exec keycloak rm -f "$KCCFG"
```

The session file lives on a tmpfs, and under Quadlet every restart is a recreate (`--replace`), so
any restart or deploy of keycloak clears it — which is also why step 4 costs nothing. On a local
Docker Compose stack the root filesystem is writable: plain `docker exec keycloak
/opt/keycloak/bin/kcadm.sh …` without `--config`, and the scripts' default prefix, work there.

> [!warning] Step 1's truststore after the internal-TLS step-3 release (#2036) — open question
> *(added 2026-09-25)* Until #2036's release is deployed, `/run/secrets/keystore.p12` in the
> keycloak container is the **shared** self-signed keystore, and step 1 works as written — it did on
> production on 2026-09-25. From that release on, `quadlet/systemd/keycloak.container` mounts
> `/etc/basetool/secrets/tls/keycloak.p12` at that path — Keycloak's **own leaf**, signed by the internal
> CA — and mounts **no** truststore at all (unlike backend, frontend and ingest, which get
> `/run/secrets/internal-truststore.p12`). Two consequences, neither verified yet:
>
> - Step 1 unchanged probably still works: the JDK trusts the certificate of a key entry in a
>   truststore — that is why the shared keystore worked — and kcadm talks to Keycloak itself at
>   `https://localhost:18443`, whose leaf names `localhost`. The password is the same
>   (`KC_HTTPS_KEY_STORE_PASSWORD`; the mint used it for every store).
> - If it fails with a PKIX error, give kcadm the CA-only truststore through the tmpfs the session
>   file already uses — `sudo -u basetool podman exec -i keycloak sh -c 'cat > /opt/keycloak/data/tmp/truststore.p12' < /etc/basetool/secrets/tls/truststore.p12`,
>   then `config truststore --trustpass - /opt/keycloak/data/tmp/truststore.p12` — and delete it in
>   step 4 with the session file. That is a write into the container like the session file itself.
>
> Whether the keycloak unit should mount the CA-only truststore like the three apps, so kcadm has a
> proper anchor, is an open doc/code question.
>
> *(2026-09-25, evening)* #2036's release is **v1.12.0, on production since 17:44 UTC**, and step 4
> has made the CA the only anchor there — so production is now in the untested shape above. Step 1
> as written has **not** been run against it yet; the next kcadm session is the first test. The same step appears in
> [`KEYCLOAK_HARDENING_RUNBOOK.md` § 0.4](../archive/KEYCLOAK_HARDENING_RUNBOOK.md#04-open-a-session).

### Why a service account and not the admin user

**kcadm cannot log in as an admin account that has OTP enabled.** Its `config credentials` command
offers exactly three authentication modes — `--user/--password`, `--client/--secret` and
`--client/--keystore` — and none of them can carry a second factor. The direct-grant login simply
fails, and it fails as `invalid_grant` / **"Invalid user credentials"**, which reads as a wrong
password and sends you looking in the wrong place. The realm's own log is what disambiguates it:

```bash
sudo -u basetool podman logs --tail 300 keycloak 2>&1 | grep LOGIN_ERROR \
    | grep -oE 'realmName="[^"]*"|error="[^"]*"' | tail -10
```

`error="invalid_user_credentials"` for an account whose password demonstrably works in the Admin
Console means the second factor, not the password. (`user_temporarily_disabled` would mean the
brute-force lockout instead — `basetool` has `bruteForceProtected` on with `failureFactor: 5`, so retrying
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
be used for minutes, not to sit in the realm holding `manage-realm`. Re-create it the next time a
script needs a session.

## Open findings (hardening, tracked separately)

- **`fullScopeAllowed: true` on `basetool-frontend`** grants the member's full realm role set into
  the web client's tokens. Deliberately not a hardening step: narrowing it belongs to ADR-0001's
  confidential-client migration.
- **`basetool-sc-extractor` carries an unused authorization-code flow** (`standardFlowEnabled:
  true`, loopback redirect wildcards, no PKCE) — the extractor uses only the device grant. The
  hardening runbook's thirteenth item. **Decided 2026-09-22: off, redirect URIs removed** — together
  with the frontend's `http://frontend:18081` pair (ADR-0202 amendment 1; the third entry, both
  ingest scopes on `basetool-android`, went with that client in #2674).
  `scripts/provision-keycloak-realm.py` removes both; production keeps them until it is applied
  there. `realm-config.reference.json` was hand-corrected to the target
  shape on 2026-09-23 and says so in its `_comment`; regenerate it from an export after the apply.
- **`basetool-provisioner` is in the 2026-09-09 snapshot** — an artifact of exporting mid-procedure;
  it goes when WP-K2 finishes, and the snapshot is regenerated then.

The remaining open hardening steps (2, 11, 12) are tracked in the
[hardening runbook's status table](../archive/KEYCLOAK_HARDENING_RUNBOOK.md#status).

## Resolved

- **`fullScopeAllowed: false` on `basetool-sc-extractor` (by 2026-09-09).** Hardening step 8; the
  extractor's tokens carry only its mapped realm roles.
- **ROPC disabled on `basetool-frontend` (2026-06-18).** `directAccessGrantsEnabled` is now `false`
  on the public frontend client (it used the browser authorization-code flow anyway), so the
  password can never traverse a direct-access (resource-owner-password) grant. The e2e test realm
  (`realm-export.e2e.json`) deliberately keeps it `true` for its ROPC test logins.

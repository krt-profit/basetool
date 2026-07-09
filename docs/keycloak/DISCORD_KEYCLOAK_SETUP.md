# Discord login — Keycloak & Discord setup runbook

> **Doc type:** Runbook — operator deployment steps for epic #720, Track 1.
> **Scope:** Discord **login** with a fail-closed Kartell-membership gate. **OAuth only — no bot.**
> The Discord **bot** (and the automated role-sync) belong to Track 2 and are set up separately later.
> Spec: [`docs/specs/discord-integration.md`](../specs/discord-integration.md) · decision:
> [ADR-0030](../adr/0030-discord-federation-first-login-membership-gate.md).

This is an **operator** procedure: it provisions secrets and live Keycloak config that are **never**
committed. The redacted shape of the Keycloak side is in
[`realm-config.reference.json`](realm-config.reference.json) (the `identityProviders` entry and the
`basetool-frontend` `discord_user_id` mapper).

---

## 0. What you will need

|                   Value                   |                                                              Where it comes from                                                              |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| Discord **Client ID** + **Client Secret** | the Discord application (step 1)                                                                                                              |
| Discord redirect URI                      | `https://<keycloak-host>/realms/iri/broker/discord/endpoint` (prod: `https://keycloak.profit-base.online/realms/iri/broker/discord/endpoint`) |
| **Guild ID** of the das-kartell server    | Discord client → Developer Mode → right-click the server → *Copy Server ID*                                                                   |
| **KRT-Mitglied role ID**                  | Server Settings → Roles → right-click *KRT-Mitglied* → *Copy Role ID* (numeric, **not** the name)                                             |

Enable **Developer Mode** in Discord (User Settings → Advanced) to get the *Copy ID* options.

---

## 1. Create the Discord application (OAuth2 only)

1. <https://discord.com/developers/applications> → **New Application** → name it (e.g. "Basetool Login").
2. **OAuth2** tab → copy the **Client ID** and **Client Secret** (reset the secret to reveal it).
3. **OAuth2 → Redirects** → add exactly the Keycloak broker endpoint from the table above. Save.
4. No bot, no privileged intents, no scopes selected on this screen — Keycloak requests the scopes.
   (The bot + `Server Members` intent are a **Track 2** step; do not enable them now.)

---

## 2. Build & stage the provider JAR

The Discord identity provider and the membership gate ship as one JAR from the `keycloak-spi` module.

```bash
./gradlew :keycloak-spi:build
# Output: keycloak-spi/build/libs/keycloak-spi-<version>.jar
```

Stage it into the providers volume on the Keycloak host (bind-mounted at `/opt/keycloak/providers`,
see `docker-compose.yml`):

```bash
sudo install -D -m 0644 keycloak-spi/build/libs/keycloak-spi-*.jar \
    /var/iri/code/keycloak/providers/keycloak-spi.jar
# 0644 is REQUIRED: the quay Keycloak image runs as uid 1000 and must read the JAR
# (the same world-readable lesson as the shared keystore.p12).
```

Restart Keycloak so the `start` command re-runs the provider build and discovers the JAR:

```bash
docker compose --profile prod up -d --no-deps keycloak
# Then confirm the provider loaded — "Discord" appears under Identity Providers → Add provider → Social.
```

---

## 3. Add the Discord identity provider in Keycloak

Realm `iri` → **Identity providers** → **Add provider** → **Social → Discord**.

- **Alias:** `discord` (must match — it is the `kc_idp_hint` value and the broker redirect path).
- **Client ID / Client Secret:** from step 1.
- **Default Scopes:** `identify email guilds.members.read` (the gate needs `guilds.members.read`).
- **Store Tokens:** **ON** (the gate reuses the stored Discord access token).
- **Trust Email:** optional; leave OFF unless you accept Discord's `verified` flag.
- **Hide on login page:** leave **OFF** (the default). Keycloak lists only non-hidden IdPs in the
  login page's social block, so this is what makes the **Discord** button appear on the Keycloak login
  form itself — reachable from the extractor's device-grant login and any direct login, not just the
  app-sidebar shortcut (the krt-theme renders the block; see §5).
- Save.

### 3a. Attribute importer mapper (keeps the Discord id on the Keycloak user)

On the new Discord IdP → **Mappers** → **Add mapper**:

- **Name:** `discord-id`
- **Mapper type:** *Attribute Importer* (the Discord user-attribute mapper from the SPI).
- **Social Profile JSON Field Path:** `id`
- **User Attribute Name:** `discord_user_id`
- **Sync Mode Override:** **`force`** — see the note below.

> **Set Sync Mode Override to `force`.** Under the default (`inherit` → the IdP's `import`/`legacy`)
> this importer writes the attribute **only** when Keycloak *creates* a user from the Discord
> federation — i.e. only for accounts that *register* via Discord. An existing account that links
> Discord later goes through `updateBrokeredUser`, which the JSON attribute importer honours **only**
> under `force`; otherwise the attribute (and historically the token claim) stayed empty and the
> member list showed no Discord icon for it. With `force` the attribute is refreshed on every Discord
> broker login and at account-console linking. Since ADR-0036 the **token claim no longer depends on
> this attribute** (see 3b), so this mapper is now defence-in-depth + admin-console visibility; `force`
> keeps the stored attribute honest. (Our `DiscordIdentityProvider` calls `storeUserProfileForMapper`
> on every brokered round-trip, so the `/users/@me` JSON node is present and `force` *sets* the id
> rather than clearing it.)

### 3b. Carry the link into the token (federated-identity mapper — the claim source)

The claim is sourced from the Keycloak **federated-identity link**, not the user attribute, so it is
present for accounts linked at any time and on every login method (ADR-0036). Use the SPI protocol
mapper from the provider JAR (step 2):

Realm `iri` → **Clients → `basetool-frontend` → Client scopes →
`basetool-frontend-dedicated` → Add mapper → By configuration → *Discord Federated Identity***:

- **Name:** `discord_user_id`
- **Identity provider alias:** `discord` (must match the IdP alias from step 3)
- **Token Claim Name:** `discord_user_id`
- **Add to ID token / access token / userinfo:** ON · **Claim JSON Type:** String

The backend reads this `discord_user_id` claim and persists it on `app_user` (REQ-DATA-006). Because
the mapper reads the federated link at token-issuance time, **every** login — including a pure
username/password login of a Discord-linked account — carries the claim.

> **Do not also add the legacy *User Attribute* mapper for `discord_user_id`.** Two mappers writing
> the same claim conflict. The federated-identity mapper replaces it; the attribute importer in 3a
> keeps the user attribute for visibility only, it is not mapped into the token.

### 3c. Back-fill already-linked accounts (automatic — no operator step)

The backend's scheduled user sync (`app.keycloak.sync.*`, the existing `backend-service` admin
client) also reads `GET /users/{id}/federated-identity` and persists the `discord` link onto
`app_user.discord_user_id`. This repairs accounts that linked Discord **before** the
federated-identity mapper (3b) was deployed, with no re-login — they appear with the Discord icon
after the next daily sync run (05:00). The read is incremental (ADR-0085): only accounts still
missing a local link are checked each run, so an admin who needs the icon sooner can trigger "Sync
now" on the member-management page. No extra Keycloak config is needed beyond the admin client
already used for user sync (it needs `view-users`).

> **The approval gate (REQ-SEC-017) does NOT depend on these mappers.** Every brand-new non-admin
> registration lands `PENDING` regardless of whether the `discord_user_id` claim is present — the
> decision is deliberately decoupled from Discord detection. Mappers 3a/3b drive the member-list
> Discord indicator (REQ-SEC-019) and recognising a returning Discord user, not admin approval.

### 3d. (Optional) Capture the per-guild server nickname for the approval queue

To show each pending user's **das-kartell server nickname** in the admin approval queue
(REQ-DATA-008), capture the Discord `nick` and carry it into a token claim, mirroring
`discord_user_id`. This is **optional and fail-open** — skip it and the nickname column simply stays
empty; it never affects the login or the membership gate.

1. **Provide the guild id.** The nickname comes from the guild-member call, so the identity provider
   (the SPI inside Keycloak) needs the das-kartell guild id. It is **already wired** into the
   **Keycloak** service in `docker-compose.yml` as `DISCORD_GUILD_ID: ${DISCORD_GUILD_ID:-}` (the
   `:-` makes it optional — unset disables nickname capture, fail-open), so you only set the value
   in `.env` (it is **not** a secret — any guild member can read it):

   ```dotenv
   # .env (repo root) — gitignored, never committed
   DISCORD_GUILD_ID=<das-kartell guild id>   # same value as the gate's Guild ID (step 4.3)
   ```

   > **`.env` alone is not enough for a *new* variable.** Compose uses `.env` only to interpolate
   > `${...}` references that already exist in a service's `environment:` block — it does not inject
   > `.env` keys into containers by itself. `DISCORD_GUILD_ID` is pre-wired here, so setting it in
   > `.env` suffices; a brand-new variable would also need its `environment:` reference added.

   Restart Keycloak to pick up the change.

2. **Attribute importer mapper.** On the Discord IdP → **Mappers** → **Add mapper**:

   - **Name:** `discord-guild-nickname`
   - **Mapper type:** *Attribute Importer*
   - **Social Profile JSON Field Path:** `guild_nick` *(the synthetic field the provider injects)*
   - **User Attribute Name:** `discord_guild_nickname`
   - **Sync Mode Override:** **Force** *(so the nickname refreshes on every login, not only the first)*
3. **Carry it into the token.** Realm `iri` → **Clients → `basetool-frontend` → Client scopes →
   `basetool-frontend-dedicated` → Add mapper → By configuration → User Attribute**:
   - **Name:** `discord_guild_nickname`
   - **User Attribute:** `discord_guild_nickname`
   - **Token Claim Name:** `discord_guild_nickname`
   - **Add to ID token / access token / userinfo:** ON · **Claim JSON Type:** String

The backend reads the `discord_guild_nickname` claim and persists it on `app_user` for display in the
approval queue. Discord caps a server nickname at 32 characters; the SPI and backend bound it
defensively.

---

## 4. Bind the fail-closed membership gate (the safety property)

The gate runs in a dedicated *First Broker Login* flow.

1. Realm `iri` → **Authentication → Flows** → duplicate **First broker login** →
   name it `First broker login - discord`.
2. In that copy, **Add step** → **Discord Guild + KRT-Mitglied Gate** → set it **Required**.
   Place it **before** the *Review Profile / Create User* steps so a non-member is denied before any
   user is created.
3. On that execution → **⚙ → Config**:
   - **Guild ID:** the das-kartell guild id (step 0).
   - **KRT-Mitglied role ID:** the numeric role id (step 0).
   - **Discord API base URL:** leave the default `https://discord.com/api/v10`.
4. Realm `iri` → **Identity providers → discord → Advanced settings → First Login Flow** →
   select `First broker login - discord`. Save.

The gate calls `GET /users/@me/guilds/{guildId}/member` with the user's brokered token and admits the
login **only** on HTTP 200 with `roles[]` containing the role id. It **fails closed** on any error or
ambiguity (5xx / timeout / malformed / rate-limited), distinct from a clean 404 (not in guild).

---

## 5. Verify

- A Discord account **in** das-kartell **with** KRT-Mitglied → federation completes, lands the user
  PENDING (admin approval required, T1.3), and writes `discord_user_id`.
- A Discord account **not** in the guild, or **without** KRT-Mitglied → login denied, no session.
- If the optional nickname capture (§3d) is configured, the admin approval queue shows the user's
  das-kartell server nickname; otherwise that column stays blank (the login is unaffected either way).
- Keycloak/SPI logs contain **no** Discord ids, tokens or profile payloads.
- Username/password login is unchanged.
- The Discord consent screen is shown only on the **first** authorization; subsequent logins skip it
  (the provider sends `prompt=none`, so Discord does not re-prompt once the app is authorized for
  these scopes).

> **Enforced once, at first link.** The guild + KRT-Mitglied check runs in the first-broker-login
> flow — i.e. only when the Discord account is first linked, not on every login. A member later
> kicked from the guild or stripped of KRT-Mitglied keeps access until the Track 2 role-sync lands;
> until then, revoke access by disabling/removing the user in Keycloak.

---

## 6. Secrets & rotation

- The Discord **Client Secret**, **Guild ID** and **role ID** live only as Keycloak component config
  supplied at deploy — never in git. The committed realm reference shows `__SET_AT_DEPLOY__`.
- Rotating the client secret: reset it in the Discord app, update the IdP config, no restart needed.
- Re-staging a new provider JAR (step 2) **does** need a Keycloak restart to rebuild providers.

> **Track 2 (later):** the Discord **bot** (bot token + `Server Members` privileged intent, read-only
> invite) and the automated role/unit sync are configured in a separate runbook when Track 2 ships.

---

## 7. Optional: deny a colliding first-login (account-existence precheck, REQ-SEC-022)

Stops a member who already has a Basetool account from creating a duplicate by signing in with
Discord: a brand-new Discord first-login whose Discord username, per-guild server nickname, or e-mail
already matches an existing account is denied with a localized "link your existing account instead"
page (`discordAccountAlreadyExists`). It is the **second stage** of the same first-login gate (after
the guild/role gate), runs only at first-broker-login, and is **fail-open** — leave it unconfigured
and nothing changes. It is **HTTPS only**; certificate validation is never disabled.

### 7.1 Shared secret (both services)

Set the **same** value on the backend and the Keycloak SPI:

- `KRT_DISCORD_SPI_SHARED_SECRET` — a long random string. On the backend it guards
  `POST /internal/discord/account-existence` (blank → endpoint disabled, returns 503); the SPI
  presents it in the `X-KRT-SPI-Secret` header.

### 7.2 Keycloak SPI env (keycloak container)

- `KRT_BACKEND_PRECHECK_URL` — `https://backend:11261/internal/discord/account-existence` (must be `https://`).
- `KRT_DISCORD_SPI_SHARED_SECRET` — the same value as on the backend.
- `KRT_BACKEND_TRUSTSTORE_PATH` — path inside the container to the truststore (see §7.3).
- `KRT_BACKEND_TRUSTSTORE_PASSWORD` — its password.

### 7.3 Truststore for the backend certificate

The backend serves a self-signed certificate, so the Keycloak container must trust it. Export the
backend's certificate from the shared keystore into a dedicated trust-only PKCS#12 store:

```bash
# Export the backend cert (use the alias in your keystore.p12) ...
keytool -exportcert -rfc \
  -keystore keystore.p12 -storetype PKCS12 -storepass "$SERVER_SSL_KEY_STORE_PASSWORD" \
  -alias <backend-cert-alias> -file backend.crt

# ... and import it into a trust-only store the SPI loads.
keytool -importcert -noprompt \
  -keystore backend-truststore.p12 -storetype PKCS12 -storepass "$KRT_BACKEND_TRUSTSTORE_PASSWORD" \
  -alias backend -file backend.crt
```

Mount it read-only into the keycloak service and point `KRT_BACKEND_TRUSTSTORE_PATH` at it (a compose
override; the base `docker-compose.yml` plumbs the env vars but deliberately leaves this mount to the
operator so non-users are unaffected):

```yaml
services:
  keycloak:
    volumes:
      - /var/iri/secrets/backend-truststore.p12:/opt/keycloak/conf/backend-truststore.p12:ro
```

If the truststore is missing or wrong, the HTTPS call fails the handshake and the precheck simply
fails open (the login proceeds to the normal PENDING queue). Rotate the secret by changing it on both
services; rebuild the truststore when the backend certificate is rotated.

### 7.4 Verify

- A new Discord login whose username / server nickname / e-mail matches an existing account → denied
  with the "account already exists, link instead" page; no session, no new account.
- A new, non-colliding Discord login → lands PENDING as before.
- Unset `KRT_BACKEND_PRECHECK_URL` → the precheck is skipped (fail-open), colliding logins land PENDING.
- Linking Discord to an existing account from the Account Console still works (the precheck is skipped
  for an already-authenticated session).
- Keycloak/SPI logs contain **no** candidate usernames, nicknames, e-mails or Discord ids.


# ADR-0030 — Discord federation via an owned Keycloak SPI with a fail-closed first-login membership gate

- **Status:** Accepted — Track 1 implemented. The import-attribute half of the *link claim* is **superseded by [ADR-0036](0036-discord-link-recognised-from-federated-identity.md)**. *Status corrected 2026-09-22:* it said "operator Discord + Keycloak setup pending"; the Discord identity provider is live in production — a read-only production query on 2026-09-13 counted 45 Discord-linked accounts.
- **Date:** 2026-06-20
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-SEC-016 · REQ-DATA-006 · REQ-SEC-017 · REQ-NOTIF-012 · REQ-DATA-008 · issue #720 · #721 · #723 · runbook `docs/keycloak/DISCORD_KEYCLOAK_SETUP.md`

## Context

We want members to log in with Discord, but **only** people who are actually in the DAS KARTELL
Discord (the `das-kartell` guild) **and** hold the `KRT-Mitglied` role. Login is **additive** — the
existing Keycloak username/password flow stays untouched. Three forces shape the decision:

- **Discord is not an OIDC provider.** Its token endpoint returns a bare OAuth2 `access_token`, no
  `id_token`. Keycloak's generic *OpenID Connect v1.0* identity provider therefore cannot broker it;
  Keycloak ships no built-in Discord provider either.
- **Membership must be proven, not asserted.** The guarantee "everyone who logs in is a verified
  Kartell member" is the whole point. It has to be enforced at the strongest possible boundary so a
  non-member never obtains a session in the first place.
- **No bot on the login path.** Reading a *user's own* guild membership needs only the OAuth2 scope
  `guilds.members.read` and the user's own token (`GET /users/@me/guilds/{guild}/member`). A bot
  (with the privileged `GUILD_MEMBERS` intent) is only needed for the *later* automated role-sync
  (Track 2), so the login track must not depend on it.

## Decision

We will ship an **owned Keycloak provider module** (`keycloak-spi/`, a plain library JAR compiled
to Java-21 bytecode and mounted into `/opt/keycloak/providers`) containing two cooperating
providers, and enforce membership **inside Keycloak, before any session is issued**:

1. **`DiscordIdentityProvider`** — a social OAuth2 identity provider extending Keycloak's
   `AbstractOAuth2IdentityProvider` (the same base its own GitHub/Google providers use). It runs the
   authorization-code dance against Discord, reads `GET /users/@me`, and brokers the identity keyed
   by the Discord user id. A companion `DiscordUserAttributeMapper` (an attribute importer) writes
   the Discord id into the `discord_user_id` user attribute; a `basetool-frontend` protocol mapper
   carries it into the token, and the backend persists it on `app_user` (the auto-link, REQ-DATA-006).
   Default scopes include `guilds.members.read`.

2. **`DiscordGuildRoleGateAuthenticator`** — a *First Broker Login* authenticator (added `REQUIRED`
   to a dedicated flow bound to the Discord IdP). After the OAuth exchange it calls `GET
   /users/@me/guilds/{guildId}/member` with the **brokered user access token** and admits the login
   **only** when the response is HTTP 200 **and** its `roles[]` contains the configured
   `KRT-Mitglied` role id (matched by **numeric id**, never display name). It **fails closed**: any
   5xx, timeout, network error, malformed body, or rate-limit exhaustion denies the login — a denial
   distinct from a clean HTTP 404 (not in guild). It never logs tokens, payloads or Discord ids
   (REQ-SEC-016).

On top of this, the application keeps its own **PENDING approval gate** (REQ-SEC-017): a brand-new
Discord login lands with no authorities until an admin approves. The membership gate and the
approval gate are independent layers — the first proves *Kartell membership*, the second is
*human admission control*. After approval, Basetool roles/units stay **manually** managed in Track 1
(the automated Discord-role → app-role/unit sync is Track 2).

We also capture the user's **per-guild server nickname** to give the approving admin a recognisable
in-server identity (REQ-DATA-008). Discord's `/users/@me` profile has no nickname (it is per-guild),
so `DiscordIdentityProvider` fetches the guild-member object best-effort, injects the `nick` into the
brokered profile JSON, and a standard *Attribute Importer* + protocol mapper carry it into the
`discord_guild_nickname` claim — exactly the `discord_user_id` shape. Crucially this capture is
**fail-open**: unlike the membership gate, a missing or failed nickname must never block the login,
because it is display-only and admin-only. The two postures coexist by design — *fail closed* on the
security boundary, *fail open* on cosmetic enrichment.

## Consequences

- **Membership is enforced at the strongest boundary.** A non-member or a member lacking
  `KRT-Mitglied` never receives a Keycloak session — there is no authenticated request to defend
  against downstream. The app-side approval gate is defense-in-depth, not the primary control.
- **A new build + deploy artifact.** `keycloak-spi` must be built and its JAR staged into the
  providers volume (`0644`, world-readable for the uid-1000 Keycloak image — same lesson as the
  shared keystore). The `start` command re-runs the provider build on boot, so a restart picks it up.
- **Bytecode is pinned to the Keycloak runtime JDK (21), not the repo toolchain (25).** A Java-25
  class file would throw `UnsupportedClassVersionError` at provider load. The module compiles with
  `--release 21`; this must move in lockstep with the Keycloak image's JDK.
- **The SPI is coupled to the Keycloak 26 provider API.** A major Keycloak upgrade may require
  touching the module; the compile-against-the-real-jars build catches API drift early.
- **One synchronous outbound call per first login** (to Discord, ≤10 s timeout). Fail-closed means a
  Discord outage blocks *new* Discord logins — acceptable; credential login is unaffected.
- **Two opposite failure postures, on purpose.** The membership gate fails **closed** (a Discord
  hiccup denies a new login); the optional nickname capture fails **open** (a Discord hiccup just
  omits the nickname). The nickname adds a second best-effort guild-member call per login when the
  `DISCORD_GUILD_ID` env var is set — bounded by the same timeout and swallowing every error, so it
  can never break or stall a login (REQ-DATA-008).
- **Discord secrets live as Keycloak component config supplied at deploy** (IdP client id/secret;
  gate guild id + role id) — never committed. The redacted shape is documented in the realm
  reference and the runbook.

## Alternatives considered

- **Keycloak's generic OpenID Connect v1.0 provider** — rejected: Discord issues no `id_token`, so
  the OIDC provider cannot complete brokering.
- **A third-party Discord provider JAR** (community plugin) — rejected: we prefer owned, auditable
  code with no added supply-chain surface, especially for an authentication boundary; the gate
  authenticator has to live in our module anyway.
- **Enforce membership in the application after login** (read the guild via the token in the BFF) —
  rejected: by then a Keycloak session already exists; the guarantee is strongest when no session is
  ever minted for a non-member. Enforcing in the IdP first-login flow is that boundary.
- **Use a bot to check membership at login** — rejected for Track 1: the user's own
  `guilds.members.read` token proves their membership without any bot, keeping the bot (and its
  privileged intent) entirely off the login critical path. The bot is introduced only for the
  automated role-sync in Track 2 (ADR-0031, planned).
- **Map Discord roles to app roles/units at login** — deferred to Track 2: Track 1 deliberately
  keeps Basetool roles manual so login (the membership guarantee) ships first and the role-sync
  machinery is not on its critical path.

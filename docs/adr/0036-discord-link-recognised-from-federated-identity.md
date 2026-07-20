# ADR-0036 — Discord link recognised from the Keycloak federated identity, not the import-time attribute

- **Status:** Accepted
- **Date:** 2026-06-22
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-DATA-006 · REQ-SEC-019 · supersedes the import-attribute half of [ADR-0030](0030-discord-federation-first-login-membership-gate.md) for the *link claim* · resolves discord-integration.md open decision #2 · extended by [ADR-0111](0111-admin-mediated-discord-registration-linking.md) (admin-mediated linking) · runbook `docs/keycloak/DISCORD_KEYCLOAK_SETUP.md`

## Context

The member-management page shows a Discord icon for every account that is linked to Discord
(REQ-SEC-019), driven by `app_user.discord_user_id`. That column is populated from the
`discord_user_id` token claim, which (per ADR-0030) was sourced from a Keycloak **user attribute**
written by the `DiscordUserAttributeMapper` *attribute importer*.

A reported bug: the icon shows for accounts that **registered via Discord** but not for accounts that
linked Discord **later** (an existing credential account that added a Discord federated identity).
Root cause, confirmed against the Keycloak 26 source:

- The attribute importer writes the user attribute only on the **import** path. For a brand-new
  federated user, `IdentityBrokerService.preprocessFederatedIdentity()` stamps the value onto the
  `BrokeredIdentityContext`, which Keycloak flushes onto the user when it **creates** it — so
  Discord-registered accounts get the attribute.
- For an account that already exists (`BROKER_REGISTERED_NEW_USER == false` at first-broker-login, or
  an Account-Console "Linked accounts" link), `importNewUser` is **not** called; the link goes through
  `updateFederatedIdentity → IdentityProviderMapperSyncModeDelegate.delegateUpdateBrokeredUser`, which
  invokes `updateBrokeredUser` **only under sync mode `FORCE`** (`AbstractJsonUserAttributeMapper`
  makes `updateBrokeredUserLegacy` a deliberate no-op). Under the Keycloak defaults the attribute is
  **never** written, and a pure username/password login never runs any IdP mapper at all.

So the link signal was tied to *how and when* the account was linked, not to *whether* it is linked.
This was [discord-integration.md](../specs/discord-integration.md) **open decision #2** ("link an
existing credential account to a Discord identity, or only forward via Discord login?"). We resolve
it: an existing account **may** be linked to Discord (via the Keycloak Account Console, the only
self-service path at the time; [ADR-0111](0111-admin-mediated-discord-registration-linking.md) later
added an admin-mediated path from the approval queue), and such a link **must** be recognised exactly
like a Discord registration.

## Decision

Make the **Keycloak federated-identity link** — not a derived user attribute — the source of truth
for "this account is linked to Discord". The link (`FederatedIdentityModel` for the `discord` alias)
exists for **every** linked user regardless of how or when it was created, and independently of any
mapper sync mode. Three layers, defence-in-depth:

1. **Primary — `DiscordFederatedIdentityMapper` (SPI protocol mapper).** A new OIDC protocol mapper
   in `keycloak-spi/` emits the `discord_user_id` claim from
   `session.users().getFederatedIdentity(realm, user, "discord").getUserId()` at token-issuance time.
   Because it reads the link (not an attribute) and runs whenever a token is issued, the claim is
   present on **every** login method — including a pure credential login — for **every** linked user.
   It replaces the `oidc-usermodel-attribute-mapper` as the `basetool-frontend` claim source; the
   claim name (`discord_user_id`) and the backend's persist-on-login are unchanged.

2. **Backfill — Admin-API federated-identity sync (`KeycloakService`).** The scheduled user sync also
   reads `GET /users/{id}/federated-identity`, picks the `discord` entry, and persists its id onto
   `app_user.discord_user_id` via `syncUser(KeycloakUserDto)`. This repairs **already-linked existing
   accounts with no re-login of any kind**, on the sync cadence. It only *sets* the link, never clears
   it (the fetch is best-effort and returns `null` on any failure, so clearing on `null` could wrongly
   wipe a real link). Since ADR-0085 (5000-account scaling) this read is **incremental**: it runs only
   for roster users without a local link yet — the already-linked population is skipped each run, and a
   *relink* to a different account is caught by layer 1 at the linker's next login.

3. **Defence-in-depth — attribute importer Sync Mode = `FORCE`.** The `DiscordUserAttributeMapper`
   keeps the Keycloak **user attribute** populated (useful for admin-console visibility and as a
   second source), now with Sync Mode Override = `FORCE` so a Discord re-login / account-console link
   refreshes it. It is no longer the claim source, so its previous gaps no longer cause the bug.

## Consequences

- **The icon is correct for every linked account, on every login method, with no user action**
  (layer 2 backfills the existing population; layer 1 keeps it correct going forward).
- **The approval / recognition logic (REQ-SEC-017) is unchanged.** `syncUser(Jwt)` keys "new Discord
  registration" off `created` (no `app_user` row by subject) **and** the claim. Layer 1 makes the
  claim present on logins of *existing* linked users too, but those are not `created`, so they never
  re-trigger PENDING or the admin notification. A brand-new subject can only carry a Discord link if
  it genuinely federated, so the Discord-registration path is unaffected. The username-fallback
  suppression for Discord logins also only matters when no row exists by subject.
- **The icon does not imply the guild gate (REQ-SEC-016) ran.** Account-Console linking bypasses the
  first-broker-login gate. The indicator is purely informational, admin-only, and never grants
  anything; surfacing it for account-console-linked members is more accurate, not a privilege change.
  The membership gate remains the boundary for *new Discord logins* and is untouched.
- **One `/federated-identity` Admin-API call per *unlinked* user per sync**, best-effort and swallowed
  — a transient failure yields `null` and never wipes a link or aborts the run. Since ADR-0085 the
  linked majority is skipped (incremental back-fill), so the per-run cost is bounded by the unlinked
  population, not the full roster.
- **A realm-config change is required at deploy:** swap the `basetool-frontend` `discord_user_id`
  protocol mapper to `discord-federated-identity-mapper` and set the importer mapper to `FORCE` (see
  the runbook). The new SPI mapper ships in the same provider JAR as ADR-0030's providers.

## Alternatives considered

- **Sync Mode = `FORCE` alone (config only).** Rejected as the *sole* fix: it writes the attribute
  only on a Discord broker round-trip, never on a pure credential login, and never back-fills dormant
  already-linked accounts. Kept as layer 3 only.
- **Backend Admin-API sync alone (no SPI mapper).** Rejected as the *sole* fix: it back-fills
  existing accounts but is eventually-consistent (sync cadence) and leaves the token claim wrong
  between a fresh link and the next sync. Kept as layer 2.
- **Read the federated identity in the BFF on each request instead of a Keycloak mapper.** Rejected:
  the backend is a resource server that only sees the token; reaching into the Keycloak Admin API on
  the request path would add latency and coupling. The mapper puts the fact where the backend already
  reads it — the token.


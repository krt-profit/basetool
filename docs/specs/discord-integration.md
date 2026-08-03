> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-29.
> **Owner area:** AUTH/SEC · **Related ADRs:** ADR-0030 (federation + first-login gate); ADR-0036 (Discord link recognised from the federated identity); ADR-0051 (account-existence precheck denies a colliding first-login); ADR-0111 (admin-mediated linking of a Discord registration to an existing account); role/unit sync (planned — Track 2)

# Discord integration — login, membership gate & admin approval

## Context & goal

Members should be able to log in to Basetool with their Discord account, but **only** if they are
verified DAS KARTELL members — present in the `das-kartell` Discord guild **and** holding the
`KRT-Mitglied` role. Discord login is **additive** to the existing Keycloak credential login. A
brand-new Discord user lands in a **PENDING** state with no access until an admin approves; admins
are notified. After approval, Basetool roles and org-unit memberships are assigned **manually** with
the existing tooling — the automated Discord-role → app-role/unit **sync is a separate, later track**
(Track 2) and is out of scope here.

This is epic **#720, Track 1** (issues #721–#725). The federation and the gate are implemented in an
owned Keycloak provider module (`keycloak-spi/`), per [ADR-0030](../adr/0030-discord-federation-first-login-membership-gate.md).

## Requirements

### REQ-DATA-006 — Discord account link on the user

Every Basetool user MAY carry a single Discord account id. The `app_user.discord_user_id` column
(nullable, **unique**, text) records the Discord user id (numeric snowflake). The source of truth is
the Keycloak **federated-identity link** (`discord` alias), not the import-time user attribute, so
the link is recognised for an account however and *whenever* it was linked — registered via Discord
**or** an existing credential account linked later, whether the member self-linked via the Keycloak
Account Console (ADR-0036) or an admin linked a pending registration onto it from the approval queue
(REQ-SEC-026, ADR-0111). It reaches the backend two ways, both
persisting onto this column: (1) the `discord_user_id` token claim, emitted by the SPI
`DiscordFederatedIdentityMapper` from the federated link on **every** login (so even a pure
credential login of a linked user carries it), persisted by `UserService.syncUser(Jwt)`; and (2) the
scheduled Admin-API user sync, which reads `GET /users/{id}/federated-identity` and persists the
`discord` link via `UserService.syncUser(KeycloakUserDto)` — back-filling accounts with no re-login.
Path (2) is **incremental** (5000-account scaling, ADR-0085): the sync reads the federated-identity
endpoint only for roster users who do **not** already carry a local link (`getKnownDiscordLinkedUserIds`
is the skip-set), so the already-linked majority is never re-read; a *relink* to a different Discord
account is still caught by path (1) at the linker's next login. `null` for users who never linked
Discord. Because the link is the recognition key
for a returning Discord user, it must be unique across users; Postgres treats `NULL` as distinct, so
all credential-only users coexist.

**Acceptance**

- [ ] `app_user.discord_user_id` exists: nullable, `VARCHAR(32)`, with a unique constraint (V172).
- [ ] The JPA `User` entity maps it (`@Column(name = "discord_user_id", unique = true)`), and
  `ddl-auto: validate` boots clean against the migration.
- [ ] Two distinct users cannot hold the same non-null Discord id (DB unique).
- [x] The backend persists the `discord_user_id` onto the user row whenever it sees it — from the
  token claim on any login (`syncUser(Jwt)`) and from the Admin-API federated-identity read on the
  scheduled sync (`syncUser(KeycloakUserDto)`). Both paths only **set** the link, never clear it on a
  missing value (a best-effort lookup returns `null` on failure, which must not wipe a real link).
- [x] The `discord_user_id` claim is sourced from the federated link by `DiscordFederatedIdentityMapper`,
  so it is present for accounts linked **after** creation and on **every** login method — not only for
  accounts that registered via Discord (ADR-0036, fixes the missing member-list indicator).
- [x] A Discord login is recognised **only** by the Keycloak subject / `discord_user_id`, never by
  `preferred_username`. The legacy username fallback (kept for pre-UUID credential rows) is suppressed
  for a Discord login, so a fresh Discord identity is never silently matched onto a pre-existing row —
  no account-link or privilege inheritance, and the PENDING gate (REQ-SEC-017) can never be bypassed
  that way. Surfacing the link claim on an existing user's login does not re-trigger the PENDING gate:
  the "new registration" decision keys off `created` (no row by subject), which an existing user is not.
  This subject-only rule governs how a returning Discord login is **matched onto** a row (to link or
  inherit); it does **not** preclude a username / server-nickname / e-mail comparison made purely to
  **reject** a brand-new first-login that collides with an existing account and redirect it to manual
  linking (REQ-SEC-022). That comparison never links and never inherits, so the no-silent-inheritance
  guarantee is preserved — and strengthened.

**Enforced by:** `BackendApplicationTests` (schema validate) · `UserServiceDiscordSyncTest` (subject-only recognition: a Discord login never consults `findByUsername`) · `UserServiceSyncTest` (scheduled sync back-fills the Discord link, and leaves it untouched on a `null`) · `KeycloakServiceTest` (the Admin-API sync attaches the `discord` federated id, ignores other IdPs) · `DiscordFederatedIdentityMapperTest` (claim derived from the federated link) · **Code:** `User`, `V172__add_discord_user_id_to_app_user.sql`, `UserService.syncUser`, `KeycloakService.fetchUsers`, `DiscordFederatedIdentityMapper` · **Issues:** #721, #724 · **Decision:** ADR-0036

### REQ-SEC-019 — Discord-link indicator in member management (admin-only, no raw id)

The admin member-management page (`/members`) surfaces whether each account is Discord-linked, as a
read-only column **between** the "Missions-Manager" and "Status" columns. The signal is a derived
boolean `UserDto.discordLinked` — `true` iff the user has a non-blank `discord_user_id`
(REQ-DATA-006) — computed in `UserMapper.toDto`. The **raw Discord id (snowflake) is never carried
in any DTO**; only the boolean fact of the link leaves the backend, consistent with the
never-log/never-expose-Discord-id posture of REQ-SEC-016. The page is already `@PreAuthorize(ADMIN)`
(frontend) so the indicator is admin-only; every peer/guest redaction shape that strips PII leaves
`discordLinked` `null`, so the link status never reaches non-admins through any shared-`UserDto`
path (mission participants, pickers, etc.). The visual treatment follows the monochrome-icon
design-system convention: linked → the Discord brand mark in the inherited link/text colour
(`currentColor`, like the sibling GitHub mark), not linked → a muted em-dash.

**Acceptance**

- [x] `UserDto.discordLinked` is `true` exactly when `app_user.discord_user_id` is non-null and
  non-blank, derived in `UserMapper.toDto`; the raw id is never added to any DTO.
- [x] `/members` renders a Discord column between "Missions-Manager" and "Status": a linked account
  shows the `krt-icon-discord` brand mark (neutral `currentColor`, with a localized title/aria-label),
  a non-linked account shows a muted em-dash.
- [x] The peer/guest redaction shapes (`UserController.redactToPeerShape` and the shared
  `MissionGuestRedactor.cleanupUserForGuest`, used by both the mission and finance-ledger views) set
  `discordLinked = null`, so it is not exposed to non-admin viewers.
- [x] The three message bundles (default/de/en) carry `members.discord`, `members.discord.linked`,
  `members.discord.not_linked` (umlauts `\uXXXX`-escaped in the `.properties`).

**Enforced by:** `UserMapperTest` (linked / not-linked / blank-id → `discordLinked`) · `MembersPageDiscordColumnRenderTest` (icon rendered only for the linked member + column header) · `DtoOpenApiContractTest` (frontend mirror ⊆ committed `openapi.json`) · `MessageBundleConsistencyTest` (key parity) · **Code:** `UserDto`, `UserMapper`, `members.html`, `messages*.properties`

### REQ-SEC-016 — Fail-closed guild + KRT-Mitglied membership gate

A Discord login MUST be denied (no Keycloak session issued) unless the federated Discord user is a
member of the configured guild **and** holds the configured `KRT-Mitglied` role, matched by
**numeric role id** (never display name). The check runs inside the Keycloak first-broker-login flow
(`DiscordGuildRoleGateAuthenticator`), calling `GET /users/@me/guilds/{guildId}/member` with the
brokered user access token (scope `guilds.members.read`). It **fails closed**: any ambiguity — 5xx,
timeout, network error, malformed body, or `429` after the retry budget — denies the login, distinct
from a clean `404` (not in guild). Tokens, payloads and Discord ids are **never logged**.

**Acceptance**

- [x] In-guild **and** holds `KRT-Mitglied` (HTTP 200, `roles[]` ∋ role id) ⇒ login allowed.
- [x] In-guild but missing the role ⇒ denied.
- [x] Not in guild (clean 404) ⇒ denied.
- [x] 5xx / timeout / malformed body / 429-after-retries ⇒ denied (**fail closed**).
- [x] Role is matched by numeric id; renaming the Discord role does not change the outcome.
- [ ] No token, payload or Discord id appears in any log line. _(by design — only the coarse decision is logged; proven by the T1.4 PII grep.)_
- [ ] Credential (non-Discord) login is unaffected by the gate. _(T1.4 e2e.)_
- [x] The anonymous sidebar exposes a **localized** Discord login entry point (`nav.login.discord`, all three message bundles) that brokers the login this gate guards. It carries the Discord brand mark, which inherits the link colour (`currentColor`) like the footer GitHub mark — no hard-coded blurple, per the monochrome-icon design-system convention.
- [x] The Keycloak login page itself renders configured (non-hidden) IdPs as social buttons via the
  krt-theme `login.ftl` social block, so the Discord entry point is reachable from the credential
  form, the extractor's device-grant verification page, and any direct login — not only the app
  sidebar. Requires the `discord` IdP's "Hide on login page" = OFF.

**Enforced by:** `DiscordMembershipCheckerTest` (keycloak-spi) proves the decision matrix · `MessageBundleConsistencyTest` (frontend) pins the `nav.login.discord` key across the default/de/en bundles · _(planned T1.4: login-gate e2e + log PII grep)_ · **Code:** `DiscordGuildRoleGateAuthenticator(+Factory)`, `DiscordMembershipChecker`, `fragments/sidebar.html`, `fragments/icons.html` (`krt-icon-discord`) · **Issues:** #723, #725

### REQ-SEC-022 — Deny a colliding Discord first-login & redirect to account linking (fail-open)

A **brand-new** Discord first-broker-login MUST be denied — and the user pointed at linking their
existing account — when the incoming Discord identity collides with an account that already exists:
the Discord **username** or the **per-guild server nickname** matches (case-insensitively) an
existing account's login **username** or in-app **display name**, or the Discord **e-mail** matches
an existing account's e-mail. This stops a member who already has a Basetool/credential account from
silently creating a duplicate `PENDING` registration; instead they are told to link Discord to their
existing account (Account Console → Linked accounts → Discord, ADR-0036).

The check runs as the **second stage** of the first-broker-login gate
(`DiscordGuildRoleGateAuthenticator`), **after** the fail-closed membership gate (REQ-SEC-016) admits
the user and **before** the Keycloak user is created. Because the in-app display name lives only in
the backend, the gate asks the backend over an internal **HTTPS** endpoint (`POST
/internal/discord/account-existence`, guarded by a shared secret in the `X-KRT-SPI-Secret` header)
whether a match exists; only the boolean fact crosses the wire — no account data — and the candidate
names/e-mail are **never logged** (REQ-OBS). On a confident match the gate renders the localized
`discordAccountAlreadyExists` error page and ends the flow with `ACCESS_DENIED` (no session, no
`app_user` row). It matches **only to reject** — never to link or inherit, which REQ-DATA-006 still
forbids — so it strengthens, not weakens, the anti-impersonation posture.

**Fail-open by design (the inverse of REQ-SEC-016).** This is a duplicate-account guard, not a
security boundary, so any ambiguity lets the registration proceed to the normal PENDING queue rather
than blocking a legitimate new member: the precheck is **skipped** when the feature is unconfigured
(no `KRT_BACKEND_PRECHECK_URL` / `KRT_DISCORD_SPI_SHARED_SECRET`), when the URL is not `https://`
(HTTPS only — never HTTP), or when the flow is an **account-linking** flow (an already-authenticated
session, ADR-0036, so the precheck never denies the very account being linked); and a backend error,
timeout, TLS failure, or unparseable answer is treated as "unknown" → allow. Certificate validation
is **never** disabled — the SPI trusts the backend's self-signed certificate via a configured PKCS#12
truststore, and a TLS failure simply fails open.

**Acceptance**

- [x] A new Discord first-login whose username / server nickname matches an existing account's
  username or display name, or whose e-mail matches an existing e-mail, is denied with the
  `discordAccountAlreadyExists` page; no session is issued and no `app_user` row is created.
- [x] The match is case-insensitive and the backend returns only `{ "exists": <bool> }` — never any
  account data; the candidate names/e-mail are never logged.
- [x] The precheck is skipped (fail-open allow) when unconfigured, when the URL is not HTTPS, when
  the backend answers non-200 / errors / times out, or when the answer is unparseable.
- [x] The precheck is skipped during an account-linking flow (already-authenticated session,
  ADR-0036), so linking Discord to an existing account is never denied against that account.
- [x] The SPI calls the backend over HTTPS only, trusting it via a configured PKCS#12 truststore;
  certificate validation is never disabled. A blank backend shared secret disables the endpoint (503).
- [x] A configured (non-blank) backend shared secret must be **at least 32 characters** — the endpoint
  is `permitAll` and deliberately exempt from the `/api/**` rate limiter, so the shared secret is its
  only credential and must not be a short, online-guessable value. A weaker value fails context
  startup (`DiscordSpiPrecheckProperties#isSharedSecretBlankOrStrong` `@AssertTrue`), while a blank
  value stays valid because it disables the endpoint.
- [x] The two krt-theme login bundles (de/en) carry `discordAccountAlreadyExists`.

**Enforced by:** `BackendAccountCheckerTest` (fail-open HTTP matrix) · `DiscordGuildRoleGateAuthenticatorTest` (deny-on-exists with the right message key, allow-on-not-exists, fail-open on unknown, skip on linking / unconfigured / non-HTTPS) · `DiscordAccountExistenceServiceTest` (candidate normalisation + name/e-mail split + empty-candidate short-circuit) · `DiscordAccountExistenceControllerTest` (shared-secret gate: 503 unconfigured / 401 bad / 200 exists) · `DiscordSpiPrecheckPropertiesTest` (blank secret valid, <32-char secret rejected, ≥32-char secret valid) · **Code:** `DiscordGuildRoleGateAuthenticator`, `BackendAccountChecker`, `BackendTrustSupport`, `DiscordGuildRoleGateAuthenticatorFactory`, `DiscordAccountExistenceController`, `DiscordAccountExistenceService`, `DiscordSpiPrecheckProperties`, `UserRepository#existsByLowerUsernameOrDisplayNameIn` / `#existsByLowerEmail`, krt-theme `messages_*.properties` · **Decision:** ADR-0051

### REQ-SEC-026 — Admin-mediated linking of a Discord registration to an existing account

A member who already has a Basetool account but signs in via Discord **can slip past the fail-open
collision precheck (REQ-SEC-022)** and land in the PENDING approval queue as a seemingly-new
registration — typically because their Discord **username** differs from their in-app/server name
(the reported conrad7247/MadrukSedras case). For exactly this, an admin can **link** such a pending
registration onto the existing account from the approval queue (`/admin/discord-registrations`,
ADMIN-gated) instead of approving a duplicate: the third **"Verknüpfen"** action opens a
server-searched account picker (the `remote-users` combobox over `/users/search`, so the admin finds
the account by its real name even when the Discord handle differs) and links it.

Linking moves the Discord identity onto the **surviving existing account** and disposes of the
throwaway Discord-registered account. It is orchestrated by
`UserRegistrationService.linkRegistrationToExistingAccount` as a **non-transactional orchestrator**
(Keycloak writes are external side-effects that cannot roll back with the DB, mirroring the
`OperationService.setPayoutStatus` pattern):

1. The pending row is optimistic-locked (client `version` echoed from `PendingRegistrationDto`,
   checked via `OptimisticLock.checkOptionalClient`) and must still be `PENDING` — else a `409`
   (`BusinessConflictException`). The target must be a **distinct, active** account that is **not
   already Discord-linked** — else a `409`.
2. The incoming Discord **snowflake is resolved authoritatively from Keycloak**
   (`KeycloakService.readDiscordLink`, `GET /users/{pendingId}/federated-identity`) — so linking
   works even when the `discord_user_id` claim mapper never persisted the id locally — **with a
   fallback to the persisted local `discord_user_id`** when Keycloak no longer knows the pending
   user. The fallback recovers a registration whose throwaway Keycloak user was already deleted by an
   earlier partial failure (its `app_user` row, and thus the snowflake, survives the rolled-back DB
   half); only when both are empty is there nothing to link (`409`). For the fallback to be
   reachable, `readDiscordLink` maps a **`404` (the Keycloak user no longer exists) to empty** rather
   than letting it propagate as a `500` — a deleted user has no readable link, so the local
   `discord_user_id` takes over; every other Admin-API error still propagates truthfully.
3. Keycloak write (idempotent on retry): `POST /users/{targetId}/federated-identity/discord`
   attaches the identity (a `409` for the **same** snowflake is success; a different one is a genuine
   conflict). It requires the sync service account to hold the **`manage-users`** realm-management
   role — one of the only two Keycloak **writes** the backend makes (`KeycloakService` is otherwise
   read-only).
4. The DB merge (transactional, self-proxied) deletes the throwaway `app_user` — freeing the unique
   `discord_user_id` — via the FK-safe `UserDeletionService.deleteUser` (after clearing its
   `inKeycloak` flag), then stamps the surviving account's `discord_user_id` (+ captured guild
   nickname) and writes an `ApprovalDecision.LINKED` audit row (against the surviving account, no
   PII). The `LINKED` value is whitelisted by the `chk_user_approval_event_decision` check constraint
   as of **V223** — the missing value originally rolled every link back with a `23514` violation.
5. Only **after** the DB merge commits is the throwaway Keycloak user removed (`DELETE
   /users/{pendingId}`, a `404` is success). Deleting it **last** is what makes a retry safe: a
   rolled-back DB half leaves the throwaway Keycloak user intact for a clean re-read next attempt.

REQ-SEC-022's login-time deny is **unchanged**: a confidently-colliding first-login is still denied
and redirected to self-service linking; this action resolves the registrations that the fail-open
precheck let through. Because a `PENDING` account carries **zero** authorities (REQ-SEC-017) until an
admin acts, no privilege can be inherited before the link, so the merge never widens access.

**Acceptance**

- [x] A third **link** action on the ADMIN-only approval queue links a pending Discord registration
  onto an admin-chosen existing account via a server-searched account picker (`remote-users`).
- [x] The Discord federated identity is moved onto the surviving account in Keycloak and the
  throwaway Keycloak user + `app_user` row are removed; the surviving account gains `discord_user_id`
  (+ guild nickname). The snowflake is read authoritatively from Keycloak.
- [x] The action is optimistic-locked (stale / non-`PENDING` → `409`), rejects a self / non-active /
  already-linked target (`409`), and the frontend proxy relays the backend `409` verbatim
  (`propagateBackendError`) so `krt-fetch.js` keeps its reload-vs-toast distinction.
- [x] The Keycloak writes are idempotent (409-same-snowflake / 404-on-delete treated as success);
  the identity-link precedes the DB merge and the throwaway-user **delete follows it**, so a retry
  after a DB failure re-reads the surviving throwaway user and re-applies cleanly. The snowflake
  resolves from Keycloak with a local `discord_user_id` fallback for the already-deleted-user case,
  and `readDiscordLink` maps a `404` (user gone) to empty so that fallback is actually reached
  instead of surfacing a `500`.
- [x] A `LINKED` `UserApprovalEvent` is recorded against the surviving account (no PII / free text).
  Discord registration is **not** a unified-audit area, so no `AuditEventType`/viewer-filter change.
- [ ] Operator: the sync service account is granted `manage-users` (on top of `view-users` /
  `view-realm`) before deploy, or the link action `403`s at the Keycloak write.

**Enforced by:** `UserRegistrationServiceTest` (link happy path + guards: stale version, non-PENDING,
self / non-active / already-linked target, no-Discord-identity) · `KeycloakServiceTest`
(`linkDiscordIdentity` / `deleteUser` writes + 409/404 idempotency, `readDiscordLink`) ·
`AdminDiscordRegistrationsNicknameRenderTest` (link button + account picker render, `linkAjax`
forward) · `DtoOpenApiContractTest` (frontend mirror ⊆ committed `openapi.json`) ·
`MessageBundleConsistencyTest` (key parity) · **Code:** `UserRegistrationService`,
`DiscordRegistrationAdminController`, `KeycloakService`, `UserDeletionService`, `ApprovalDecision`,
`LinkRegistrationRequest` (backend + frontend), `AdminDiscordRegistrationsPageController`,
`admin/discord-registrations.html`, `discord-registrations.js`, `messages*.properties` · **Decision:** ADR-0111

### REQ-SEC-017 — PENDING approval withholds all authorities (fail-safe default)

**Every** brand-new **non-admin** registration lands `PENDING` and is granted **no** authorities
until an admin approves — independent of whether the login arrived via Discord or credentials. The
PENDING decision is deliberately **decoupled from Discord detection**: it must not depend on the
optional `discord_user_id` claim/mapper, otherwise a misconfigured Keycloak (attribute/protocol
mapper absent) would let a federated login inherit the `ACTIVE` default and silently skip approval.
For a PENDING (or `REJECTED`) account the entire authority assembly (realm roles + permissions +
org-unit membership + cascade) is short-circuited to a single `ROLE_PENDING_APPROVAL`, and
`ROLE_GUEST` is **not** carried. Approval moves the user to `ACTIVE`; rejection keeps them denied.
Keycloak `ADMIN`-realm-role holders are auto-`ACTIVE` (bootstrap safety — the first admin can never be
locked out). Both creation paths apply the rule — the interactive login (`syncUser(Jwt)`) and the
scheduled Keycloak reconciliation (`syncUser(KeycloakUserDto)`) — so the scheduler can never
pre-create an `ACTIVE` row that a later login would inherit. Admins are **notified** for every such
new PENDING registration (REQ-NOTIF-012), keyed off the PENDING transition itself and — like the gate
— independent of the `discord_user_id` claim, from whichever path first materialises the row
(interactive or scheduled) and exactly once. After approval, roles/units are assigned manually
(Track 1) — no automated mapping.

> **Trade-off (owner-approved 2026-06-20).** Making the default fail-safe means a brand-new
> **credential** account (created directly in Keycloak) now also requires a one-time Basetool
> approval, and the scheduled sync materialises not-yet-seen Keycloak users as `PENDING` rather than
> `ACTIVE`. This is the accepted cost of closing the mapper-misconfiguration bypass; pre-existing rows
> stay `ACTIVE` (V173 backfill), so only accounts created after this change are affected.
>
> The behaviour is gated by `app.registration.require-approval` (default **`true`** = prod). It is set
> to `false` **only** in the Playwright e2e stack (`APP_REGISTRATION_REQUIRE_APPROVAL=false` in
> `docker-compose.e2e.yml`), where `BackendSeeder` provisions fixture users on the fly and an
> interactive approval step would deadlock seeding on an ephemeral DB (no V173 backfill). The approval
> lifecycle itself stays covered by backend unit tests, not e2e.

**Acceptance**

- [x] PENDING/REJECTED ⇒ only `ROLE_PENDING_APPROVAL`, even if the JWT carries realm roles; membership
  is never consulted and `ROLE_GUEST` is not carried.
- [x] Every brand-new non-admin registration ⇒ `PENDING`, whether via Discord **or** credentials, and
  regardless of whether the `discord_user_id` claim is present (mapper-independent fail-safe).
- [x] The scheduled sync (`syncUser(KeycloakUserDto)`) creates a brand-new non-admin user `PENDING`
  too; it never changes an existing user's approval state.
- [ ] First admin (Keycloak `ADMIN` realm role) is `ACTIVE` on first login. _(syncUser carve-out; T1.4 e2e.)_
- [x] The backend API is gated independently of the frontend redirect (which is UX, not the
  boundary): `PendingApprovalAccessFilter` refuses a caller whose sole authority is
  `ROLE_PENDING_APPROVAL` with a `403` (`code = PENDING_APPROVAL`) on every `/api/**` endpoint,
  exempting only the registration-status poll. Scope and exemption are decided on the **decoded**
  path via parsed `PathPattern`s: `getRequestURI()` is the raw percent-encoded URI while Spring MVC
  routes on the decoded one, so a raw `startsWith("/api/")` test waved `/%61pi/v1/missions` past the
  gate and let `RequestMappingHandlerMapping` decode and dispatch it — reaching the many writes gated
  only on `@PreAuthorize("isAuthenticated()")`. The default `StrictHttpFirewall` blocks `%2e` /
  `%2f` / `%25`, but not `%61`.
- [x] Approve ⇒ `ACTIVE` + audit row; reject ⇒ `REJECTED` + reason in the audit.
- [x] Concurrent approve ⇒ 409 (optimistic `@Version`).
- [x] Hard-deleting a since-removed (no-longer-in-Keycloak) account first cleans up its V173 approval
  audit: the subject's own audit rows are deleted, and the deciding-admin (`decided_by_id`) and the
  denormalised `app_user.approved_by_id` back-references on other rows are nulled. The three audit
  FKs carry no `ON DELETE` clause, so without this the delete fails with a `409`
  (`user_approval_event_user_id_fkey`); the approval audit of **other** users is preserved.
- [ ] Legacy rows backfilled `ACTIVE` (V173). _(schema-validated on boot; T1.4 e2e.)_
- [x] The PENDING waiting page (`pending-approval.html`) states in plain language that the tool
  cannot be used until an admin approves the account, and sets the expectation that approval is
  manual and may take 1–2 days (`pendingApproval.message` / `pendingApproval.patience` in the
  default/de/en bundles).
- [x] **An approval reaches the waiting member without any re-login.** The waiting page polls
  `GET /pending-approval/status` (exempt from the waiting-page redirect) every 20 s and forwards to
  the tool as soon as the status turns `ACTIVE`; the poll pauses while the tab is hidden and stops
  on the terminal `REJECTED`. Independently of the page being open, `BackendRoleSyncFilter` expires
  a non-terminal verdict after 15 s (REQ-SEC-013, ADR-0122), so a member who simply returns later is
  let straight in. The page therefore promises automatic forwarding (`pendingApproval.help`) instead
  of instructing a re-login, and confirms it (`pendingApproval.approved`) before redirecting.

**Enforced by:** `CustomJwtGrantedAuthoritiesConverterTest` (gate) + `UserServiceApprovalTest` (approve/reject + 409) + `UserServiceDiscordSyncTest` (new credential ⇒ PENDING, new admin ⇒ ACTIVE) + `UserServiceSyncTest` (scheduled-sync fail-safe) + `UserServiceDeleteTest` (approval-audit cleanup precedes the user delete) + `BackendRoleSyncFilterTest` (verdict expiry + poll-path exemption) + `PendingApprovalPageControllerTest` (status poll) + `PendingApprovalAccessFilterTest` (backend `/api/**` gate, incl. the percent-encoded-prefix bypass) · **Code:** `CustomJwtGrantedAuthoritiesConverter`, `PendingApprovalAccessFilter`, `UserService.deleteUser`, `UserApprovalEventRepository` / `UserRepository.clearApprovedBy` (delete-time FK cleanup), `DiscordRegistrationAdminController`, `BackendRoleSyncFilter` (waiting-page route), `PendingApprovalPageController`, `pending-approval.html`, `pending-approval.js`, `messages*.properties` (`pendingApproval.*`) · **Issues:** #724, post-approval double re-login · **ADR:** ADR-0122

### REQ-NOTIF-012 — Admins notified on new PENDING registration

When any brand-new non-admin account enters `PENDING` (awaiting approval), every admin receives
**exactly one** in-app notification (no Discord id or PII in the payload), via the existing
data-driven notification rule engine (a `ROLE` selector with `roleCode = 'ADMIN'`, mirroring
V160/V161). The trigger is the **PENDING transition itself** — deliberately **independent of the
optional `discord_user_id` claim/mapper**, exactly as the PENDING decision is (REQ-SEC-017). A missing
or misconfigured claim mapper must never silence an approval notification any more than it may let a
login skip the gate; and because there is no reliable Discord signal at first login without that
claim, the notification fires for **every** new PENDING registration regardless of source (Discord
**or** credential). Both creation paths announce it — the interactive login (`syncUser(Jwt)`) and the
scheduled Keycloak reconciliation (`syncUser(KeycloakUserDto)`) — each gated on `created`, so whichever
path first materialises the row emits the event and the other stays silent: exactly one notification,
no persisted "announced" flag, no double-fire on a scheduler-first row or a login-then-sync race. The
same after-commit trigger additionally e-mails every admin the same notice on a second channel
(REQ-NOTIF-015).

**Acceptance**

- [x] A new PENDING registration publishes a `DISCORD_REGISTRATION_PENDING` after-commit event whose
  default rule (V174) resolves to every admin via a `ROLE` selector — fired on the PENDING transition,
  **not** gated on the `discord_user_id` claim, and from **both** the interactive and the scheduled
  sync paths (each gated on `created`).
- [x] No Discord id / token / e-mail rides the event (it carries only the user id + username).
- [x] Exactly one notification per admin, end to end (the `created` gate makes the two sync paths
  mutually exclusive for a given row).

**Enforced by:** `NotificationRuleEngineIntegrationTest#discordRegistrationPendingRuleNotifiesEveryAdmin` (V174 rule → ADMIN recipient → exactly one unread row, end to end) · `UserServiceDiscordSyncTest#newPendingRegistration_notifiesAdmins_evenWithoutDiscordClaim` (fires with the claim absent) · `UserServiceSyncTest` (scheduled path fires for a new non-admin, stays silent for an admin and for an already-persisted row) · `DiscordRegistrationPendingEvent` (no PII by construction) + `V174` seed · **Code:** `UserService.syncUser(Jwt)` / `UserService.syncUser(KeycloakUserDto)`, `DiscordRegistrationPendingEvent`, `V174` · **Issues:** #724

### REQ-NOTIF-014 — User notified by e-mail on approval / rejection (reason included)

When an admin **approves** or **rejects** a pending registration, the decided user is notified **by
e-mail** — closing the loop the waiting page (REQ-SEC-017) opens. The approval mail tells them they
can now sign in; the rejection mail states they were declined **and includes the admin's free-text
reason** (a localized placeholder when none was given). Built on the reusable transactional e-mail
channel (REQ-NOTIF-013, [ADR-0064](../adr/0064-transactional-email-delivery-channel.md)): `approveUser` /
`rejectUser` publish a data-only `UserApprovalDecidedEvent` inside the deciding transaction; an
after-commit `@Async(MAIL_EXECUTOR)` listener composes a localized plain-text mail and sends it
best-effort. A rolled-back or 409-conflicting decision sends nothing; a mail failure never affects
the decision. The mail is localized in the configured default locale (`app.mail.default-locale`, no
per-user locale is stored yet). A user with no e-mail on file is silently skipped. Address, name and
reason are **never logged** (REQ-OBS).

**Acceptance**

- [x] Approving a PENDING registration publishes an approval `UserApprovalDecidedEvent`
  (`approved = true`, no reason); rejecting publishes a rejection event carrying the admin's reason
  (`UserServiceApprovalTest`).
- [x] A stale-version (409) or non-PENDING (409) decision publishes **no** decision-mail event
  (`UserServiceApprovalTest`).
- [x] The composed approval mail carries the approval subject/body; the rejection mail carries the
  rejection subject/body plus the reason (or a localized "no reason given" placeholder when blank)
  (`UserApprovalMailServiceTest`).
- [x] A recipient with no e-mail on file is skipped; the after-commit listener swallows any mail
  failure (`UserApprovalMailServiceTest`, `UserApprovalMailEventListenerTest`).
- [x] The `email.*` subject/body/greeting/sign-off/reason keys exist in all three backend bundles
  (default/de/en, umlauts `\uXXXX`-escaped) (`MessageBundleConsistencyTest`).

**Enforced by:** `UserServiceApprovalTest` (publish on decide, none on 409) · `UserApprovalMailServiceTest`
(approval/rejection composition, reason placeholder, skip-on-no-email) · `UserApprovalMailEventListenerTest`
(delegate + swallow) · `MessageBundleConsistencyTest` (key parity + umlaut escaping) · **Code:**
`UserService.approveUser`/`rejectUser`, `event/UserApprovalDecidedEvent`, `service/UserApprovalMailService`,
`service/UserApprovalMailEventListener`, `messages*.properties` (`email.*`) · **Decision:** ADR-0064 · **Issues:** #720

### REQ-NOTIF-015 — Admins notified by e-mail on new PENDING registration

Alongside the in-app admin notification of REQ-NOTIF-012, every admin **with an e-mail address on
file** also receives that "a new registration is awaiting approval" notice **by e-mail** — the same
signal on a second channel, so an admin who is not currently in the tool still learns a registration
is waiting. It reuses the transactional e-mail channel (REQ-NOTIF-013,
[ADR-0064](../adr/0064-transactional-email-delivery-channel.md)) as its **second consumer** after the
account decision mail (REQ-NOTIF-014). The trigger is the **same** after-commit
`DISCORD_REGISTRATION_PENDING` event that raises the in-app notification — fired on the PENDING
transition itself, independent of the `discord_user_id` claim, from both the interactive
(`syncUser(Jwt)`) and the scheduled (`syncUser(KeycloakUserDto)`) paths (each gated on `created`) — so
the e-mail fires exactly when the in-app notification does, once per new PENDING registration
regardless of source.

Because the event carries no PII beyond the new user's display username (REQ-NOTIF-012), the mail
service resolves the admin **recipients** itself from the DB (`UserRepository.findAllAdmins()`) and
sends **one** localized plain-text mail per admin; admins without an address are skipped. Composition
and delivery run after commit on the `@Async(MAIL_EXECUTOR)` pool, best-effort: a rolled-back
registration mails nothing, and any send failure is swallowed so it never affects the registration or
the in-app notification. Each admin is greeted by their effective name and the new registrant's
username is interpolated into the body (a name-less variant when the event carried none); the mail is
localized in the configured default locale (`app.mail.default-locale`). Recipient addresses, names
and the username are **never logged** (REQ-OBS) — only the recipient count.

**Acceptance**

- [x] A new PENDING registration sends one pending-registration mail per admin with an e-mail on
  file, resolved via `UserRepository.findAllAdmins()`; admins without an address are skipped, and with
  no mailable admin nothing is sent (`PendingRegistrationMailServiceTest`).
- [x] The mail carries the pending-registration subject and a body containing the new registrant's
  username, or the name-less body variant when the event carried no username
  (`PendingRegistrationMailServiceTest`).
- [x] Composition/sending runs off-thread after commit on `MAIL_EXECUTOR`; the after-commit listener
  swallows any mail failure so the committed registration is never affected
  (`PendingRegistrationMailEventListenerTest`).
- [x] The `email.pendingRegistration.*` subject/body/bodyNoName keys exist in all three backend
  bundles (default/de/en, umlauts `\uXXXX`-escaped) (`MessageBundleConsistencyTest`).

**Enforced by:** `PendingRegistrationMailServiceTest` (per-admin composition, skip-on-no-email,
name-less body, empty-admins no-op) · `PendingRegistrationMailEventListenerTest` (delegate + swallow) ·
`MessageBundleConsistencyTest` (key parity + umlaut escaping) · **Code:**
`service/PendingRegistrationMailService`, `service/PendingRegistrationMailEventListener`,
`event/DiscordRegistrationPendingEvent`, `repository/UserRepository#findAllAdmins`,
`messages*.properties` (`email.pendingRegistration.*`) · **Decision:** ADR-0064 · **Issues:** #720

### REQ-DATA-008 — Discord guild nickname captured at login & shown at approval (admin-only)

To let an admin recognise who a pending Discord registration actually is, Basetool captures the
name the guild **displays** for the user — the per-guild `nick` they set inside the `das-kartell`
guild, **falling back to the account's global display name (`user.global_name`) when no per-guild
nick is set** — and shows it beside the name in the admin registration-approval queue. The fallback
matters because Discord renders a member as `nick ?? global_name ?? username`: a member who never
set a server nickname would otherwise surface as a blank em-dash even though the guild clearly shows
their global name (the reported conrad7247/MadrukSedras case, where the Discord handle differs from
the recognisable server name). The `app_user.discord_guild_nickname` column (nullable,
`VARCHAR(255)`) holds it. Capture is **best-effort and fail-open**: `DiscordIdentityProvider` fetches
the guild-member object (`GET /users/@me/guilds/{guildId}/member`, guild id from the
`DISCORD_GUILD_ID` env var, scope `guilds.members.read`) via
`DiscordGuildNicknameReader.readGuildDisplayName` (nick, else `user.global_name`), injects the
resulting name into the brokered profile JSON under `guild_nick`, and a Keycloak Attribute Importer +
protocol mapper carry it into the `discord_guild_nickname` token claim (mirroring `discord_user_id`);
the backend persists it in `UserReconciliationService.syncUser`. Any failure — no nickname **and** no
global name, capture mappers absent, env var unset, Discord error/timeout — simply leaves it `null`;
it must **never** block or delay the login, in deliberate contrast to the fail-closed membership gate
(REQ-SEC-016). It refreshes on every Discord login (mapper sync mode FORCE). It is **display-only**
(grants nothing), **admin-only** (carried solely in the approval-queue `PendingRegistrationDto`,
never in any shared `UserDto`), and **never logged** (it is a name — REQ-OBS). The
first-broker-login collision precheck's nickname candidate deliberately stays **nick-only**
(`readNickname`), so broadening the display never widens that anti-duplicate name match.

**Acceptance**

- [ ] `app_user.discord_guild_nickname` exists: nullable `VARCHAR(255)` (V178); `ddl-auto: validate`
  boots clean against the migration.
- [x] The Keycloak SPI reads the guild display name best-effort and **fails open** — `nick` if set,
  else `user.global_name`; a Discord error/timeout or neither name present yields no value and never
  breaks the login. The precheck's `readNickname` view stays nick-only
  (`DiscordGuildNicknameReaderTest`).
- [x] `UserService.syncUser(Jwt)` persists a non-blank `discord_guild_nickname` claim (trimmed,
  length-bounded) and leaves the field `null` when the claim is absent (`UserServiceDiscordSyncTest`).
- [x] The admin approval queue renders the captured nickname beside the name; a registration with no
  captured nickname falls back to a muted em-dash (`AdminDiscordRegistrationsNicknameRenderTest`).
- [x] The nickname rides only the admin-only `PendingRegistrationDto` — never added to any shared
  `UserDto`, so it is never exposed to non-admins.
- [ ] Operator: the `DISCORD_GUILD_ID` env var and the two Keycloak mappers (Attribute Importer
  `guild_nick` → `discord_guild_nickname`, sync mode FORCE; the `discord_guild_nickname` protocol
  mapper) are configured per the runbook. If absent, the column stays `null` (graceful no-op).

**Enforced by:** `DiscordGuildNicknameReaderTest` (keycloak-spi fail-open matrix) · `UserServiceDiscordSyncTest` (claim persisted / absent) · `AdminDiscordRegistrationsNicknameRenderTest` (frontend column) · `DtoOpenApiContractTest` (frontend mirror ⊆ committed `openapi.json`) · `MessageBundleConsistencyTest` (key parity) · **Code:** `DiscordIdentityProvider`, `DiscordGuildNicknameReader`, `User`, `V178__add_discord_guild_nickname_to_app_user.sql`, `UserService.syncUser`, `PendingRegistrationDto` (backend + frontend), `DiscordRegistrationAdminController`, `admin/discord-registrations.html` · **Issues:** #720

## Out of scope

- **Automated Discord-role → app-role/org-unit sync** and the Discord **bot** — Track 2 (#726–#730),
  ADR-0031 (planned). Track 1 keeps Basetool roles manual.
- **Continuous membership enforcement.** The guild + KRT-Mitglied gate (REQ-SEC-016) runs **once**,
  at first-broker-login when the Discord identity is first linked. A member later removed from the
  guild or stripped of `KRT-Mitglied` keeps Basetool access until the Track 2 role-sync revokes it —
  Track 1 does no periodic re-check.
- **Discord OAuth application + Keycloak realm provisioning** (client id/secret, IdP, mappers, the
  custom first-broker-login flow, the gate config) — operator steps in
  [`DISCORD_KEYCLOAK_SETUP.md`](../keycloak/DISCORD_KEYCLOAK_SETUP.md); never committed secrets.

## Open questions

- **Baseline floor on approval** — does approval auto-grant a baseline `KRT_MEMBER`, or does the
  admin seat every role by hand? Track-1 default: by hand (epic open decision #1).
- **Existing-member migration** — ~~link an existing credential account to a Discord identity, or only
  forward via Discord login? (epic open decision #2)~~ **Resolved (ADR-0036):** an existing account
  may be linked to Discord via the Keycloak Account Console and is recognised exactly like a Discord
  registration. The link is sourced from the Keycloak federated identity (SPI claim mapper +
  Admin-API backfill), not the import-time attribute, so the member-list indicator lights up for it
  on every login method. **Extended (ADR-0111 / REQ-SEC-026):** an admin can also link a pending
  Discord registration onto the existing account directly from the approval queue — for members whose
  Discord handle differs from their in-app name, so the automatic collision check never recognised
  them and the registration reached the queue.


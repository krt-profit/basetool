# ADR-0111 — Admin-mediated linking of a Discord registration to an existing account

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-SEC-026 · REQ-DATA-006 · REQ-DATA-008 · extends [ADR-0036](0036-discord-link-recognised-from-federated-identity.md) (self-service linking → admin-mediated) · complements [ADR-0051](0051-discord-first-login-account-existence-precheck.md) (REQ-SEC-022 login-time deny, unchanged) · runbook `docs/keycloak/DISCORD_KEYCLOAK_SETUP.md`

## Context

A member who already has a Basetool account (e.g. `MadrukSedras`) signed in via Discord (handle
`conrad7247`) and appeared as a **new** pending registration in the admin approval queue. Two things
combined to let this happen:

1. The **automatic collision precheck (REQ-SEC-022) is fail-open and name-based.** It matches the
   Discord username / server nickname / e-mail against existing accounts. The Discord *username*
   (`conrad7247`) differs from the in-app / server name (`MadrukSedras`), so the username did not
   match; and the one signal that *would* have matched — the server nickname — was not captured
   because this member has no per-guild `nick` set (their server name is their global display name),
   which the capture path ignored. So the login slipped through to the PENDING queue.
2. Until now the **only** way to reconcile this was **self-service**: the member logs in with their
   legacy credentials and links Discord in the Keycloak Account Console (ADR-0036). The admin had no
   way to link on the member's behalf; approving would create a **duplicate** account.

The owner wants to link such a registration onto the existing account **himself**, from the queue.
(The nickname-capture gap is fixed separately under REQ-DATA-008 — the display now falls back to the
global name — but name mismatches remain the normal case, so a manual admin path is still needed.)

**Constraint discovered:** `KeycloakService` was strictly **read-only**. Moving a Discord federated
identity onto an existing account, and removing the throwaway Discord user, are Keycloak **writes**
that did not exist and that the frontend (a resource-server BFF) cannot make.

## Decision

Add an **admin-mediated link action** to the Discord approval queue (REQ-SEC-026). Keep the scope
deliberately narrow: the registration is already in the queue, so **REQ-SEC-022's login-time deny and
the whole precheck subsystem are left untouched** — this only adds a way to resolve registrations the
fail-open precheck let through.

- **UI.** A third **"Verknüpfen"** action beside Approve / Reject opens a modal with a
  **server-searched account picker** (the existing `remote-users` combobox over `/users/search`), so
  the admin finds the account by its real name even when the Discord handle differs. In-place
  `krtFetch`, no reload, no native dialog.
- **Backend orchestration.** `UserRegistrationService.linkRegistrationToExistingAccount` is a
  **non-transactional orchestrator** (external Keycloak writes cannot roll back with a DB
  transaction — the same shape as `OperationService.setPayoutStatus`). It optimistic-locks + PENDING-
  guards the pending row and validates the target (distinct, active, not already Discord-linked),
  resolves the incoming **snowflake authoritatively from Keycloak** (`readDiscordLink`, so it works
  even when the `discord_user_id` claim mapper never persisted the id locally) **with a fallback to
  the persisted local `discord_user_id`** (recovery when the throwaway Keycloak user was already
  deleted by an earlier partial failure — for which `readDiscordLink` maps a `404` "user not found"
  to *empty* rather than a `500`, so the local fallback is actually reached; corrected 2026-07-20,
  the initial fallback only handled the user-still-exists-but-no-link case), links the identity onto
  the target, commits the DB merge
  through a self-proxied transactional method, and **only then deletes the throwaway Keycloak user**.
  Deleting it *last* — after the DB is consistent — is what makes a retry safe: a rolled-back DB half
  leaves the throwaway Keycloak user intact so the next attempt re-reads it cleanly. (The original
  order deleted it *before* the DB merge; a DB failure then stranded the registration on an empty
  Keycloak read — corrected 2026-07-20 together with V223 below.)
- **Keycloak writes (the only writes the backend makes).** `linkDiscordIdentity`
  (`POST /users/{id}/federated-identity/discord`) and `deleteUser` (`DELETE /users/{id}`), both
  **idempotent** on retry (a `409` for the *same* snowflake and a `404` on delete are treated as
  success; a `409` for a *different* snowflake is a genuine conflict). They require the sync service
  account to gain the **`manage-users`** realm-management role (on top of `view-users`/`view-realm`).
- **DB merge.** Delete the throwaway `app_user` **first** (freeing the unique `discord_user_id`) via
  the FK-safe `UserDeletionService.deleteUser` — after clearing its `inKeycloak` flag, since its
  Keycloak user is already gone — then stamp the surviving account's `discord_user_id` (+ guild
  nickname) and write an `ApprovalDecision.LINKED` audit row against the surviving account.
- **Survivor.** The **existing account wins** (keeps its id, roles, data, history); the
  Discord-registered duplicate is removed.

## Consequences

- **Works on the current registration immediately after deploy** — it is an existing PENDING row; the
  member neither re-logs-in nor re-registers. The next Discord login brokers to the surviving
  account's subject, which is already `ACTIVE`, so there is no pending trap.
- **The backend gains a Keycloak write surface.** `KeycloakService` is no longer read-only; it makes
  exactly two writes, guarded by the `manage-users` grant. This is an **operator prerequisite**: the
  grant must be applied before deploy or the action `403`s. Documented in the runbook.
- **No security regression.** REQ-SEC-022's deny is unchanged, and a `PENDING` account carries **zero**
  authorities (REQ-SEC-017) until an admin acts — so nothing can be inherited before the link, and the
  merge never widens access. The link is a deliberate admin action, not the silent subject-only
  matching REQ-DATA-006 forbids; it is effected at the Keycloak federated-identity layer (onto the
  surviving subject), so recognition then flows through the existing REQ-DATA-006 paths.
- **DB migration V223 (corrected 2026-07-20).** The original decision claimed "no DB migration" on
  the grounds that `discord_user_id` / `discord_guild_nickname` already exist and `LINKED` is an
  `@Enumerated(STRING)` value. That overlooked the `chk_user_approval_event_decision` **check
  constraint** from V173, which whitelisted only `('APPROVED', 'REJECTED')` — so inserting the
  `LINKED` audit row failed at flush with a `23514` violation and rolled the whole link back (every
  link attempt `409`ed; the reported conrad7247/MardukSedras case). **V223** widens the whitelist to
  include `LINKED`. The collision context is still not persisted.
- **Not a unified-audit area.** Discord registration stays outside the ten audited areas (ADR-0051);
  the `LINKED` event lives in the bespoke `user_approval_event` trail, not `AuditEventType`.

## Alternatives considered

- **Replace the login-time deny (route confident collisions into the queue) + remove the precheck
  subsystem.** Considered and initially planned, but rejected as out of scope: the reported case is
  already in the queue (the precheck failed open), so the large REQ-SEC-022 rewrite was unnecessary
  churn to a binding security requirement. Kept the deny; added only the manual link.
- **Self-service (native Keycloak first-broker-login re-auth linking).** Rejected: it is hands-off,
  produces no "Freigabeantrag" the admin acts on, and does not match the owner's request to link on
  the member's behalf.
- **App-only link (move `app_user.discord_user_id` without the Keycloak write).** Rejected as broken:
  the federated identity is the source of truth (ADR-0036); without moving it the member still could
  not log in via Discord to the existing account, and the scheduled sync would undo the app-only link.
- **Auto-suggest the matching account in the picker.** Dropped for this case: the matcher keys on the
  same name signals that already failed (handle differs, nickname absent), so it would suggest
  nothing. A plain server-side search is what the admin needs.


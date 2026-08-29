# ADR-0142 — One user identifier, named `user_id`, with the Keycloak `sub` as an authentication input

- **Status:** Accepted
- **Date:** 2026-08-23
- **Deciders:** @greluc
- **Applies to:** `backend`, `frontend`, `ingest`, the Android app (`basetool-android`)

## Context

Identifying, naming and picking users is spread across four components, and it looked
inconsistent enough to ask whether the project should standardise on the Keycloak `sub` or on a
backend user id.

**It already has one identifier.** `app_user.id` is a `UUID` that is *not* generated: it is written
with the value of the token's `sub` (`UUID.fromString(jwt.getSubject())`, `UserReconciliationService`).
Backend, frontend, ingest and the app all key on that same UUID, and no component holds a second
identity of its own. The question "sub or user id" therefore has no answer, because they are the
same value.

What is genuinely inconsistent is everything *around* that value:

|       Dimension       |                                                                                         State found (2026-08-22)                                                                                         |
|-----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Column name           | 34 columns named `*_user_id` / `user_id`, 5 named `*_sub` (`notification.recipient_sub`, `notification_rule_selector.user_sub`, `personal_blueprint.owner_sub`, `personal_inventory_item.owner_sub`)     |
| Column type           | 36 `UUID`, 3 `VARCHAR(64)` (`personal_blueprint.owner_sub`, `personal_inventory_item.owner_sub`, `member_evaluation.user_id` — see the correction below)                                                 |
| Referential integrity | 39 columns carry a foreign key to `app_user(id)`; 5 do not — the four above plus `member_evaluation.user_id`. The two audit *target* columns are FK-less deliberately, so the trail outlives the account |
| API property          | `userId` on 26 schemas, `userSub` on 2                                                                                                                                                                   |
| Backend access path   | `@CurrentUserId` (15), `@CurrentUserSub` (25), `AuthenticatedSubject` (12 files), inline `jwt.getSubject()` (4)                                                                                          |
| Frontend access path  | `principal.getSubject()` (15 sites, 9 files) — and `authentication.getName()`, which is the `preferred_username`, a *name*. Three controllers carry comments warning about exactly that confusion        |
| Display name          | `username`, `displayName`, `discordGuildNickname` and the denormalised `actor_handle` snapshots, with no single resolver; 6 DTOs carry `username`, 10 carry `displayName`                                |

The FK-less five are already recorded as a defect class in
[`data-persistence.md`](../specs/data-persistence.md): nothing cascades and no retention job reaches
them, so they survive a deleted account and are silently re-adopted if the same subject returns
(V227 purges what earlier deletions leaked).

One hazard is worse than the naming. `UserReconciliationService` falls back to matching by
`preferred_username` when no row is found by id, and associates the session with that row. From
then on `app_user.id` is *not* the caller's `sub` for that member — the invariant everything else
rests on is broken silently, for one row, at login time.

## Decision

1. **The identifier is `app_user.id`, a `UUID`, and it is called `user_id` everywhere** — column,
   DTO property, path variable, annotation, MDC field, log field.
2. **The Keycloak `sub` is an authentication input, not a name for anything.** It appears in exactly
   one place per process: the seam that turns an authenticated request into a user id
   (`AuthenticatedSubject` in the backend). Nothing downstream says "sub".
3. **Every column holding a user id carries a foreign key to `app_user(id)`**, with an explicit
   `ON DELETE` clause. The two audit *target* columns keep their exemption, and it is stated in the
   migration rather than left as an absence.
4. **`app_user.id` continuing to equal the `sub` is an implementation detail, not a contract.** It
   is written once, at provisioning, and nothing may derive one from the other afterwards. That is
   what makes a later split (a generated primary key plus a unique `keycloak_sub` column) a
   one-table change instead of a 39-table one.
5. **The `preferred_username` fallback stops rebinding sessions.** A token whose `sub` matches no
   row provisions a new user or fails; it never adopts a row found by name.

> [!warning] Corrected 2026-08-28
> The inventory above originally counted `member_evaluation.user_id` as a `UUID`, leaving two
> `VARCHAR(64)` columns. It is `VARCHAR(64)` too (`V72__add_promotion_system.sql`), which V227's own
> comment had right all along — so point 3 needed **three** casts, not two. Found while implementing
> it (issue #1638); the code was the authority and the ADR is corrected here rather than quietly.

## Status of the decision

Point 3 is **implemented**: `V235__add_foreign_keys_to_user_identity_columns.sql` recasts the three
`VARCHAR(64)` columns to `UUID` and gives all five a foreign key to `app_user(id)` with
`ON DELETE CASCADE`, states the two audit-target exemptions in `COMMENT ON COLUMN`, and adds the
partial index the rule-selector column was missing. `UserIdentityColumnForeignKeyTest` holds the
line for columns added later.

Points 1 and 2 are **implemented except for the wire format** (#1640):
`V236__rename_sub_columns_to_user_id.sql` renames the four `*_sub` columns; the backend, the
frontend and the OpenAPI path variables follow; `@CurrentUserSub` is gone and `@CurrentUserId` is
the one annotation; the frontend's fifteen `principal.getSubject()` calls go through a single
`CurrentUser` helper. **What is deliberately left:** `userSub` on the two notification-rule-selector
schemas. Renaming a served property breaks the frozen external contract (`REQ-API-009`) and needs a
dual-served deprecation window with an `@ApiDeprecation` sunset, which is its own change. Until then
`NotificationRuleMapper` carries the one explicit mapping that bridges the entity's `userId` onto
the DTO's `userSub`, with a test — MapStruct matches by name, so the rename mapped it to `null`
silently and the build stayed green.

One direct `jwt.getSubject()` read stays on purpose: `UserService#getUserIdFromJwt(Jwt)`. It runs
during authentication, before a `SecurityContext` exists, so it cannot go through
`AuthenticatedSubject` — it *is* the seam point 2 asks for, on the authentication-time side.

Point 5 is still open (issue #1639).

## Consequences

**What gets better.** One name for one thing, in four components and in the wire format. The
FK-less columns stop outliving accounts. The frontend's `getName()`-versus-`getSubject()` trap loses
its second half, because there is nothing left called `sub` to confuse with a name. And the identity
provider stops being welded to the schema: if Keycloak is ever replaced, one column changes.

**What it costs.** Renaming `userSub` → `userId` on two API schemas is a **breaking change to the
frozen external contract** (`REQ-API-009`, `ExternalContractTest`) and to the Android app's generated
DTOs. It has to ship as a deprecation window — both properties served, the old one marked
`@ApiDeprecation` — not as a rename, because a shipped app cannot be asked to update in step.

**What is deliberately not decided here.** Whether `app_user` eventually gets a generated primary
key with `keycloak_sub` beside it. Point 4 keeps that door open at the price of one migration; it is
not worth doing while the values agree.

**Display names are a separate problem.** Which of `username` / `displayName` /
`discordGuildNickname` a surface shows is not an identity question and is not settled by this ADR.

## Alternatives considered

- **Standardise on the name `sub`.** Same amount of work, opposite direction, and it writes the
  current identity provider into 39 column names. The moment the provider changes, every name lies.
- **Leave it.** The values are consistent today, so nothing is broken *now*. Rejected because the
  five FK-less columns and the username fallback are how it stops being true — quietly, one row at a
  time, in the tables that hold notifications and personal inventory.


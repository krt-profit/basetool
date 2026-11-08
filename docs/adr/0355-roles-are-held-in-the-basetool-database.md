# ADR-0355 — Roles are held in the Basetool database

- **Status:** Accepted
- **Date:** 2026-11-08
- **Deciders:** @greluc
- **Related:** spec [`security-and-access.md`](../specs/security-and-access.md) (REQ-SEC-088) ·
  [`audit.md`](../specs/audit.md) (REQ-AUDIT-001) ·
  [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md) · issues #3140 (decision), #3150
  (write model), #3151 (cutover)

## Context

The four application roles (Admin, KRT Member, Bank Employee, Bank Management) are Keycloak realm
roles. The backend mirrors them from `realm_access.roles` into `user_roles` at every login and in
the daily roster sync, and nothing in the Basetool writes them: admins are told to set them in
Keycloak. A role removed in Keycloak only takes effect at the next login or sync, the bank
management cannot give Bank Employee itself, and no role change leaves an audit trail in the
Basetool.

## Decision

1. **`user_roles` becomes the source of truth for the four roles.** Keycloak keeps
   authentication, the Discord gate and the client scopes.
2. **The Basetool assigns them.** Admins assign and withdraw every role on the member page; the
   bank management assigns and withdraws Bank Employee in the bank grant management. Both surfaces
   update in place.
3. **A user's role set has its own optimistic counter**, `app_user.roles_version`, bumped by one
   conditional `UPDATE` and independent of the row `@Version`; the `roles` collection is excluded
   from the row's optimistic lock. A role change therefore never collides with an edit of the user
   record, and a stale role set answers `409`.
4. **The last active admin keeps Admin** (`409 LAST_ADMIN`). The Admin role row is locked for the
   check, so concurrent withdrawals are serialised.
5. **Withdrawing KRT Member is a departure:** it publishes `MemberDepartedEvent`, so the exchange
   revocations follow from the database write.
6. **Emergency access:** `app.bootstrap.admin-subs` lists Keycloak subjects that receive Admin at
   login while no other active admin exists. Set through env / Quadlet; every grant is audited.
7. **Every change leaves traces:** `USER_ROLE_ASSIGNED` / `USER_ROLE_WITHDRAWN` (role and target,
   no free text), the member's `USER_ROLE_CHANGED` notice and the counter
   `basetool_user_role_changes_total{role,action}`.
8. **One release, two slices.** The write model ships with the cutover that stops login and sync
   from writing roles and makes the backend read them only from `user_roles`; alone, the next login
   would overwrite a role assigned in the Basetool.

## Consequences

- A role change is visible in the Basetool's audit trail and reaches the member as a notice.
- The bank management manages its staff without an admin.
- Until the cutover ships, the token stays authoritative at login; the two must not be released
  apart.
- A lost admin team is recovered through `APP_BOOTSTRAP_ADMIN_SUBS`, not through Keycloak.

# ADR-0355 — Roles are held in the Basetool database

- **Status:** Accepted; the cutover (#3151) is *Amendment 1*, its follow-ups *Amendment 2*, the
  admin-OTP marker *Amendment 3*
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
- The write model and the cutover ship in one release; alone, the next login would overwrite a
  role assigned in the Basetool.
- A lost admin team is recovered through `APP_BOOTSTRAP_ADMIN_SUBS`, not through Keycloak.

## Amendment 1 — the cutover (#3151)

1. **The backend reads roles from `user_roles` only.** `CustomJwtGrantedAuthoritiesConverter`
   ignores `realm_access`; every role change publishes `UserRolesChangedEvent`, and the converter
   evicts the member's cached authorities after the commit.
2. **The frontend maps no role from the token.** `BackendRoleSyncFilter` takes them from
   `/api/v1/users/me` on the first request of a session and every 60 seconds (ADR-0122).
3. **Login and the roster sync write no role.** The sync keeps names, enabled state and existence;
   `KeycloakService` no longer reads realm roles.
4. **Approval grants KRT Member** (`ApprovalRoleGrant`), recorded as the approving admin's
   assignment. A bootstrap subject's Admin authorises the very login that earns it.
5. **Keycloak holds none of the four roles** (Admin stays as the OTP marker, *Amendment 3*). The
   provisioner deletes them and withholds the
   `roles` scope from `basetool-frontend` (ADR-0202 *Amendment 9*); the e2e and sandbox realms
   carry neither, and the e2e accounts get their roles in `user_roles` at stack start.
6. **The partial-role-scope guard is deleted** (ADR-0141, REQ-SEC-036).
7. **Switch and rollback** follow `docs/deployment.md` → *Roles in the Basetool: the switch and its rollback*: a final roster sync on
   the previous release, then the deploy and the provisioner; back, the previous release, its
   provisioner and `scripts/restore-keycloak-realm-roles.py` from a `user_roles` export.

## Amendment 2 — the cutover's follow-ups (#3151)

1. **Grafana has its own admin role.** Grafana's login reads the client role `grafana-admin` of
   the `grafana` client, assigned in Keycloak to the few Grafana admins; the Basetool's `Admin`
   does not reach Grafana (REQ-OBS-008, ADR-0202 *Amendment 10*). The runbook assigns it before
   the provisioner deletes the realm roles.
2. **Bank holders follow a role change at once.** After a role change commits,
   `BankHolderRoleChangeListener` reconciles that member's holder (REQ-BANK-029); the daily sweep
   stays as the repair path.
3. **`backend-service` gives `view-realm` back**, because no Basetool call reads the realm any
   more (ADR-0202 *Amendment 10*).

## Amendment 3 — the realm role `Admin` stays as the admin-OTP marker (#3151)

Keycloak's admin OTP (REQ-SEC-082) is conditioned on the realm role `Admin`
(`conditional-user-role`), and a condition on a role that does not exist matches nobody, so
deleting it would switch the second factor off for every admin.

1. **Keycloak keeps the realm role `Admin`**, used only as that condition. The provisioner retires
   KRT Member, Bank Employee and Bank Management and lists a realm without `Admin` as a manual step;
   the test realms hold none of the four, and the hardening integration test creates `Admin` itself.
2. **The Basetool never reads it.** Authorisation comes from `user_roles` alone (Amendment 1).
3. **Its assignments are kept in step by hand**: whoever gives or withdraws the Basetool's Admin also
   maps or unmaps the realm role, as the runbook's step says. An account with the Basetool's Admin and
   no realm role is not asked for a second factor.

# ADR-0264 — The member rank follows the stored Keycloak role

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** spec [`role-model.md`](../specs/role-model.md) (REQ-ROLE-007) ·
  [`api-conventions.md`](../specs/api-conventions.md) (REQ-API-019) ·
  [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md) · ADR-0235 · issue #2636

## Context

Every member carries a numeric member rank from 1 to 20 on `app_user`, set by an admin on the
member-edit page. `UserService.updateUserAttributes` accepts ranks 1–12 only for a member with the
`OFFICER` realm role and ranks 13–20 only for a member with `KRT_MEMBER` and without `OFFICER`.

`OFFICER` is granted by hand in Keycloak next to a leadership seat. Nothing in the tool removes it
when the seat goes; the tool learns a role change only through the Keycloak reconciliation or the
member's next login. On 2026-10-16 an admin tried to demote a former squadron lead from rank 8 to
rank 20 and got a generic "invalid argument" toast on both tabs of the edit page: the refusal was an
`IllegalArgumentException`, answered as `ILLEGAL_ARGUMENT`, and the membership tab submits the same
form, so the attributes call failed there too before the membership change was sent.

Two ways were open: let the rank range ignore the role, or keep the rule and make the refusal say
what to do.

## Decision

1. **The rank range keeps following the stored Keycloak role.** The tool does not write realm
   roles, so it cannot resolve the mismatch itself, and a rank that contradicts the role would
   misstate the member's standing on every surface that shows it.
2. **The refusal has its own problem code.** `RANK_OUTSIDE_ROLE_RANGE` (`400`, in the identity
   module's `IdentityProblemCode`) carries a role-specific, localized detail that names the role
   and the way out, and the extension properties `role`, `minRank` and `maxRank`.
3. **The edit page shows the range before the save and names the field after it.** A hint under
   the rank select states the member's role and its range; a refusal is shown on the rank field,
   so the admin can reset the rank and save the Staffel change.

## Consequences

- A former lead is demoted in two steps: remove `OFFICER` in Keycloak, then set the rank once the
  role change has been picked up. The page says so.
- A role-less account is still not range-checked.
- Other clients (the Android app) can branch on the code and read the range from the problem body.

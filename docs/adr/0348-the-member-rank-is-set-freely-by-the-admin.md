# ADR-0348 — The member rank is set freely by the admin

- **Status:** Accepted
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Supersedes:** [ADR-0264](0264-the-member-rank-follows-the-stored-keycloak-role.md)
- **Amends:** [ADR-0300](0300-officer-reach-is-derived-per-org-unit-and-the-keycloak-officer-role-is-retired.md)
  — decision 5, the rank half: officer reach no longer sets a member-rank range
- **Related:** spec [`role-model.md`](../specs/role-model.md) (REQ-ROLE-007) ·
  [`api-conventions.md`](../specs/api-conventions.md) (REQ-API-019) ·
  [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)

## Context

Every member carries a numeric member rank from 1 to 20 on `app_user`, set only by an admin on the
member-edit page. ADR-0264 tied the permitted range to the member's standing, and ADR-0300 moved
that standing from the Keycloak `OFFICER` role to officer reach: a member with a leadership seat held
ranks 1–12, a `KRT Member` without one ranks 13–20, and a mismatch was refused with
`RANK_OUTSIDE_ROLE_RANGE`.

The ranks are assigned only by the owner, who keeps rank and seats consistent by hand. The coupling
forced an order of operations (change the seat first, then the rank) and refused ranks the owner
deliberately wanted, for example a rank in the officer band for a member without a seat.

## Decision

1. **Any account may hold any member rank 1–20**, independent of its roles and leadership seats.
   The only check left is the scale itself; a rank outside 1–20 is `ILLEGAL_ARGUMENT`.
2. **The range machinery is removed:** the problem code `RANK_OUTSIDE_ROLE_RANGE` (and with it the
   identity module's `IdentityProblemCode`), the admin-only `GET /api/v1/users/{id}/rank-range`, the
   identity SPI `OfficerReachDirectory`, and the range hint and refusal on the member-edit page.
3. **A rank change stays audited** as `MEMBER_RANK_CHANGED` (REQ-AUDIT-001).

## Consequences

- Rank and seat no longer have to be changed in a fixed order; keeping them consistent is the
  admin's responsibility.
- The member rank carries no rights; officer reach still comes only from leadership seats
  (ADR-0300), so a low rank without a seat grants nothing.
- A client that branched on `RANK_OUTSIDE_ROLE_RANGE` or read `/rank-range` no longer gets either.

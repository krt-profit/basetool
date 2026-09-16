# ADR-0004 — Ownerless leadership ("Bereichsleitung") missions

- **Status:** Accepted
- **Date:** 2026-06-07
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) `REQ-ORG-003/004/009` · [`security-and-access.md`](../specs/security-and-access.md) · [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md) §3.5 · direct production report (no tracking issue)

## Context

Every org-unit-scoped aggregate is stamped with an owning OrgUnit at create time via
`OwnerScopeService`. V99/V102 made `owning_org_unit_id` NOT NULL everywhere; V132 then relaxed it
for the three personal aggregates (ship, refinery order, inventory item) so a user with **no**
OrgUnit membership could still create those, attributing the row through its per-user owner column.
V132 deliberately left mission/operation/job_order NOT NULL, on the stated premise that they "have
no per-user owner column to fall back on".

That premise is wrong for `mission`: it carries `mission.owner_id` (the creator). The organisation
has leadership roles ("Bereichsleitung") that sit **above** every Staffel and SK and therefore
belong to no OrgUnit. Such a user is authenticated and carries the `officer` role, so the mission
create endpoint (`isAuthenticated()`) admits them — but the create **service** routed through the
*strict* `resolveOrgUnitForPickerOutput`, which 400s a membershipless user. Result: a real
production report of a leadership user unable to create any mission. The requirement is that
such a user can create a mission that is public by default and hidden when marked internal.

## Decision

We will treat `Mission` as a fourth nullable-owner aggregate. `V144` drops the NOT NULL on
`mission.owning_org_unit_id`; `MissionService.createMission` routes through
`resolveOrgUnitForPickerOutputNullable`, so a membershipless owner who supplies no picker output
yields a **null** owning OrgUnit instead of a 400. The resulting *ownerless mission* is attributable
through `mission.owner_id` and gated by mission visibility rules that take the whole organisation as
the owning scope (the membershipless analogue of "a Staffel-internal mission is visible to its
Staffel"):

- **Public** (`is_internal = false`) → visible to everyone, anonymous visitors included.
- **Internal** (`is_internal = true`) → visible to organisation members-or-above
  (`AuthHelperService.isMemberOrAbove()`), hidden from guests/anonymous.

Editing follows the normal mission-management gate: `canEditMission` is a no-op (returns `true`) for
an ownerless mission, so `MissionSecurityService.canManageMission` decides via its usual
elevated-role-or-owner/manager check (owner, co-managers, mission-managers/officers, admins) — the
same path as a normal mission, minus the squadron-scope narrowing. The mission list queries
(`searchMissions`, `findAllActiveReference`) gain a
`viewerIsMemberOrAbove` predicate flag so an internal ownerless mission also surfaces in the lists of
members-or-above, keeping the list and the per-row detail gate consistent.

## Consequences

- A membershipless leadership user can create missions; the public default matches the org's
  expectation that such missions are visible to everyone unless flagged internal.
- `Mission` joins ship/refinery/inventory as a nullable-owner aggregate; the create matrix now has a
  documented 0-membership carve-out (REQ-ORG-004). `Operation` and `JobOrder` stay NOT NULL (org-owned
  by construction, no creator-owner fallback).
- A new visibility audience (`isMemberOrAbove`) is introduced for one narrow case; it never widens
  org-owned mission visibility, only the `owning_org_unit IS NULL` branch.
- The list queries carry one extra boolean param; all three scope-filtered mission queries move in
  lockstep so the dropdown, the list page and the detail gate cannot diverge.
- No frontend change: `MissionDto`/`MissionListDto` already publish `owningSquadron` as a nullable
  reference and the templates already guard it (`th:if`), so an ownerless mission renders a muted
  "—" badge.
- `V144` is non-destructive (DROP NOT NULL is catalog-only); existing missions keep their owner.
  Rollback re-tightens the column and fails only if an ownerless mission has been created meanwhile.

## Alternatives considered

- **Model "Bereichsleitung" as an OrgUnit and add the leader to it.** Rejected: it is neither a
  Squadron nor a Special Command, so it would leak into the Staffel list, the promotion system, the
  SK queue and profit-eligibility. A third OrgUnit kind would be a large, cross-cutting change far
  out of proportion to the bug.
- **Internal ownerless mission visible only to owner + co-managers + admins.** Rejected as the read
  audience: it makes "internal" almost useless for org-wide leadership coordination and breaks the
  established "internal = visible to the owning scope" analogy (a Staffel-internal mission is visible
  to the whole Staffel, not just its creator). Edit access is governed separately by
  `canManageMission`'s role/owner gate.
- **Leave the list queries unchanged and rely on the detail gate.** Rejected: a member-or-above could
  open an internal ownerless mission by URL but never see it in the list — exactly the list/detail
  divergence the codebase has been bitten by before.

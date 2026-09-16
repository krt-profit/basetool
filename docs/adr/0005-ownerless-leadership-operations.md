# ADR-0005 — Ownerless leadership ("Bereichsleitung") operations

- **Status:** Accepted
- **Date:** 2026-06-09
- **Deciders:** Repository owner (@greluc)
- **Related:** [ADR-0004](0004-ownerless-leadership-missions.md) · spec [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) `REQ-ORG-003/004/009` · [`security-and-access.md`](../specs/security-and-access.md) · issue #500

## Context

[ADR-0004](0004-ownerless-leadership-missions.md) made `Mission` a nullable-owner aggregate so
organisation leadership ("Bereichsleitung") — which sits above every Staffel and SK and belongs to no
OrgUnit — can plan org-wide missions instead of being 400'd by the strict create-time owner resolver.
It explicitly left `Operation` (and `JobOrder`) NOT NULL, on the premise that they are "org-owned by
construction with no creator-owner fallback".

That premise is literally true — `operation` has no per-user owner column — but it blocks the same
real workflow for operations: issue #500 is a production report of an admin in the Bereichsleitung
(member of no Staffel/SK) unable to create an operation. `OperationService.createOperation` routed
through the *strict* `resolveOrgUnitForPickerOutput`, which 400s a membershipless caller with
`User has no org-unit membership — cannot stamp an aggregate owner`. An operation is the umbrella that
groups missions for payout; leadership planning an org-wide effort must be able to create one.

## Decision

We extend the nullable-owner carve-out to `Operation`. `V145` drops the NOT NULL on
`operation.owning_org_unit_id`; `OperationService.createOperation` routes through
`resolveOrgUnitForPickerOutputNullable`, so a membershipless caller who supplies no picker output
yields a **null** owning OrgUnit instead of a 400. The resulting *ownerless operation* differs from an
ownerless mission in two ways, both forced by the operation data model:

- **No creator-owner column.** Unlike `mission.owner_id`, an operation has no per-user owner. An
  ownerless operation is therefore attributable only as an organisation-wide leadership operation
  (audited via `created_at`/`updated_at`); we deliberately do **not** add an `owner_id` column, since
  operations have never tracked a creator and doing so would be a separate, broader change.
- **No public escape.** Operations carry no `is_internal` flag and are never anonymous-visible, so
  there is no public-vs-internal split. An ownerless operation is the org-wide analogue of a
  Staffel-internal operation: visible to organisation members-or-above
  (`AuthHelperService.isMemberOrAbove()`), hidden from guests/anonymous. `canSeeOperation` defers to
  `isMemberOrAbove()` on the null-owner branch; the three scoped operation queries (`findAllScoped`,
  `findAllReferenceScoped`, `searchOperations`) gain a `viewerIsMemberOrAbove` predicate flag so the
  list, the picker and the per-row detail gate stay consistent.

Editing follows the role gate already on the controller: `canEditOperation` is a no-op (returns
`true`) for an ownerless operation, so the `@PreAuthorize` checks decide — an org-wide operation is
editable by any mission manager (`hasRole('MISSION_MANAGER')`) and deletable by any admin
(`hasRole('ADMIN')`), the same path as a normal operation minus the squadron-scope narrowing.

## Consequences

- A membershipless leadership user can create operations; #500 is fixed.
- `Operation` joins ship/refinery/inventory/mission as a nullable-owner aggregate; the create matrix's
  0-membership carve-out (REQ-ORG-004) now covers it. `JobOrder` stays NOT NULL (org-owned by
  construction, no ownerless-leadership use case).
- The members-or-above audience introduced for missions (ADR-0004) is reused for operations; it never
  widens org-owned operation visibility, only the `owning_org_unit IS NULL` branch.
- All three scope-filtered operation queries carry one extra boolean param and move in lockstep, so
  the dropdown, the list page and the detail gate cannot diverge.
- No frontend change: the create-modal owner-picker is already optional and auto-hidden for a caller
  with ≤1 membership (so a membershipless user submits a null `owningOrgUnitId`), and no operation
  template renders an owning-OrgUnit badge, so an ownerless operation renders cleanly.
- `V145` is non-destructive (DROP NOT NULL is catalog-only); existing operations keep their owner.
  Rollback re-tightens the column and fails only if an ownerless operation has been created meanwhile.

## Alternatives considered

- **Add an `operation.owner_id` creator column to mirror missions exactly.** Rejected: operations have
  never tracked a per-user creator (even Staffel-owned ones), so this would be a new, broader concept —
  new column, DTO/mapper fields, display and edit-ownership semantics — far out of proportion to the
  bug. An ownerless operation is an *organisation* effort, not a personal one; attribution as
  "organisation-wide leadership operation" is the right model.
- **Make an ownerless operation public (visible to everyone, like a public mission).** Rejected:
  operations are internal economy/payout records with no public-visibility concept (REQ-ORG-003,
  strict-staffel, no public escape). Members-or-above is the correct audience, mirroring "internal =
  visible to the owning scope" with the whole organisation as the scope.
- **Keep operations NOT NULL and require leadership to pick an arbitrary Staffel as owner.** Rejected:
  it mis-attributes an org-wide operation to one Staffel, leaks it into that Staffel's scope, and
  forces a meaningless choice on the user — the same reasons ADR-0004 rejected the equivalent for
  missions.

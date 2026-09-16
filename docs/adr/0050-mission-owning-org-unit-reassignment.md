# ADR-0050 — Mission owning-org-unit reassignment

- **Status:** Accepted
- **Date:** 2026-06-29
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) `REQ-ORG-018` (amends `REQ-ORG-004`) · [`mission-detail-tabs.md`](../specs/mission-detail-tabs.md) `REQ-MISSION-004` · [`audit.md`](../specs/audit.md) `REQ-AUDIT-001` · [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) `REQ-FE-001` · ADR-0004 (ownerless leadership missions)

## Context

A mission's owning OrgUnit (`mission.owning_org_unit_id`) was stamped once at create time
(`REQ-ORG-004`) and treated as immutable thereafter: it gates, together with `is_internal`, who may
see and edit the mission (`REQ-ORG-003`, `OwnerScopeService.canSeeMission` / `canEditMission`). In
practice a mission is sometimes created under the wrong unit, or its responsibility moves between
Staffeln/SKs, or a Staffel mission should become an org-wide leadership mission (or vice versa). The
only remedy was delete-and-recreate, which loses the participant roster, finance entries and audit
trail. There was no requirement forbidding reassignment — immutability was incidental, not designed.

The codebase already had every primitive: the `owner-picker` fragment + `/me/pickable-org-units`
(create-form OrgUnit picker), `OwnerScopeService.canEditOrgUnit` (the `REQ-ORG-016` cascade-aware
edit scope), `JobOrderService.reassignResponsibleOrgUnit` (a precedent dedicated reassignment
endpoint gated on `canEditOrgUnit`, audited from→to with kind+id only), the section-scoped
optimistic-lock counter family (`coreVersion` / `partyLeadVersion` …), and the `krtFetch`
fragment-swap live-update foundation.

## Decision

We will make `Mission.owningOrgUnit` **reassignable after creation** through a dedicated, audited,
version-checked endpoint surfaced as a "Verantwortliche Einheit" control in the mission Verwaltung tab.

- **Endpoint** `PUT /api/v1/missions/{id}/owning-org-unit` with
  `UpdateMissionOwningOrgUnitRequest{owningOrgUnitId, version}` → `MissionService.updateOwningOrgUnit`.
  `owningOrgUnitId == null` re-homes the mission to **ownerless** (the public-leadership form of
  ADR-0004 / `REQ-ORG-009`).
- **Two orthogonal gates.** (1) The controller reuses `@missionSecurityService.canChangeOwner` — the
  same gate as the user-owner change beside it (admin, scope-officer who may edit the mission, or the
  mission owner). (2) The service validates the *target* against the caller's assignable scope via a
  new `OwnerScopeService.resolveReassignTargetOrgUnit`: an admin may assign anywhere or to ownerless;
  a non-admin may only pick a direct membership or a `canEditOrgUnit` descendant, and may pick
  ownerless **only when membershipless** (mirroring who may *create* an ownerless mission).
- **Concurrency.** A new section-scoped counter `Mission.owningOrgUnitVersion`
  (`@OptimisticLock(excluded = true)`, migration V195) guards it — chosen over a companion aggregate
  (the `MissionOwnership` shape used for the user-owner) because the owning OrgUnit is a single
  nullable association exactly like `partyLeadUser`, for which the section-counter is already the
  established pattern. Reassignment never bumps `Mission.version`, so it never 409s a concurrent edit
  of an unrelated section; two managers racing on the assignment 409 against each other.
- **Audit.** A new `MISSION_OWNING_ORG_UNIT_CHANGED` event records the from/to org units as
  `kind:id` references only — no names, no free text (`REQ-AUDIT-001`).
- **Live update.** The control lives in the `#mission-mgmt-results` swap container; on success the
  panel re-renders in place and the sticky-head owning-squadron badge is patched directly from the
  returned DTO (the badge sits outside every swap container), so there is no full reload
  (`REQ-FE-001`).

## Consequences

- Reassignment is now a first-class operation; the roster, finance, units and audit trail survive a
  move that previously required delete-and-recreate.
- Re-homing **retroactively** re-scopes visibility through the existing gates (no frozen create-time
  ACL): `NULL → org` narrows a public-leadership mission, `org → NULL` widens it, `A → B` re-points
  it. We accept this retroactivity as the intended semantics — the alternative (ACLs frozen at
  creation) contradicts how every other scoped query already reads `owning_org_unit_id` live.
- `is_internal` stays **orthogonal** and is not auto-adjusted: hiding a previously public mission
  still requires flipping `is_internal` separately. We accept the small footgun (moving a mission to
  a private unit does not hide it unless also marked internal) in exchange for not entangling two
  independent controls.
- The reassignment does **not** cascade to participants, finance entries, units or the linked
  operation — they keep their own ownership, consistent with how the mission aggregate already owns
  its children independently.
- Only `Mission` becomes reassignable; every other aggregate's owning OrgUnit stays create-time-only.
  The shared picker resolver is untouched — reassignment uses a separate resolver with no auto-stamp
  / home-Staffel fallback, so create-time stamping behaviour is unchanged.
- The Verwaltung picker offers the caller's pickable set plus "Keine"; an admin who is not a member
  of the relevant unit reassigns via the API or by pinning context — the UI does not enumerate every
  org unit for admins (consistent with the create-form picker). The backend authorises the full
  admin-anywhere range regardless.

## Alternatives considered

- **Companion `MissionOrgUnitOwnership` aggregate** (mirroring `MissionOwnership` for the user-owner).
  Rejected: a single nullable association does not need a separate table/entity/repository; the
  section-counter (`owningOrgUnitVersion`) is the lighter, already-established pattern for exactly
  this shape (`partyLeadVersion`).
- **Bundle owningOrgUnit into the general mission update / core patch.** Rejected: it would entangle a
  tenancy change with ordinary edits, blur the permission gate, and make the audit trail and the
  optimistic-lock scope harder to reason about. The `JobOrder` precedent isolates reassignment for
  exactly these reasons.
- **Keep it immutable; require delete-and-recreate.** Rejected: loses roster/finance/audit and is the
  status quo the change exists to fix.
- **Admin-only reassignment.** Rejected by the repository owner in favour of parity with the
  user-owner change (admin / scope-officer / owner), with the target-scope gate preventing a
  non-admin from moving a mission outside their own assignable units.
- **Auto-clear `is_internal` when moving to a "private" unit.** Rejected: `is_internal` is an
  independent visibility control; silently flipping it on reassignment would surprise users and
  couple two orthogonal concerns.

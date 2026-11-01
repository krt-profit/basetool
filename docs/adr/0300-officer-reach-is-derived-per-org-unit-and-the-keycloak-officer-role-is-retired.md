# ADR-0300 — Officer reach is derived per org unit and the Keycloak OFFICER role is retired

- **Status:** Accepted — implemented on the integration branch (#2847); the production
  realm-role deletion is a separate owner-approved provisioner run (ADR-0202 amendment 8).
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Amends:** [ADR-0042](0042-unified-membership-rank-with-delegated-appointment.md) — its baseline
  grant: a leadership seat now also confers officer reach in its unit, and every seat requires
  `KRT Member`
- **Related:** spec [`role-model.md`](../specs/role-model.md) (REQ-ROLE-002, REQ-ROLE-007) ·
  [`security-and-access.md`](../specs/security-and-access.md) (REQ-SEC-004, REQ-SEC-015,
  REQ-SEC-047, REQ-SEC-065) · [`org-notifications.md`](../specs/org-notifications.md) (REQ-ORG-029)
  · [`bank.md`](../specs/bank.md) (REQ-BANK-037) · [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)
  · [ADR-0026](0026-cascading-scope-without-admin.md) ·
  [ADR-0042](0042-unified-membership-rank-with-delegated-appointment.md) ·
  [ADR-0297](0297-the-navigation-is-one-task-grouped-default-with-desktop-modes-a-configurable-tab-bar-and-a-scoped-entity-search.md)
  · map #2800

## Context

`OFFICER` is one flat Keycloak realm role, granted and removed by hand. It is not tied to a unit:
`RequestScopeResolver.currentOversightScope()` adds every squadron membership of a holder, so an
officer of one unit gets officer power in every unit they merely belong to. The role and the org
chart's leadership seats drift apart, which is why REQ-ORG-029 reports mismatches to the admins.
`PurchasingAccessPolicy.leadsUnit` checks an `OFFICER@<unit>` authority that nothing mints. The
org chart already derives per-unit reach from seats (`confersOwnUnitOfficerReach`,
`OrgUnitCascadeService.cascadedOfficerReach`). The navigation of ADR-0297 follows the active org
unit and needs to know where a member is an officer.

## Decision

1. **Officer reach is derived only from the org chart:** Staffel leadership ranks and the SK lead
   for their own unit, the three Bereich ranks for the Bereich and its direct children, OL members
   for every unit. Admin passes every officer check. There is no manual grant, no per-membership
   flag and no global override. Bank Management gets no reach; it keeps its `BANK_*` rights.
2. **Authorisation checks the target's owning unit** (the unit of the mission, job order, promotion
   topic, …); lists are filtered to the caller's officer units. The surfaces that move to per-unit
   reach include hangar owner details, the Leitung page, promotion management, job-order writes,
   mission finance and owner change, the operation "unpaid" reset and the purchasing `leadsUnit`
   check. The active context drives only visibility, with the union when nothing is pinned.
3. **`GET /me/capabilities` and `/me/layout` become pin-aware** and return the officer flags for the
   active context; every frontend `ROLE_OFFICER` gate is replaced by them. The frontend keeps no
   reach logic of its own.
4. **Every leadership seat requires the Keycloak role `KRT Member`.** An appointment without it is
   refused; a seat whose holder loses it later stays in the org chart but confers no reach.
   `MEMBER_OR_ABOVE` no longer lists `OFFICER`, so membership rights rest on `KRT Member` alone.
5. **Dependent rules follow the derived reach:** an admin may hold any member rank 1–20, since the
   admin's pass counts for the range; for everyone else rank 1–12 requires reach in at least one
   unit (REQ-ROLE-007); `ORG_RELATIVE_ROLE OFFICER` resolves to seat holders directly in the context
   unit, without cascade, and a global `ROLE OFFICER` rule to every seat holder; REQ-ORG-029's
   notice becomes "seat holder without `KRT Member`"; the `GLOBAL_ROLE OFFICER` view-grant bucket
   on the special accounts is removed, its grants deleted by a migration that writes one
   `bank_audit_event` per removed grant with the system as actor.
   The `LOGISTICIAN` and `MISSION_MANAGER` buckets of the special accounts are retired the same way,
   because a Sonderkonto has no owning unit to resolve a per-unit grant against.
6. **The Keycloak role is retired by a hard cut in one release:** the code reads only derived reach,
   `UserReconciliationService` ignores `OFFICER`, a migration removes the seeded role and its
   permissions, the provisioning script deletes the realm role afterwards, and the test, e2e and
   sandbox realms drop it. **Precondition:** a pre-cut report of every seat holder without
   `KRT Member`; the cut ships only once that list is empty.
7. **No new audit event:** reach follows from seat changes, which `ROLE_GRANTED`, `ROLE_CHANGED` and
   `ROLE_REVOKED` already record.

## Consequences

- An officer acts only where they hold a seat; the cross-unit gap closes, and the role can no longer
  drift from the org chart.
- REQ-ROLE-002, REQ-ROLE-007 (dropping its out-of-scope line), REQ-SEC-004, REQ-SEC-015,
  REQ-SEC-047, REQ-SEC-065, REQ-ORG-029, REQ-BANK-037 and the role matrix are amended, and
  lose their planned markers with the cut.
- The implementation also amends every other requirement that names the `OFFICER` role or
  `ADMIN_OR_OFFICER`: REQ-ROLE-004; REQ-SEC-002a, -005, -006, -007, -009, -020, -037, -040, -042,
  -053, -058, -060, -061, -072, -083, -085; REQ-ORG-005, -015, -017, -021; REQ-BANK-008, -010,
  -021, -025…028, -035; REQ-NOTIF-007, -008, -011, -017, -024; REQ-ORDERS-014, -023…026, -035,
  -041, -042; REQ-HANGAR-001, -003, -004, -007; REQ-MISSION-029; REQ-INV-041; REQ-API-009,
  -012; REQ-DATA-002; REQ-OPS-050; REQ-OBS-016; REQ-XCH-043; REQ-FE-016; and the purchasing
  `leadsUnit` check, which no requirement names yet.
- A hand-granted `OFFICER` without a seat loses its power at the cut; that is intended.
- Org-relative officer notifications reach fewer people: the OL and the Bereich are no longer
  flooded by every unit below them.
- The cut is one release that cannot be rolled back by a flag; the pre-cut report is the guard.

## Alternatives considered

- **Keep the Keycloak role and add a per-unit check:** two sources of truth that keep drifting, as
  REQ-ORG-029 shows today. Rejected.
- **A manual per-membership officer flag or a global override:** reintroduces hand-maintained state
  beside the seats. Rejected.
- **A phased dual-read transition:** keeps the cross-unit gap open and both models live for the
  transition. Rejected for the hard cut behind the pre-cut report.
- **Cascading org-relative officer notifications:** floods the OL and the Bereich. Rejected.
- **Officer reach for Bank Management:** bank rights are a separate axis (REQ-BANK-007). Rejected.

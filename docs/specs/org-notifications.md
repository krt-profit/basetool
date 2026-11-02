> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-31.
> **Owner area:** ORG · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md), [ADR-0300](../adr/0300-officer-reach-is-derived-per-org-unit-and-the-keycloak-officer-role-is-retired.md)

# Organisation notifications

## Context & goal

Issue #2414 tells the right people about two organisational facts that were visible only on a page: a
leadership seat holder without the Keycloak role `KRT Member`, which every seat requires, and a member
who left the organisation. Both notices are published after the commit, carry only a member's display name, a unit's
name and coded words, and have a seeded, admin-editable rule. Each can be muted (REQ-NOTIF-027).

## Requirements

### REQ-ORG-029 — A leadership seat holder without KRT Member tells the admins

Every leadership seat requires the Keycloak role `KRT Member` (REQ-ROLE-002), and a seat confers
officer reach only while its holder has it (ADR-0300). A member **holds a seat** when any of their
memberships is not a plain member (`OrgUnitMembershipRepository#existsLeadershipSeat`). The admin
notice reports the one inconsistency that can still drift: a seat holder without `KRT Member`. An
admin is judged like everyone else; the `OFFICER` role plays no part.

- **When it fires.** `OrgUnitMembershipService` calls `OrgUnitSeatNotifier#seatChanged` after
  `assignSquadronRank`, `removeSquadronRank`, `addBereichLeader`, `removeBereichLeader`,
  `addOlMember`, `removeOlMember` and `toggleLead`; a member who still holds a seat and no `KRT
  Member` afterwards is reported (`seatCode` `APPOINTED` / `REMOVED`). A role reconciliation is the
  usual trigger: `UserReconciliationService` tells the identity-owned `RolesChangedObserver`
  (implemented by `OrgUnitSeatNotifier#onRolesChanged`) whenever it replaces a member's roles, and a
  seat holder whose new roles lack `KRT Member` is reported with one of their seats (`seatCode`
  `ROLES_RECONCILED`, no actor). The seat stays in the org chart.
- **What it says.** `ORG_LEADERSHIP_ROLE_MISMATCH`; the default rule notifies every admin (`ROLE`
  `ADMIN`, the actor included) with the member, the unit, the coded seat change (`seatCode`), the
  seat (`rankCode`) and the only `mismatchCode`, `MISSING_KRT_MEMBER`. A second notice of the same
  member replaces the first. Notices stored with the retired codes `MISSING_OFFICER` /
  `SURPLUS_OFFICER` keep their labels.
- **When it clears.** `ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED` clears the notice once the member holds
  `KRT Member` again or holds no seat any more, after a seat change or a reconciliation.
- **Before the cut.** `scripts/officer-cut-report.sql` reports every seat holder without `KRT
  Member` read-only; the release that retires `OFFICER` ships only when that report is empty
  (REQ-SEC-004).

### REQ-ORG-030 — A departed member tells the leadership of their units

`OrgUnitDepartureNotifier` listens for `MemberDepartedEvent` (REQ-XCH-008) after the commit, in a
transaction of its own, and publishes `ORG_MEMBER_DEPARTED` once per unit the member belonged to. The
default rule notifies that unit's leadership (`ORG_RELATIVE_ROLE` `UNIT_LEADERSHIP` on `RESPONSIBLE`,
REQ-NOTIF-024) with the member, the unit, the coded reason (`reasonCode`: `disabled`, `role_lost`,
`removed`) and whether a seat became vacant (`vacancyCode`). When the member held a seat, the parent
Bereich's leadership is told as well, once per Bereich. The departed member is never a recipient.

**Acceptance**

- [x] A seat holder without `KRT Member` is reported after a seat change and after a reconciliation that
  took the role away; an admin is judged alike.
- [x] The notice reaches the admins, is replaced by a newer one and goes when the member holds `KRT
  Member` again or no seat any more.
- [x] The pre-cut report lists every seat holder without `KRT Member` and the rows the `OFFICER` cut
  touches, read-only and without names.
- [x] A departure reaches the leadership of the member's units and the parent Bereich of a vacated seat, never the member.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Enforced by:** `OrgNoticeIntegrationTest`, `OrgUnitSeatNotifierTest`, `OfficerCutReportIntegrationTest`, `OrgUnitDepartureNotifierTest`,
`OrgUnitMembershipServiceTest`, `UserReconciliationServiceTest`, `SeededNotificationRulesIntegrationTest`,
`NotificationPageControllerTest` (`targetOf_…`) · **Code:** `orgunit/api/events/OrgNotices`,
`orgunit/api/OrgUnitSeatNotifier`, `orgunit/internal/OrgUnitDepartureNotifier`, `orgunit/api/OrgUnitMembershipService`,
`identity/api/UserReconciliationService`, `identity/api/RolesChangedObserver`, `orgunit/api/OrgUnitMembershipRepository#existsLeadershipSeat`,
`identity/api/UserRepository#hasAnyRoleCode`, `V279__seed_organisation_notification_rules.sql`,
`scripts/officer-cut-report.sql` ·
**Issues:** #2414, #2841

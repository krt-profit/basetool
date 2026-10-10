> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-10.
> **Owner area:** ORG · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)

# Organisation notifications

## Context & goal

Issue #2414 tells the right people about two organisational facts that were visible only on a page: a
leadership change that leaves a member's `OFFICER` role out of step with their seats, and a member who
left the organisation. `OFFICER` is granted by hand in Keycloak next to a seat, so the
two drift. Both notices are published after the commit, carry only a member's display name, a unit's
name and coded words, and have a seeded, admin-editable rule. Each can be muted (REQ-NOTIF-027).

## Requirements

### REQ-ORG-029 — A leadership change that does not fit the OFFICER role tells the admins

`OrgUnitMembershipService` calls `OrgUnitSeatNotifier#seatChanged` after `assignSquadronRank`,
`removeSquadronRank`, `addBereichLeader`, `removeBereichLeader`, `addOlMember`, `removeOlMember` and
`toggleLead`. A member **holds a seat** when any of their memberships is not a plain member
(`OrgUnitMembershipRepository#existsLeadershipSeat`). The roles disagree when the member holds a seat
and neither `OFFICER` nor `ADMIN`, or holds `OFFICER` without a seat and without `ADMIN`.

- A disagreement publishes `ORG_LEADERSHIP_ROLE_MISMATCH`; the default rule notifies every admin
  (`ROLE` `ADMIN`, the actor included) with the member, the unit, the coded seat change (`seatCode`:
  `APPOINTED` / `REMOVED`), the seat (`rankCode`) and the coded `mismatchCode` (`MISSING_OFFICER` /
  `SURPLUS_OFFICER`). A second mismatch of the same member replaces the first.
- When the roles fit after the change, `ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED` clears an earlier
  notice. So does a role reconciliation that makes them fit: `UserReconciliationService` tells the
  identity-owned `RolesChangedObserver` (implemented by `OrgUnitSeatNotifier#onRolesChanged`)
  whenever it replaces a member's roles.

### REQ-ORG-030 — A departed member tells the leadership of their units

`OrgUnitDepartureNotifier` listens for `MemberDepartedEvent` (REQ-XCH-008) after the commit, in a
transaction of its own, and publishes `ORG_MEMBER_DEPARTED` once per unit the member belonged to. The
default rule notifies that unit's leadership (`ORG_RELATIVE_ROLE` `UNIT_LEADERSHIP` on `RESPONSIBLE`,
REQ-NOTIF-024) with the member, the unit, the coded reason (`reasonCode`: `disabled`, `role_lost`,
`removed`) and whether a seat became vacant (`vacancyCode`). When the member held a seat, the parent
Bereich's leadership is told as well, once per Bereich. The departed member is never a recipient.

**Acceptance**

- [x] A seat without `OFFICER`, `OFFICER` without a seat, a fitting set and an admin are judged as above.
- [x] The mismatch reaches the admins, is replaced by a newer one and goes when the roles fit.
- [x] A departure reaches the leadership of the member's units and the parent Bereich of a vacated seat, never the member.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Enforced by:** `OrgNoticeIntegrationTest`, `OrgUnitSeatNotifierTest`, `OrgUnitDepartureNotifierTest`,
`OrgUnitMembershipServiceTest`, `UserReconciliationServiceTest`, `SeededNotificationRulesIntegrationTest`,
`NotificationPageControllerTest` (`targetOf_…`) · **Code:** `orgunit/api/events/OrgNotices`,
`service/OrgUnitSeatNotifier`, `service/OrgUnitDepartureNotifier`, `service/OrgUnitMembershipService`,
`service/UserReconciliationService`, `identity/api/RolesChangedObserver`, `repository/OrgUnitMembershipRepository#existsLeadershipSeat`,
`repository/UserRepository#hasAnyRoleCode`, `V279__seed_organisation_notification_rules.sql` ·
**Issues:** #2414

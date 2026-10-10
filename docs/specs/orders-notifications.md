> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-10.
> **Owner area:** ORDERS · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)

# Job order notifications

## Context & goal

Issue #2414 closes the gaps of the order notices (`notifications.md`, REQ-NOTIF-008, -017): the
unit an order moved to, the unit that asked for it, the member assigned to it and the member whose
claim an edit withdrew now hear about it instead of finding it by opening the page. Each is published
after the commit, never for the actor, carries no free text beyond the order's display id and contact
handle and a material name, and has a seeded, admin-editable rule. Each can be muted (REQ-NOTIF-027).

## Requirements

### REQ-ORDERS-041 — An order handed to another unit tells that unit's leadership

`JobOrderService#reassignResponsibleOrgUnit` publishes `JOB_ORDER_REASSIGNED`. The default rule notifies
the officers, leads and logisticians of the **new** responsible unit (`ORG_RELATIVE_ROLE`,
`RESPONSIBLE`), names the order, its handle and the old and the new unit, and excludes the actor. The
event clears the order's earlier `JOB_ORDER_CREATED`, `JOB_ORDER_UPDATED_BY_REQUESTER` and
`JOB_ORDER_REASSIGNED` notices for every recipient, so the previous unit stops being asked about an
order that is no longer theirs.

### REQ-ORDERS-042 — A completed, rejected or deleted order tells the requesting unit's leadership

`updateJobOrderStatus` (to `COMPLETED` or `REJECTED`), `completeJobOrderWithinTransaction` (the last
handover) and `deleteJobOrder` publish `JOB_ORDER_FINISHED` next to `JOB_ORDER_CLOSED`. The default
rule notifies the officers, leads and logisticians of the **requesting** unit (`REQUESTING`); the
word „abgeschlossen / abgelehnt / gelöscht" comes from the coded parameter `statusCode`
(REQ-NOTIF-028). No requester-user column exists, so the person who placed the order is reached only
through that unit's leadership. A deleted order's notice carries the entity tag `JOB_ORDER_DELETED`, so
the inbox row has no page to link; an order without a requesting unit tells nobody.

### REQ-ORDERS-043 — An assignment tells the assignee

`JobOrderAssigneeService#addAssignee` publishes `JOB_ORDER_ASSIGNEE_ADDED` when the assignee is not the
actor and the member was not assigned already; the default rule notifies the assignee with the order,
its handle and the actor. `#removeAssignee` publishes `JOB_ORDER_ASSIGNEE_REMOVED`, which clears that
member's assignment notice only (REQ-NOTIF-025).

### REQ-ORDERS-044 — A withdrawn claim tells the member who made it

`MaterialClaimService#withdrawOrphanedClaimsWithinTransaction` (an order edit removed the bucket) and
`#withdrawAllForOrderWithinTransaction` (the order went back to a squadron) publish
`JOB_ORDER_CLAIM_WITHDRAWN` once per claim whose `claimedByUser` is set; the default rule notifies that
member with the order, the material and the coded reason (`ORDER_CHANGED`, `DE_ESCALATED`). A claim
the member withdrew themselves (`withdrawClaim`) tells nobody.

**Acceptance**

- [x] Each event reaches the leadership or member its seeded rule names and never the actor.
- [x] A reassignment clears the earlier creation notices; a removal clears the assignment notice of that
  member only.
- [x] An order without a requesting unit, an assignment of yourself and a repeated assignment tell nobody.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Enforced by:** `JobOrderNoticeIntegrationTest`, `JobOrderServicePriorityAndStatusTest`
(`finishedNotice…`), `MaterialClaimServiceTest` (`withdraw…tells…`), `SeededNotificationRulesIntegrationTest`,
`NotificationPageControllerTest` (`targetOf_…`) · **Code:** `joborder/api/events/JobOrderNotices`,
`service/JobOrderService`, `service/JobOrderAssigneeService`, `service/MaterialClaimService`,
`V274__seed_job_order_notification_rules.sql` · **Issues:** #2414

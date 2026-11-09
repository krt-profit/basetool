> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-10.
> **Owner area:** ORDERS · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)

# Job order notifications

## Context & goal

Issue #2414 closes the gaps of the order notices (`notifications.md`, REQ-NOTIF-008, -017): the
unit an order moved to, the unit that asked for it, the member assigned to it and the member whose
claim an edit withdrew now hear about it instead of finding it by opening the page. Each is published
after the commit, never for the actor, carries no free text beyond the order's display id and contact
handle and a material name, and has a seeded, admin-editable rule. Each can be muted (REQ-NOTIF-027).

## Approved target: notification audiences and causes

> **Status:** Accepted/planned, implementation pending. This section and the scoped amendments
> below supersede the narrower legacy JobOrder defaults for the target. Existing runtime paragraphs
> and tests are baseline evidence only.

Use existing notification channels, preferences and current access; introduce no watcher enrolment.
The default target policy is:

| Logical cause | Concretely affected default audience |
| --- | --- |
| New or changed order/need/responsibility | Current writers of the affected requesting/responsible units, with established leadership reach and contextual Logistician grants; the processing side learns new/changed work and the requester side important need/state changes |
| Participation or commitment change, including automatic reductions/endings | Writers of the affected Staffel and responsible-unit coordination; one grouped cause, not one message per material line |
| Personal assignment or linked monetary fact changed by another actor | The concretely affected linked Bearbeiter, holder or recipient for that change; a name without an account is no Inbox recipient |
| Completion, rejection, cancellation, reopening or clarification | The affected unit writers and any concretely affected personal participation under the preceding rules |

The event's actor receives direct action feedback, not another copy of their own notice. The
original Bank applicant is not the actor of a later staff decision and may legitimately receive
its outcome. Accountless participants are coordinated through the responsible unit writers,
without an invented account, channel or notification identity.

Routine individual money movements are live/history updates, not broadcasts to every participant.
Do not notify all Profit members or all administrators. The global-Admin creation default is removed;
an administrator who independently qualifies through an affected functional relationship may receive it.

Each logical cause/revision gives at most one grouped notice per recipient across relevant roles
and the same Bank/order decision. Preserve distinct necessary Bank approval tasks. Existing type
muting and priority still apply: an order wrapper cannot recreate a muted Bank success. Resolve
recipient/current-access links without disclosing private Bank notes, hidden missions or blueprints.

Publish/register data-only causes in the business transaction; notification creation/fan-out and
live delivery follow successful commit under the existing architecture. Replayed causes are
idempotent; genuine later revisions remain distinct. Remove or replace obsolete action prompts
when resolved or responsibility changes. Inbox cleanup never deletes the participant-readable
business history. Transfer from SK to Staffel is no longer a reason to delete preserved contributions
or send a misleading withdrawal notice.

**Required acceptance (not executed)**

- [ ] One change reducing several material promises produces one grouped notice per affected recipient.
- [ ] A routine money movement sends no all-reader/all-Profit/Admin broadcast.
- [ ] A Bank staff decision retains its legitimate applicant/approval outcome without duplicate order success or actor confusion.
- [ ] Muting, stale-action cleanup and current-access links preserve their independent gates.
- [ ] Accountless names receive no fabricated Inbox identity; complete business history survives notice replacement.

## Requirements

### REQ-ORDERS-041 — An order handed to another unit tells that unit's leadership

> **Approved planned JobOrder amendment (not implemented):** Apply [the approved shared JobOrder contract](orders-core.md) to all three kinds. Notify the actually affected unit writers, retire obsolete responsibility prompts, exclude the actor of this event and combine one logical cause per recipient. Transfer no longer deletes material contributions merely because the destination is a Staffel.
>
> The existing paragraphs and verification references below describe the current implementation baseline.

`JobOrderService#reassignResponsibleOrgUnit` publishes `JOB_ORDER_REASSIGNED`. The default rule notifies
the officers, leads and logisticians of the **new** responsible unit (`ORG_RELATIVE_ROLE`,
`RESPONSIBLE`), names the order, its handle and the old and the new unit, and excludes the actor. The
event clears the order's earlier `JOB_ORDER_CREATED`, `JOB_ORDER_UPDATED_BY_REQUESTER` and
`JOB_ORDER_REASSIGNED` notices for every recipient, so the previous unit stops being asked about an
order that is no longer theirs.

### REQ-ORDERS-042 — A completed, rejected or deleted order tells the requesting unit's leadership

> **Approved planned JobOrder amendment (not implemented):** The shared contract also covers cancellation, reopening and clarification for the concretely affected unit/personal audiences. Early manual MATERIAL/ITEM completion remains distinct from delivered quantities. Routine financial bookings do not notify every reader. See [the approved shared JobOrder contract](orders-core.md); runtime acceptance is pending.
>
> The existing paragraphs and verification references below describe the current implementation baseline.

`updateJobOrderStatus` (to `COMPLETED` or `REJECTED`), `completeJobOrderWithinTransaction` (the last
handover) and `deleteJobOrder` publish `JOB_ORDER_FINISHED` next to `JOB_ORDER_CLOSED`. The default
rule notifies the officers, leads and logisticians of the **requesting** unit (`REQUESTING`); the
word „abgeschlossen / abgelehnt / gelöscht" comes from the coded parameter `statusCode`
(REQ-NOTIF-028). No requester-user column exists, so the person who placed the order is reached only
through that unit's leadership. A deleted order's notice carries the entity tag `JOB_ORDER_DELETED`, so
the inbox row has no page to link; an order without a requesting unit tells nobody.

### REQ-ORDERS-043 — An assignment tells the assignee

> **Approved planned JobOrder amendment (not implemented):** Keep personal assignee notices and narrow personal rights. Include the permitted linked-person money changes made by another actor under [the approved shared JobOrder contract](orders-core.md) and [the approved aUEC contract](orders-auec.md), without treating an original bank requester as the actor of a later staff decision.
>
> The existing paragraphs and verification references below describe the current implementation baseline.

`JobOrderAssigneeService#addAssignee` publishes `JOB_ORDER_ASSIGNEE_ADDED` when the assignee is not the
actor and the member was not assigned already; the default rule notifies the assignee with the order,
its handle and the actor. `#removeAssignee` publishes `JOB_ORDER_ASSIGNEE_REMOVED`, which clears that
member's assignment notice only (REQ-NOTIF-025).

### REQ-ORDERS-044 — A withdrawn claim tells the member who made it

> **Approved planned JobOrder amendment (not implemented):** Replace the last-editor/per-claim withdrawal default with the affected-Staffel and responsible-coordination policy in [the approved shared JobOrder contract](orders-core.md). Changes and automatic material reductions are one grouped cause per recipient, not invented fulfilled provision. Preserve contribution history; a responsibility transfer alone is no withdrawal.
>
> The existing paragraphs and verification references below describe the current implementation baseline.

`MaterialClaimService#withdrawOrphanedClaimsWithinTransaction` (an order edit removed the bucket)
publishes `JOB_ORDER_CLAIM_WITHDRAWN` once per claim whose `claimedByUser` is set; the default rule
notifies that member with the order, the material and the coded reason `ORDER_CHANGED`. A claim the
member withdrew themselves (`withdrawClaim`) tells nobody. Responsibility transfers preserve active
claims and do not publish withdrawal notices. Existing `DE_ESCALATED` events remain readable as
historical notices.

**Acceptance**

- [x] Each event reaches the leadership or member its seeded rule names and never the actor.
- [x] A reassignment clears the earlier creation notices; a removal clears the assignment notice of that
  member only.
- [x] An order without a requesting unit, an assignment of yourself and a repeated assignment tell nobody.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Enforced by:** `JobOrderNoticeIntegrationTest`, `JobOrderServicePriorityAndStatusTest`
(`finishedNotice…`), `MaterialClaimServiceTest` (`withdraw…tells…`), `SeededNotificationRulesIntegrationTest`,
`NotificationPageControllerTest` (`targetOf_…`) · **Code:** `joborder/api/events/JobOrderNotices`,
`joborder/internal/JobOrderService`, `joborder/internal/JobOrderAssigneeService`, `joborder/internal/MaterialClaimService`,
`V274__seed_job_order_notification_rules.sql` · **Issues:** #2414

The hierarchy foundation implements the grouped policy for the new automatic ineligibility trigger:
all ended legacy promises of one hierarchy change are grouped by order and affected Staffel,
then one cause per order carries the union of current affected-Staffel and responsible-unit writers.
The owning orgunit directory includes actual covering contextual grants and leadership reach, never
unrelated global administrators or only the last recorder. Existing preferences, actor exclusion and
recipient deduplication stay in the notification engine; historical line-level audit remains intact.

Initial automatic reconciliation uses the same grouped policy before application readiness, with a
system actor. Already-ended promises produce no new cause on a repeated start.

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

The shared MATERIAL and ITEM policy below uses the existing channels and Inbox preferences. The
linked-money and Bank outcomes follow their separate [aUEC](orders-auec.md) and
[optional Bank](orders-auec-bank.md) contracts.

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

An accepted provision for a linked holder is registered as `JOB_ORDER_MONEY_PROVISIONED`.
The notice is limited to that account even when a configured rule selects additional users, and
the delivery guard rechecks the specific holder link. Matching replays and accountless entries create no additional
personal notice. The live order room carries only section invalidations.

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

**Linked-money acceptance**

- [ ] Routine money movements use the affected linked-person policy.
- [ ] A Bank staff decision retains its legitimate applicant or approval outcome in one cause.
- [ ] An accountless participant is coordinated through the responsible unit writers, and notice
  replacement preserves the authorised business history.

## Requirements

### REQ-ORDERS-041 - Responsibility transfer tells the affected unit writers

`JobOrderService#reassignResponsibleOrgUnit` groups `JOB_ORDER_REASSIGNED` for current writers of the
former and new responsible units and the requesting unit. The notice names the order and both
responsible units. Transfer retires outstanding processing and clarification prompts, including the
earlier creation, need edit, reassignment and reopening notices. Contribution facts and their
authorised history stay with the order.

### REQ-ORDERS-042 - Lifecycle and clarification tell the affected parties

Completion, rejection, cancellation and deletion use `JOB_ORDER_FINISHED` for current requesting and
responsible writers and active assigned accounts. The coded `statusCode` renders the specific outcome
(REQ-NOTIF-028). A deleted order carries the `JOB_ORDER_DELETED` entity tag and has no detail link.
Closing clears the order prompts present at that transition through `JOB_ORDER_CLOSED`.
The cleanup applies after commit even if the order has since changed status. Prompts created for
later work and historical outcome notices remain.

Reopening uses the same functional and assigned audience through `JOB_ORDER_REOPENED`. Manual
MATERIAL or ITEM completion may leave unmet quantities in the order's retained facts.

An organisational change that makes the current responsibility invalid raises
`JOB_ORDER_CLARIFICATION_REQUIRED` for the affected writers and active assigned accounts. Repeating
the same eligibility state creates no new prompt. Restoration or an eligible transfer clears it.

### REQ-ORDERS-043 - An assignment tells the assignee

`JobOrderAssigneeService#addAssignee` publishes `JOB_ORDER_ASSIGNEE_ADDED` for a newly assigned
account other than the actor. Removal publishes `JOB_ORDER_ASSIGNEE_REMOVED`, which clears that
member's assignment notice (REQ-NOTIF-025). Linked monetary outcomes follow the
[aUEC contract](orders-auec.md), including the original applicant's distinct role in a staff
decision.

### REQ-ORDERS-044 - Support and promise changes tell the affected writers

Participation and material notices address current writers of the affected Staffel and the
responsible unit. Explicit enrolment or withdrawal uses `JOB_ORDER_PARTICIPATION_CHANGED`. A first
promise uses the original material-change preference. A Staffel becoming responsible is covered by
the transfer cause and keeps its contributions.

Automatic ineligibility endings and order-driven changes group affected promises in one order
revision, including support endings with no open promise. Current covering contextual grants and
leadership reach determine recipients. Initial reconciliation uses the same path with a system
actor. Already ended promises and unchanged eligibility do not produce a duplicate notice.

**Acceptance**

- [ ] Creation and need changes reach the affected current writers while excluding the event's
  actor and unrelated global administrators.
- [ ] Transfer reaches the affected units once and retires the earlier responsibility prompts.
- [ ] Lifecycle changes and clarification reach current unit writers and active assigned accounts.
- [ ] Delivery of a delayed cause cannot restore an action prompt superseded by a committed change.
- [ ] Delayed closure retires its obsolete prompts and preserves prompts for later work.
- [ ] Material reductions, support changes and their derived outcomes remain one notice per
  recipient and order revision under the eligible original preferences.
- [ ] Assignment removal clears the affected account's assignment prompt.

**Enforced by:** `ProfitHierarchyNoticeIntegrationTest`, `MaterialContributionEffectsIntegrationTest`,
`JobOrderNoticeIntegrationTest`, `NotificationLifecycleEventsTest`,
`SeededNotificationRulesIntegrationTest` · **Code:** `joborder/api/events/JobOrderNotices`,
`joborder/internal/JobOrderChangeEffects`, `joborder/internal/JobOrderActionNoticeGuard`,
`joborder/internal/JobOrderLifecycleNotices`, `V384__seed_shared_order_notification_audiences.sql`

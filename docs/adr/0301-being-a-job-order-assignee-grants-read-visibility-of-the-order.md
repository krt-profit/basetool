# ADR-0301 — Being a job-order assignee grants read visibility of the order

- **Status:** Accepted — implemented: the grant, the picker, the external-assignee audit (REQ-ORDERS-047, the tenancy exception), the „Ich bearbeite“ segment and its capability flag (REQ-ORDERS-040).
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** spec [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) (the assignee-grant
  exception) · [`orders-overview-materials.md`](../specs/orders-overview-materials.md)
  (REQ-ORDERS-040) · REQ-ORDERS-047 · REQ-ORDERS-043 · REQ-AUDIT-001 ·
  [ADR-0299](0299-the-home-page-is-a-personalisable-widget-dashboard.md) · map #2800

## Context

A job order already has any number of assignees („Bearbeiter", `JobOrderAssignee`, with an optional
note and its own version): profit members sign themselves up and off, a Logistician+ adds and
removes anyone, and both are notified and audited. Volunteers for an order often come from units
that cannot see it — units that are not profit-eligible, or another unit outside the order's scope
— and are recruited outside the tool. Org-unit tenancy then hides the order from the person who is
supposed to work on it, and there is no read of "orders I am an assignee of" for a dashboard widget.

## Decision

1. **"Assigned to me" means holding an assignee entry.** No single responsible person is added;
   self sign-up and Logistician assignment stay as they are.
2. **A Logistician with access to the order may assign any active account.** The picker searches
   handles over all active accounts, lists the processing unit's members first and marks the others
   with their unit; it shows no further personal data.
3. **The assignee entry grants read visibility of that one order, in full** (materials, progress,
   assignees). An assignee who reaches the order only through the entry may edit their own note and
   remove themselves; status, lines and handovers stay with the processing unit. The grant widens
   the order's detail page, the overview's „Ich bearbeite" segment and its „Alle" list; „Zu
   bearbeiten" keeps the normal tenancy scope.
4. **The grant lasts as long as the entry exists,** whatever the order's status; leaving the unit
   does not end it. Erasing the account removes the entries (`JobOrderMemberUnlink`); no membership
   observer is added.
5. **Read and navigation:** `GET /api/v1/orders` gains `assignedToMe=true`; the overview segments
   become Meine · Ich bearbeite · Zu bearbeiten · Alle; a user without profit access who holds an
   entry sees „Aufträge" with only „Ich bearbeite", through a capability flag.
6. **Assigning someone who is not a direct member of the processing unit is audited separately**
   as `JOB_ORDER_EXTERNAL_ASSIGNEE_ADDED` / `JOB_ORDER_EXTERNAL_ASSIGNEE_REMOVED`, even when they
   could see the order anyway; no personal data in the payload.

## Consequences

- The grant is a recorded exception to org-unit tenancy: the tenancy spec, REQ-SEC and REQ-ORG name
  it, REQ-ORDERS-047 states it and its limits, REQ-ORDERS-040 gains the fourth segment.
- A Logistician can widen the visibility of one order to any account; the reach is bounded to that
  order, read-only beyond the assignee's own entry, and visible in the audit log.
- The REQ-AUDIT-001 coverage list, the unified viewer's Aufträge filter, the DE/EN labels, the
  audit-event counter and the role matrix gain the external assignee.
- The „Ich bearbeite" widget (ADR-0299) uses the page total of the filtered read as its count.

## Alternatives considered

- **Hide-only** (an assignee outside the scope stays assigned but cannot see the order): the person
  asked to do the work cannot read what to do. Rejected.
- **Member-only** (only accounts that can already see the order may be assigned): excludes exactly
  the volunteers from other or non-profit units the assignment exists for. Rejected.
- **End the grant when the order closes or the assignee leaves the unit:** the assignee loses the
  record of work they did, and the order's history becomes unreadable to them. Rejected.
- **A separate "responsible person" field:** a second concept beside the existing assignees.
  Rejected.
- **An in-tool "offer help" flow:** volunteers come forward outside the tool today. Rejected for
  this round.

> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-22.
> **Owner area:** ORDERS · **Related ADRs:** ADR-0301

# Job-order assignee notes and the assignee grant

## Context & goal

A job order (Auftrag) tracks its **Bearbeiter** — the users who signed up to work on it.
Beyond just being listed, an assignee often needs to communicate *context* to the rest of
the team: when they will work on the order, or which part they take. This spec governs the
optional free-text **note** attached to each assignee entry, who may change it, and the read
visibility an assignee entry grants (REQ-ORDERS-047).

Before this feature the assignee link was a pure many-to-many join with no extra data; the
note promoted that link to a first-class edge (`JobOrderAssignee`) carrying the note and its
own optimistic-lock version. The whole Bearbeiter section is driven by AJAX fragment swaps,
so enrolling, unenrolling and editing notes refresh the list in place without a page reload.

## Requirements

### REQ-ORDERS-013 — Per-assignee note

Each assignee entry on a job order MAY carry one optional free-text note, at most
**500 characters**. The note is plain text, stored HTML-escaped-on-render, never logged, and
visible to **everyone who can see the order**. A note may be created, replaced, or cleared
independently of the rest of the order. Editing a note bumps only the assignee edge's own
`@Version`, never the parent order's version, so a note edit never collides with an
order-level write; a stale note edit surfaces as HTTP 409.

**Acceptance**

- [ ] A user with a note set sees it rendered under their name in the Bearbeiter list.
- [ ] Setting a note over 500 characters is rejected at the API boundary (HTTP 400).
- [ ] A blank/whitespace note clears the value.
- [ ] Two concurrent note edits: the second, version-stale edit returns HTTP 409.
- [ ] Editing a note does not change the parent order's `version`.
- [ ] Setting a note is audited as `JOB_ORDER_ASSIGNEE_NOTE_SET` (only the note's length, never its
  text), clearing it as `JOB_ORDER_ASSIGNEE_NOTE_CLEARED` (REQ-AUDIT-001).

**Enforced by:** `JobOrderServiceAssigneeAndListTest` (AssigneeNoteTests), `JobOrderMapperTest`
· **Code:** `JobOrderAssigneeService.updateAssigneeNote` / `deleteAssigneeNote` (reached through
the `JobOrderService` facade), `JobOrderController.setAssigneeNote` / `deleteAssigneeNote`
(`PUT` / `DELETE /api/v1/orders/{id}/assignees/{userId}/note`), frontend
`JobOrderWriteController` + `orders-detail.js` · **Issues:** —

### REQ-ORDERS-014 — Who may change an assignee note

A user may create, edit, or delete the note **only on their own assignee entry**. A
**Logistician-or-above** (the role hierarchy promotes Officer and Admin into Logistician) may
change the note on **any** entry of an order they can see; an Admin can see every order. This
is the same self-or-logistician rule that already gates adding and removing assignees
(`verifyAssigneeAccess` + `@jobOrderAccessPolicy.canSeeJobOrder`), applied unchanged — no new
permission concept. The 403 decision lives in the controller, not the service, so business
logic stays free of `SecurityContextHolder` reads (ArchUnit invariant).

**Acceptance**

- [ ] A non-logistician editing their own note succeeds without consulting the role helper.
- [ ] A non-logistician editing another user's note is rejected (HTTP 403); the service is
  never called.
- [ ] A logistician editing another user's note on a visible order succeeds.

**Enforced by:** `JobOrderControllerTest` (set/deleteAssigneeNote auth tests)
· **Code:** `JobOrderController.verifyAssigneeAccess` · **Issues:** —

### REQ-ORDERS-047 — Being an assignee grants read visibility of that order

> **Status: planned** (ADR-0301, map #2800). Until it ships, the current text below / the current
> code stays authoritative.

An assignee entry (`JobOrderAssignee`) is the only meaning of „assigned to me"; there is no separate
responsible person. Profit members still sign themselves up and off, and a Logistician-or-above of a
visible order still adds and removes anyone (REQ-ORDERS-014). The entry additionally grants its
holder **read visibility of that one order**, an exception to org-unit tenancy (REQ-ORG-003,
ADR-0301).

- **Who may be assigned.** A Logistician with access to the order may assign **any active
  account**, including members of units that are not profit-eligible. The assignee picker is a
  handle search over all active accounts: members of the processing (responsible) unit first, every
  other hit marked with its unit, no further personal data.
- **What the grant shows.** An assignee who reaches the order only through the grant sees its detail
  **in full** (materials, progress, assignees, notes) and finds it in the „Ich bearbeite" segment
  (`assignedToMe=true`) and in the „Alle" list (`ALL`) of the overview (REQ-ORDERS-040). „Zu
  bearbeiten" (`TO_PROCESS`) keeps the normal tenancy scope, and the grant widens no other list.
- **What the grant allows.** Such an assignee may edit their own note (REQ-ORDERS-013) and remove
  themselves, nothing else. Status, priority, lines, claims, handovers and production stay with the
  processing unit; any other write answers `403`.
- **How long it lasts.** As long as the entry exists, whatever the order's status. Leaving the unit
  does not end it; removing the entry does. Account erasure removes the entries
  (`JobOrderMemberUnlink`, REQ-DATA-008); no membership observer is added.
- **Audit.** Adding or removing an assignee who is not a direct member of the processing unit
  records `JOB_ORDER_EXTERNAL_ASSIGNEE_ADDED` / `JOB_ORDER_EXTERNAL_ASSIGNEE_REMOVED` in the
  Aufträge area instead of `JOB_ORDER_ASSIGNEE_ADDED` / `_REMOVED`, also when the order is visible
  to them anyway (an SK-public order). The assignee is the target user; the details carry no
  handle, unit name or free text (REQ-AUDIT-001). The removal by account erasure keeps its existing
  summary event.
- **Notice.** The assignment notice of REQ-ORDERS-043 applies unchanged.

**Acceptance**

- [ ] An assignee outside every visible unit opens the order's detail and sees it in full; a
  unit-mate of that assignee who holds no entry still cannot open it.
- [ ] Such an order appears in the assignee's „Ich bearbeite" and „Alle" lists, never in „Zu
  bearbeiten".
- [ ] Such an assignee edits their own note and removes themselves; a status, line or handover
  write answers `403`.
- [ ] The grant survives a status change and the assignee leaving their unit, and ends with the
  entry.
- [ ] Adding and removing a non-member of the processing unit records the two external event types;
  a direct member records the existing ones.
- [ ] The picker lists the processing unit's members first and marks every other hit with its unit.

**Enforced by (to be added):** `JobOrderAccessPolicyTest` (`assigneeGrant_*`),
`JobOrderAssigneeGrantIntegrationTest`, `JobOrderServiceAssigneeAndListTest`
(`externalAssignee_*`), `AuthorizationMatrixTest`, `AdminAuditLogPageControllerTest` · **Code:**
`JobOrderAccessPolicy.canSeeJobOrder`, `JobOrderAssigneeService`, `AuditEventType` · **Related:**
REQ-ORDERS-013, REQ-ORDERS-014, REQ-ORDERS-040, REQ-ORDERS-043, REQ-ORG-003, REQ-AUDIT-001 ·
**Decision:** ADR-0301

## Out of scope

- The assignee enroll/unenroll rules themselves (who may add/remove a Bearbeiter) — unchanged
  and covered by the existing self-or-logistician rule in
  [`security-and-access.md`](security-and-access.md).
- Notes on any other aggregate (missions, operations, inventory). This is job-order only.
- Rich text / markdown — the note is plain text by design.

## Open questions

None.

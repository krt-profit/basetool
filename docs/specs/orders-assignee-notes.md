> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-22.
> **Owner area:** ORDERS · **Related ADRs:** none

# Job-order assignee notes

## Context & goal

A job order (Auftrag) tracks its **Bearbeiter** — the users who signed up to work on it.
Beyond just being listed, an assignee often needs to communicate *context* to the rest of
the team: when they will work on the order, or which part they take. This spec governs the
optional free-text **note** attached to each assignee entry, and who may change it.

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
(`verifyAssigneeAccess` + `@ownerScopeService.canSeeJobOrder`), applied unchanged — no new
permission concept. The 403 decision lives in the controller, not the service, so business
logic stays free of `SecurityContextHolder` reads (ArchUnit invariant).

**Acceptance**

- [ ] A non-logistician editing their own note succeeds without consulting the role helper.
- [ ] A non-logistician editing another user's note is rejected (HTTP 403); the service is
  never called.
- [ ] A logistician editing another user's note on a visible order succeeds.

**Enforced by:** `JobOrderControllerTest` (set/deleteAssigneeNote auth tests)
· **Code:** `JobOrderController.verifyAssigneeAccess` · **Issues:** —

## Out of scope

- The assignee enroll/unenroll rules themselves (who may add/remove a Bearbeiter) — unchanged
  and covered by the existing self-or-logistician rule in
  [`security-and-access.md`](security-and-access.md).
- Notes on any other aggregate (missions, operations, inventory). This is job-order only.
- Rich text / markdown — the note is plain text by design.

## Open questions

None.

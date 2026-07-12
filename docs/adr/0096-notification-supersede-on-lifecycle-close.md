# ADR-0096 — Clear stale "action needed" notifications on lifecycle close via an event-declared supersede hook

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** @greluc
- **Related:** REQ-NOTIF-018 · REQ-NOTIF-002 (after-commit production) · REQ-NOTIF-010 (SSE push) · REQ-NOTIF-006 (poll fallback) · REQ-BANK-026 / REQ-NOTIF-011 (bank booking-request lifecycle) · ADR-0014 (notification inbox) · ADR-0015 (data-driven rule engine) · ADR-0022 (`ACCOUNT_GRANT` selector) · #1252

## Context

When an org-unit officer/lead raises a bank booking request, the notification engine creates a
`BANK_BOOKING_REQUEST_CREATED` inbox item for the bank management and every employee granted on the
target account (REQ-BANK-026, ADR-0022). That item means "a request is waiting for you". Once the
request is **confirmed** or **rejected** by an employee, or **cancelled** (withdrawn) by the
requester, the waiting item is stale — the work it points at is done — yet it lingered in every
staff inbox until the recipient manually deleted it or the 90-day retention sweep removed it
(REQ-NOTIF-009). A pile of already-handled "new request" items is noise that hides the genuinely
pending ones.

Nothing in the substrate cleared a notification when the thing it referenced moved on. The three
lifecycle-terminating events already existed (`BANK_BOOKING_REQUEST_CONFIRMED` /
`…_REJECTED`, both notifying the requester; cancel published nothing), and every notification already
carries the loose `entity_type` + `entity_id` back-reference (ADR-0014) that ties a created-item to
its request.

## Decision

### An event declares the notification types it supersedes for its own entity

`NotificationEvent` gains `default Set<NotificationType> resolvesNotificationTypes()` (empty by
default). When `NotificationCreationService` processes an event, it deletes — **before** creating any
new notification — every outstanding notification whose `type` is in that set and whose
`entity_type` + `entity_id` match the event, across **all** recipients, in one atomic
`deleteByTypeInAndEntity` statement. The three bank lifecycle-terminating events each resolve
`BANK_BOOKING_REQUEST_CREATED`; a new notify-nobody `BANK_BOOKING_REQUEST_CANCELLED` event is added
so the requester's own withdrawal also clears the staff items (its sole pipeline effect is that
removal — it seeds no rule).

The hook lives on the generic contract, not as a bank special-case, so any future "created →
resolved" lifecycle (e.g. an order closed clearing its "new order" items) reuses it by overriding one
method and adding no engine code — consistent with REQ-NOTIF-003's "a new producer needs no schema
change" extensibility.

### Delete the rows, don't mark them read

A superseded item is removed, not flipped to read. Marking-read would leave the handled requests
cluttering the full `/notifications` history and still counting against nothing useful; the item has
no residual value once its request is decided, and the audit trail of the decision lives in the bank
audit log (REQ-BANK-024), not the inbox. Delete keeps the inbox a live work-queue.

### Run in the after-commit creation pipeline, and push to the cleared recipients too

Removal runs inside `NotificationCreationService.createFromEvent` — the same fresh, off-request-thread
transaction that creates notifications (REQ-NOTIF-002) — rather than synchronously inside the bank
decision transaction. This keeps every notification side effect on the one after-commit seam (a
rolled-back decision clears nothing; the bank service stays free of notification-repository
coupling). The method now returns the **union** of the new-notification recipients and the
recipients whose stale items were cleared, so the `AFTER_COMMIT` listener's SSE fan-out
(REQ-NOTIF-010) reaches the affected staff and their bell badge + open dropdown refresh **live** the
moment a request is decided. The in-app poll (REQ-NOTIF-006) remains the guaranteed fallback.

Removal and creation touch disjoint rows — different `type`, different recipients (staff vs
requester) — so nothing is created and immediately deleted; ordering only affects the returned
recipient set, not the data.

## Consequences

- **Positive:** handled booking requests stop cluttering staff inboxes; the fix is a generic,
  reusable substrate hook rather than a bank wart; no schema migration (open enum + a code-level
  behaviour); the affected staff see the change live.
- **Neutral:** a new `NotificationEventType.BANK_BOOKING_REQUEST_CANCELLED` (no `NotificationType`,
  so no new i18n key) and one new event record.
- **Trade-off / accepted race:** creation is async-after-commit (REQ-NOTIF-002), so a request
  decided within the sub-second window before its own created-notifications commit could, in theory,
  have the delete run first and the create land after — leaving one stale item. The window requires a
  machine-speed decide-immediately-after-create and self-heals at the next lifecycle event or the
  retention sweep; not worth serializing the two async tasks for. Not chosen: a synchronous delete in
  the decision transaction (would couple the bank service to the notification repo and still race the
  async create the same way).


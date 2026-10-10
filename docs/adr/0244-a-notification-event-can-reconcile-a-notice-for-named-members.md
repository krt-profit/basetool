# ADR-0244 — A notification event can reconcile a notice for named members

- **Status:** Accepted
- **Date:** 2026-10-10
- **Deciders:** @greluc
- **Related:** spec [`notifications.md`](../specs/notifications.md) (`REQ-NOTIF-018`,
  `REQ-NOTIF-023`) · spec [`bank.md`](../specs/bank.md) (`REQ-BANK-026`, `REQ-BANK-034`) ·
  [ADR-0096](0096-notification-supersede-on-lifecycle-close.md) (supersede on lifecycle close) ·
  issue #2413

## Context

The notification engine knows two ways to act on a notice: an event creates notifications for the
members its rules resolve, and an event can supersede — delete — every notification of named types
for its entity, for **all** recipients (ADR-0096).

Issue #2413 needs a third: when the responsible holders of a bank account change, the open requests
on that account should be announced to the new holder and no longer to the former one. The notice
about a request is one `BANK_BOOKING_REQUEST_CREATED` row per recipient, shared by the bank
management, the employees granted on the account and the responsible holder. Superseding it would
delete the staff's notices too, and raising it again would re-notify every one of them — including
staff who had deleted the notice on purpose. A separate notification type for the responsible holder
would avoid that, but gives a member who is both bank staff and responsible holder two notices for
one request and still cannot express "the holder changed".

## Decision

1. `NotificationEvent` gains `reconcileRecipients()`, a set of member ids, empty by default.
2. When it is non-empty, `NotificationCreationService` reconciles instead of creating: it evaluates
   the rules of the event's type as usual, then, **for the named members only**, creates the notice
   for each who is entitled now and holds none of `resolvesNotificationTypes()` for the entity, and
   deletes it for each who holds one and is no longer entitled. Nobody else is touched.
3. The deletion is a new per-recipient statement,
   `NotificationRepository#deleteByTypeInAndEntityForRecipients`.
4. The first use is `BankBookingRequestNoticesReconciledEvent`: published per open request when the
   holders change, evaluated against the `BANK_BOOKING_REQUEST_CREATED` rules, naming the members who
   became or stopped being holders.

## Consequences

- A former holder whom another selector still reaches — bank management, an account grant — keeps
  the notice, because the rules, not the change, decide entitlement.
- Staff notices are never deleted or duplicated by a holder change, and a member who deleted their
  notice is not notified again unless their own standing changed.
- An event that reconciles must name its notice types in `resolvesNotificationTypes()`; with none,
  or without an entity, the engine logs and does nothing.
- Rejected: a separate notification type per selector (duplicate notices for members in two
  roles); superseding for everyone and re-raising (re-notifies staff whose standing did not change);
  deleting notices directly from the bank module (bypasses the engine's live push and its module
  boundary).

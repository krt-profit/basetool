# ADR-0251 — Four-eyes for every bank booking request, admin excepted

- **Status:** Accepted
- **Date:** 2026-10-13
- **Deciders:** @greluc
- **Related:** spec [`bank.md`](../specs/bank.md) (REQ-BANK-062, REQ-BANK-041, REQ-BANK-023,
  REQ-BANK-047) · [ADR-0123](0123-responsible-holder-exempt-from-approval-limits.md) ·
  [ADR-0066](0066-krt-account-amount-tiered-approval-ladder.md) ·
  [ADR-0109](0109-krt-middle-band-bankleitung-and-over-ceiling-auto-request.md) ·
  [`SUPPLY_AND_PURCHASING_PLAN.md`](../SUPPLY_AND_PURCHASING_PLAN.md) (E-03, F-1 … F-4)

## Context

A booking request moves through two decisions: the in-app owner approval of an over-limit request
(REQ-BANK-041, the KRT ladder of REQ-BANK-047) and the confirmation by a bank employee, which books the
money (REQ-BANK-023). Neither compared the person deciding with the person who raised the request.
"Nobody approves their own request" held only structurally: the approver classes are usually other people
than the requester (ADR-0123), but a bank employee with the matching grant who files a request could
confirm it himself, and a Bankleitung member could grant the approval of a KRT-ladder request he raised.

The planned material purchasing (plan `SUPPLY_AND_PURCHASING_PLAN.md`) pays from the KRT account through
ordinary bank requests, so the control that makes a purchase request trustworthy is the same one every other
request should have. The owner decided on 2026-10-07 to apply it to the whole bank, not to purchasing
requests only.

## Decision

The member who raised a booking request **may neither grant its owner approval nor confirm it**
(`BANK_FOUR_EYES_REQUIRED`, 409). A second person decides.

- **Rejecting is free (F-1).** A rejection moves no money and is in effect the cancel the requester may do
  anyway.
- **Direct bookings are unchanged (F-2).** A bank employee's direct deposit, withdrawal and transfer, a
  payout to themselves included, is not a request and stays as it is.
- **The holder exemption stays (F-3).** A responsible holder's own request needs no owner approval
  (ADR-0123); the second person is the bank employee who confirms, and the holder may not confirm it.
- **Bank management is bound, admin is not (F-4).** An admin may grant and confirm their own request, the
  emergency path; every other role needs a second person.
- **The surfaces follow the rule.** `callerMayConfirm` of the staff queue is `false` for the requester, and
  the "Fremde Anträge" approval list leaves out the caller's own requests unless they are an admin, so the
  buttons are not offered; the service check is the gate.
- A request whose only eligible confirmer is its requester stays `PENDING` until another employee with the
  grant, a member of the bank management or an admin confirms it.

## Consequences

- A one-person bank desk can no longer clear its own request; it needs a second grant holder, bank
  management or an admin. That is the intent.
- The check lives in `BankBookingRequestService` under the row lock, next to the existing pending and
  version guards, so every caller of confirm and of the owner approval is covered, the REST controllers and
  any later command API alike.
- No schema change: the requester is `requested_by`, already on the row.

## Alternatives considered

- **Purchasing requests only.** Rejected by the owner: the rule belongs to the bank, and a second code path
  for "purpose-bound" requests would have to be kept apart from the first.
- **Binding the admin too.** Rejected: it removes the emergency path.
- **Turning a bank employee's self-payout into a request, or every direct booking into a request.**
  Rejected: the second abolishes direct booking, which the design keeps for what nobody files a request for.
- **Forbidding a requester's own rejection.** Rejected: symmetry without a risk it removes.

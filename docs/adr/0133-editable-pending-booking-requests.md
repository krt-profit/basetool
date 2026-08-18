# ADR-0133 — A pending booking request becomes editable, but only in part

- **Status:** Accepted
- **Date:** 2026-08-18
- **Context requirements:** REQ-BANK-022, REQ-BANK-041, REQ-BANK-045, REQ-BANK-055, REQ-BANK-056
- **Supersedes / amends:** amends [ADR-0021](0021-off-ledger-booking-requests.md) (off-ledger
  booking requests) by naming which of a pending request's fields the requester may still change

## Context

`BankBookingRequest` shipped as an off-ledger aggregate that is mutable *by the bank* (confirm,
reject) but frozen for its author: `account`, `type`, `amount`, `note`, `justification`,
`targetAccount`, `splitEnabled`, `splitPercent` and `requiredApprover` were all `updatable = false`,
and the requester's only move was to cancel and re-file.

For a typo in an amount or a Begründung that is the wrong trade. Cancelling loses the request's
place in the queue, produces a `BOOKING_REQUEST_CANCELLED` + `BOOKING_REQUEST_CREATED` pair that
reads like two unrelated events, and makes the requester re-enter every field.

The obvious fix — "make the row editable" — is not safe wholesale, for two independent reasons.

1. **The approval snapshot.** `requires_owner_approval` / `applicable_limit` /
   `required_approver` are snapshotted **at creation** from the requester's applicable limit
   (REQ-BANK-041). The confirm path is org-unit-blind and only reads the boolean. So a naive edit
   that changes `amount` without touching the snapshot lets a requester file 100 aUEC under a 1000
   ceiling, edit it to 100 000, and arrive at the bank employee still flagged "no approval needed".
   That is an approval-gate bypass, not a cosmetic bug.
2. **The already-granted approval.** Once the responsible holder has clicked "Freigabe erteilen",
   they approved *that amount for that reason*. An edit afterwards converts a small approved request
   into an arbitrarily large pre-approved one.

## Decision

**A pending request is editable by its author, with a deliberately partial field set and two hard
preconditions.**

### Editable

`amount`, `note`, `justification`, the `TRANSFER` destination account, and the Empfänger columns
(REQ-BANK-055). Their `updatable = false` is removed.

### Not editable: the source account and the movement kind

They stay `updatable = false`. This is the load-bearing half of the decision.

The source account determines the applicable approval limit, whether a Begründung is mandatory
(`requiresDebitJustification`), and whether the caller may request against it at all (REQ-BANK-039
view eligibility). The type determines the whole field shape — split for a deposit, destination for a
transfer, Empfänger for a withdrawal. A request whose account or type changed is not a corrected
request; it is a different one.

Allowing them would also put the edit endpoint on the eligibility-resolution path, where a mistake
is an authorization defect rather than a bad edit. Cancel-and-re-raise already covers the case and is
one click on the same row.

`requiredApprover` becomes updatable too — not as a field the client may set, but because an amount
edit must be able to re-route the approval band.

### The approval snapshot is re-derived, through the same code

`OrgUnitBankAccessService.resolveApprovalRouting(account, amount)` was **extracted** from the inline
block in `createBookingRequest` and is now called by both paths. This is the whole point: a second,
subtly different copy of the limit rule would be an approval-gate bypass. The extraction is the
mechanism that makes divergence impossible, not a tidiness refactor.

### An approved request is frozen

`BANK_REQUEST_ALREADY_APPROVED` (409). The UI withholds the edit button in that state so the
requester is not offered a dead end, but the backend is the authority — the button's absence is a
courtesy, the 409 is the guard.

### Guards, all under the row lock

Ownership (foreign id → **404**, never 403, matching `cancelOwn`'s per-user isolation so the
endpoint cannot probe which ids exist), still-`PENDING`, not-yet-approved, and the echoed
`@Version`. REQ-BANK-045 is re-checked against the unchanged source account, so an edit cannot blank
a mandatory Begründung.

### No notification

An edit fires no notification event. The request was already announced when it was raised and stays
in the same queues; the staff queue and the approval tab pick the change up through the existing
live-sync broadcast. Firing `BankBookingRequestCreatedEvent` again would re-announce a "new" request
to bank staff who already have it.

## Consequences

- The `bank_booking_request` row is no longer append-only in its business fields. V232's counterparty
  CHECK is therefore written to hold after an `UPDATE`, not only after an `INSERT`. **The ledger is
  unaffected** — `bank_transaction` / `bank_posting` stay strictly insert-only (REQ-BANK-004); a
  request is explicitly the mutable, off-ledger counterpart (ADR-0021).
- An edit produces one `BOOKING_REQUEST_UPDATED` audit row. The audit trail records *that* the
  request changed and the new amount, but not a before/after of the free-text fields — the details
  payload carries no user free text and no PII (REQ-BANK-012). Reconstructing an edit history is
  consciously out of scope; the request row itself always shows the current state.
- The edit modal is rendered **per row**, not as a primed singleton. The Empfänger picker is a remote
  combobox: it seeds itself from a server-rendered `selected` option, but the `data-field-*` priming
  mechanism writes only the hidden value, so the next blur restores the empty committed label and
  the selection is silently lost. Per-row modals also keep the echoed `version` correct after every
  fragment swap. The cost is markup proportional to the number of editable requests, which is small
  for a personal list.

## Alternatives considered

- **Full edit including account and type.** Rejected: see above. The gain is marginal (a wrong
  account is rare and cancel covers it) and it moves an authorization decision onto a write path
  whose failure mode is silent.
- **Reset the approval instead of refusing the edit.** Editing an approved request would clear
  `owner_approval_granted` and re-request approval. Rejected as the default: it silently discards a
  decision a second person already made, and the approver gets no signal that their sign-off was
  revoked. Refusing is louder and the cancel-and-re-raise path is explicit about starting over.
- **PATCH semantics.** Rejected in favour of PUT: with a per-row modal the client always holds the
  complete current state, and "an omitted field clears it" is simpler to reason about than
  distinguishing absent from null in a JSON body.
- **A separate `bank_booking_request_revision` history table.** Rejected as disproportionate — the
  audit event records that an edit happened, and the approval gate (not an edit trail) is what
  actually protects the money.


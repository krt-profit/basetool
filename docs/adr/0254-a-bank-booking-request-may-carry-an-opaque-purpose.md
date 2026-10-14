# ADR-0254 — A bank booking request may carry an opaque purpose

- **Status:** Accepted
- **Date:** 2026-10-14
- **Deciders:** @greluc
- **Related:** spec [`bank.md`](../specs/bank.md) (REQ-BANK-061, REQ-BANK-063) ·
  [ADR-0020](0020-bank-org-unit-aware-access-seam.md) ·
  [ADR-0232](0232-modules-interact-through-commands-observers-and-after-commit-events.md) ·
  [ADR-0251](0251-four-eyes-for-every-bank-booking-request-admin-excepted.md) ·
  [`SUPPLY_AND_PURCHASING_PLAN.md`](../archive/SUPPLY_AND_PURCHASING_PLAN.md) (§4.3, §5.2)

## Context

Buying material pays money out of the KRT account in tranches and returns the remainder. Each of those
movements must be an ordinary booking request on the existing ladder and queue, because the bank is
the control the design relies on: a second person confirms, the amount routes through the KRT bands.
The purchasing module, which sits above the bank, has to know which request belongs to which deal and
to learn when the bank confirmed, rejected or cancelled it. The bank, which knows nothing about org
units or trade, must stay that way (ADR-0020).

## Decision

- A request MAY carry `purpose_kind` and `purpose_ref`, both or neither. The kind is a closed enum in
  `bank.api` (`BankPurposeKind`, first value `PURCHASING_DEAL`); the reference is an id the bank never
  reads. The two columns are written only through the bank's command API
  `bank.api.PurposeBoundRequests`, never by a REST surface, and are immutable.
- The command API raises a withdrawal or a deposit on a funding account (`CARTEL`, `SPECIAL`) and
  cancels a pending request of a kind. It runs `MANDATORY` in the caller's transaction, makes the
  caller the requester, and so lets the four-eyes rule of ADR-0251 bind the trader.
- The bank reports the outcome through `BookingRequestPurposeObserver`, an observer SPI the bank owns
  and the module of the kind implements. The bank calls it inside its own transaction after the state
  change, audit row and notice event, so the owner's state equals the request's state at commit and a
  failing observer rolls the bank's decision back. The bank resolves observers lazily
  (`ObjectProvider`) because the owner depends on the command API.
- The booked transaction is not tagged; a report that splits outflows by purpose joins the
  transaction through `bank_booking_request.resulting_transaction_id`.

## Consequences

- The bank gains two nullable columns, one index, one command API and one SPI, and no knowledge of deals.
- A request that carries a purpose is indistinguishable in the bank UI from any other except for a
  read-only chip, so staff decide it like any request.
- Every cancellation of a purpose-bound request reaches the observer, including the owner's own, so an
  observer must be idempotent.
- A new purpose kind is an enum value, an observer and a funding rule, with no schema change.

## Alternatives considered

- **A foreign key from the bank to the deal.** Rejected: the bank would depend on a higher module and
  ADR-0020 would be broken.
- **An after-commit event instead of an observer.** Rejected: the deal's state could then differ from
  the request's for the moment between commit and listener, and a money state must never lag.
- **A second ledger for purchasing.** Rejected: the bank's ledger, ladder and audit already do exactly
  this.

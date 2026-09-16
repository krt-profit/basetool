# ADR-0021 — Confirm-before-post booking requests as a mutable, off-ledger aggregate

- **Status:** Accepted
- **Date:** 2026-06-17
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-BANK-022 · REQ-BANK-023 · REQ-BANK-024 · ADR-0010 · issue #666

## Context

The bank ledger is **append-only double-entry** (ADR-0010, REQ-BANK-004): `bank_transaction`
and `bank_posting` rows are never updated or deleted (no `@Version`), corrections are
`REVERSAL` transactions, and an ArchUnit rule forbids `@Modifying`/delete on the ledger
repositories. Epic #666 F2 introduces deposit/withdrawal **requests** raised by officers/leads
that must be **visible and audited immediately** but **move no money** until a bank employee
confirms the movement actually happened — recording the holder only at that point. A request
therefore has a genuine mutable lifecycle (`PENDING → CONFIRMED | REJECTED | CANCELLED`) that
the immutable ledger model cannot and must not represent.

## Decision

We will store booking requests in a **separate, mutable, off-ledger** table,
`bank_booking_request` (Flyway V159), carrying the standard optimistic-locking `@Version`. A
`PENDING` request has no posting and no holder. **Only confirmation books value**, by reusing
the existing `BankLedgerService` path — which gives the request the same account-lock,
holder-activity and **overdraft-at-confirmation** guards as a direct booking — then flips the
request to `CONFIRMED`, records the holder, and links the resulting ledger transaction. A DB
check constraint pins that only a `CONFIRMED` request carries a holder + resulting transaction.
The append-only ledger and its ArchUnit/integrity invariants stay **untouched**; off-ledger
requests contribute no postings and are excluded from the ledger-integrity sweep.

## Consequences

- The ledger keeps its append-only guarantee and integrity invariants intact; the mutable
  request lifecycle lives entirely outside it.
- Confirmation inherits every ledger guard for free (no duplicated overdraft/holder logic), and
  re-checks overdraft at confirmation time — the correct moment, since balances may have moved
  since the request was raised.
- Requests are auditable from creation (`BOOKING_REQUEST_*` events) before any money moves.
- Cost: a second persistence model for "intended" vs "booked" money; the close-account guard
  must also block on open requests (REQ-BANK-025), and the confirm path follows the
  `…WithinTransaction` discipline (request mutated by dirty-checking, pessimistically locked) so
  a double-confirm cannot double-book.

## Alternatives considered

- **A `PENDING` transaction state on the ledger** — rejected: makes the append-only ledger
  mutable, breaking ADR-0010 and the integrity/ArchUnit guarantees, and pollutes balance sums.
- **Book immediately, reverse if wrong** — rejected: the whole point is that money has *not*
  moved yet; booking-then-reversing would show phantom balances and misstate holder custody.

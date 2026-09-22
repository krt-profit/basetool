# ADR-0010 — Bank ledger: append-only double-entry with compute-on-read balances

- **Status:** Accepted (epic #556 delivered, 2026-06-13). The append-only double-entry +
  compute-on-read decision stands; the **holder-coupling aspect** (every posting names a
  holder; per-`(account, holder)` sub-balance; holder-level overdraft) is **superseded by
  [ADR-0039](0039-bank-holder-ledger-decoupled-from-accounts.md)** (2026-06-22), which moves
  the holder dimension into its own append-only ledger, decoupled from accounts. The insert-only
  ledger is further **amended by [ADR-0183](0183-a-granted-erasure-anonymises-the-handle-snapshots-in-place.md)**
  (2026-09-15) by exactly one named method, the in-place anonymisation of a granted erasure.
- **Date:** 2026-06-12
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-BANK-003..006, REQ-BANK-013 (`docs/specs/bank.md`) · ADR-0003 ·
  ADR-0009 · [ADR-0039](0039-bank-holder-ledger-decoupled-from-accounts.md) · epic
  [#556](https://github.com/krt-profit/basetool/issues/556)

## Context

The bank must record deposits, withdrawals and transfers (account ↔ account, and
intra-account **holder rebookings** — aUEC exists only on Star Citizen player accounts,
so every bank account must track which player physically holds which part of its
balance, REQ-BANK-003), keep a complete audit trail, survive a Star Citizen wipe (admin
reset to zero) **without losing history**, and produce period statements with
opening/closing balances and holder distributions. Candidate models:

- a mutable `balance` column updated in place (simple, but history-free and
  audit-hostile);
- a single-leg booking table (one row per movement with from/to columns — transfers are
  one row, deposits have a null side);
- classic double-entry: a transaction header plus 1..n signed postings.

Constraints from the codebase: the inventory area already shipped the
append-only-plus-group-on-read pattern (ADR-0003) after the merge-on-write model caused
optimistic-locking bugs; money amounts are `BigDecimal NUMERIC(19,4)` whole-aUEC
(ADR-0002); the project has repeatedly been bitten by `@Version` collisions in multi-step
write flows (CLAUDE.md concurrency section).

## Decision

We will model the ledger as **append-only double-entry**:

- `bank_transaction` — header: type (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `WIPE_RESET`,
  `REVERSAL`), initiating user (FK `ON DELETE SET NULL`), note, optional FK to a
  reversed transaction, `created_at`. Insert-only.
- `bank_posting` — leg: FK transaction, FK account, signed `NUMERIC(19,4)` amount,
  **mandatory FK holder** (`bank_holder`, the player physically holding/moving the
  money — every leg names exactly one). Insert-only. `TRANSFER` legs sum to zero per
  transaction; a `REVERSAL`'s legs are the negated mirror of the reversed transaction's
  legs (their sum is the negation of the original's sum — zero exactly when the
  original was a `TRANSFER`).
- **No UPDATE/DELETE ever** on either table; corrections are `REVERSAL` transactions
  referencing the original. The **wipe reset is itself a transaction type** — one
  `WIPE_RESET` posting per non-zero (account, holder) sub-balance — so the post-wipe
  zero state is derived the same way as every other balance and the pre-wipe history
  stays intact.
- **Balances are computed on read** (`SUM(amount)` grouped by account, and by
  account + holder for the distribution), backed by composite indexes
  `(account_id, created_at)` and `(account_id, holder_id)`. No materialized balance
  column in v1; the no-overdraft check (account level **and** per-holder sub-balance)
  runs inside the booking transaction with an atomic guard so concurrent bookings
  serialize on the account.

## Consequences

- **Easier:** statements, 30-day dashboard deltas and the 3-month export are pure range
  aggregations over one table; the audit question "what did the balance look like on
  date X" is answerable by construction.
- **Easier:** no `@Version` churn on hot rows — ledger rows are never updated, so the
  whole optimistic-locking trap class documented in CLAUDE.md cannot occur on bookings.
- **Easier:** wipe reset needs no special storage path, no deletes, no schema reset.
- **Harder / accepted:** balance reads cost an aggregate instead of a column read —
  acceptable at org scale (ADR-0009) and structurally identical to the accepted
  inventory pattern (ADR-0003). If volume ever demands it, a running-balance snapshot
  table can be added without changing the write model.
- **Accepted cost:** "edit a booking" does not exist as UX; users learn the reversal
  flow (which is the point — banks do not edit history).

## Alternatives considered

- **Mutable balance column (+ history table on the side)** — rejected: the history
  table inevitably drifts from the balance (two write paths); audit log and ledger
  would disagree; exactly the bug class ADR-0003 eliminated for the inventory.
- **Single-leg booking rows with from/to columns** — rejected: transfers become
  half-nullable rows, per-account aggregation needs UNIONs over both columns, and the
  per-holder partitioning of every account (REQ-BANK-003) does not fit a single row
  cleanly. Double-entry keeps every aggregate a single GROUP BY.
- **Event sourcing with projection rebuild** — rejected: a framework-grade pattern for
  a problem the relational ledger already solves; no replay consumer exists.
- **Hard reset on wipe (truncate ledger)** — rejected: violates the explicit
  requirement that history and audit trail survive a wipe (REQ-BANK-013).

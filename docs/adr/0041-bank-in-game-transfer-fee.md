# ADR-0041 — Bank: factor the in-game transfer fee into holder-initiated transfers

- **Status:** Superseded by [ADR-0052](0052-bank-transfer-fee-borne-by-debited-account.md)
  (2026-06-29) — the fee is now **added on top** of the entered amount and borne by the debited
  account (the entered amount is what *arrives*), reversing the carve-out semantics this ADR fixed.
  The ledger/integrity/reversal machinery below is unchanged; only the direction of the fee flips.
  Originally Accepted (epic #556 follow-up, owner-approved 2026-06-23).
- **Date:** 2026-06-23
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-BANK-033 + amended REQ-BANK-004/-006/-011/-020/-031
  (`docs/specs/bank.md`) · [ADR-0039](0039-bank-holder-ledger-decoupled-from-accounts.md)
  (the two-ledger model this builds on) · [ADR-0010](0010-bank-double-entry-append-only-ledger.md)
  · the operation payout's fee model (`OperationService`, `operation.transfer_fee_rate`) ·
  epic [#556](https://github.com/krt-profit/basetool/issues/556)

## Context

Star Citizen charges an in-game fee on every aUEC transfer a player actively initiates. The
bank's money is physically held by **holders** (ADR-0039); whenever a holder sends money in
the game — paying out a withdrawal, moving custody to another holder, or funding an
account-to-account transfer that changes the holder — the game skims that fee. If the bank
booked only the requested amount, the holder would have to cover the fee from their **private**
in-game wealth. That is unacceptable: the bank staff must suffer no disadvantage.

The operation payout already models the same in-game fee (a flat, runtime-editable rate under
`operation.transfer_fee_rate`, default 0.5%, editable at `/admin/settings`). The bank must use
the **same** rate so one knob governs the whole org.

The owner fixed the semantics: the amount a staffer enters is the **gross they send** and is
debited **in full** from the source (account + holder). The fee is **carved out of** that gross
(not added on top), so the **destination receives less** — the effective amount that arrives is
`gross − fee`. The fee and the arriving amount must both be shown explicitly. Deposits are
exempt — whoever pays money *in* bears their own fee, which is not the bank's concern.

## Decision

Record the carved-out fee on the transaction header and credit the destination the net.

- **New column `bank_transaction.transfer_fee` (`NUMERIC(19,4)`, `NOT NULL DEFAULT 0`, `>= 0`,
  V183).** Set at booking time to `round(gross × rate)` in **whole aUEC** (HALF_UP — mobiGlas
  transfers carry no fractional aUEC). The rate comes from `BankTransferFeeService`, which reads
  the same `operation.transfer_fee_rate` setting (same default + range validation as the
  operation payout).
- **Per type:**

  |                 Type                  |          Fee          |           Source leg(s)           |        Destination leg(s)        |
  |---------------------------------------|-----------------------|-----------------------------------|----------------------------------|
  | `WITHDRAWAL`                          | `round(gross × rate)` | account `−gross`, holder `−gross` | — (recipient external, gets net) |
  | `TRANSFER` (holder **changes**)       | `round(gross × rate)` | source account/holder `−gross`    | dest account/holder `+net`       |
  | `TRANSFER` (**same** holder)          | `0`                   | source account/holder `−gross`    | dest account/holder `+gross`     |
  | `HOLDER_TRANSFER`                     | `round(gross × rate)` | source holder `−gross`            | dest holder `+net`               |
  | `DEPOSIT` / `WIPE_RESET` / `REVERSAL` | `0`                   | (unchanged)                       | (unchanged)                      |

  where `net = gross − fee`. A same-holder account transfer moves no money in-game (the holder
  merely re-labels which account owns it), so it stays fee-free.

- **The source is always debited the full gross** — so the holder books exactly what they
  physically sent and is never out of pocket. The bank entity (not the staffer) absorbs the fee.

- **Display:** the booking modals show a live "Gebühr / kommt an" preview as the staffer types
  (a `GET /api/v1/bank/transfer-fee-rate` feeds `bank.js`); the account- and holder-history rows
  show the fee and the arriving amount on every outgoing fee-bearing leg.

## Consequences

- **A fee-bearing `TRANSFER` / `HOLDER_TRANSFER` no longer nets to zero across its legs — it
  nets to `−transfer_fee`** (real money lost to the game). The REQ-BANK-020 integrity checks
  (`findTransferTransactionsWithNonZeroSum`,
  `findHolderMovementTransactionsWithNonZeroSum`) are widened from `SUM(legs) = 0` to
  `SUM(legs) = −transfer_fee`. The **REVERSAL mirror** invariant is **unaffected**: a reversal
  negates the actual recorded legs (the destination leg is already net), so the original +
  reversal still cancel exactly per account/holder.
- **A reversal carries `transfer_fee = 0` and restores the full gross** to the source account and
  holder — it deliberately does **not** model a second real in-game transfer (which would itself
  cost a fee). A reversal is a bookkeeping correction of an already-recorded movement, not a new
  send; if the money must physically move back in-game that is a fresh withdrawal/transfer (which
  carries its own fee), not the reversal.
- **The ADR-0039 invariant `Σ account balances = Σ holder balances` now holds for every type
  except `HOLDER_TRANSFER`.** A holder→holder Umbuchung reduces the holder total by the fee
  without touching any account, so after internal reconciliations the **account total exceeds
  the holder total by the cumulative `HOLDER_TRANSFER` fees** — i.e. the bank's allocated-to-
  accounts figure honestly overstates the physically-held figure by the fees the game ate. The
  negative-account guard (REQ-BANK-006) is unchanged; the holder dimension may still go negative.
- **Same rate as operations** by construction (shared setting key); no separate bank knob to
  drift. The fee rounds to whole aUEC in the bank (the operation payout keeps scale 2 inside its
  own breakdown — a deliberate, isolated difference).
- **Append-only preserved:** V183 only adds a column; no row is mutated. `transfer_fee` is
  `updatable = false`.
- **Audit:** the existing per-booking audit detail gains a `(fee N aUEC)` suffix on fee-bearing
  transactions (numbers only, no PII); no new `AuditEventType` — the fee is extra detail on the
  existing deposit/withdrawal/transfer/Umbuchung events, not a new activity.

## Alternatives considered

- **Gross-up (enter the net to deliver, system computes the higher gross to send so the
  recipient still gets the net)** — rejected by the owner. The chosen model is the inverse:
  enter the gross sent, show what arrives. It mirrors the operation payout (fee = sent × rate,
  recipient gets sent − fee) and keeps "what is debited" equal to "what the holder typed".
- **Display-only (show the fee but book only the entered amount)** — rejected: the holder would
  send more than the ledger records, so their recorded stash would overstate reality and they
  *would* end up out of pocket. Booking the gross on the source is what makes them whole.
- **Model the fee as a separate explicit leg to keep transfers zero-sum** — considered;
  rejected as needless complexity. The fee is real money lost to the game, so *some* leg sum
  must be non-zero regardless; recording it on the header (and crediting the destination the
  net) is the smallest honest representation.
- **A separate bank fee rate** — rejected: the owner wants one rate for the whole org.

# ADR-0052 — Bank: the in-game transfer fee is added on top and borne by the debited account

- **Status:** Accepted (owner-requested 2026-06-29)
- **Date:** 2026-06-29
- **Deciders:** Repository owner (@greluc)
- **Related:** supersedes [ADR-0041](0041-bank-in-game-transfer-fee.md) (the carve-out model) ·
  spec REQ-BANK-033 + REQ-BANK-006/-011/-031 (`docs/specs/bank.md`) ·
  [ADR-0039](0039-bank-holder-ledger-decoupled-from-accounts.md) (the two-ledger model) ·
  [ADR-0010](0010-bank-double-entry-append-only-ledger.md) ·
  epic [#556](https://github.com/krt-profit/basetool/issues/556)

> **Amended (2026-07-05, #998, owner-approved) — the `HOLDER_TRANSFER` Umbuchung is no longer
> fee-free; its fee is borne by the KRT (`CARTEL`) account.** This ADR (and ADR-0039) exempted the
> internal holder→holder Umbuchung from the fee ("the staff bear it personally"). The owner reversed
> that for the Umbuchung: a fee-bearing Umbuchung reduces the **source holder's** custody by the fee
> (source holder leg `−(amount + fee)`), credits the destination the full `amount`, and debits the fee
> from the **CARTEL account** (a single `−fee` account leg — the one exception to "no account leg on a
>
>> `HOLDER_TRANSFER`" of ADR-0039). So the holder ledger nets to `−fee` and the CARTEL account bears
>> the real aUEC lost to the game, the same `SUM(legs) = −transfer_fee` shape as a fee-bearing
>> `TRANSFER`, except only the CARTEL account is touched. The CARTEL account is overdraft-guarded and
>> never driven negative (missing/closed → `BANK_ACCOUNT_CLOSED`, uncovered fee → `BANK_OVERDRAFT`);
>> the Umbuchung stays uncapped (no REQ-BANK-047 tier check) and the inclusive-fee mode (#999) does not
>> apply. A tiny amount whose fee rounds to `0` keeps the legacy fee-free shape. See REQ-BANK-031.
>> Rationale: the KRT (the org's central account) already absorbs org-level costs, so charging it the
>> reconciliation fee — rather than the individual staffer — matches how the org actually settles.

## Context

ADR-0041 made the bank absorb the Star Citizen in-game transfer fee so bank staff are never out
of pocket, but chose a **carve-out** model: the entered amount was the *gross sent* and was debited
in full from the source, the fee was carved out of it, and the **destination received less**
(`gross − fee`). In practice the staff think in terms of *the amount that must arrive* — "move
500 000 to that account" means 500 000 should land there, not 497 500. The carve-out model forced
the staffer to gross up by hand, and the receiving side silently came up short.

The owner reversed the decision: the entered amount must be **what arrives**, and the fee is paid
**on top** by the account the money leaves from. The owner fixed the worked example: a 500 000
transfer at the 0.5% rate must debit the source **502 500** so the destination receives the full
500 000. Accounts must never be driven negative by the fee — a booking the source cannot cover
(amount **plus** fee) is refused.

The rate stays the single org-wide `operation.transfer_fee_rate` (default 0.5%, editable at
`/admin/settings`), shared with the operation payout, rounded to whole aUEC (HALF_UP).

## Decision

Add the fee **on top** of the entered amount and book it against the debited source. The entered
amount is the amount that **arrives**; the source is debited `amount + fee`.

- **Fee** `= round(amount × rate)` in whole aUEC (HALF_UP), recorded on
  `bank_transaction.transfer_fee` exactly as before (`BankTransferFeeService.feeOn`). A new
  `BankTransferFeeService.totalDebit(amount) = amount + feeOn(amount)` names the gross debited.
- **Per type** (where `gross = amount + fee`):

  |                 Type                  |          Fee           |           Source leg(s)           |      Destination leg(s)       |
  |---------------------------------------|------------------------|-----------------------------------|-------------------------------|
  | `WITHDRAWAL`                          | `round(amount × rate)` | account `−gross`, holder `−gross` | — (recipient gets `amount`)   |
  | `TRANSFER` (holder **changes**)       | `round(amount × rate)` | source account/holder `−gross`    | dest account/holder `+amount` |
  | `TRANSFER` (**same** holder)          | `0`                    | source account/holder `−amount`   | dest account/holder `+amount` |
  | `HOLDER_TRANSFER` (Umbuchung)         | `0` (fee-free)         | source holder `−amount`           | dest holder `+amount`         |
  | `DEPOSIT` / `WIPE_RESET` / `REVERSAL` | `0`                    | (unchanged)                       | (unchanged)                   |

  The internal **`HOLDER_TRANSFER`** Umbuchung (at `/bank/manage?tab=halter`) is deliberately
  **fee-free**: it reconciles physically-held stashes among bank staff, who bear any in-game fee on
  that move **personally** — it is not a customer-facing transfer, so the bank does not model a fee
  for it (REQ-BANK-031). Only the customer-facing `WITHDRAWAL` and holder-changing `TRANSFER` carry
  the fee.

- **Overdraft (REQ-BANK-006):** the source-account no-overdraft guard runs against the **gross**
  (`amount + fee`). A booking whose account cannot cover the amount **plus** its fee is refused
  (`BANK_OVERDRAFT`) — the fee can never drive an account negative. The holder dimension stays
  unconstrained (ADR-0039): a holder may go negative.

- **Display:** the booking modals show a live "Gebühr / wird abgebucht" preview (the fee and the
  gross debited, `amount + fee`) fed by `GET /api/v1/bank/transfer-fee-rate`; the account- and
  holder-history rows keep showing, on every outgoing leg, the fee and the amount that arrived
  (`|leg| − fee`, which now equals the entered amount).

## Consequences

- **The ledger machinery is unchanged.** For a fee-bearing holder-changing `TRANSFER`, source
  `−(amount + fee)` and destination `+amount` still sum to `−fee`, so the REQ-BANK-020 integrity
  invariant (`SUM(legs) = −transfer_fee`) holds verbatim; the fee-free `HOLDER_TRANSFER` carries
  `transfer_fee = 0` and nets to zero, which the same check accepts. The `REVERSAL` negated-mirror
  invariant is untouched, and V183's `transfer_fee` column needs no migration. Only the *direction*
  of the fee flips on the customer-facing moves: which side keeps the fee (source) and which gets the
  round number (destination).
- **A reversal still restores the full gross** to the source account and holder and carries
  `transfer_fee = 0` — it negates the actual recorded legs (source `+gross`, destination `−amount`),
  so the source is made whole and the destination gives back exactly what arrived. No second
  in-game fee is modelled (a real re-send is a fresh booking).
- **History rows need no logic change.** An outgoing leg is the gross debited, so the existing
  "`|amount| − transferFee`" display is still the amount that arrived; only the preview's second
  figure flips from "what arrives" to "what is debited" (new `bank.fee.debited.label`).
- **A withdrawal/transfer of the exact balance now fails** when a fee applies — the account must
  hold the amount *and* its fee. This is the intended guard, not a regression.
- **Same rate as operations** by construction (shared setting key); the carve-out ADR-0041 is
  superseded but its ledger/integrity/reversal reasoning carries over unchanged.

## Alternatives considered

- **Keep the carve-out model (ADR-0041)** — rejected by the owner: the receiving account silently
  came up short and staff had to gross up by hand to make a round number arrive.
- **Solve `gross = amount / (1 − rate)` for the exact in-game fee** (the fee is really charged on
  the amount *sent*) — rejected: the owner specified the simpler arithmetic (`fee = amount × rate`,
  debit `amount + fee`, e.g. 502 500 for 500 000 @ 0.5%), matching how staff reason about it and
  mirroring the operation payout's `× rate` formula.
- **Model the fee as a separate explicit leg to keep transfers zero-sum** — rejected as needless
  complexity, same as in ADR-0041: the fee is real money lost to the game, so some leg sum must be
  non-zero; recording it on the header is the smallest honest representation.
- **A separate bank fee rate** — rejected: one rate governs the whole org.


# ADR-0039 — Bank: holder custody decoupled from accounts via a second append-only ledger

- **Status:** Accepted (epic #556 follow-up, owner-approved 2026-06-22)
- **Date:** 2026-06-22
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-BANK-003/-004/-006/-011/-013/-020/-031 (`docs/specs/bank.md`) ·
  [ADR-0010](0010-bank-double-entry-append-only-ledger.md) (holder-coupling aspect superseded) ·
  ADR-0002 · epic [#556](https://github.com/krt-profit/basetool/issues/556)

## Context

The original ledger (ADR-0010, REQ-BANK-003/-006) coupled **every** posting to a
`(account, holder)` pair: the per-`(account, holder)` sub-balance summed exactly to the
account balance, and a withdrawal was guarded at **both** the account level **and** the
holder's sub-balance **on that account**.

That contradicts the physical reality of Star Citizen aUEC. aUEC sits on a *player's*
in-game account, not on the bank's notion of which org account "booked" it. In practice a
custodian receives money attributed to one account and pays out a request booked against a
different account: a holder is credited 1 000 000 aUEC via Staffel Iridium and 1 000 000
via Staffel Palladium, then pays out a 2 000 000 request booked against Staffel Vanadium
using the money he physically holds. The custodian may even **front his own money** — going
temporarily negative — to satisfy a payout, to be reconciled later by redistribution among
the bank staff.

So the holder's physical custody must be a single **global** figure, decoupled from any
account; a holder balance may go **negative**; and the **account** figure must stay
**hard-guarded at ≥ 0** (the org never books more out of an account than it holds).

## Decision

We will split the ledger into **two append-only dimensions** that share one
`bank_transaction` header:

- **`bank_posting` — account dimension only:** `(transaction, account NOT NULL, signed
  amount, created_at)`. The `holder_id` column is **dropped**. Account balance =
  `SUM(amount)` grouped by account.
- **`bank_holder_posting` — holder dimension only (new table):** `(transaction, holder NOT
  NULL, signed amount, created_at)`. A holder's **global** balance = `SUM(amount)` grouped
  by holder over the whole bank — never partitioned by account.

Each transaction co-records exactly the legs its type needs:

|                       Type                        |                 Account legs                  |                 Holder legs                  |
|---------------------------------------------------|-----------------------------------------------|----------------------------------------------|
| `DEPOSIT`                                         | `+X` on A                                     | `+X` on H                                    |
| `WITHDRAWAL`                                      | `−X` on A                                     | `−X` on H                                    |
| `TRANSFER` (account→account, **retained**)        | `−X` on A, `+X` on B                          | `−X` on H_src, `+X` on H_dst                 |
| `HOLDER_TRANSFER` (**new** — pure reconciliation) | — (none)                                      | `−X` on H_src, `+X` on H_dst                 |
| `WIPE_RESET`                                      | one `−balance` leg per non-zero account       | one `−balance` leg per non-zero holder       |
| `REVERSAL`                                        | negated mirror of the original's account legs | negated mirror of the original's holder legs |

**Overdraft is asymmetric:**

- **Account dimension — hard-guarded.** No posting may drive an account below zero. The
  check runs under the account row's pessimistic lock (unchanged from ADR-0010), so
  concurrent bookings cannot jointly overdraw an account. Applies to `WITHDRAWAL`, the
  account→account `TRANSFER` source, and the account legs of a `REVERSAL`.
- **Holder dimension — unconstrained.** A holder balance may go negative; the imbalance is
  corrected later by a `HOLDER_TRANSFER` (REQ-BANK-031). No holder-coverage check exists on
  any path. `BANK_HOLDER_OVERDRAFT` is retired.

Both tables are insert-only; corrections are `REVERSAL` transactions. The invariant
`Σ account balances = Σ holder balances` holds by construction (deposit/withdrawal move both
dimensions equally; account→account transfers and holder→holder transfers are internal to
their own dimension and net to zero). **Amended by
[ADR-0041](0041-bank-in-game-transfer-fee.md), then restored by
[ADR-0052](0052-bank-transfer-fee-borne-by-debited-account.md):** ADR-0041's fee-bearing
`HOLDER_TRANSFER` temporarily broke this (it reduced the holder total without touching any account,
so the account total exceeded the holder total by the cumulative Umbuchung fees). ADR-0052 makes the
internal `HOLDER_TRANSFER` Umbuchung **fee-free** again — and books a customer-facing
withdrawal/transfer's fee equally on both the account and holder dimensions — so the invariant holds
by construction once more for every type. (Historical fee-bearing Umbuchung rows booked under
ADR-0041 keep their recorded fee on the append-only ledger.)

## Consequences

- **Easier:** the "which account booked it vs which player holds it" mismatch is
  representable; reconciliation between custodians is a first-class operation.
- **Easier:** a holder's balance is one global `SUM` (the existing `holderTotal` query); no
  per-`(account, holder)` matrix to maintain.
- **Harder / accepted:** an account no longer has a meaningful per-account holder
  distribution — the account-detail panel and the statement/management-report distribution
  sections are **removed** (REQ-BANK-014/-015 amended). The per-booking holder *annotation*
  on an account's history is derived via the shared `bank_transaction` (the sibling holder
  leg), not a column on `bank_posting`.
- **Harder / accepted:** two insert-only tables instead of one; the integrity sweep and the
  reversal mirror span both. The per-`(account, holder)` negative-balance integrity check is
  **removed** (negative holder balances are now legal); the negative-**account** check stays
  (it must never fire).
- **Migration:** `V180` creates `bank_holder_posting` and backfills one holder leg per
  existing `bank_posting` row (history preserved); `V181` drops `bank_posting.holder_id` with
  its FK/index. No row is ever mutated — append-only is preserved (insert + structural change
  only).
- **ArchUnit** `bankLedgerRepositoriesMustStayInsertOnly` extends to
  `BankHolderPostingRepository`.

## Alternatives considered

- **Single `bank_posting` table with nullable `account_id` + `holder_id` + a CHECK** —
  recommended by the implementer for the smaller migration, **rejected by the owner** in
  favour of a clean, self-describing second ledger (one table = one dimension). The nullable
  form overloads each row to do double duty and mixes coupled and single-dimension rows in
  one table; the separate ledger keeps each dimension's queries and invariants trivial, at
  the cost of a larger migration.
- **Keep the `(account, holder)` coupling and merely relabel the global total as
  authoritative** — rejected: the asymmetric overdraft (account hard, holder free) and the
  negative-holder allowance cannot be expressed while the invariant "sub-balances sum to the
  account balance" still holds.
- **Holder balance per account (status quo, ADR-0010)** — rejected: contradicts the physical
  reality that a player's aUEC is not partitioned by the bank's account structure.

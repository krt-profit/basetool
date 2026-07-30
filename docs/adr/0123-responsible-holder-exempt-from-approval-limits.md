# ADR-0123 — The account's responsible holder is exempt from approval limits

- **Status:** Accepted
- **Date:** 2026-07-30
- **Deciders:** @greluc
- **Related:** spec REQ-BANK-041 · REQ-BANK-034 · REQ-BANK-047 · ADR-0045 · ADR-0066 · ADR-0109

## Context

REQ-BANK-041 resolves a per-account approval limit for the requester of a withdrawal/transfer and
snapshots `requires_owner_approval` onto the request. An earlier amendment **inverted the
missing-limit semantics** to make approval the safe default:

```
requires_owner_approval = (applicable_limit == null) || amount > applicable_limit
```

That computation is **requester-agnostic** — it never asked *who* is requesting. The account's
responsible holder (REQ-BANK-034: the Staffelleiter / SK-Leiter of an `ORG_UNIT` account, the
Bereichsleiter of an `AREA` account, any OL member on the KRT `CARTEL` account) was resolved through
exactly the same tiers as everyone else. Because `STAFFELLEITER` is not even an addressable limit
bucket (`SQUADRON_ROLE_BUCKETS` = `KOMMANDOLEITER`, `STELLV_KOMMANDOLEITER`, `ENSIGN`), a holder
matched no tier at all and fell through to *approval required*.

The result observed in production on 2026-07-29: the Staffelleiter of NEMESIS raised a 2 000 000 aUEC
withdrawal on **his own** squadron account and the request was flagged `ÜBER LIMIT`, with himself as
the `RESPONSIBLE_HOLDER` who had to approve it. The only way to clear the flag was for him to click
"Freigabe erteilen" on his own request — a pure no-op ritual. This was not an isolated
misconfiguration: at the time **16 of 17** active `ORG_UNIT` accounts and **all 6** `AREA` accounts
carried zero limit rows, so effectively every debit request org-wide was flagged, and the marker lost
all signal value.

The label compounded it: `applicable_limit` was `null`, so nothing had been exceeded — the chip
claimed "Über Limit" where the truth was "no limit is configured".

## Decision

We will **exempt the account's responsible holder from every approval gate on that account**, and
evaluate the exemption **before** any limit or ladder resolution:

```
requires_owner_approval = !isResponsibleHolder && ((applicable_limit == null) || amount > applicable_limit)
```

- A limit is an instrument the holder wields **against third parties**. It is not a control *over*
  the holder, who is by definition the person accountable for the account.
- The exemption is **uniform across account types**, including the KRT (`CARTEL`) account, whose
  amount-tiered ladder (REQ-BANK-047, ADR-0109) an OL member likewise bypasses. Splitting the rule by
  account type would have made "who is exempt" depend on a second, invisible axis.
- For an exempt holder the whole snapshot is empty: `requires_owner_approval = false`,
  `applicable_limit = null`, `required_approver = null`. No limit row is even read.
- The read paths (`listOverseenOrgUnitBalances`, `getViewableAccountDetail`) carry a new
  `approvalExempt` flag, which rides onto the request modal's source-account `<option>` as
  `data-exempt`. Without it the client could not tell "exempt" from "no limit configured", since both
  present as a `null` limit — and the latter must still warn.

**Explicitly out of scope:** this removes the *second signature*, not the booking. A holder's request
is still filed `PENDING` and still has to be confirmed and booked by a bank employee / the
Bankleitung (REQ-BANK-023) — that step is what moves the money and mirrors the in-game booking. What
falls away is only the two-step owner approval: the in-app "Freigabe erteilen" and the bank
employee's mandatory *"Freigabe durch Kontoverantwortlichen erfolgt"* checkbox.

Also out of scope: `exceedsCartelDirectBookingCeiling`, the ceiling on a bank employee's **direct**
ledger booking against the KRT account. That is a bank-staff ledger control, not an ownership
question, and it still converts an over-`T1` direct booking into a request. Only the approver on the
resulting request is dropped when the acting employee is themselves an OL member — so that no one is
ever routed back to themselves.

## Consequences

**Positive**

- The no-op self-approval disappears. A holder's request goes straight to the bank queue.
- The `ÜBER LIMIT` marker regains signal: it now appears only where a *third party* genuinely needs a
  holder's sign-off.
- Limits become configurable-as-intended — an account can be left with no limits at all and the
  holder still operates it freely, while everyone else needs approval.

**Negative / risks**

- A squadron account has **no second pair of eyes** on the holder's own debits. This is deliberate:
  the accountable party is the holder, and the bank-side confirmation (REQ-BANK-023) plus the full
  audit trail (REQ-BANK-024) remain as the detective controls.
- On the KRT account this widens the ADR-0109 ladder's exception from "nobody" to "OL members". The
  ladder still governs every other requester unchanged.
- The exemption is snapshotted at create time, so **requests filed before this change keep their
  flag**. They are cleared the normal way (holder grants approval, or the bank employee ticks the
  checkbox); no backfill migration is run, since the snapshot is an audit record of the rule that
  applied when the request was raised.


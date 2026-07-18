# ADR-0109 — KRT middle-band approver is the Bankleitung, and an over-ceiling direct booking auto-files a request

- **Status:** Accepted
- **Date:** 2026-07-18
- **Deciders:** @greluc
- **Related:** amends [ADR-0066](0066-krt-account-amount-tiered-approval-ladder.md) (KRT amount-tiered
  approval ladder) · spec REQ-BANK-047 · builds on ADR-0020 (org-unit-aware bank seam), ADR-0021/0022
  (off-ledger booking requests + notifications)

## Context

ADR-0066 gave the KRT (`CARTEL`) account an amount-tiered approval ladder for money leaving it: below
the bank-employee ceiling `T1` a bank employee self-approves; between `T1` and `T2` the **Bereichsleiter
Profit** approves; above `T2` the **Organisationsleitung**. Two problems surfaced in use:

1. **Wrong middle-band approver.** The owner's intent for the `T1..T2` band is the **actual bank
   management** (the Bankleitung, `ROLE_BANK_MANAGEMENT`), not the Bereichsleiter Profit. Routing it to
   the Profit Bereichsleiter was a mistake in ADR-0066.

2. **The direct-booking cap was a dead end.** A plain bank employee's *direct* over-`T1` withdrawal /
   transfer was refused with `409 BANK_CARTEL_APPROVAL_REQUIRED`. The frontend never mapped that code to
   a message, so the user saw the generic „Ein unerwarteter Fehler ist aufgetreten" — and even with the
   right message there was no path forward from the booking modal (the request-raising surface lives on a
   different page, `/org-unit-bank`). The owner wants the over-ceiling attempt to be **filed as a request
   automatically** and the employee told to notify the Bankleitung.

The two hard bank invariants are unchanged: `bankClassesMustNotConsultOrgUnitScope` and
`orgUnitAwareBankSeamIsContainedToOneClass` — every org-unit-aware decision stays in the single
`OrgUnitBankAccessService` seam; and **no regression** for non-KRT accounts.

## Decision

1. **Middle band → Bankleitung.** `BankRequestApprover.AREA_LEAD_PROFIT` is renamed to **`BANK_MANAGEMENT`**
   (V222 rewrites persisted `required_approver` rows). The `T1..T2` band resolves to a plain Keycloak-role
   check — `authHelperService.hasReachableRole(BANK_MANAGEMENT)` — in the seam's band computation,
   `canApprove`, `canSeeForeignRequest` and `listRequestsForResponsibleAccounts`. `isProfitBereichsleiter`
   stays, but only for the `CARTEL_BANK` responsible holder (REQ-BANK-037); it is no longer the KRT
   middle-band approver. Because `BANK_MANAGEMENT > BANK_EMPLOYEE` in the role hierarchy, the Bankleitung
   both grants the in-app approval **and** confirms/books the request from the bank-staff queue
   (`/bank/requests`).

2. **Over-ceiling direct booking → auto-request (`202`).** `BankBookingGuards.requireCartelDirectBookingAllowed`
   (which threw) becomes the boolean `exceedsCartelDirectBookingCeiling`. When it is true,
   `BankBookingController.bookWithdrawal` / `bookTransfer` no longer book and no longer throw: they call the
   new seam method `OrgUnitBankAccessService.raiseCartelDirectBookingRequest`, which stamps the band-routed
   `required_approver` (shared `resolveCartelApprovalRouting` with the officer create path) and files a
   `PENDING` request via the existing `create(...)` path (audit + notification included). The endpoints now
   return a **`BankBookingOutcomeDto`** — `201` + `transaction` when booked, `202` + `pendingRequest` when
   filed. The holder and any Empfänger from the direct-booking form are **not** carried over — like every
   booking request they are (re-)recorded by the confirming bank employee (REQ-BANK-040); only amount, note
   and justification transfer. The controller reaching the seam violates neither ArchUnit pin (it depends on
   the seam, not on `OwnerScopeService`, and does not itself bridge `OwnerScopeService` + the account repo).

3. **Notifications.** `resolveResponsibleHolderUserIds(CARTEL)` returns **all `OL_MEMBER`s** only; the
   Profit-Bereichsleiter is dropped from the CARTEL union (it stays the `CARTEL_BANK` responsible holder).
   The middle-band Bankleitung is a Keycloak realm role, not an org-unit membership, so it is not enumerable
   through the membership-based notification seam; the Bankleitung picks the request up in its bank-staff
   queue and the requester notifies it directly (the frontend shows a „Antrag angelegt — der Bankleitung
   Bescheid geben" notice on the `202`). Consequently a Profit-Bereich leadership change no longer ripples
   onto the KRT responsible-holder audit (`CARTEL_BANK` still does).

4. **Frontend error-message repair.** The previously-unmapped bank `409` codes (`BANK_OWNER_APPROVAL_REQUIRED`,
   `BANK_JUSTIFICATION_REQUIRED`, `BANK_FEE_EXCEEDS_AMOUNT`, `BANK_NOT_REVERSIBLE`, `BANK_REQUEST_NOT_PENDING`,
   `BANK_ACCOUNT_HAS_PENDING_REQUESTS`, `BANK_SPLIT_NO_TARGETS`, `BANK_SPLIT_TOO_SMALL` and the now-retired
   `BANK_CARTEL_APPROVAL_REQUIRED`) are added to the frontend `GlobalExceptionHandler` code→message map with
   dedicated `error.bank.*` keys, so they no longer fall through to `error.unexpected`. The KRT ladder labels
   („Bereichsleiter Profit" → „Bankleitung") are corrected on the read-only ladder and the threshold editor.

## Consequences

- The KRT `T1..T2` band is now approved by the Bankleitung, matching the owner's intent; the ladder reads
  bank employee → Bankleitung → Organisationsleitung.
- A bank employee's over-ceiling KRT booking is a smooth „filed for approval" flow instead of a `409` dead
  end, and every bank business-`409` now carries a meaningful inline message.
- The `CARTEL_APPROVAL_REQUIRED` code is retained in the error vocabulary but is no longer thrown on the
  happy path.
- Both ArchUnit pins stay green; the threshold storage/editor stays org-unit-blind; all band routing stays in
  the one seam.
- Out of scope (unchanged from ADR-0066): cumulative/period budgets, per-approver quotas, configurable
  approver graphs, and carrying holder/Empfänger onto an auto-filed request.


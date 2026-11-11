> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-09.
> **Owner area:** BANK · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)

> **Status:** Bank-owned linked request and direct outcome notices are implemented. Cross-domain
> grouping and the composed aUEC journey remain part of the approved target.
> **Approved target:** [orders-auec-bank.md](orders-auec-bank.md) (REQ-ORDERS-076), ADR-0357.

# Bank notifications

## Context & goal

Issue #2414 closes the gaps of the bank notices (`bank.md`, REQ-BANK-026, -034, -041): the people who
must act on or know about an approval, a grant, a direct booking or a holder hear about it in the
inbox instead of finding it on the page. Each is published after the commit, never for the actor,
carries only amounts, account numbers, a holder handle and the acting staff member's display name,
and has a seeded, admin-editable rule. Each can be muted (REQ-NOTIF-027).

## Requirements

### REQ-BANK-057 — An approved request is ready to confirm

> Linked authorisation requests use the same readiness path and ADR-0353 attestation.
> A holder-transfer request goes directly to the Bank queue. The account-owner step applies to
> the linked actions that require it.

`BankBookingRequestService#applyOwnerApprovalWithinTransaction` (reached by
`OrgUnitBankRequestService#grantOwnerApproval`) publishes `BANK_BOOKING_REQUEST_APPROVED` when the
approval is granted. The default rule notifies the bank management (`ROLE` `BANK_MANAGEMENT`), the
bank employees granted on the account (`ACCOUNT_GRANT`) and the requester (`EVENT_RECIPIENT`), names
the request, its amount, the account and the approver, and excludes the approver. The approval
also clears the approval-needed notice (REQ-NOTIF-033). Revoking the approval publishes
`BANK_BOOKING_REQUEST_APPROVAL_REVOKED`, which clears the notice and tells the required approvers
again that their approval is needed; confirming, rejecting and cancelling the request clear it too
(`DECIDED_REQUEST_NOTICES`, REQ-NOTIF-018).

### REQ-BANK-058 — A change to a bank grant tells the grantee

`BankGrantService#createGrant`, `#updateGrant` and `#deleteGrant` publish `BANK_GRANT_CHANGED` (coded
`grantChangeCode` `CREATED` or `UPDATED`, with the three rights as `YES` / `NO` words) or
`BANK_GRANT_REVOKED`. The default rules notify the grantee (`EVENT_RECIPIENT`), never the actor. Each
notice replaces the grantee's earlier grant notices for that account (per-recipient supersede,
REQ-NOTIF-025) and leaves every other grantee's alone.

### REQ-BANK-059 — A direct booking by bank staff tells the members it affects

> **Approved composition target:** Group the same logical outcome/recipient across Bank/order
> roles, retaining necessary approval
> tasks, mute and priority. Later staff decisions use that actor, not the applicant. Rollback,
> replay and duplicate callbacks announce no extra mutation. Private notes and unrelated Bank data
> do not enter order notices. REQ-ORDERS-073/-075/-076 owns the order meaning.

`BankBookingController` (withdrawal, transfer, reversal) and `BankHolderController` (holder transfer)
call `BankDirectBookingNotifier` after the ledger booked; the ledger itself publishes nothing, because
confirming a request books through it and already has its own notice. A withdrawal with a member
counterparty publishes `BANK_PAYOUT_BOOKED` (the member paid out to); a holder transfer publishes
`BANK_HOLDER_TRANSFER_BOOKED` (the receiving holder's member, skipped for a holder without one); a
withdrawal, a transfer's source account and every account leg of a reversal publish
`BANK_ACCOUNT_DEBITED` (the account's responsible holders, `ACCOUNT_RESPONSIBLE`; coded `debitCode`
`WITHDRAWAL`, `TRANSFER` or `REVERSAL`). Deposits are never announced, because a split deposit fans out
over every squadron account. A booking that an over-limit request replaced announces nothing.

The linked Bank command publishes direct booking notices in its transaction. A direct no-ledger
authorisation emits `BANK_FUNDS_AUTHORISED` for the funding account's responsible holders, with
the authorised amount and the deciding employee. Its rule excludes that employee and honours
per-type muting. A linked request uses the ordinary request lifecycle, with the actual decider and
without a second direct notice. A holder-transfer request reaches Bank management under the
existing request-created rule and appears in the ordinary Bank staff queue. It has no account
grantee or approval task.

### REQ-BANK-060 — A deactivated holder who still holds money tells the bank management

`BankHolderReconciliationService#reconcileAll` publishes `BANK_HOLDER_DEACTIVATED_WITH_BALANCE` when it
deactivates a holder whose balance is not zero; the default rule notifies the bank management with the
holder's handle and the balance. The notice goes when the holder is reactivated (the sweep or
`BankHolderService#updateHolder`) or when a posting brings their balance to zero
(`BankPostingWriter`), through `BANK_HOLDER_NOTICE_CLEARED`.

**Acceptance**

- [x] Each event reaches the members its seeded rule names and never the actor.
- [x] Revoking an approval and deciding the request clear the ready-to-confirm notice.
- [x] A grant notice replaces the grantee's earlier one and nobody else's.
- [x] A deposit, a booking that only raised a request and a holder without a member tell nobody.
- [x] A holder without money, a reactivated holder and an emptied holder have no standing notice.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Approved target acceptance - not implemented**

- [ ] New linked requests retain necessary approval/readiness tasks and remove stale prompts under current access.
- [ ] One logical Bank/order outcome reaches each recipient once across overlapping roles; a muted outcome is not recreated as order success.
- [ ] Direct/requested/no-ledger/replay/rollback paths produce truthful, nonduplicated notices.
- [ ] Staff decisions use their real actor and disclose no private approval/staff note through order notices.

**Baseline enforced by:** `BankNoticeIntegrationTest`, `BankDirectBookingNotifierTest`,
`BankBookingRequestServiceTest` (`applyOwnerApprovalWithinTransaction_…`), `BankGrantServiceTest`,
`BankHolderReconciliationServiceTest`, `BankHolderServiceTest`, `BankPostingWriterNoticeTest`,
`SeededNotificationRulesIntegrationTest`, `NotificationPageControllerTest` (`targetOf_…`) ·
**Code:** `bank/api/events/BankNotices`, `bank/internal/BankDirectBookingNotifier`,
`BankActorResolver`, `BankBookingRequestService`, `BankGrantService`,
`BankHolderReconciliationService`, `BankHolderService`, `BankPostingWriter`,
`bank/web/BankBookingController`, `BankHolderController`,
`V278__seed_bank_notification_rules.sql` · **Issues:** #2414

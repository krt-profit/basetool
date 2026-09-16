# ADR-0045 — Bank: view-based request eligibility, user-initiated transfers & per-account approval limits

- **Status:** Accepted (amended 2026-06-28 — see *Amendment* below)
- **Date:** 2026-06-27
- **Deciders:** @greluc
- **Related:** spec REQ-BANK-039 / REQ-BANK-040 / REQ-BANK-041 / REQ-BANK-042 (amends
  REQ-BANK-022/-023/-027) · builds on ADR-0020 (org-unit seam), ADR-0021 (off-ledger requests),
  ADR-0043 (responsibility & visibility) · concurrency rule in `CLAUDE.md`

## Context

The confirm-before-post booking request (ADR-0021) let only the **own-level oversight** seats of an
org unit raise deposit/withdrawal requests, and transfers were a pure bank-staff operation. The
owner wants three coupled changes: (1) let any authorised user transfer from an account they hold to
another account; (2) let anyone who may **view** an account request against it; (3) give the account's
responsible holder (ADR-0043) a way to delegate — set per-tier limits up to which a tier may request
without explicit approval, and require explicit approval above them.

Two hard constraints shape the design. First, the ArchUnit invariant
`bankClassesMustNotConsultOrgUnitScope` (ADR-0011/0020): no `Bank*`-named class may consult
`OwnerScopeService`; all org-unit-aware decisions live in the single `OrgUnitBankAccessService` seam.
Second, **no regression**: accounts with no limits configured must behave exactly as before.

## Decision

We will:

1. **Make booking-request eligibility equal view eligibility.** The seam gates request creation on
   `canView(account)` (REQ-BANK-039) for the request-capable types `ORG_UNIT` / `AREA` / `CARTEL`,
   replacing the own-level-oversight gate.

2. **Add a `TRANSFER` request type** with a nullable `target_account_id` (any active account as
   destination). Confirmation books a real transfer through the existing `BankLedgerService.bookTransfer`
   path — reusing the destination-visibility check, the in-game fee and the account/holder legs
   (REQ-BANK-040) — with the bank `can_transfer` capability required on the source.

3. **Add per-account, per-tier approval limits** (`bank_account_approval_limit`, tiers mirroring the
   REQ-BANK-035 visibility buckets). The requester's applicable limit is **resolved in the seam at
   creation time** (individual > max-of-role-tiers > all-members > unlimited) and **snapshotted** onto
   the request (`requires_owner_approval`, `applicable_limit`). The org-unit-blind confirm path reads
   only the snapshot boolean. A missing limit = unlimited (no regression).

4. **Make over-limit approval two-step:** the responsible holder may grant it in-app from a new
   "Fremde Anträge" tab (recorded on the request), and the bank employee must tick a mandatory
   "approval obtained" checkbox at confirmation (pre-filled when the holder granted it). All four
   actions — limit set/clear, holder grant/revoke, employee confirmation — are audited.

Limits are configurable by the responsible holder, bank management and admin; never by a plain bank
employee. The dimension helpers (`configurable` / `allMembersSupported` / `roleBuckets`) are pure
static functions so they run identically wherever called and need no org-unit input.

## Consequences

- **Easier:** members the holder trusts can transact through requests without bespoke per-action
  grants; the holder self-services delegation via limits; the bank stays org-unit-blind because the
  only org-unit-aware step (limit resolution) happens once, in the seam, and is frozen onto the row.
- **Harder / costs we accept:** more people can file requests (bank confirmation still gatekeeps every
  booking, so this is safe but noisier — notably every KRT member may file against the `CARTEL`
  account); the approval state lives on the request `@Version`, so a holder's grant and an employee's
  confirm serialise (a concurrent confirm 409s and reloads — existing behaviour). Approval limits are
  **per-request** ceilings, not cumulative budgets.
- **Follow-up:** none required; cumulative/period budgets and notifications for over-limit requests are
  explicitly out of scope for now.

## Alternatives considered

- **Resolve the limit at confirmation time** — rejected: it would force the org-unit-blind confirm
  path (a `Bank*` class) to consult `OwnerScopeService`, breaking the ArchUnit seam invariant. The
  creation-time snapshot keeps the bank blind.
- **Grant request-eligibility through the existing `bank_account_grant` capability rows** — rejected:
  those are the bank-staff capability surface (ADR-0011); reusing them for org-unit members would
  conflate two audiences. View-based eligibility reuses the holder-configured visibility the holder
  already controls (ADR-0043).
- **A separate `TRANSFER_REQUEST_*` audit/event family** — rejected: a transfer request is the same
  off-ledger aggregate; reusing `BOOKING_REQUEST_*` with the type in the payload avoids a parallel
  lifecycle.

## Amendment (2026-06-28, @greluc) — deposits are unrestricted (REQ-BANK-042)

Decisions (1) and (3) above are scoped to money *leaving* an account. The owner decided that a
**deposit** request — which only *adds* money and is confirmed by a bank employee on in-game receipt
— carries no risk that view-gating or limits would mitigate, so it is exempt from both:

- **Eligibility (amends decision 1 / REQ-BANK-039):** a deposit is requestable by **any
  authenticated user against any `ACTIVE` account** — every type (incl. `SPECIAL` / `CARTEL_BANK`),
  with no `canView` and no request-capable-type gate. Withdrawals and transfers keep view-based
  eligibility unchanged.
- **Approval limits (amends decision 3 / REQ-BANK-041):** a deposit is never flagged
  `requires_owner_approval` and resolves no `applicable_limit`; the limit rows are not consulted.
  Limits remain in force for withdrawals and transfers.

The carve-out is a type branch at the top of `OrgUnitBankAccessService.createBookingRequest` — the
deposit path consults neither `OwnerScopeService` nor the limit rows, so the ArchUnit seam invariant
and the org-unit-blind confirm path are untouched. The only guard left on a deposit is the
account-active check in `BankBookingRequestService.create` (`BANK_ACCOUNT_CLOSED`). The org-unit bank
page shows the request CTA whenever any active account exists and offers a merged source picker (all
active accounts for a deposit, the caller's request-capable accounts for a withdrawal/transfer).

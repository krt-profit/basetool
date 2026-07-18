# ADR-0066 — Bank: KRT-account amount-tiered 3-stage approval ladder, "Mitglieder des Bereichs" audience & members-only ALL_MEMBERS limits

- **Status:** Accepted — the middle-band approver (→ **Bankleitung**, was Bereichsleiter Profit) and
  the over-ceiling direct-booking behavior (→ **auto-request `202`**, was `409`) are amended by
  [ADR-0109](0109-krt-middle-band-bankleitung-and-over-ceiling-auto-request.md); the ladder structure
  below stands.
- **Date:** 2026-07-02
- **Deciders:** @greluc
- **Related:** spec REQ-BANK-047 / REQ-BANK-048 (amends REQ-BANK-034 / REQ-BANK-035 / REQ-BANK-041) ·
  builds on ADR-0020 (org-unit seam), ADR-0043 (responsibility & visibility), ADR-0045 (per-account
  approval limits — this ADR supersedes its single-approver/single-boolean assumption for the KRT
  account) · concurrency rule in `CLAUDE.md`

## Context

The per-account approval model (ADR-0045) resolves, at request creation, a single scalar limit and a
single boolean `requires_owner_approval`, and routes every over-limit approval to **one** approver
class — the account's derived responsible holder (ADR-0043). For the **KRT account** (`CARTEL`) the
owner wants a stronger, escalating control on money *leaving* it: the approver should depend on the
**amount**, climbing bank employee → Bereichsleiter Profit → Organisationsleitung. That is a genuine
multi-approver routing, which ADR-0045 explicitly did not model.

Two further, smaller asks come with it: (a) a Bereichskonto should be able to open its balance/limit to
its **whole area** (Bereichsleitung + all child Staffel/SK members), not just the Bereich's direct
members; (b) the "Alle Mitglieder" limit tier should mean **the account's own org unit**, not a
catch-all that also covers outsiders holding only an individual view grant.

Two hard constraints are unchanged: the ArchUnit invariants `bankClassesMustNotConsultOrgUnitScope` and
`orgUnitAwareBankSeamIsContainedToOneClass` — every org-unit-aware decision stays in the single
`OrgUnitBankAccessService` seam; and **no regression** for non-KRT accounts.

## Decision

1. **KRT amount-tiered ladder (REQ-BANK-047).** Store two whole-aUEC thresholds `T1 ≤ T2` on the
   `bank_account` row (`employee_approval_ceiling` / `area_lead_approval_ceiling`, V203; they share the
   row `@Version`, like `balance_target`). A KRT withdrawal/transfer request is classified at creation
   into a band → required approver, snapshotted as a new `bank_booking_request.required_approver`
   (`BankRequestApprover` = `RESPONSIBLE_HOLDER` | `AREA_LEAD_PROFIT` | `ORGANISATIONSLEITUNG`): `≤ T1`
   employee (no external approval), `T1..T2` Bereichsleiter Profit, `> T2` Organisationsleitung. Unset
   `T1 = 0`, unset `T2 = +∞`. This **replaces** the per-audience limits on the KRT account.

2. **Thresholds are Bankleitung-only, edited on the bank surface.** `T1`/`T2` are two plain account
   columns, so their editor is org-unit-blind: a new `BankAccountService.setCartelApprovalTiers` gated
   `hasRole('BANK_MANAGEMENT')` behind a new Verwaltung tab (`/bank/manage?tab=krt-freigaben`), audited
   `CARTEL_APPROVAL_TIERS_SET/CLEARED`. Only the amount→approver **resolution** and the band→identity
   mapping (Profit-Bereichsleiter via `findByDepartment(PROFIT)`, OL via `OL_MEMBER`) stay in the seam,
   snapshotting the required approver so the `Bank*` confirm path stays blind and reuses the existing
   `BANK_OWNER_APPROVAL_REQUIRED` checkbox machinery.

3. **Band-routed approval + direct-booking cap.** "Fremde Anträge" is filtered per request by the
   caller's band (`canApprove`), so the Bereichsleiter Profit sees only the `AREA_LEAD_PROFIT` band and
   the OL only the `ORGANISATIONSLEITUNG` band (admins all). A plain bank employee's **direct** KRT
   withdrawal/transfer above `T1` is refused `BANK_CARTEL_APPROVAL_REQUIRED` (guard in
   `BankLedgerService.requireCartelDirectBookingAllowed`, called only by the direct-booking controller,
   never the already-approved confirmation path); management/admin are uncapped.

4. **`AREA_MEMBERS` audience (REQ-BANK-048).** Add a fifth `BankAccountViewGranteeKind` value for the
   whole Bereich cascade, resolved in the seam via a new
   `OwnerScopeService.currentUserIsMemberOfAreaCascade(bereichId)`. Because view grants and approval
   limits share the enum, it extends both tables (V202), offered only for AREA accounts.

5. **Members-only `ALL_MEMBERS` limit.** `resolveApplicableLimit` gates the `ALL_MEMBERS` tier behind
   actual owning-unit membership and folds `AREA_MEMBERS` into the same most-permissive (maximum)
   resolution — retiring ADR-0045's catch-all-for-everyone. The KRT account's all-member **visibility**
   (REQ-BANK-037) is untouched.

## Consequences

- The KRT account now has a genuine multi-approver, amount-banded routing — richer than ADR-0045's
  single boolean. This ADR supersedes that assumption **for the KRT account only**; every other account
  keeps the single responsible-holder routing.
- REQ-BANK-034's "CARTEL responsibility = OL collegium" is refined: the OL remains the KRT account's
  target/visibility owner, but request approval is band-routed and the notification set is `OL ∪
  Profit-Bereichsleiter` so both band approvers are notified.
- Both ArchUnit pins stay green: threshold storage/editing is org-unit-blind (`BankAccountService`,
  two columns), all org-unit-aware routing stays in the one seam.
- Cumulative/period budgets, per-approver quotas and configurable approver graphs remain out of scope.


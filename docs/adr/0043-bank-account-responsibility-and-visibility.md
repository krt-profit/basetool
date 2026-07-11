# ADR-0043 — Bank account responsibility: derived holder, configurable visibility, read-only drill-in & balance target

- **Status:** Accepted (epic #556 follow-up, owner-approved 2026-06-24)
- **Date:** 2026-06-24
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-BANK-034/-035/-036/-037/-038 (`docs/specs/bank.md`) ·
  [ADR-0011](0011-bank-authorization-model.md) (bank stays org-unit-blind) ·
  [ADR-0020](0020-bank-org-unit-aware-access-seam.md) /
  [ADR-0028](0028-bank-bereich-ol-access-seam.md) (the seam this extends) ·
  [ADR-0039](0039-bank-holder-ledger-decoupled-from-accounts.md) (the *other* "holder") ·
  epic [#556](https://github.com/krt-profit/basetool/issues/556)

## Context

The org-unit bank page (REQ-BANK-021/-027/-028) showed officers/leads a **balance-only** card for the
accounts they oversee. The owner wants the *user* side (not bank staff) of the bank widened:

- Each org-unit account should have a **responsible person** ("Kontoverantwortliche/r") who can decide
  who else may see the balance and set an aspirational **balance target**.
- A user who may see an account should be able to **click into it** and see the **bank-employee detail
  view with the full transaction history** and pull a **Kontoauszug (PDF)** — read-only, no booking.
- The **cartel/KRT account** should always be visible to **all** KRT members; **Sonderkonten** should be
  visible to the OL and all Bereichsleiter (and to users the OL/bank management open them to); the
  bank's own operating account (`CARTEL_BANK`) should be the Profit-Bereichsleiter's responsibility.

Two hard constraints: the bank must stay **org-unit-blind** (REQ-BANK-008, ADR-0011 — `BankSecurityService`
never consults `OwnerScopeService`, pinned by `bankClassesMustNotConsultOrgUnitScope`), and the existing
**`BankHolder`/"Halter"** concept (aUEC custody, ADR-0039) must not be confused with the new
"responsible person".

## Decision

1. **Derived responsible holder (REQ-BANK-034).** The responsible person is **not** stored or assigned;
   it is a function of org-unit leadership. It is resolved interactively for the caller ("am I
   responsible?") inside the `OrgUnitBankAccessService` seam and — with no principal — reverse-resolved
   for the notification engine and the leadership-change audit in the OwnerScope-free
   `OrgUnitBankResponsibilityService` (split out under audit Thema 7, #1254). The mapping is the same in
   both: Staffel→`STAFFELLEITER`, SK→`SK_LEAD`, Bereich→`BEREICHSLEITER`, `CARTEL`→every `OL_MEMBER`
   (collegial), `CARTEL_BANK`→`BEREICHSLEITER` of a `Department.PROFIT` Bereich, `SPECIAL`→none. Code
   never calls it "holder" (it is `responsible` / "Kontoverantwortliche/r" in the UI) to avoid the
   `BankHolder` collision.

2. **Configurable visibility (REQ-BANK-035) as additive view grants.** A new `bank_account_view_grant`
   table (V189) holds *additional* read access, polymorphic on `grantee_kind`
   (`MEMBERSHIP_ROLE` | `GLOBAL_ROLE` | `USER` | `ALL_MEMBERS`). Row existence = view access; there are
   **no** capability flags (booking stays bank-staff). It is deliberately a **separate** table from the
   bank-staff `bank_account_grant`. The seam evaluates grants per account type (membership-role on the
   owning unit for org-unit accounts; global role for Sonderkonten; all-members = unit members vs all
   KRT members by type).

3. **Fixed cartel/special audiences (REQ-BANK-037).** `CARTEL` is always all-members-visible;
   `CARTEL_BANK` is holder-only on the org-unit side; `SPECIAL` auto-views are bank staff + OL + every
   `BEREICHSLEITER`, plus OL-/management-configured grants. This **tightens** REQ-BANK-028 (Bereich
   coordinators/operators lose the auto-view) — owner-approved, spec amended in the same change.

4. **Read-only drill-in (REQ-BANK-038).** Anyone who may view an account may open a read-only copy of
   the bank-staff detail with the **history** and export a **Kontoauszug**. The seam authorizes
   (`canView`), then **reuses the bank's own org-unit-blind read/PDF code** (`BankAccountService`,
   `BankStatementReportService`). Capabilities are all-false. The **player-custody ("Halter") column is
   redacted**: the seam nulls the holder/counter-holder handles on the booking rows, and
   `generateStatement(..., redactHolders=true)` drops the Halter column from the PDF.

5. **Balance target (REQ-BANK-036)** is a nullable `bank_account.balance_target` column. Settable by the
   responsible holder (org-unit seam) **and** by bank staff with access (bank surface); Sonderkonto
   targets are bank-staff-only. It shares the row's `@Version` (target edits and rename/close are both
   infrequent) and is audited.

6. **Audit (REQ-BANK-012)** gains `BALANCE_TARGET_SET` / `BALANCE_TARGET_CLEARED` /
   `BALANCE_VISIBILITY_GRANTED` / `BALANCE_VISIBILITY_REVOKED`; the statement export reuses
   `STATEMENT_EXPORTED`. Details carry only the grantee kind/role code (no free text, no PII).

All org-unit-aware logic stays in the one seam; `BankSecurityService` is untouched; both ArchUnit pins
stay green. Bank management's ability to configure Sonderkonto visibility is expressed by the seam
authorizing `ROLE_BANK_MANAGEMENT` for `SPECIAL` (it reads the role, not org-unit scope).

## Consequences

- **Easier:** account responsibility tracks leadership automatically (no assignment UI, no drift); the
  whole bank stays org-unit-blind; the read-only detail reuses the existing bank read/PDF code rather
  than duplicating it.
- **Accepted:** the org-unit page is now reachable by any KRT member (the seam returns an empty set for
  a member who may view nothing); the SPECIAL auto-view is tightened (a behaviour change to REQ-BANK-028,
  amended in the same PR per the docs-as-code rule).
- **Accepted:** `balance_target` shares the account row's `@Version`; a concurrent holder-target edit and
  a bank-staff rename/close surface a 409 (both are infrequent). A 1-column side table would isolate the
  lock; deferred unless contention is observed.
- **Unchanged:** the bank-staff surface, the grant model, the holder ledger; `bankClassesMustNotConsultOrgUnitScope`
  and `orgUnitAwareBankSeamIsContainedToOneClass` stay green.

## Alternatives considered

- **Store an assignable account owner** — rejected: the owner's rule is "always the Staffelleiter / …",
  so a stored assignment would just drift from the role; deriving it is simpler and self-healing.
- **Reuse `bank_account_grant` for view grants** — rejected: that is the bank-staff capability grant
  (org-unit-blind, flag-bearing); overloading it would blur the two audiences and the ArchUnit boundary.
- **Keep the org-unit view balance-only** — rejected by the owner: viewers should see the history and
  pull a statement; custody is redacted to keep player-level holdings bank-internal.
- **Expose the holder/Halter column to org-unit viewers** — rejected by the owner (privacy): the column
  is redacted in both the history table and the PDF for org-unit viewers; bank staff keep the full view.
- **Show the full holder distribution** — moot: ADR-0039 already removed the per-account holder
  distribution from the account detail.


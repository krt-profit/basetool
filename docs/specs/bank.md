> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-30.
> **Owner area:** BANK · **Related ADRs:** ADR-0009, ADR-0010, ADR-0011
> **Status:** Implemented — epic
> [#556](https://github.com/krt-profit/basetool/issues/556) delivered (Phases 1–5). The
> acceptance boxes are ticked and the `Enforced by` links point at the shipped code and
> tests; subsequent behaviour changes keep this spec in sync in the same PR.

# Kartell bank — accounts, ledger, grants, audit, exports

## Context & goal

DAS KARTELL runs an in-game bank for its members and units. The basetool gets a dedicated
**bank area** in which a dedicated staff — defined solely by the bank roles, fully
independent of any org-unit membership — manages
accounts for every organizational layer — Staffeln, Spezialkommandos, areas (Bereiche),
the cartel as a whole, the cartel bank itself, and dynamically named special accounts.
There are **no accounts for individual players**. Because aUEC in Star Citizen only
exists on *player* accounts, every bank account additionally tracks its **holder
distribution**: which player physically holds which part of the account's balance (e.g.
the area-Profit account holds 1 000 aUEC — 500 with player greluc, 250 with carol, 250
with doppi). Bank staff book deposits, withdrawals and transfers; every change lands in
an immutable audit log that only administrators can read. Account statements and a management overview are exported
as KRT-design PDFs. The feature is deliberately **independent of seasons, price lines and
the mission/operation profit flows** — it is a standalone ledger.

Work is tracked in epic [#556](https://github.com/krt-profit/basetool/issues/556); the
phase-by-phase implementation plan (including per-phase deployment steps) lives in
[`docs/BANK_PLAN.md`](../BANK_PLAN.md).

## Requirements

### REQ-BANK-001 — Account model & account types

The bank manages **accounts** (`bank_account`). Every account has a unique human-readable
account number (generated, format `KB-<zero-padded sequence>`, e.g. `KB-0042`), a display
name, a type, a status (`ACTIVE` / `CLOSED`) and optimistic-locking metadata. Supported
account types:

|     Type      |                                 Owner reference                                 |       Cardinality        |
|---------------|---------------------------------------------------------------------------------|--------------------------|
| `ORG_UNIT`    | FK → `org_unit` (Staffel **or** Spezialkommando)                                | at most one per org unit |
| `AREA`        | free-form area name (Bereiche are not entities — see `docs/specs/org-chart.md`) | many                     |
| `CARTEL`      | none (the organization as a whole)                                              | singleton                |
| `CARTEL_BANK` | none (the bank's own operating account)                                         | singleton                |
| `SPECIAL`     | free-form name, dynamically created                                             | many                     |

There is deliberately **no per-player account type** — players appear only as *holders*
of organizational money (REQ-BANK-003). Singleton and per-owner uniqueness are enforced
by partial unique indexes, not by application convention alone.

**Acceptance**

- [x] Creating a second `CARTEL`, `CARTEL_BANK` or per-org-unit account is rejected with
  a 409 and a stable problem code.
- [x] Account numbers are unique, server-generated and never reused.
- [x] New entities reference org units via an `org_unit` FK (`org_unit_id`), never
  `squadron_id` (ArchUnit rule
  `noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities`).

**Enforced by:** `BankAccountServiceTest`, `BankControllerSecurityTest`, `DatabaseIndexMigrationTest` (V150 partial uniques), `BankManagePageControllerOrgUnitPickerMvcTest` (create-account org-unit picker renders names + ids, not `null`) · **Code:** `model/BankAccount`, `service/BankAccountService`, `db/migration/V150`, `controller/BankManagePageController` · **Issues:** #556

> **Amended by epic #692 (REQ-BANK-027):** Bereiche are now first-class `org_unit` kinds (REQ-ORG-014),
> so the `AREA` row's owner reference becomes a link to a `BEREICH` `org_unit` (one `AREA` account per
> Bereich) instead of a free-form area name, and `CARTEL` is linked to the `ORGANISATIONSLEITUNG`. This
> lets the `OrgUnitBankAccessService` seam resolve `AREA`/`CARTEL` accounts by Bereich/OL membership.

### REQ-BANK-002 — Dynamic account lifecycle, no hard delete

**All** account types — org-unit, area, cartel, cartel-bank and special — are created at
runtime by bank management (and admins) through the same endpoint; nothing is seeded by
migration, including the two singletons. Accounts are thereby **dynamically creatable
and closable** without a migration or restart.

> **Amendment (REQ-BANK-030, ADR-0040):** bank **employees** may additionally create
> `SPECIAL` accounts (Sonderkonten) — and only those — through the same endpoint, receiving
> an auto-grant on the account they create. Every **other** account-relationship action
> (creating any non-`SPECIAL` type, rename, close, reopen) stays bank-management-only. Closing an account requires a **zero
> balance** (transfer the remainder first); a closed account becomes read-only (no
> postings) but stays visible to authorized readers with its full history. Closed accounts
> can be reopened by bank management. Accounts are **never hard-deleted**; deactivating an
> org unit (soft delete, see `SquadronService.deleteSquadron`) does not touch its bank
> account.

**Acceptance**

- [x] Closing an account with a non-zero balance is rejected (409, stable code).
- [x] Postings on a `CLOSED` account are rejected; reads and statements still work.
- [x] Reopen restores full booking capability and is audited.

**Enforced by:** `BankAccountServiceTest` (close requires zero balance, reopen) · **Code:** `service/BankAccountService`, `db/migration/V150` · **Issues:** #556

### REQ-BANK-003 — Holder custody: a global dimension, decoupled from accounts

aUEC physically exists only on Star Citizen **player accounts**, so the bank tracks —
**separately from** the per-account balances — **how much each player physically holds**.
It maintains a bank-local **holder registry** (`bank_holder`: one row per custodian,
optional FK to `app_user` plus a denormalized handle snapshot so the ledger survives user
deletion) **plus a holder ledger** (`bank_holder_posting`, ADR-0039) recording signed
custody movements. A holder's balance is a single **global** figure — `SUM(amount)` over
the holder ledger across the whole bank — and is **deliberately decoupled from the
accounts**: the money booked on an account and the money a player physically holds are
tracked in parallel and need not coincide per account (a custodian credited via Staffel A
may pay out a request booked against Staffel B using the money he physically holds). A
**deposit** and a **withdrawal** each co-record exactly **one account leg and one holder
leg**; an **account↔account transfer** moves both dimensions (REQ-BANK-011); the
**holder→holder Umbuchung** moves only custody, touching no account (REQ-BANK-031). A
holder balance **may go negative** (a custodian fronts his own money) and is reconciled
later by an Umbuchung — there is no holder-level overdraft (REQ-BANK-006). Holders are
never hard-deleted while ledger rows reference them. Management may still **manually**
register any tool user as a custodian; in addition, **all bank staff are auto-registered**
as holders (REQ-BANK-029).

**Displayed name (live, not the snapshot).** Everywhere a holder is shown across the bank — the
*Halter* registry tab, the holder detail header, every holder-select dropdown (Umbuchung,
Ein-/Auszahlung, Anträge), the booking history, the account statement and the management report PDF
— the label is the linked user's **live effective name**: their **display name** when set,
otherwise their **username**. The denormalized `handle` snapshot is **only** a deletion fallback —
it is shown solely once the linked user is gone (`user_id` set to NULL), keeping the ledger
readable. So changing a display name is reflected immediately across the whole bank, and the
registry is ordered by that live name. The resolution lives in `BankHolder.getDisplayName()` for the
entity surfaces and in a `CASE` over the left-joined user in the holder-ledger projections
(`holderTotals`, `findHolderLegsByTransactionIds`) for the history/report surfaces; the holder
read fetch-joins the user to stay N+1-free. The append-only ledger **notes** and the **audit log**
keep the name as recorded at the time of the action (they record what happened, not the current
identity).

**Acceptance**

- [x] A deposit/withdrawal records exactly one holder leg; an account↔account transfer two
  holder legs; a holder→holder Umbuchung two holder legs and **no** account leg.
- [x] A holder's displayed balance is the **global** sum across the whole bank, not a
  per-account figure; the account-detail view and both PDFs no longer show a per-account
  holder distribution.
- [x] A holder row whose linked user is deleted keeps its handle snapshot and its ledger
  history.
- [x] Every bank surface shows the holder's **live** effective name (display name preferred,
  username fallback); renaming a user is reflected immediately in the registry, dropdowns, history
  and statements, while a deleted user's rows fall back to the frozen handle snapshot.

**Enforced by:** `BankLedgerServiceTest` (account/holder legs per type, global holder balance), `BankHolderServiceTest`, `BankHolderLiveDisplayNameTest` (live name preferred + rename reflected + deleted-user snapshot fallback, registry & statement), `BankReportServiceTest` (holder column on statements) · **Code:** `model/BankHolder` (`getDisplayName`), `model/BankHolderPosting`, `mapper/BankHolderMapper`, `repository/BankHolderRepository` (`findAllWithUser`), `repository/BankHolderPostingRepository` (`holderTotals` / `findHolderLegsByTransactionIds` live `CASE`), `service/BankHolderService`, `db/migration/V180`, `db/migration/V181` · **ADR:** [ADR-0039](../adr/0039-bank-holder-ledger-decoupled-from-accounts.md) · **Issues:** #556

### REQ-BANK-004 — Append-only double-entry ledger

All value movements are recorded on **two append-only ledgers** (ADR-0039) sharing one
`bank_transaction` header (type, initiator, note, justification, timestamp — the optional
`justification`/Begründung is captured only for a `WITHDRAWAL` / `TRANSFER`, REQ-BANK-045):
`bank_posting` rows carry the
**account** dimension (account, signed amount) and `bank_holder_posting` rows the **holder**
dimension (holder, signed amount). Transaction types and the legs they co-record:
`DEPOSIT` (one `+` account leg + one `+` holder leg), `WITHDRAWAL` (one `−` account leg +
one `−` holder leg), `TRANSFER` (account↔account: two account legs summing to zero **and**
two holder legs summing to zero, REQ-BANK-011), `HOLDER_TRANSFER` (holder→holder Umbuchung:
two holder legs summing to zero, **no** account leg, REQ-BANK-031), `WIPE_RESET`
(admin-only, REQ-BANK-013) and `REVERSAL` (corrections). A reversal's legs are the
**negated mirror** of the reversed transaction's legs **on both ledgers**. Ledger rows are
**never updated or deleted** — mistakes are corrected by a reversal transaction that
references the original. A `WIPE_RESET` (a deliberate end-state) and a `REVERSAL` are
themselves **not reversible** (stable code `BANK_NOT_REVERSIBLE`). Account balance is the
SQL sum of its `bank_posting` rows and a holder's global balance the SQL sum of its
`bank_holder_posting` rows, both computed on read (ADR-0010/0039).

> **Amended by REQ-BANK-043:** a **split** `DEPOSIT` is the one deposit that carries **more than one
> account leg** — one positive leg per credited account (the named remainder + each squadron share)
> against a **single** positive holder leg over the gross (the money landed once). The legs sum to
> the gross; deposits stay fee-free. A plain (non-split) deposit keeps the one-account-leg shape.

**Acceptance**

- [x] No service or repository code path issues `UPDATE`/`DELETE` on `bank_transaction`,
  `bank_posting` or `bank_holder_posting` (test-pinned, mirroring REQ-INV-001 in
  `inventory-lager.md`).
- [x] `TRANSFER` account legs and holder legs each sum to zero per transaction;
  `HOLDER_TRANSFER` holder legs sum to zero and book no account leg; `REVERSAL` legs are the
  negated mirror of the reversed transaction's legs on **both** ledgers.
- [x] A reversal stores a FK to the reversed transaction; reversing twice is rejected.
- [x] Reversing a `WIPE_RESET` or a `REVERSAL` is rejected (409 `BANK_NOT_REVERSIBLE`).

**Enforced by:** `BankLedgerServiceTest` (append-only, two-ledger legs, reversal mirror, non-reversible targets), `ArchitectureTest` (`bankLedgerRepositoriesMustStayInsertOnly`) · **Code:** `model/BankTransaction`, `model/BankPosting`, `model/BankHolderPosting`, `service/BankLedgerService`, `db/migration/V153`, `db/migration/V180` · **ADR:** [ADR-0039](../adr/0039-bank-holder-ledger-decoupled-from-accounts.md) · **Issues:** #556

### REQ-BANK-005 — Whole-aUEC amounts

Bank amounts follow the project-wide whole-number-amounts contract
(`docs/specs/whole-number-amounts.md`, ADR-0002): `BigDecimal` mapped to
`NUMERIC(19,4)`, write DTOs validated with the value-based `@WholeNumber` constraint and
`@DecimalMin("1")` (a zero-amount booking is meaningless), display rounded HALF_UP to
whole aUEC via the frontend `MoneyFormat` bean. Amount inputs use
`<input type="number" step="1" inputmode="numeric">`.

> **Amended by REQ-BANK-043:** a split deposit's percentage is a whole-percent (1–100) and its
> `slice = round(gross × P / 100)` is whole (HALF_UP); the slice is distributed whole-aUEC across the
> squadron accounts by the largest-remainder rule so every per-account leg stays whole and the legs
> sum back to the gross exactly (no aUEC created or lost).

**Acceptance**

- [x] `500.00` accepted, `500.5` rejected (400) on every booking endpoint.
- [x] `0` and negative request amounts are rejected (sign is determined by the
  transaction type, not by the caller).

**Enforced by:** `BankControllerSecurityTest` (fractional amount rejected with 400) · **Code:** `@WholeNumber` on `model/dto/request/Bank*Request` · **Issues:** #556

### REQ-BANK-006 — No account overdraft (holders may go negative)

No posting may take an **account** balance below zero. A withdrawal or an account↔account
transfer is validated against the current account balance **inside the booking transaction**
with an atomic/locked check (the source account row is pessimistically locked) so concurrent
bookings cannot jointly overdraw an account. Violations surface as 409 `BANK_OVERDRAFT` (not
500).

The **holder** dimension has **no overdraft** (ADR-0039): a holder balance **may go
negative** — a custodian fronts his own aUEC to satisfy a payout, and the imbalance is
reconciled later by a holder→holder Umbuchung (REQ-BANK-031). No booking path checks holder
coverage; `BANK_HOLDER_OVERDRAFT` is retired.

**Acceptance**

- [x] Concurrent withdrawals that would jointly overdraw an account: exactly one
  succeeds (concurrency test).
- [x] A withdrawal/transfer whose named holder lacks coverage but whose **account** covers it
  **succeeds**, driving the holder balance negative; the account overdraft is still rejected.
- [x] The error response names the account and the available balance — without leaking
  data to callers who cannot see the account (403 takes precedence).

**Enforced by:** `BankLedgerServiceTest` (account overdraft code + concurrency tests, holder-may-go-negative) · **Code:** `service/BankLedgerService` (pessimistic account lock + account-only coverage guard) · **ADR:** [ADR-0039](../adr/0039-bank-holder-ledger-decoupled-from-accounts.md) · **Issues:** #556

### REQ-BANK-007 — Keycloak roles & hierarchy

Bank access is anchored on two new **Keycloak realm roles** (names proposed, owner may
rename before Phase 1 — see Open questions):

- `Bank Employee` → `ROLE_BANK_EMPLOYEE` — may use the bank area within the limits of
  their per-account grants (REQ-BANK-009).
- `Bank Management` → `ROLE_BANK_MANAGEMENT` (Bankleitung) — sees and manages
  **all** accounts, holders and grants.

Role hierarchy (both `SecurityConfig` beans, kept in sync):
`ROLE_ADMIN > ROLE_BANK_MANAGEMENT > ROLE_BANK_EMPLOYEE`. Admins therefore pass every
bank gate (REQ-BANK-010); bank management passes every employee gate. The roles are
seeded in `DataInitializer` (matched by code `BANK_EMPLOYEE` / `BANK_MANAGEMENT`),
documented in `ROLES_AND_PERMISSIONS.md`, and added to the E2E realm export
(`frontend/src/e2e/resources/realm-export.e2e.json`).

**Acceptance**

- [x] A user with only `Bank Employee` and zero grants sees an empty bank area — no
  account data, no audit log.
- [x] `hasRole('BANK_EMPLOYEE')` is satisfied by management and admins via the hierarchy.
- [x] `ROLES_AND_PERMISSIONS.md` documents the bank column/row in the access matrix.

**Enforced by:** `SecurityConfig` role hierarchy + `DataInitializer` seed · **Code:** `config/SecurityConfig`, `config/DataInitializer` · **Issues:** #556

### REQ-BANK-008 — Bank membership is independent of org-unit membership

Working in the bank — as employee or management — requires only a basetool account and
the respective Keycloak role (REQ-BANK-007) plus grants (REQ-BANK-009). It is **fully
independent of org-unit membership**: a bank employee or bank manager may, but need
not, also be a member of a Staffel, Spezialkommando or any other org unit. Org-unit
memberships neither qualify nor disqualify anyone for the bank, and joining or leaving
an org unit has **no effect** on bank roles, grants or access. Consequently, bank
authorization decisions **never consult org-unit scope**: `OwnerScopeService` scoping,
contextual `ROLE_X@orgUnitId` authorities and the `X-Active-Org-Unit-Id` admin pin have
no influence on any bank gate — `BankSecurityService` evaluates only the bank roles and
the grant table.

> **Amendment (epic #666, owner-approved):** REQ-BANK-021/-022 add a single, narrow org-unit
> capability — officers/leads may see the **balance** of, and raise **booking requests**
> against, their own org unit's account. This does **not** weaken the rule above:
> `BankSecurityService` and the ledger stay 100% org-unit-blind. All org-unit logic is
> isolated in one deliberately non-`Bank*`-named seam, `OrgUnitBankAccessService` (ADR-0020),
> which is the **only** class permitted to bridge `OwnerScopeService` and the bank
> (pinned by `ArchitectureTest.orgUnitAwareBankSeamIsContainedToOneClass`). The officer/lead
> surface lives outside the bank URL/role space (`/api/v1/org-units/bank/**`).
>
> **Amendment (epic #692, REQ-BANK-027):** the same seam — still the only org-unit bridge — extends this
> narrow capability **up the hierarchy**: a Bereichsleitung over its `AREA` account, the OL over the
> `CARTEL` account, plus **view-only** drill-down into subordinate accounts. `BankSecurityService` and
> the ledger stay 100% org-unit-blind; both ArchUnit pins remain in force.

**Acceptance**

- [x] A user who is a Staffel/SK member **and** holds `Bank Employee` plus a grant can
  use the bank exactly like a membership-less bank employee (matrix test).
- [x] Joining or leaving an org unit changes nothing about a user's bank access or
  grants (test pins both directions).
- [x] Bank gates ignore the `X-Active-Org-Unit-Id` pin and contextual org-unit
  authorities (test).

**Enforced by:** `BankSecurityServiceTest`, `ArchitectureTest` (`bankClassesMustNotConsultOrgUnitScope`) · **Code:** `service/BankSecurityService` · **Issues:** #556

### REQ-BANK-009 — Per-account grants: view + deposit / withdraw / transfer

Fine-grained bank permissions are **app-managed grants**, not Keycloak roles
(ADR-0011): a `bank_account_grant` row per (user, account) with three independent
capability flags — `can_deposit`, `can_withdraw`, `can_transfer`. The **existence** of a
grant row gives **view access** to that account (a row with all flags false is
view-only). This expresses every required combination: deposit-only, withdraw-only,
both, and rebooking permission. Grants are managed by bank management and admins; every
grant change (create, flag change, revoke) is audited (REQ-BANK-012). Grants can only be
**created** for users holding the `Bank Employee` role; whether the grantee belongs to
an org unit is irrelevant (REQ-BANK-008).

**Acceptance**

- [x] Capability matrix is enforced server-side per endpoint: deposit needs
  `can_deposit` on the account, withdrawal `can_withdraw`, transfer `can_transfer`
  on the **source** account (REQ-BANK-011 for the destination rule).
- [x] Grant management endpoints are gated `hasRole('BANK_MANAGEMENT')` (admins pass via
  hierarchy) and reject grantees lacking the employee role.
- [x] Every grant mutation produces exactly one audit event with before/after flags.

**Enforced by:** `BankGrantServiceTest`, `BankControllerSecurityTest` · **Code:** `service/BankGrantService`, `model/BankAccountGrant`, `db/migration/V152` · **Issues:** #556

### REQ-BANK-010 — Visibility matrix

|                                         Actor                                          |                                                                                             Sees                                                                                             |                                          May change                                           |
|----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| Everyone without a bank role (anonymous, `GUEST`, members)                             | **nothing** (no bank surface at all)                                                                                                                                                         | nothing                                                                                       |
| Officer / lead of an org unit (no bank role; via the org-unit seam, REQ-BANK-021/-022) | **balance only** of their overseen org unit's account (active only; Bereich/OL also see the cartel-wide special accounts view-only, REQ-BANK-028) + status of their **own** booking requests | raise / cancel own **booking requests** (off-ledger; book nothing)                            |
| Bank employee (role + grants; org-unit membership irrelevant, REQ-BANK-008)            | accounts they hold a grant on                                                                                                                                                                | bookings per their capability flags; confirm/reject requests on those accounts per capability |
| Bank management (role; org-unit membership irrelevant)                                 | **all** accounts, holders, grants                                                                                                                                                            | all bookings, account lifecycle, holders, grants                                              |
| Admin (`ROLE_ADMIN`)                                                                   | everything incl. the **audit log**                                                                                                                                                           | everything incl. wipe reset (REQ-BANK-013)                                                    |

> **Amendment (REQ-BANK-029/-030/-031, ADR-0040):** bank **employees** additionally reach the
> bank-administration page to create `SPECIAL` accounts (only those) and to use the holder menu
> incl. the holder→holder Umbuchung; all other account-lifecycle actions, manual holder
> registration and grant management stay management-only. All bank staff are auto-registered as
> holders. These widen the employee's "may change" cell only; the audit-log/admin rows below are
> unchanged.

The audit log is **admin-only** — bank management does **not** see it. The bank area
contributes nothing to the anonymous/guest surface (consistent with REQ-SEC-009). Bank
endpoints follow the two-gate model (URL matrix outer, `@PreAuthorize` inner):
`/api/v1/bank/admin/**` additionally gets a `hasRole('ADMIN')` URL gate.

> **Amendment (REQ-BANK-034..038):** the "members see nothing" rule is further relaxed on the
> **org-unit** surface only (never the bank-staff surface, which stays grant/role-driven and
> org-unit-blind). A KRT member now sees, on the org-unit bank page, the accounts they are entitled to
> view — the cartel/KRT account (all members), an account their responsible holder has granted them,
> and (for OL/Bereichsleiter) Sonderkonten — including a **read-only detail with the transaction
> history and a Halter-redacted Kontoauszug** (REQ-BANK-035/-037/-038). They still cannot book; player
> custody is redacted from their view.
>
> **Amendment (REQ-BANK-042):** the org-unit surface additionally lets **any authenticated user**
> raise a **deposit** request against **any active account** (every type, even one they cannot view),
> with no approval limit — a deposit only adds money and is confirmed by a bank employee on receipt.
> Withdrawal/transfer requests stay view-gated and limit-gated; the bank-staff surface is unchanged.

**Acceptance**

- [x] A role/permission matrix test (mirroring `RolePermissionsE2eTest`) proves every
  cell of the table above, including the "nothing" row.
- [x] The sidebar "Bank" group renders only for `BANK_EMPLOYEE`-or-above; the audit-log
  page only for admins.

**Enforced by:** `BankControllerSecurityTest` (member 403, employee/management/admin matrix, admin-only audit) · **Code:** `service/BankSecurityService`, `controller/BankAccountController` · **Issues:** #556

### REQ-BANK-011 — Account↔account transfer semantics

A `TRANSFER` (Konto→Konto-Umbuchung, e.g. Staffel → SK, area → cartel) moves value between
**two different accounts**: two account legs **and** two holder legs — the physical custody
moves with the booked money (a source and a destination holder; they may be the same player).
When the holder **changes**, the source physically sends the money in-game, so the **transfer
fee is added on top** (REQ-BANK-033, ADR-0052): the source is debited the gross (`amount + fee`),
the destination credited the full entered amount, and both leg pairs net to `−fee`; a
**same-holder** transfer is a fee-free re-label and nets to zero. The employee needs `can_transfer` on the **source**
account; the **destination** must be an account the employee can see (any grant row). Bank
management and admins are unrestricted. The source account is guarded against overdraft
(REQ-BANK-006); the holder dimension is not. The **intra-account holder rebooking** of the
old model is **removed** — custody is no longer per-account, so moving money between players
is the global holder→holder Umbuchung (REQ-BANK-031), not a transfer.

**Acceptance**

- [x] An employee with `can_transfer` on A but no grant on B cannot transfer A → B (403).
- [x] A transfer's account legs and holder legs each sum to zero; the source account may not
  be overdrawn; same source/destination account is rejected (`BANK_SELF_TRANSFER`).
- [x] The transfer appears in the audit log and the statement PDF with both account legs and
  the involved holders.

**Enforced by:** `BankLedgerServiceTest` (account+holder legs sum zero, same-account reject, source overdraft) · **Code:** `service/BankLedgerService#bookTransfer` · **ADR:** [ADR-0039](../adr/0039-bank-holder-ledger-decoupled-from-accounts.md) · **Issues:** #556

### REQ-BANK-012 — Immutable, complete, admin-only audit log

Every bank mutation writes exactly one row to an **insert-only** audit table
(`bank_audit_event`, modeled after `external_sync_report` — no `@Version`, no updates):
bookings of every type, reversals, account lifecycle (create/close/reopen), holder
registry changes, every grant change, the wipe reset, **PDF exports** (statement and
management export, with parameters), and — since REQ-BANK-035/-036 — **balance-target**
changes (`BALANCE_TARGET_SET` / `BALANCE_TARGET_CLEARED`) and **balance-visibility** grants
(`BALANCE_VISIBILITY_GRANTED` / `BALANCE_VISIBILITY_REVOKED`, with the grantee kind/role code in
the details payload and the target user id for individual-user grants — no free text, no PII), and —
since REQ-BANK-047 — the **KRT-account approval-threshold** changes (`CARTEL_APPROVAL_TIERS_SET` /
`CARTEL_APPROVAL_TIERS_CLEARED`, the two amounts in the details payload — no PII), and — since
REQ-BANK-034 (ADR-0070) — a change of an account's **derived responsible holder**
(`ACCOUNT_RESPONSIBLE_CHANGED`, with the old and new responsible-holder user-id sets in the details
payload — ids are system identifiers, not free text or PII).
Each event stores: timestamp, actor user id (FK
`ON DELETE SET NULL`) **plus** a denormalized actor handle snapshot (the trail must
survive user deletion), event type, affected account/transaction/target-user references,
and a compact details payload. The audit log is readable **only by admins**
(`hasRole('ADMIN')` on URL **and** method gate), paginated and filterable (period, actor,
account, event type). Audit rows are never exposed through any non-admin endpoint.

The audit table is business data, not logging — the `docs/specs/observability.md` rule
(never log names/emails/tokens to the **log stream**) still applies to bank code.

**Acceptance**

- [x] For every mutating bank endpoint a test asserts exactly one matching audit event
  (type, actor, references).
- [x] Audit write failures fail the business transaction (same TX — no silent gaps).
- [x] Non-admin access to the audit endpoints/pages: 403; the page link is hidden.

**Enforced by:** `BankAuditServiceTest`, `BankControllerSecurityTest` (management 403 on audit) · **Code:** `service/BankAuditService`, `controller/BankAdminController`, `db/migration/V154` · **Issues:** #556

### REQ-BANK-013 — Admin wipe reset

A Star Citizen wipe erases in-game currency. The admin area gets **one button** that
resets **both** dimensions to zero (ADR-0039): one `WIPE_RESET` transaction books one
account leg per account with a non-zero balance **and** one holder leg per holder with a
non-zero global balance, bringing every account balance **and** every holder balance to
exactly zero (the two dimensions are zeroed independently). History, statements and audit
trail are **preserved** — nothing is deleted. The action requires `ROLE_ADMIN` and a KRT-styled danger confirmation modal
(no native dialogs) with an explicit consequence text **and a type-to-confirm hurdle**
(the design system's `.confirm-input` pattern, reserved for wipe-reset-grade actions),
and writes one summarizing audit event plus the individual transactions. The operation
is idempotent (a second click on an all-zero bank is a no-op with a notice).

**Acceptance**

- [x] After the reset every account balance and every holder **global** balance is zero;
  pre-wipe statements still render correctly.
- [x] The button sits in the admin area, is admin-only, and uses the danger-modal
  pattern with type-to-confirm (`btn-danger` + danger `.krt-modal` + `.confirm-input`),
  matching the A1 mockup (`proposals/bank-admin-varianten.html`).
- [x] The audit log contains the summary event (actor, account count, total zeroed).

**Enforced by:** `BankLedgerServiceTest` (wipe reset relative + idempotent), `BankControllerSecurityTest` (admin-only) · **Code:** `service/BankLedgerService#resetAllBalances`, `controller/BankAdminController` · **Issues:** #556

### REQ-BANK-014 — Account statement PDF (Kontoauszug)

For every account they can see, bank staff can export an **account statement PDF** for a
**user-selected period** (from/to, reusing the `datetime-split-group` filter pattern):
header with account number/name/type/status, opening balance at period start, every
booking in the period (timestamp, type, counter-account where applicable, holder, note,
justification (REQ-BANK-045), signed amount, running balance) and the closing balance. The
per-account **holder
distribution** is **removed** (ADR-0039: custody is no longer per-account); each booking row
keeps its holder annotation, derived from the transaction's holder leg. The PDF is generated backend-side with OpenPDF, follows
the KRT design system (page background, KRT orange `#E77E23`, **embedded Lato** — the
existing Helvetica-based reports predate the rule), and is delivered via the established
`ResponseEntity<byte[]>` + frontend-proxy + fetch/blob download pattern with the
`X-User-Time-Zone` header. Statement labels come from the backend message bundles
(German default), not string literals. Every export is audited (REQ-BANK-012).

> **Amended (2026-07-03):** the "other side" column is renamed **GEGENSEITE → QUELL-/ZIELKONTO** (the
> `pdf.bank.col.counterparty` label), and the **Begründung + Notiz** are carved out of their own
> columns into an **indented per-booking sub-row** beneath each booking (reason first), so the main
> columns stay wide (REQ-BANK-044/-045). The same rename + sub-row apply to the management report
> (REQ-BANK-015). The redacted variant now drops **only** the Halter column and keeps
> Quell-/Zielkonto (REQ-BANK-038 amended).

**Acceptance**

- [x] Opening + sum of period postings = closing balance, pinned by a `PdfTextExtractor`
  test (existing test style).
- [x] Statement access follows REQ-BANK-010 (employee: granted accounts only).
- [x] PDF uses embedded Lato and the KRT page background; no PII beyond the account's
  own data; **no** per-account holder distribution section.

**Enforced by:** `BankReportServiceTest` (statement balances, period filter, distribution, one audit event) · **Code:** `service/BankStatementReportService`, `controller/BankAccountController#downloadStatement` · **Issues:** #556

### REQ-BANK-015 — Management export: all accounts, last three months (PDF)

Bank management and admins can export a **single PDF over all accounts** covering the
**last three months** (rolling window). Per account: a header line with opening balance
(3 months ago), in/out totals, net change and closing balance, **followed by the itemized
bookings of the window** (the owner asked for the *changes*, not just totals); plus an
overall summary section up front. The per-account holder distribution is **removed**
(ADR-0039); instead the report closes with a single **global holder-balance section** — every
holder's current global custody (which is bank-wide, not per-account). Same
design/delivery/audit rules as REQ-BANK-014. Employees cannot trigger this export.

**Acceptance**

- [x] Endpoint gated `hasRole('BANK_MANAGEMENT')`; employee → 403.
- [x] Per-account net change equals the difference of the two balances and the sum of
  the itemized bookings (test-pinned).

**Enforced by:** `BankReportServiceTest` (per-account summaries, audit event) · **Code:** `service/BankManagementReportService`, `controller/BankExportController` · **Issues:** #556

### REQ-BANK-016 — Dashboards

> **Amended (2026-07-05, #996):** the dashboard gains **two per-user view options**, persisted
> client-side in `localStorage` (the Lager pattern) and replayed through the `layout` / `group` query
> parameters on a `bankGrid` fragment swap: a **card ⇆ table layout** switch (the card grid stays the
> default) and an **alphabetical ⇆ by-Bereich grouping**. The A→Z-by-name order below stays the
> default and the within-group order; the by-Bereich view chunks the same cards into **coloured
> vertical groups** (not tabs) in a fixed order — the **KRT rubric** (CARTEL + KRT-bank), one group
> **per Bereich** (A→Z by Bereich name, each leading with its `AREA` account then its Staffel/SK
> accounts), the **Sonderkonten**, an **"Ohne Bereich"** bucket for org-unit accounts with no Bereich,
> and finally every **closed** account. Each Bereich header is tinted with its department's
> Bereichsfarbe (`--color-dept-*`, REQ-ORG-018), mirroring the org chart. The account→Bereich mapping
> is a display-only owner-label read resolved from the account's owning org unit + its parent via one
> bounded query (no per-account N+1); the bank stays org-unit-blind (REQ-BANK-008 —
> `BankDashboardService` reads `OrgUnitRepository`, never `OwnerScopeService`). The account-name
> filter (REQ-BANK-046) applies across every view.
>
> **Amended (2026-07-05) — view options as checkboxes + dashboard Kontobewegung CTA:** the two per-user view options are now **independent checkboxes** (a "Tabellenansicht" toggle and a "Nach Bereich gruppieren" toggle, `bank.dashboard.view.tableToggle` / `.groupToggle`) rather than filled orange segmented buttons — **both unchecked is the default card grid ordered A→Z**; each still persists per user in `localStorage` and replays through the `layout` / `group` query parameters on the `bankGrid` fragment swap (`bank.js` reads their `checked` state on `change`, not a button's `aria-pressed`). The dashboard header additionally gains the shared direct-booking **"Kontobewegung"** CTA + unified movement modal (REQ-BANK-023/-017, shown when an active account exists); a successful booking re-renders the `bankGrid` in place so balances and 30-day deltas refresh without a reload (`bankGrid` refresh target, REQ-FE-005). The **Verwaltung / Berechtigungen** links are removed from the header — they remain reachable from the sidebar (`nav.bank.manage` / `nav.bank.grants`) — while the three-month report stays bank-management-only. `BankPageController#addMovementModalData` assembles the modal's catalogs (active accounts, holders, counterparty users, org units, fee rate) only on the full-page render, not the `bankGrid` fragment swap, and reuses the unchanged `/deposits` · `/withdrawals` · `/transfers` endpoints, so this adds **no new endpoint, audit event or metric**.

The bank landing page (`/bank`) is a **dashboard** in the design system's **D1 card
grid** layout (`proposals/bank-dashboard-varianten.html`): one `.kpi-card` per visible
account showing the current balance and the **net change over the last 30 days**
(sign-colored `.kpi-delta--pos/--neg`), plus a compact 30-day trend visualization
(server-computed inline SVG sparkline — no charting library exists or is introduced;
visual spec: `preview/components-kpi-sparkline.html`). Cards link to the account
detail; closed accounts render dimmed (`.kpi-card--closed`). Cards are ordered
alphabetically (case-insensitive) by account name in both perspectives. This
**A→Z-by-name, case-insensitive ordering is the canonical order for every bank account list** —
not just the dashboard cards: the management table (`/bank/manage`), the grant filter selects,
the transfer-target picker on the account detail, and the org-unit balance cards + booking-request
source picker all order their accounts the same way, implemented once in the shared
`controller/BankAccountOrder` helper so every account picker and overview reads identically.
Management totals
render as the `.kpi-total` aggregate strip. Bank employees see the accounts they hold grants on;
bank management (and admins) see **all** accounts plus aggregate totals (sum of
balances, total 30-day in/out). Itemized recent bookings deliberately live on the
account **detail** page, not the dashboard — the dashboard shows the net change.
Balances and deltas come from one grouped backend query (`/api/v1/bank/dashboard`) — no
per-account N+1 (REQ-DATA-003).

**Acceptance**

- [x] Employee dashboard lists exactly the granted accounts; management/admin dashboard
  lists all accounts + totals row.
- [x] Cards are ordered alphabetically (case-insensitive) by account name.
- [x] Every account picker and overview (dashboard, management table, grant filters,
  transfer-target / booking-request source pickers, org-unit balance cards) orders accounts
  A→Z by name (case-insensitive), via the shared `BankAccountOrder` helper.
- [x] 30-day delta equals the sum of postings in the window (test-pinned).
- [x] Dashboard renders correctly on all four device classes (REQ-UI responsive rules).
- [x] A client-side account-name live filter sits beside the view-option checkboxes and hides
  non-matching cards as the user types (REQ-BANK-046).
- [x] The two view options render as independent checkboxes (both unchecked = the default card grid
  A→Z); the header carries the direct-booking Kontobewegung CTA + modal (when an active account
  exists) and no longer the Verwaltung / Berechtigungen links (frontend `BankDashboardFilterMvcTest`).

**Enforced by:** `BankPageControllerTest` (sparkline scaling), `BankAccountOrderTest` (A→Z
case-insensitive, null-safe, original list untouched), frontend `BankDashboardFilterMvcTest` (filter
wiring, view-option checkboxes, Kontobewegung CTA/modal, Verwaltung/Berechtigungen absent),
single-statement repository reads · **Code:** `service/BankDashboardService`,
`controller/BankPageController` (`addMovementModalData`), `controller/BankAccountOrder` (shared
account ordering for `BankManagePageController` / `BankGrantsPageController` /
`OrgUnitBankPageController`), `templates/bank-dashboard.html`, `static/js/bank.js`, `static/css/bank.css`
· **Issues:** #556

### REQ-BANK-017 — UI: design system, i18n, modals

The entire bank UI (and both PDFs) follows the DAS KARTELL design system
(`docs/specs/ui-design-system.md`; visual source of truth:
`.claude/skills/das-kartell-design/README.md`): Lato-only typography, brand colors,
square-first HUD styling, the four responsive device classes, and **no native browser
dialogs** (KRT modals / `showKrtConfirm` only). The design system additionally ships
**final-draft mockups for all four bank pages** (`proposals/bank-dashboard-varianten.html`,
`bank-konto-detail-varianten.html`, `bank-verwaltung-varianten.html`,
`bank-admin-varianten.html`; submodule pin ≥ `2ba5678`) plus a dedicated bank component
layer in `krt-components.css` (`.kpi-*`, `.holder-*`, `.stack-*`, `.matrix-flag`,
`.krt-modal`, `.confirm-input` — documented in the submodule `README.md` "Bank
patterns" block). The bank pages are **built to match these mockups** using the shipped
component classes; deviations need an owner decision. Every user-visible string lives
in `messages.properties` / `_de` / `_en` under new `bank.*`, `admin.bank.*` and
`nav.bank.*` keys (umlauts as `\uXXXX` escapes in `.properties`).

> **Amended (2026-07-03) — account-detail density:** both account-detail booking tables (bank-staff
> `bank-account-detail.html` and the member `org-unit-bank-account-detail.html`) drop the wide
> Notiz + Begründung columns; a booking that carries a reason and/or note becomes an **expandable
> row** — a leading chevron (`.bank-chevron`, rotated on `aria-expanded`) marks it, and clicking the
> row (or the `.bank-row-toggle` keyboard control) reveals an inline **detail sub-row** with the
> Begründung **first**, then the Notiz. On the bank-staff detail the **Konto-Info** panel (plus the
> read-only approval limits) becomes a **collapsible tile above** the now full-width history
> (`.bank-collapse-head`, collapsed by default) — there is not enough horizontal room side by side.
> Both toggles are CSP-safe, document-delegated in `bank.js` (survive the `krtFetch` fragment swap),
> and every new string is a `bank.*` key (`bank.detail.toggleDetails`, the renamed
> `bank.booking.counterparty` = "Quell-/Zielkonto").
>
> **Amended (2026-07-05, #997) — single "Kontobewegung" entry point + "?" field hints:** the
> account-detail (K1) booking actions collapse from three separate buttons/modals (Einzahlen /
> Auszahlen / Transfer) into **one "Kontobewegung"** button, shown when the caller may do any of
> deposit/withdraw/transfer on the active account. It opens a **single modal whose movement-type
> selector** (Einzahlung / Auszahlung / Transfer, only the permitted types offered) switches the
> type-specific fields and routes the submit to the correct existing endpoint
> (`/deposits` · `/withdrawals` · `/transfers`); the three backend endpoints are unchanged, so audit
> and monitoring are unchanged (exactly one audit event per booking, per REQ-BANK-012). The reusable
> modal lives in `templates/fragments/bank-movement-modal.html` and is shared with the requests
> overview (REQ-BANK-023). All field hints in the modal — including the **optionality notes** (the
> counterparty being optional, the payer-holder note) — become inline **"?" hover/focus tooltips**
> via the generalised `fragments/scu-hint :: fieldHint(key)` marker instead of small sub-field text;
> hint text stays in `bank.*` i18n keys and is mirrored onto `aria-label`. New keys:
> `bank.action.movement`, `bank.modal.movement.title`, `bank.movement.field.sourceAccount(.placeholder/.hint)`.
>
> **Amended (2026-07-05) — Kontobewegung modal polish:** three refinements to the shared movement modal. (1) On the account-selectable variant (requests overview + dashboard) the account label follows the movement type (`bank.js` `syncMovementRows`): a **deposit** lands ON the account so its label is **Zielkonto** (a deposit has no source account — money from a source account would be a transfer), while a withdrawal/transfer keeps **Quellkonto**; the label carries per-type `data-label-*` (reusing `bank.field.targetAccount`). (2) The "kein Tool-Account" counterparty toggle (`bank-counterparty.html`) now sits **below** the Einzahler/Empfänger picker (and its external-name alternative) and above the shared Einheit row, matching reading order. (3) The **"?" field-hint tooltip** (`fragments/scu-hint`) no longer clips inside a scrolling `.krt-modal-body` (whose `overflow-y: auto` also clips horizontally): `common-handlers.js` measures the bubble against its clipping ancestor on hover/focus and sets a `--hint-shift` CSS variable that slides it back inside (the arrow counter-shifts to keep pointing at the disc). All three are UI-only — no endpoint, DTO, audit or monitoring change.

**Acceptance**

- [x] No hardcoded user-visible strings in templates/JS/Java (review + lint pass).
- [x] All confirmation flows (close account, wipe reset, reversal) use KRT modals.
- [x] Each bank page visually matches its final-draft mockup (dashboard D1, detail K1,
  management W1 + G1/G2, admin A1 + A2) and reuses the design-system bank component
  classes instead of bespoke CSS.

**Enforced by:** `:frontend:check` (htmlhint/eslint/stylelint/prettier) over the bank templates · `BankInPlaceFragmentMvcTest` (single Kontobewegung entry point in `accountBody`) · **Code:** `static/css/bank.css`, `templates/bank-*.html`, `templates/fragments/bank-movement-modal.html`, `templates/fragments/scu-hint.html`, `templates/admin/bank*.html`, `static/js/bank.js` · **Issues:** #556, #997

### REQ-BANK-018 — API & persistence conventions

Bank endpoints live under `/api/v1/bank/**` and follow
`docs/specs/api-conventions.md` (DTO-only boundaries, MapStruct, `@Valid`, RFC 7807,
`PageResponse` with whitelisted sort fields, UTC storage, SpringDoc/`openapi.json`
upkeep) and `docs/specs/data-persistence.md` (Flyway-only schema, no N+1). Mutable rows
(`bank_account`, `bank_account_grant`, `bank_holder`) carry `@Version` and
echo it through DTOs and `data-version` DOM attributes; ledger and audit rows are
insert-only and deliberately version-less. The booking flow observes the CLAUDE.md
concurrency rules (`…WithinTransaction` pattern, no bulk updates in loops).

**Acceptance**

- [x] ArchUnit suite passes with the bank controllers/services registered in the
  relevant rule whitelists (`BankSecurityService` as accepted gate).
- [x] `openapi.json` regenerated in every phase PR that touches the API.

**Enforced by:** `OpenApiGeneratorTest`, `docs/specs/api-conventions.md` · **Code:** `controller/Bank*Controller`, `web/PaginationUtil` · **Issues:** #556

### REQ-BANK-019 — Season independence

The bank has **no coupling** to seasons, price lines, mission finance entries, operation
payouts or job-order profit flows. No bank code reads or writes those aggregates; no
automated flow books into the bank in v1 (all bookings are manual by bank staff).
Integrations (e.g. auto-booking operation payouts) are explicitly out of scope and would
require a spec change.

**Acceptance**

- [x] Bank services/repositories have no dependency on mission/operation/order types
  (ArchUnit-checkable package rule).

**Enforced by:** `ArchitectureTest` (`bankClassesMustStaySeasonAndProfitIndependent`) · **Code:** all `Bank*` production classes (the package-wide rule forbids any season/profit dependency) · **Issues:** #556

### REQ-BANK-020 — Storage, performance & integrity

PostgreSQL remains the **single datastore** for the bank (ADR-0009) — no additional
database, cache layer or event store is introduced. Balance reads are SQL aggregates
backed by a composite index on `bank_posting (account_id, created_at)`; the dashboard and
statement queries are grouped single-statement reads. A scheduled integrity job (pattern:
`task/UserSyncTask`) periodically verifies the ledger invariants (`TRANSFER` account legs and
holder legs each sum to zero; `HOLDER_TRANSFER` holder legs sum to zero with no account leg;
`REVERSAL` legs are the negated mirror on **both** ledgers; **no negative account
balances** — the holder dimension is intentionally allowed to be negative (REQ-BANK-006), so
it is **not** checked; an audit row exists for every audited transaction — every type except
`WIPE_RESET`, which is summarized by one event, not one per generated transaction) and reports
violations as `ERROR` log events with `correlationId`.

> **Amended by REQ-BANK-043:** a **split** `DEPOSIT` has several positive account legs summing to the
> gross against one holder leg of the gross. It is a `DEPOSIT`, so the zero-/`−fee`-sum invariants
> (which apply only to `TRANSFER`/`HOLDER_TRANSFER`) do not constrain it; it still carries exactly one
> audit row (`DEPOSIT_SPLIT_BOOKED`), so the audit-row-per-transaction sweep is unchanged.
>
> **Amended (ADR-0052 fee model; #998 Umbuchung fee):** the account- and holder-leg sums for
> `TRANSFER` and `HOLDER_TRANSFER` net to **`−transfer_fee`**, not zero — a fee-free row (`transfer_fee
> = 0`) still nets to zero, so the same `SUM(legs) + transfer_fee = 0` check holds. Since #998 a
> fee-bearing `HOLDER_TRANSFER` books a single `CARTEL` **account leg** of `−fee` (the fee borne by the
> KRT account, REQ-BANK-031), so the account-leg sweep now covers `HOLDER_TRANSFER` too; a fee-free
> Umbuchung books no account leg and never appears in that check. The metric/alert
> (`basetool_bank_ledger_integrity_violations`) is unchanged — the sweep simply accepts the new shape,
> so it never false-fires (REQ-OBS-011).

**Acceptance**

- [x] Dashboard and account-list reads stay statement-bounded (no per-account N+1): a
  seeded ≥ 100-account test pins the statement count independent of the account count
  (the N+1 property holds regardless of total posting volume).
- [x] The integrity job flags a synthetically corrupted ledger in tests, including a
  transaction missing its audit row (the summarized `WIPE_RESET` is excluded).

**Enforced by:** `BankLedgerIntegrityServiceTest` (synthetic corruption incl. missing audit row), `BankReadNoNPlusOneTest` (statement-count bound), `DatabaseIndexMigrationTest` (composite indexes) · **Code:** `service/BankLedgerIntegrityService`, `task/BankLedgerIntegrityTask`, `db/migration/V150`+`V153` indexes · **Issues:** #556

### REQ-BANK-021 — Org-unit officer/lead balance view

Officers and leads may see the **current balance — and nothing else** — of the bank
account of an org unit they oversee (epic #666 F1). "Oversee" is the existing oversight
scope (ADR-0020): an officer oversees their own Staffel, an SK lead the Spezialkommando(s)
they lead, an admin all org units (or the one pinned). The view is **balance-only**: no
transaction history, no holder distribution, no audit — those stay a bank-staff surface
(REQ-BANK-010). It is exposed **outside** the bank role space, under
`/api/v1/org-units/bank/balances` (authenticated; the scope decides the result), so an
officer/lead needs **no** bank role and reaching it never grants any other bank surface.
This is the sole, owner-approved relaxation of REQ-BANK-008's "members see nothing" rule,
and it is mediated entirely by a non-`Bank*` seam so `BankSecurityService` stays
org-unit-blind.

> **Amendment (REQ-BANK-028):** the page lists **only active accounts** (a `CLOSED` account is
> filtered out), and a Bereich/OL overseer (or admin) additionally sees the cartel-wide **special
> accounts** (Sonderkonten) **view-only**.
>
> **Amendment (30-day trend):** each card additionally shows the same **30-day trend** the bank
> dashboard renders (REQ-BANK-016) — the sign-colored net delta and the server-computed inline SVG
> sparkline of the daily end-of-day balances. The figures are derived per account from one extra
> windowed posting-slice query (no per-account N+1) via the shared `BankTrendCalculator` /
> `BankSparkline` helpers, so both surfaces stay identical. This stays balance-/aggregate-only — it
> still exposes no transaction history, holders or audit.
>
> **Amendment (REQ-BANK-035/-037/-038):** the page is no longer balance-only. The visible set now also
> includes accounts the responsible holder has **granted** the caller (REQ-BANK-035) and the
> all-members cartel account (REQ-BANK-037), so the page is reachable by any KRT member (not just
> officers/leads). Clicking a visible card opens a **read-only account detail with the transaction
> history and a Halter-redacted Kontoauszug** (REQ-BANK-038) — booking actions stay out; the history
> and statement are no longer a bank-staff-only surface for these viewers, but player custody is
> redacted.
>
> **Amendment (design-system layout):** the page uses the design-system tab nav — **Konten** (the
> dense account list) and **Meine Anträge** (the requester's own requests) — with both panels and
> their counts living inside the `orgUnitBank` swap fragment, so a request create/cancel updates the
> list and the request tab-count in place (REQ-FE-005); the active tab is carried in a `#tab=` deeplink
> that is re-applied after each swap. A booking request is raised from a **single page-level CTA**
> whose modal carries an **account selector** filled only with the caller's requestable accounts
> (`canRequest`); the CTA + modal are shown only when at least one such account is visible, replacing
> the former per-card request button + "view only" label. Accounts render with a linked name, 30-day
> trend, balance, target progress and a single "Details" action — no per-account status chip and no
> per-card CTA.
>
> **Amendment (card/table view toggle):** the "Konten" list carries a **single per-user view toggle**
> ("Tabellenansicht", `bank.dashboard.view.tableToggle`) that switches it between a **card grid**
> (default) and the **dense table** (the former `.ou-list`), mirroring the dashboard's layout switch
> (REQ-BANK-016) **but without the by-Bereich grouping** — this surface has no grouping option. The
> choice is persisted per user in `localStorage` and replayed through the `layout` query parameter on
> a dedicated `orgUnitBankAccounts` sub-fragment swap (`#ou-acc-results`), so a toggle re-renders only
> the switchable account list in place (REQ-FE-005), never a reload. Like the dashboard, the layout
> also rides in the address bar (`history:true`), and the `orgUnitBank` refresh target now **preserves
> the query** so a request create/cancel swap re-renders the list in the caller's chosen layout
> server-side (no client re-application needed). The toggle + the account-name filter (REQ-BANK-046)
> live outside `#ou-acc-results` but inside the `orgUnitBank` fragment, so the checkbox keeps its
> state across a layout swap and resets the filter on a request swap. Its `data-ou-view-*` attributes
> are distinct from the dashboard's `data-bank-view-*` module so the two never cross-fire.

**Acceptance**

- [x] An officer/lead sees only the balance of org units in their oversight scope; a plain
  member receives an empty list (no leak of account existence). (`OrgUnitBankAccessServiceTest`)
- [x] The response carries no history/holders/audit — balance + account identity + the 30-day
  delta/sparkline aggregates only.
- [x] Each card shows the 30-day delta + sparkline trend, derived from the windowed posting slices
  with no per-account N+1 (`OrgUnitBankAccessServiceTest`, frontend `OrgUnitBankPageControllerMvcTest`).
- [x] The seam is the only class bridging `OwnerScopeService` and the bank accounts
  repository (`ArchitectureTest.orgUnitAwareBankSeamIsContainedToOneClass`); `BankSecurityService`
  never depends on `OwnerScopeService` (`bankClassesMustNotConsultOrgUnitScope`).
- [x] A slim standalone page lists the overseen balances, gated to officers/leads, not
  `BANK_EMPLOYEE` (frontend `OrgUnitBankPageControllerMvcTest`).
- [x] A client-side account-name live filter sits above the Konten list and hides non-matching
  card/table items as the user types (REQ-BANK-046).
- [x] The Konten list defaults to a card grid and offers a single per-user "Tabellenansicht" toggle
  (no by-Bereich grouping) that swaps the `orgUnitBankAccounts` sub-fragment in place, persisted in
  `localStorage` and re-applied after an `orgUnitBank` swap (frontend `OrgUnitBankPageControllerMvcTest`).

**Enforced by:** `OrgUnitBankAccessServiceTest`, `OrgUnitBankControllerTest`, `ArchitectureTest`, frontend `OrgUnitBankPageControllerMvcTest`, frontend `BankPageControllerTest` (sparkline scaling) · **Code:** `service/OrgUnitBankAccessService`, `service/BankTrendCalculator`, `controller/OrgUnitBankController`, `model/dto/OrgUnitBankBalanceDto`, `repository/BankAccountRepository#findByOrgUnitId`, `repository/BankPostingRepository#postingSlicesSince`, frontend `controller/OrgUnitBankPageController`, frontend `controller/BankSparkline`, `templates/org-unit-bank.html`, `templates/fragments/org-unit-bank-views.html`, `static/js/bank.js` (org-unit view-toggle module), `static/css/bank.css` · **Issues:** #666, #668, #669

### REQ-BANK-022 — Confirm-before-post booking requests: create & cancel

> **Amended by REQ-BANK-039/-040/-041:** request eligibility is now **view-based** (anyone who may
> view a request-capable account may request, not just own-level oversight), the movement kinds now
> include **`TRANSFER`** (REQ-BANK-040), and an over-limit request is flagged for the responsible
> holder's approval (REQ-BANK-041). The create/cancel off-ledger mechanics below are unchanged.

Officers/leads may raise **deposit/withdrawal requests** against their overseen org unit's
account (epic #666 F2). A request is recorded **`PENDING`** and **off-ledger** (ADR-0021):
it is audited on creation (`BOOKING_REQUEST_CREATED`) and visible, but moves **no money** —
the balance does not change until a bank employee confirms it (REQ-BANK-023). The requester
chooses **no holder** (that is recorded at confirmation). A requester sees the status of
their **own** requests only (per-user isolation) and may **cancel** an own request while it
is still `PENDING` (`BOOKING_REQUEST_CANCELLED`). The requester endpoints live under
`/api/v1/org-units/bank/requests/**`, scope-checked by the same seam (create requires the
caller to oversee the org unit; cancel requires owning the request). A request cannot be
raised against a `CLOSED` account.

**Acceptance**

- [x] Create stamps `PENDING`, the requester + requester-handle snapshot, audits
  `BOOKING_REQUEST_CREATED`, and books nothing (`BankBookingRequestServiceTest`).
- [x] Create outside the caller's oversight scope is denied **before** the account is
  resolved (no existence leak); a closed account is rejected `BANK_ACCOUNT_CLOSED`
  (`OrgUnitBankAccessServiceTest`, `BankBookingRequestServiceTest`).
- [x] Cancel is restricted to the requester's own `PENDING` request (foreign id → 404,
  already-decided → 409 `BANK_REQUEST_NOT_PENDING`), with optimistic-lock echo.
- [x] Request form (no holder field) + own-status list with a cancel action on the slim page,
  AJAX no-reload (frontend `OrgUnitBankPageControllerMvcTest`).

**Enforced by:** `BankBookingRequestServiceTest`, `OrgUnitBankAccessServiceTest`, `OrgUnitBankControllerTest`, frontend `OrgUnitBankPageControllerMvcTest` · **Code:** `service/OrgUnitBankAccessService`, `service/BankBookingRequestService`, `model/BankBookingRequest`, `db/migration/V159`, frontend `controller/OrgUnitBankProxyController`, `templates/org-unit-bank.html`, `static/js/bank.js` · **Issues:** #666, #670, #672

### REQ-BANK-023 — Booking-request confirmation/rejection by bank staff

> **Amended by REQ-BANK-040/-041:** confirmation also books a **`TRANSFER`** (recording source +
> destination holders, via `bookTransfer`), and an **over-limit** request additionally requires the
> bank employee's mandatory "approval obtained" checkbox (`BANK_OWNER_APPROVAL_REQUIRED`, 409) before
> it can be confirmed, audited as `BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED`.
>
> **Amended (2026-07-05, #995):** the staff queue is a **single table with parallel status filters**
> — Ausstehend / Bestätigt / Abgelehnt / Zurückgezogen toggled independently rather than the former
> mutually-exclusive tabs, defaulting to **only Ausstehend**. The `status` query parameter is now
> multi-valued (comma-separated / repeatable; an absent parameter defaults to `PENDING`, a blank one
> is the deliberate "all filters off" selection → empty table with a distinct hint), and the backend
> pages over `findByStatusIn` / `findByStatusInAndAccountIdIn`. The selection is persisted **per user**
> in `localStorage` (the Lager pattern) and replayed on load, and each request row carries an
> **expandable Begründung + Notiz sub-row** reusing the account-detail chevron pattern
> (REQ-BANK-017/-045).
>
> **Amended (2026-07-05, #997) — direct-booking "Kontobewegung" CTA:** the queue header gains a
> page-level **"Kontobewegung"** button (shown when at least one active account exists) that opens the
> **shared unified movement modal** (REQ-BANK-017). Because this surface is not account-scoped, the
> modal adds a **source-account selector**; bank staff **book directly** against the chosen account
> (a deposit/withdrawal/transfer, not a request) through the unchanged `/api/proxy/bank/{deposits,
> withdrawals,transfers}` endpoints — so this adds **no new endpoint, audit event or metric** (the
> booking is audited exactly once by the existing ledger flow). The controller assembles the modal's
> data (active accounts, holders, counterparty users, fee rate) only on the **full-page** render, not
> on the `requestQueue` fragment swap; a successful booking re-renders the queue in place (unchanged,
> since a direct booking is not a request).
>
> **Amended (2026-07-05) — status filters as checkboxes:** the parallel status filters render as **independent checkboxes** (Ausstehend / Bestätigt / Abgelehnt / Zurückgezogen) instead of filled orange toggle chips, so an active filter no longer shouts. The behaviour is unchanged — each is independent, the selection persists per user in `localStorage` and replays through the multi-valued `status` query parameter (`NONE` sentinel = all off), defaulting to only Ausstehend — only the control and its styling change (`bank.js` reads each checkbox's `checked` state on `change`, not a button's `aria-pressed`).

A **bank employee** confirms or rejects a `PENDING` request under
`/api/v1/bank/requests/**` (`BANK_EMPLOYEE` URL+method gate). **Confirmation** records the
**holder** (deposit → who received the money; withdrawal → who paid it out) and books a real
ledger transaction by reusing `BankLedgerService` — so the account lock, holder-activity
guard and the **overdraft check are re-evaluated at confirmation time** (not at request
time), exactly like a direct booking (REQ-BANK-006). It requires the per-account capability
matching the type (`can_deposit` for a deposit, `can_withdraw` for a withdrawal); the request
then flips to `CONFIRMED`, links the resulting transaction, and writes both the
`DEPOSIT_BOOKED`/`WITHDRAWAL_BOOKED` ledger audit and a `BOOKING_REQUEST_CONFIRMED` event.
**Rejection** requires visibility of the account, records a reason, flips to `REJECTED` and
moves no money. A non-`PENDING` request is rejected `BANK_REQUEST_NOT_PENDING`; the request
row is pessimistically locked and `@Version`-guarded so two decisions cannot double-book.

**Acceptance**

- [x] Confirm books exactly one transaction with the recorded holder, re-checks overdraft at
  confirmation, requires the matching capability (else 403), and double-confirm/stale-version
  → 409 (`BankBookingRequestServiceTest`, reusing `BankLedgerServiceTest` ledger guarantees).
- [x] Reject flips to `REJECTED` with a reason and books nothing; needs account visibility.
- [x] Staff confirmation queue + confirm modal (holder selector) + reject modal, AJAX no-reload
  (frontend `BankRequestQueuePageControllerMvcTest`).
- [x] The queue's account column leads with the account's display name (`accountName`) for
  readability, with the account number shown small underneath; the org-unit shorthand is dropped
  from this column (frontend `BankRequestQueuePageControllerMvcTest`).
- [x] The queue is one table with parallel status-filter checkboxes (default Ausstehend), the
  selection saved per user; an empty selection shows a distinct hint, and each request row expands to
  show its Begründung + Notiz (frontend `BankRequestQueuePageControllerMvcTest`,
  `BankRequestControllerTest`, `BankBookingRequestServiceTest`).
- [x] The queue header carries a page-level **Kontobewegung** CTA (when an active account exists) that
  opens the shared unified movement modal with a source-account selector and books directly via the
  existing endpoints — no new endpoint/audit/metric (frontend
  `BankRequestQueuePageControllerMvcTest`).

**Enforced by:** `BankBookingRequestServiceTest`, `BankRequestControllerTest`, frontend `BankRequestQueuePageControllerMvcTest` · **Code:** `service/BankBookingRequestService`, `controller/BankRequestController`, frontend `controller/BankRequestQueuePageController`, `controller/BankProxyController`, `templates/bank-requests.html`, `templates/fragments/bank-movement-modal.html` · **Issues:** #666, #671, #672, #997

### REQ-BANK-024 — Off-ledger requests: audit & ledger-integrity isolation

The `bank_booking_request` table is **mutable** (`@Version`) and **off the append-only
ledger** (ADR-0021): a `PENDING`/`REJECTED`/`CANCELLED` request never has a posting, and a
`CONFIRMED` request has exactly one linked transaction whose postings reconcile normally
(V159 `chk_bank_booking_request_confirmed_refs`). The four lifecycle
`BOOKING_REQUEST_{CREATED,CONFIRMED,REJECTED,CANCELLED}` audit events join the existing admin-only
audit log (REQ-BANK-012) — requests are auditable from creation, before any money moves; the
over-limit `BOOKING_REQUEST_OWNER_APPROVAL_*` events (REQ-BANK-041) join the same log + filter. The ledger-integrity invariants (REQ-BANK-020) are **unaffected**:
off-ledger requests contribute no postings and are intentionally excluded from the integrity
sweep.

**Acceptance**

- [x] V159 enforces that only a `CONFIRMED` request carries a holder + resulting transaction.
- [x] The lifecycle `BOOKING_REQUEST_*` events are append-only audit rows (no DB CHECK on
  `event_type`, enum is source of truth).
- [x] Admin audit log surfaces + filters these event types (still admin-only): the
  `EVENT_TYPES` filter list + `admin.bank.audit.event.BOOKING_REQUEST_*` i18n labels.

**Enforced by:** `db/migration/V159`, `BankBookingRequestServiceTest` (audit on each transition) · **Code:** `model/BankBookingRequest`, `model/BankAuditEventType` · **Issues:** #666, #673

### REQ-BANK-025 — Close-account guard & org-unit-feature role matrix

An account with an open (`PENDING`) booking request **cannot be closed** — it is rejected
`BANK_ACCOUNT_HAS_PENDING_REQUESTS`, alongside the existing zero-balance rule (REQ-BANK-002):
the requests must be confirmed, rejected or cancelled first. The org-unit features extend the
visibility matrix (REQ-BANK-010) with exactly one capability for officers/leads — balance-only
view + own-request status — and nothing else; bank staff/management/admin gain the
confirm/reject/queue surface; the audit log stays admin-only.

**Acceptance**

- [x] Close with an open `PENDING` request → 409 `BANK_ACCOUNT_HAS_PENDING_REQUESTS`
  (`BankAccountServiceTest`).
- [x] Role matrix verified end to end (`BankOrgUnitRequestsE2eTest`): an officer reaches the slim
  page and raises a request, a plain member is locked out, a granted bank employee confirms it from
  the staff queue moving the balance, the officer cancels and the employee rejects; the officer
  cannot reach the `BANK_EMPLOYEE`-only queue and a pure bank employee cannot reach the
  officer/lead page. The per-action capability + audit admin-only invariants are additionally
  pinned by the backend tests.

**Enforced by:** `BankAccountServiceTest`, `BankBookingRequestServiceTest`, frontend `OrgUnitBankPageControllerMvcTest` / `BankRequestQueuePageControllerMvcTest`, e2e `BankOrgUnitRequestsE2eTest` · **Code:** `service/BankAccountService#closeAccount`, `exception/BankConflictException` · **Issues:** #666, #673

### REQ-BANK-026 — Notifications on booking-request lifecycle

> **Amended (responsible-holder notifications, REQ-BANK-034, owner request):** the account's
> **responsible holder** (Kontoverantwortliche) is now also notified when a request on their account
> is **created or decided**. A new **`ACCOUNT_RESPONSIBLE`** selector resolves the responsible
> holder(s) from the account carried by the event (`contextAccountId()`), with the org-unit-aware
> derivation kept inside the `OrgUnitBankAccessService` seam
> (`resolveResponsibleHolderUserIds`, REQ-BANK-008) so the bank stays org-unit-blind. On **create**
> the holder joins the existing `BANK_BOOKING_REQUEST_CREATED` rule (its text is account-centric); on
> **confirm/reject** two new seeded rules (V194) produce the account-centric
> `BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED` / `…_REJECTED` notification types (the requester keeps
> the existing requester-directed text). The confirm/reject events now carry the account id so the
> selector can resolve. `exclude_actor` still drops the creating/deciding actor, so a holder who is
> the requester or the decider is not notified about their own action.

The booking-request lifecycle is notified in-app (epic #666; owner-requested addition), reusing
the data-driven notification engine (REQ-NOTIF-007, ADR-0015) rather than hardcoding recipients:

- **On creation**, the **bank management** and the **employees granted on the target account** are
  notified. A `BANK_BOOKING_REQUEST_CREATED` event drives a seeded default rule (V160) with a
  `ROLE` selector (`BANK_MANAGEMENT`) and a new **`ACCOUNT_GRANT`** selector that resolves the
  employees holding a `bank_account_grant` on the account carried by the event.
- **On confirmation/rejection**, the **requesting officer/lead** is notified of the outcome (the
  rejection reason is rendered in the text). `BANK_BOOKING_REQUEST_CONFIRMED` /
  `BANK_BOOKING_REQUEST_REJECTED` events drive seeded rules (V161) with a new **`EVENT_RECIPIENT`**
  selector that resolves the directed recipient (the requester) carried by the event.

Both `ACCOUNT_GRANT` and `EVENT_RECIPIENT` read their context off the event and populate no selector
columns (ADR-0022, REQ-NOTIF-011). The deciding/requesting actor is excluded; every rule is
admin-editable at runtime.

- **On decision or withdrawal, the staff's "new booking request" items are cleared.** Once a request
  is **confirmed**, **rejected** (both by a bank employee) or **cancelled** (the requester withdraws
  their own still-pending request), the `BANK_BOOKING_REQUEST_CREATED` notifications shown to the bank
  management + the account's grant holders are stale and are **removed** from their inboxes via the
  generic notification-supersede mechanism (REQ-NOTIF-018): the three lifecycle-terminating events
  each `resolvesNotificationTypes() = {BANK_BOOKING_REQUEST_CREATED}`. Cancellation additionally fires
  the notify-nobody `BANK_BOOKING_REQUEST_CANCELLED` event whose sole purpose is that removal. The
  affected staff's badge/bell refresh live.

**Acceptance**

- [x] Creating a request fires `BANK_BOOKING_REQUEST_CREATED` after commit; the seeded rule
  resolves bank management + the account's grant holders, excluding the requester
  (`RuleEvaluationServiceTest`, `BankBookingRequestServiceTest`).
- [x] Confirming/rejecting a request fires `BANK_BOOKING_REQUEST_CONFIRMED` /
  `BANK_BOOKING_REQUEST_REJECTED` after commit; the seeded rules notify the requester via the
  `EVENT_RECIPIENT` selector, excluding the deciding employee
  (`RuleEvaluationServiceTest`, `BankBookingRequestServiceTest`).
- [x] Confirming / rejecting / cancelling a request (after commit) removes the
  `BANK_BOOKING_REQUEST_CREATED` notifications for that request from the staff's inboxes
  (REQ-NOTIF-018; `BankBookingRequestServiceTest`, `NotificationCreationServiceTest`).
- [x] The `ACCOUNT_GRANT` / `EVENT_RECIPIENT` selectors read their context from the event and need
  no schema change to the selector table.
- [x] The notifications render in the bell/inbox via `notifications.type.BANK_BOOKING_REQUEST_*`
  (i18n keys in all three bundles, named placeholders `{accountNo}`/`{amount}`/`{requester}`/`{reason}`).

**Enforced by:** `RuleEvaluationServiceTest`, `BankBookingRequestServiceTest`,
`NotificationCreationServiceTest` · **Code:**
`event/BankBookingRequest{Created,Confirmed,Rejected,Cancelled}Event`,
`service/RecipientResolutionService#resolveAccountGrantHolders`,
`model/SelectorKind#{ACCOUNT_GRANT,EVENT_RECIPIENT}`, `db/migration/V160`, `db/migration/V161`;
lifecycle-close removal in REQ-NOTIF-018 · **Issues:** #666, #673, #1252

### REQ-BANK-027 — Bereich/OL bank access via the OrgUnitBankAccessService seam (cascading view, view-based requests)

> **Amended by REQ-BANK-039:** booking-request eligibility is no longer own-level only — **any caller
> who may view a request-capable account (`ORG_UNIT` / `AREA` / `CARTEL`) may now raise a request
> against it**, so a Bereichsleitung/OL may also request on a subordinate account they can view. The
> cascading view below is unchanged; the "own-level only" request rule, its subordinate-rejection
> acceptance criterion and the `currentOwnLevelOversightScope()` implementation note are superseded
> (the gate is now `canView`, and `canRequest` now means "a request-capable account the caller may
>
>> view"). Bank-staff confirmation still gatekeeps every booking.

The org hierarchy (REQ-ORG-014) extends the epic-#666 officer/lead bank function (REQ-BANK-021/022) up
the new levels, **without** weakening the bank's org-unit-blindness (REQ-BANK-008, ADR-0011): all new
logic stays in the single non-`Bank*` seam `OrgUnitBankAccessService` (ADR-0020/0028); the ledger,
`BankSecurityService` and the grant model are untouched, and both ArchUnit pins
(`bankClassesMustNotConsultOrgUnitScope`, `orgUnitAwareBankSeamIsContainedToOneClass`) stay green.

- **Account identity:** `AREA` accounts are linked to a Bereich and `CARTEL` to the OL (replacing /
  constraining the free-form `areaName`, REQ-BANK-001).
- **View cascades down:** `listOverseenBalances()` returns the caller's own-level account **and** all
  subordinate accounts in their oversight scope (Bereichsleitung → its `AREA` account + every child
  Staffel/SK `ORG_UNIT` account; OL → `CARTEL` + every `AREA` + every `ORG_UNIT`). Strict silo holds —
  no foreign-Bereich account is listed.
- **Requests follow view eligibility (amended by REQ-BANK-039):** `createBookingRequest()` is permitted
  on **any request-capable account the caller may view** (`ORG_UNIT` / `AREA` / `CARTEL`) — so a
  Bereichsleitung/OL may request on subordinate accounts they oversee as well as their own-level one
  (the prior own-level-only rule is superseded). The confirm-before-post flow, overdraft/holder checks
  and `ACCOUNT_GRANT` notifications are unchanged; bank staff still confirm.
- The officer flow from REQ-BANK-021/022 is preserved exactly (no regression).
- **Special accounts & active-only (REQ-BANK-028):** a Bereich/OL overseer additionally sees the
  cartel-wide `SPECIAL` accounts (Sonderkonten) **view-only**, and the page lists only `ACTIVE`
  accounts.

**Acceptance**

- [x] A Bereichsleitung sees its `AREA` balance + all child `ORG_UNIT` balances; the OL sees `CARTEL` +
  all `AREA` + all `ORG_UNIT`; no foreign-Bereich balance leaks.
- [x] A Bereichsleitung can file a request on its `AREA` account and the OL on `CARTEL`; since
  REQ-BANK-039 a request on a **viewable subordinate** account is also permitted (no longer rejected).
- [x] `bankClassesMustNotConsultOrgUnitScope` and `orgUnitAwareBankSeamIsContainedToOneClass` stay green;
  the existing officer flow and ledger tests are unchanged.

**Implementation notes.** The linkage reuses the existing `bank_account.org_unit_id` FK — since
Bereich/OL are first-class `org_unit` rows (REQ-ORG-014), an `AREA` account points it at the Bereich and
`CARTEL` at the OL, so the existing `uq_bank_account_org_unit` partial unique index enforces one account
per org unit for free (one `AREA` per Bereich, one `CARTEL` per OL). `V168` only **relaxes the
`chk_bank_account_owner_ref` CHECK** (no column, no backfill — the legacy `areaName` form stays valid
during the soak; `BankAccountService` validates `kind = BEREICH`/`ORGANISATIONSLEITUNG` on creation). The
seam uses the cascading `OwnerScopeService.currentOversightScope()` for the F1 view; since REQ-BANK-039
the F2 request gate is `canView(account)` (request-capable types only), and the balance DTO's
`canRequest` flag now marks any request-capable account the caller may view. The bank-management create
form sources its picker from `GET /api/v1/org-units/active-all-kinds` (all four kinds) and filters the
options by the selected account type.

**Enforced by:** `OrgUnitBankAccessServiceTest` (cascading view incl. AREA + child accounts and the
foreign-Bereich exclusion, view-based request eligibility per REQ-BANK-039),
`OwnerScopeServiceTest` (`CurrentOversightScopeTests` cascade, `CurrentOwnLevelOversightScopeTests`,
`CanAccessBlueprintOverviewTests` Bereich/OL), `BankAccountServiceTest` (AREA→Bereich / CARTEL→OL
creation + cardinality), `V168BankAreaCartelLinkageMigrationTest` (CHECK relax + one-account-per-org-unit),
`ArchitectureTest` (both bank pins) · **Code:** `service/OrgUnitBankAccessService`,
`service/OwnerScopeService` (`currentOversightScope` / `currentOwnLevelOversightScope`),
`service/BankAccountService`, `db/migration/V168`, `controller/OrgUnitController#listActiveOrgUnitsAllKinds`,
`templates/bank-manage.html` + `static/js/bank.js`, `templates/org-unit-bank.html` ·
**ADR:** [ADR-0028](../adr/0028-bank-bereich-ol-access-seam.md) · **Issues:** #692, #699.

### REQ-BANK-028 — Org-unit bank page: special-account view for Bereich/OL & active-only listing

Two owner-approved refinements of the org-unit bank page (`/org-unit-bank`, REQ-BANK-021/-027),
mediated entirely by the existing `OrgUnitBankAccessService` seam — the bank stays org-unit-blind
(REQ-BANK-008, ADR-0011) and both ArchUnit pins (`bankClassesMustNotConsultOrgUnitScope`,
`orgUnitAwareBankSeamIsContainedToOneClass`) stay green:

- **Special accounts (Sonderkonten) are visible to Bereich/OL overseers — view-only.** A caller who
  holds a Bereich- or OL-level oversight seat (`is_bereichsleiter` / `is_bereichskoordinator` /
  `is_bereichsoperator` / `is_ol_member`) — or an admin — additionally sees the balance of every
  `SPECIAL` account on the page. Special accounts belong to no org unit, so they are matched by **type**
  (not by the oversight cascade) and are strictly **view-only**: `canRequest` is always `false` and the
  F2 create path rejects them (no owning org unit to scope). **Officers and SK leads do not** see
  special accounts — only Bereich/OL/admin do (`OwnerScopeService.currentUserHasAreaOrOlOversight()`).
  This widens the *view* only; it grants no deposit/withdrawal/booking-request capability on those
  accounts.
- **Only active accounts are listed.** The page lists `ACTIVE` accounts exclusively — a `CLOSED`
  account (org-unit *or* special) is filtered out, so the org-unit bank page always shows live
  accounts. Closing rules and the bank-staff view of closed accounts are unchanged (REQ-BANK-002/-010).

> **Amendment (REQ-BANK-037, owner-approved tightening):** the Sonderkonto auto-view is narrowed from
> "any Bereich/OL oversight seat" to **OL members and `BEREICHSLEITER` only** (plus bank staff/admin) —
> Bereichskoordinatoren and Bereichsoperatoren no longer auto-see Sonderkonten. On top of that, OL
> members or bank management may **grant** further Sonderkonto visibility (global roles / all members /
> individual users, REQ-BANK-035), and a Sonderkonto now supports the read-only drill-in + statement
> (REQ-BANK-038), not just a balance card. The seam helper is `currentUserIsOlMember()` /
> `currentUserIsBereichsleiter()` (not the broader `currentUserHasAreaOrOlOversight()`).

**Acceptance**

- [x] A Bereich/OL overseer (or admin) sees the active `SPECIAL` accounts, view-only (`canRequest`
  false) and without an org-unit identity; an officer / SK lead does not see them
  (`OrgUnitBankAccessServiceTest`, `OwnerScopeServiceTest.CurrentUserHasAreaOrOlOversightTests`).
- [x] A `CLOSED` account (org-unit or special) never appears on the page; only `ACTIVE` accounts are
  listed (`OrgUnitBankAccessServiceTest`).
- [x] A booking request cannot target a special account (it carries no org unit; the F2 own-level
  scope check never permits it) — REQ-BANK-022 is unchanged.
- [x] The page renders a special-account card labelled by its account type with a "view only" marker
  and no request button (frontend `OrgUnitBankPageControllerMvcTest`).
- [x] `bankClassesMustNotConsultOrgUnitScope` and `orgUnitAwareBankSeamIsContainedToOneClass` stay
  green; the bank stays org-unit-blind.

**Enforced by:** `OrgUnitBankAccessServiceTest` (special-account view-only + active-only filter),
`OwnerScopeServiceTest` (`CurrentUserHasAreaOrOlOversightTests`), `OrgUnitBankControllerTest`, frontend
`OrgUnitBankPageControllerMvcTest`, `ArchitectureTest` (both bank pins) · **Code:**
`service/OrgUnitBankAccessService#listOverseenOrgUnitBalances`,
`service/OwnerScopeService#currentUserHasAreaOrOlOversight`, `model/dto/OrgUnitBankBalanceDto`, frontend
`templates/org-unit-bank.html` · **ADR:** [ADR-0028](../adr/0028-bank-bereich-ol-access-seam.md)
(amendment) · **Issues:** #666, #692.

### REQ-BANK-029 — Bank staff are auto-registered as holders

Every user holding `ROLE_BANK_EMPLOYEE` or `ROLE_BANK_MANAGEMENT` is automatically present as
an **active** `bank_holder` row, reconciled idempotently at the existing role-sync points (on
each login via `UserService.syncUser` and on the periodic `UserSyncTask`) over
`UserRepository.findUserIdsByRoleCode` (ADR-0040). A `bank_holder.role_managed` flag marks
role-derived holders. When a user loses **all** bank roles, their `role_managed` holder is
**auto-deactivated** — it accepts no new incoming money, but its (possibly negative, ADR-0039)
balance survives and must be reconciled to zero by a holder→holder Umbuchung (REQ-BANK-031).
**Manually** registered holders (`role_managed = false`, REQ-BANK-003) are never touched by the
reconcile; management may still register any tool user as a custodian.

**Acceptance**

- [x] Granting a bank role makes the user a holder (create if missing, reactivate a previously
  auto-deactivated one); the reconcile is idempotent (no duplicate rows).
- [x] Losing all bank roles auto-deactivates the `role_managed` holder while preserving its
  balance and ledger history; a manually-registered holder is unaffected.
- [x] The reconcile never hard-deletes a holder and never alters a manual holder's
  `role_managed = false`.

**Enforced by:** `BankHolderReconciliationServiceTest`, `BankHolderServiceTest` · **Code:** `service/BankHolderReconciliationService`, `service/UserService` (sync hook), `task/UserSyncTask`, `repository/UserRepository#findUserIdsByRoleCode`, `model/BankHolder`, `db/migration/V182` · **ADR:** [ADR-0040](../adr/0040-bank-staff-are-holders-and-employee-administration-access.md) · **Issues:** #556

### REQ-BANK-030 — Employee bank-administration access (Sonderkonten + holder menu)

Bank **employees** reach the bank-administration page (`/bank/manage`) and its sidebar entry
(previously management-only), with **action-level** gating (ADR-0040):

- an employee may create **only `SPECIAL`** accounts (Sonderkonten) and is **auto-granted**
  full capability (`can_deposit`/`can_withdraw`/`can_transfer`) on the account they create
  (audited `GRANT_CREATED`); the `SPECIAL`-only rule is enforced **server-side**, not just in
  the UI;
- creating any **non-`SPECIAL`** type, and **rename / close / reopen**, stay
  `ROLE_BANK_MANAGEMENT`;
- the **holder menu** (view global balances + holder→holder Umbuchung, REQ-BANK-031, + the
  per-holder custody history, REQ-BANK-032 — own holder for employees, any for management) opens to
  `ROLE_BANK_EMPLOYEE`; **manual** holder register / (de)activate and grant management stay
  `ROLE_BANK_MANAGEMENT`;
- the audit log stays admin-only (REQ-BANK-012). Org-unit independence (REQ-BANK-008) is
  untouched.

**Acceptance**

- [x] An employee creates a `SPECIAL` account and is immediately operational on it via the
  auto-grant; an employee creating a non-`SPECIAL` type, renaming, closing or reopening is
  rejected (403), even via a forged request.
- [x] An employee reaches the holder menu and performs an Umbuchung; manual holder
  registration / (de)activation and grant management remain 403 for employees.
- [x] The sidebar shows the management entry to employees; the audit log stays admin-only.

**Enforced by:** `BankControllerSecurityTest`, `BankAccountServiceTest` (employee SPECIAL-only + auto-grant), frontend `BankManagePageControllerMvcTest` · **Code:** `service/BankAccountService`, `service/BankSecurityService`, `controller/BankAccountController`, frontend `controller/BankManagePageController`, `templates/bank-manage.html` · **ADR:** [ADR-0040](../adr/0040-bank-staff-are-holders-and-employee-administration-access.md) · **Issues:** #556

### REQ-BANK-031 — Holder→holder Umbuchung (reconciliation)

The bank books a **`HOLDER_TRANSFER`** transaction that moves custody between two holders —
two holder legs summing to zero, **no account leg** (ADR-0039) — so the bank staff can
redistribute the physically-held money among themselves and bring negative custodians back to
zero (REQ-BANK-006). It is gated `hasRole('BANK_EMPLOYEE')`, needs **no** per-account grant
(it touches no account), and **ignores the holder `active` flag in both directions** so a
deactivated holder's residual (positive or negative) can be reconciled. Source and destination
holder must differ. Every Umbuchung is audited (`HOLDER_TRANSFER`, REQ-BANK-012) and recorded
in the unified activity audit (REQ-AUDIT-001).

> **Amended (2026-07-05, #998, owner-approved — supersedes the fee-free clause of ADR-0052/ADR-0039):**
> the Umbuchung **bears the in-game transfer fee, borne by the KRT (`CARTEL`) account.** `fee =
> round(amount × rate)` (the shared `operation.transfer_fee_rate`): the **source** holder's custody is
> reduced by the fee (source holder leg `−(amount + fee)`), the destination is credited the full
> `amount`, and the fee is **debited from the CARTEL account** (a single `−fee` account leg). So the
> holder ledger nets to `−fee` and the CARTEL account bears the real aUEC lost to the game — the same
> `SUM(legs) = −transfer_fee` shape as a fee-bearing `TRANSFER`, except only the CARTEL account is
> touched (REQ-BANK-033/-020). The CARTEL account is **overdraft-guarded** and never driven negative:
> a **missing or closed** CARTEL rejects the Umbuchung (`BANK_ACCOUNT_CLOSED`), and a fee it cannot
> cover is a `BANK_OVERDRAFT` (REQ-BANK-006). The fee-bearing Umbuchung stays **uncapped** — no
> KRT-account approval-tier check (REQ-BANK-047) — and the inclusive-fee toggle (REQ-BANK-033, #999)
> does **not** apply (the KRT account, not a recipient, bears the fee). A **tiny** amount whose fee
> rounds to `0` keeps the legacy fee-free shape (no account leg, holder legs net to zero); historical
> fee-free rows (`transfer_fee = 0`) stay sound. A **reversal** negates all three legs.

**Acceptance**

- [x] An Umbuchung of `A` with `fee = round(A × rate)` debits the source holder `A + fee`, credits
  the destination `A`, and debits the **CARTEL** account `fee` (`transfer_fee = fee`); a tiny amount
  whose fee rounds to 0 keeps the legacy fee-free shape (no account leg, holder legs net to zero).
- [x] A missing/closed CARTEL account (`BANK_ACCOUNT_CLOSED`) or a fee it cannot cover
  (`BANK_OVERDRAFT`) rejects the Umbuchung; the CARTEL account is never driven negative.
- [x] The ledger-integrity sweep accepts the fee-bearing Umbuchung (account + holder legs net to
  `−transfer_fee`); a reversal negates all three legs and the sweep stays sound.
- [x] It works to/from a deactivated holder and may drive the source holder negative (no
  holder overdraft); same source/destination holder is rejected.
- [x] It is reachable by `BANK_EMPLOYEE` without an account grant; a member/anonymous caller is
  403; it writes a `HOLDER_TRANSFER` audit event (detail carries the fee, amounts only).

**Enforced by:** `BankLedgerServiceTest` (tiny fee-free Umbuchung, deactivated/negative allowed), `BankHolderTransferFeeTest` (fee borne by CARTEL, missing/closed/overdraft reject, reversal negates all three legs, integrity sweep sound), `BankHolderControllerTest`/`BankControllerSecurityTest` · **Code:** `service/BankLedgerService#bookHolderTransfer`, `service/BankLedgerIntegrityService` + `repository/BankTransactionRepository#findTransferTransactionsWithNonZeroSum` (integrity now covers HOLDER_TRANSFER), `controller/BankHolderController`, `model/dto/request/BankHolderTransferRequest`, `model/BankTransactionType`, `model/BankAuditEventType` · **ADR:** [ADR-0039](../adr/0039-bank-holder-ledger-decoupled-from-accounts.md) (amended by #998), [ADR-0052](../adr/0052-bank-transfer-fee-borne-by-debited-account.md) (amended by #998), [ADR-0040](../adr/0040-bank-staff-are-holders-and-employee-administration-access.md) · **Issues:** #556, #998

### REQ-BANK-032 — Holder custody history (own for employees, all for management)

From the holder menu a bank staffer opens a holder's **custody history** — the paged list of
**every** holder-ledger leg (ADR-0039) that touched that holder's global stash, newest first.
Each row carries the booking type, the signed amount on the holder's stash (positive = received,
negative = paid out), the note, and a context annotation derived from the sibling legs in the same
transaction: for a deposit/withdrawal/transfer/reversal the **account** the money moved on (the
account leg whose sign matches the holder leg), and for a **`HOLDER_TRANSFER`** the **counter
holder** of the Umbuchung. A `WIPE_RESET` leg shows neither.

**Visibility (read gate, REQ-BANK-008 preserved):** the gate is `canSeeHolder` — **bank management**
(and admins via the hierarchy) may open **any** holder's history; a plain **bank employee** may open
**only their own** holder row (the one linked to their user id). The custody history is a read view —
it mutates nothing, so it logs no audit event. It stays org-unit-blind like every other bank gate.

In the holder menu (`/bank/manage`, tab *Halter*) the handle links to the holder detail page
(`/bank/holders/{id}`) when the caller may view it (their own row, or any row for management); other
rows render the handle as plain text. The detail page is read-only (no booking actions). The
own-row match keys on the caller's OIDC `sub` (== `app_user.id` == the holder's `userId`), read from
the authenticated principal — **not** the `preferred_username` that the frontend exposes as the
authentication name — so a plain bank employee always sees the link to their own holder.

The detail page also carries a **balance-split calculator**: the holder enters their current
in-game account balance and the page shows — **purely client-side, nothing is stored** — how much
is **reserved for the bank** (= their global custody total `totalHeld`, server-rendered) and how
much is their **own private money** (`balance − totalHeld`). A negative own value means they
physically hold less than the bank's records say (a shortfall); a negative custody total means the
bank owes them, so their own money exceeds the entered balance.

**Acceptance**

- [x] A bank employee opens their own holder's history and sees every leg with the correct account
  / counter-holder annotation and signed amount; an employee requesting another holder's history is
  rejected (403), even via a forged request.
- [x] Bank management opens any holder's history; the page is paged newest-first and reuses the
  shared pager (AJAX swap, no reload).
- [x] On the *Halter* tab a plain bank employee sees the link to their own holder row (keyed on the
  OIDC `sub`, not the `preferred_username`) and reaches their own custody history; every other
  holder's handle stays plain text.
- [x] The history is read-only and writes no audit event; the gate ignores org-unit scope and the
  active-org-unit pin (REQ-BANK-008).
- [x] The balance-split calculator shows, for an entered current balance, the bank-reserved amount
  (= the custody total) and the own private money (= balance − custody total) live and client-side;
  nothing is persisted.

**Enforced by:** `BankSecurityServiceTest` (canSeeHolder: management-any / employee-own-only), `BankHolderServiceTest` (account & counter-holder annotation, 404), `BankControllerSecurityTest` (holder-history gate), frontend `BankPageControllerTest` / `BankHolderDetailFragmentMvcTest`, `BankManagePageControllerTest` (selfUserId = OIDC sub, not preferred_username), `BankHolderSelfLinkRenderMvcTest` (employee links own holder only) · **Code:** `service/BankHolderService#getHolder/#getHolderBookings`, `service/BankSecurityService#canSeeHolder`, `controller/BankHolderController`, `repository/BankHolderPostingRepository#findHolderBookings`, `model/projection/BankHolderBookingRow`, `model/dto/BankHolderBookingDto`, frontend `controller/BankPageController`, `controller/BankManagePageController` (holder self-link selfUserId), `templates/bank-holder-detail.html`, `templates/bank-manage.html`, `static/js/bank.js` (balance-split calculator) · **ADR:** [ADR-0039](../adr/0039-bank-holder-ledger-decoupled-from-accounts.md) · **Issues:** #556

### REQ-BANK-033 — In-game transfer fee on holder-initiated transfers

Star Citizen charges an in-game fee on every aUEC transfer a holder actively initiates, so the
bank factors that fee in wherever a holder physically sends money — and **only** there — so the
bank staff are never out of pocket and the requested amount still arrives in full (ADR-0052,
superseding the carve-out model of ADR-0041):

- **Where it applies:** `WITHDRAWAL` and an account-to-account `TRANSFER` **when the holder
  changes** (a same-holder transfer is a pure re-label and stays fee-free) — the customer-facing
  moves the bank makes on a member's behalf. **`DEPOSIT` is exempt** (whoever pays money *in* bears
  their own fee) and so is the internal **`HOLDER_TRANSFER`** (Umbuchung at
  `/bank/manage?tab=halter`): that reconciliation runs among bank staff, who bear its in-game fee
  **personally**, so the bank does not model it (REQ-BANK-031).
- **Semantics:** the entered amount is the amount that must **arrive** at the destination. The fee
  `= round(amount × rate)` (whole aUEC, HALF_UP) is **added on top** and recorded on
  `bank_transaction.transfer_fee`; the source (account + holder stash) is **debited the gross**
  (`amount + fee`), so the **destination receives the full entered amount** and the **debited
  account bears the fee**. A 500 000 transfer at 0.5% therefore costs the source 502 500. The fee
  and the gross debited are shown explicitly — a live preview in the booking modals (fed by `GET
  /api/v1/bank/transfer-fee-rate`) and, on every outgoing leg of the account/holder history, the
  fee plus the amount that arrived (`|leg| − fee`).
- **Rate:** the same runtime-editable setting the operation payout uses
  (`operation.transfer_fee_rate`, default 0.5%, editable at `/admin/settings`) — one rate for the
  whole org.
- **Overdraft (REQ-BANK-006):** the source-account no-overdraft guard runs against the **gross**
  (`amount + fee`) — a booking whose account cannot cover the amount **plus** its fee is refused
  (`BANK_OVERDRAFT`), so the fee can never drive an account negative. The holder dimension may
  still go negative (ADR-0039).
- **Ledger consequence (amends REQ-BANK-020):** a fee-bearing holder-changing `TRANSFER` no longer
  nets to zero across its legs — it nets to **`−transfer_fee`** (real money lost to the game), so
  the integrity sweep expects `SUM(legs) = −transfer_fee` for it (source `−(amount + fee)`,
  destination `+amount`). The fee-free `HOLDER_TRANSFER` carries `transfer_fee = 0` and nets to zero
  as before — the same `SUM(legs) = −transfer_fee` check holds with a zero fee, so no integrity
  change is needed. The `REVERSAL` mirror invariant is unchanged (a reversal negates the actual
  recorded legs, restoring the gross to the source).

> **Amended (2026-07-05, #999) — two fee modes (on-top vs. fee-inclusive):** a fee-bearing booking
> (`WITHDRAWAL`, holder-changing `TRANSFER`) carries a `feeInclusive` flag, **default `false`**. In
> **both** modes the fee is `round(entered × rate)` — only the interpretation of the entered amount
> differs:
>
> - **`feeInclusive = false` (default, unchanged, on-top):** the entered amount is what **arrives**;
>   the fee is added on top; the source is debited `amount + fee`; the destination receives `amount`.
> - **`feeInclusive = true` (inclusive):** the entered amount is the gross **debited**; the source is
>   debited exactly `amount`; the destination/recipient receives `amount − fee`. A 500 000 inclusive
>   withdrawal at 0.5% debits 500 000 and the recipient gets 497 500.
>
> The overdraft guard runs against whatever is actually debited (`amount + fee` on-top, `amount`
> inclusive). An inclusive booking whose `amount − fee ≤ 0` is rejected `BANK_FEE_EXCEEDS_AMOUNT`
> (409). The ledger-integrity invariant is preserved in both modes: a holder-changing transfer's
> account legs still net to `−transfer_fee` (inclusive: source `−amount`, destination `+(amount −
> fee)`). The flag is a **bank-staff choice at booking time only** — a booking **request** never
> carries it, so confirmation always books on-top. It is exposed on the direct-booking Kontobewegung
> modal (REQ-BANK-017) as a checkbox (default off, shown only where a fee applies), and the live
> preview shows the fee, the "wird abgebucht" gross and the "kommt an" net for the selected mode.
> Audited in the existing `WITHDRAWAL_BOOKED` / `TRANSFER_BOOKED` detail (the fee amount plus an
> `incl` marker; amounts only, no PII) — no new audit event and no monitoring change.
>
> **Amended (2026-07-05, #998) — the fee now also applies to the `HOLDER_TRANSFER` Umbuchung.** The
> internal holder→holder Umbuchung is **no longer exempt**: a fee-bearing Umbuchung debits the source
> holder `amount + fee`, credits the destination `amount`, and debits the fee from the **KRT
> (`CARTEL`) account** (a single `−fee` account leg) — see REQ-BANK-031. Same rate, same
> whole-aUEC rounding. The inclusive-fee mode above does **not** apply to the Umbuchung (the KRT
> account, not a recipient, bears it). The `HOLDER_TRANSFER` audit detail now carries the fee.

**Acceptance**

- [x] A withdrawal of `A` records `fee = round(A × rate)`, debits the account and the holder the
  gross `A + fee`, and is refused when the account cannot cover `A + fee`; the recipient effectively
  receives the full `A` and the holder is not out of pocket.
- [x] A holder-changing account transfer credits the destination the full `A` and debits the source
  `A + fee`; its legs net to `−fee`; the integrity sweep stays sound. A same-holder transfer, a
  deposit and an internal holder→holder Umbuchung record no fee and net to zero / move the full
  amount.
- [x] The booking modals show a live "Gebühr / wird abgebucht" preview (fee plus the gross debited)
  as the amount is typed; the rate is the shared `operation.transfer_fee_rate`.
- [x] A **fee-inclusive** (`feeInclusive = true`) withdrawal/transfer (#999) debits the source the
  entered amount and the destination/recipient receives `amount − fee` (500 000 → 497 500 at 0.5%);
  the account legs still net to `−transfer_fee`; `amount − fee ≤ 0` is rejected
  `BANK_FEE_EXCEEDS_AMOUNT`; a booking request never carries the flag (confirmation books on-top);
  the preview shows "wird abgebucht" and "kommt an" for the selected mode; default is off.

**Enforced by:** `BankLedgerServiceTest` (fee added on top / fee-inclusive debit + amount−fee to destination + BANK_FEE_EXCEEDS_AMOUNT, full amount to destination, overdraft against the actual debit, same-holder + holder-Umbuchung fee-free, legs net to −fee), `BankTransferFeeServiceTest` (rate resolution + whole-aUEC rounding + `totalDebit`), `BankLedgerIntegrityServiceTest`, `BankControllerSecurityTest` (rate endpoint), frontend `BankInPlaceFragmentMvcTest` (fee-inclusive toggle renders) / `BankPageControllerTest` / `BankManagePageControllerTest` / `BankAccountDetailFragmentMvcTest` / `BankHolderDetailFragmentMvcTest` · **Code:** `service/BankTransferFeeService` (`feeOn` + `totalDebit`), `service/BankLedgerService` (deposit/withdrawal/transfer/holder-transfer), `model/dto/request/BankWithdrawalRequest` + `BankTransferRequest` (`feeInclusive`), `model/BankTransaction#transferFee`, `controller/BankBookingController#getTransferFeeRate`, `repository/BankTransactionRepository` + `BankHolderPostingRepository` (integrity), `db/migration/V183`, frontend `controller/BankPageController` / `BankManagePageController`, `static/js/bank.js`, `templates/fragments/bank-movement-modal.html`, `templates/bank-account-detail.html` / `bank-manage.html` / `bank-holder-detail.html` · **ADR:** [ADR-0052](../adr/0052-bank-transfer-fee-borne-by-debited-account.md) (supersedes [ADR-0041](../adr/0041-bank-in-game-transfer-fee.md)) · **Issues:** #556, #999

### REQ-BANK-034 — Per-account responsible holder (derived, "Kontoverantwortliche/r")

Every org-unit bank account has a **derived responsible holder** — never a free assignment, always a
function of org-unit leadership, so it follows the role automatically (epic #800, REQ-ROLE-002):

|         Account (type / owner)          |                   Responsible holder                    |
|-----------------------------------------|---------------------------------------------------------|
| Staffelkonto (`ORG_UNIT` / Squadron)    | the `STAFFELLEITER` of that Staffel                     |
| SK-Konto (`ORG_UNIT` / Spezialkommando) | the `SK_LEAD` of that Spezialkommando                   |
| Bereichskonto (`AREA` / Bereich)        | the `BEREICHSLEITER` of that Bereich                    |
| OL/KRT-Konto (`CARTEL` / OL)            | **all** `OL_MEMBER`s collegially                        |
| Kartellbankkonto (`CARTEL_BANK`)        | the `BEREICHSLEITER` of any `Department.PROFIT` Bereich |
| Sonderkonto (`SPECIAL`)                 | none                                                    |

The responsible holder may set the account's balance target (REQ-BANK-036) and configure who else may
view it (REQ-BANK-035). Resolution stays inside the `OrgUnitBankAccessService` seam (org-unit-aware);
the bank surface stays org-unit-blind (REQ-BANK-008, ADR-0011). Naming note: the code calls this
*responsible* (never "holder"/"Halter"), to avoid colliding with the aUEC-custody `BankHolder`
(ADR-0039); the German UI uses "Kontoverantwortliche/r".

> **Amended by REQ-BANK-047 (middle band corrected to the Bankleitung by ADR-0109):** for **request
> approval** the KRT account (`CARTEL`) no longer routes to the OL collegium alone — the amount-tiered
> ladder inserts the **Bankleitung** (`BANK_MANAGEMENT`) as the middle band's approver between the bank
> employee and the OL (ADR-0066, corrected from the Bereichsleiter Profit by ADR-0109). This is an
> approval-routing refinement only; the OL stays the KRT account's balance-target/visibility owner. So
> `resolveResponsibleHolderUserIds(CARTEL)` — used only to *notify* — returns **all `OL_MEMBER`s**; the
> middle-band Bankleitung is a `BANK_MANAGEMENT` Keycloak role (not an org-unit membership) so it is not
> notified via this seam but picks the request up in its bank-staff queue (ADR-0109). Each approver
> still sees only its own band in „Fremde Anträge".
>
> **Amended by ADR-0070 (responsible-holder change audit):** a **change** of an account's derived
> responsible holder is now recorded in the admin bank audit log (REQ-BANK-012) as
> **`ACCOUNT_RESPONSIBLE_CHANGED`**. Because the holder is derived, the change is detected by
> **bracketing** each of the seven `OrgUnitMembershipService` leadership mutations (assign/remove
> Staffelleiter rank, toggle SK-Lead, add/remove Bereichsleiter, add/remove OL member): the seam
> `OrgUnitBankAccessService` snapshots the affected accounts' responsible-holder sets **before** the
> mutation and re-diffs **after** it, on the same transaction, and records one event per account whose
> set changed. The affected accounts are the account the org unit owns **plus** — for a
> `Department.PROFIT` Bereich — the collegial `CARTEL` and the `CARTEL_BANK` singletons (their sets
> include the Profit-Bereichsleiter, REQ-BANK-047). The event carries the **old** and **new**
> user-id sets in its details (ids, not PII), sets `targetUserId` to the sole new holder for a
> singleton set (else null, for the collegial accounts), and the initiator is the acting user
> `BankAuditService` snapshots automatically. The affected accounts are the account the org unit owns
> **plus** — for a `Department.PROFIT` Bereich — the `CARTEL_BANK`
> singleton (its set is the Profit-Bereichsleiter, REQ-BANK-034); since ADR-0109 the `CARTEL` set is
> OL-only and no longer ripples from a Profit-Bereich leadership change. Coverage spans **every
> app-driven path** that can change the derivation: the seven direct leadership mutations, the two
> indirect membership-removal paths (`removeMember` for an SK-Lead, `reconcileStaffelMemberships` for a
> Staffelleiter), and **`UserService.deleteUser`** — which snapshots all the user's org-unit accounts
> up front and **flushes** the delete before the re-diff, since the membership rows go via the DB
> `ON DELETE CASCADE`. The bank stays org-unit-blind for **authorization**: all bank access stays
> inside the seam (both ArchUnit pins hold), reached from the membership/user services via an
> `ObjectProvider` to break the constructor cycle.

**Enforced by:** `OrgUnitBankAccessServiceTest` (holder resolution per type incl. CARTEL_BANK→PROFIT-Bereichsleiter, OL collegial; `snapshotResponsibleHolders` / `…ForUser` / `recordResponsibleHolderChanges` diff + audit), `OrgUnitMembershipServiceTest` (leadership + removal brackets), `UserServiceDeleteTest` / `UserServiceAttributesTest` / `UserDeletionForeignKeyIntegrityTest` (deletion bracket + flush) · **Code:** `service/OrgUnitBankAccessService` (`snapshotResponsibleHolders(ForUser)` / `recordResponsibleHolderChanges`), `service/OrgUnitMembershipService` (leadership + `removeMember` + `reconcileStaffelMemberships` brackets), `service/UserService#deleteUser`, `repository/BereichRepository#findByDepartment`, `repository/BankAccountRepository#findFirstByType` · **ADR:** [ADR-0043](../adr/0043-bank-account-responsibility-and-visibility.md), [ADR-0070](../adr/0070-bank-responsible-holder-change-audit.md) · **Issues:** #556

### REQ-BANK-035 — Configurable balance visibility

The responsible holder may open up an account's balance **and** read-only detail (REQ-BANK-038) to
users who would not otherwise see it, via additive `bank_account_view_grant` rows (V189). The grant
*audiences* depend on the account's owning org unit:

- **Staffelkonto:** the squadron sub-ranks `KOMMANDOLEITER` / `STELLV_KOMMANDOLEITER` / `ENSIGN` (each
  separately), all Staffel members, or named individual users.
- **SK-Konto:** all SK members, or named individual users (no sub-ranks).
- **Bereichskonto:** `BEREICHSKOORDINATOR` / `BEREICHSOPERATOR` (each separately), all Bereich members,
  or named individuals.
- **Sonderkonto (`SPECIAL`):** global roles (e.g. all officers), all KRT members, or named individuals;
  configured by **OL members or bank management** (REQ-BANK-037).
- **OL/KRT-Konto (`CARTEL`)** and **`CARTEL_BANK`** carry no configurable visibility (their audiences are
  fixed by REQ-BANK-037).

> **Amended by REQ-BANK-048:** a **Bereichskonto** additionally offers a **"Mitglieder des Bereichs"**
> (`AREA_MEMBERS`) audience — the whole area cascade (Bereichsleitung **plus** every child Staffel/SK
> member), distinct from the `ALL_MEMBERS` bucket which on an AREA account means only the Bereich's
> **direct** members (the Bereichsleitung). The new value extends the shared `BankAccountViewGranteeKind`
> enum, so it applies to visibility grants **and** approval limits alike (V202).

A grant grants *view* only — booking stays a bank-staff surface (REQ-BANK-008/-010). View grants are
distinct from the bank-staff capability `bank_account_grant`. Toggling a grant is an idempotent
insert/delete and is audited (REQ-BANK-012). The org-unit bank page is reachable by any KRT member; the
seam scopes the visible accounts per caller.

**Admin override:** an admin may configure the visibility of **every** account whose visibility is
configurable (Staffel/SK/Bereich/Sonderkonto), without being the responsible holder. The fixed `CARTEL`
(always all-members) and `CARTEL_BANK` (internal) audiences stay fixed (REQ-BANK-037) — there is nothing
to configure there, not even for an admin.

**Enforced by:** `OrgUnitBankAccessServiceTest` (canView per type: oversight / membership-role grant / all-members / individual / global-role for SPECIAL; admin override), `OrgUnitBankPageControllerMvcTest` · **Code:** `model/BankAccountViewGrant`, `repository/BankAccountViewGrantRepository`, `service/OrgUnitBankAccessService`, `controller/OrgUnitBankController`, `db/migration/V189`, frontend `templates/org-unit-bank-account-detail.html` · **ADR:** [ADR-0043](../adr/0043-bank-account-responsibility-and-visibility.md) · **Issues:** #556

### REQ-BANK-036 — Balance target ("Kontostandsziel")

An account may carry an optional **balance target** (`bank_account.balance_target`, V189) — an
aspirational fill goal shown with progress to everyone who may view the balance. It is settable by the
account's responsible holder (REQ-BANK-034) **and** by bank staff with access to the account (and by an
admin on any account); for a Sonderkonto the target is bank-staff-only for non-admins. Setting/clearing
it echoes the account's `@Version` (the
target shares the row's optimistic lock with rename/close — both infrequent) and is audited
(`BALANCE_TARGET_SET` / `BALANCE_TARGET_CLEARED`). The target enforces nothing; it is a display goal.

**Enforced by:** `OrgUnitBankAccessServiceTest` (holder-only set, version check), `BankAccountServiceTest` (bank-staff set/clear + audit) · **Code:** `model/BankAccount#balanceTarget`, `service/OrgUnitBankAccessService#setBalanceTarget`, `service/BankAccountService#setBalanceTarget`, `controller/OrgUnitBankController` / `BankAccountController`, `db/migration/V189` · **ADR:** [ADR-0043](../adr/0043-bank-account-responsibility-and-visibility.md) · **Issues:** #556

### REQ-BANK-037 — Fixed cartel/special visibility (KRT account & Sonderkonten)

Two account groups have **fixed** (non-holder-configurable) visibility on the org-unit bank page:

- **OL/KRT-Konto (`CARTEL`):** its balance and read-only detail are **always visible to every KRT
  member** (`isMemberOrAbove`); the OL collegially holds it (REQ-BANK-034) and sets its target.
- **Kartellbankkonto (`CARTEL_BANK`):** visible only to its responsible holder (the Profit-Bereichsleiter)
  and to bank staff via the bank surface; not broadcast to members.
- **Sonderkonten (`SPECIAL`):** auto-visible to bank staff/admin **and** every `OL_MEMBER` **and** every
  `BEREICHSLEITER`; additionally configurable by OL members or bank management (REQ-BANK-035). This
  **tightens** the prior REQ-BANK-028 rule (which granted SPECIAL to all Bereich oversight ranks):
  Bereichskoordinatoren/-operatoren and officers no longer auto-see Sonderkonten.

**Enforced by:** `OrgUnitBankAccessServiceTest` (CARTEL all-members; CARTEL_BANK holder-only; SPECIAL OL + Bereichsleiter auto, BK/BO not) · **Code:** `service/OrgUnitBankAccessService#canView`, `service/OwnerScopeService#currentUserIsOlMember` / `#currentUserIsBereichsleiter` · **ADR:** [ADR-0043](../adr/0043-bank-account-responsibility-and-visibility.md) · **Issues:** #556

### REQ-BANK-038 — Read-only account drill-in (history + redacted statement)

A caller who may view an account (REQ-BANK-035/-037) may open a **read-only copy of the bank-staff
account detail** from the org-unit bank page: the account header, the balance + target, the 30-day
delta, the booking count and the **paginated transaction history** — plus a **Kontoauszug (PDF)**
export. No deposit/withdrawal/transfer/reversal is possible (capabilities are all-false; only the
own-level booking *request* of REQ-BANK-022 remains). The **player-custody ("Halter") column is
redacted**: the backend nulls the holder/counter-holder handles on the booking rows before they cross
the wire, and the statement PDF is rendered without the Halter column. Bank staff keep the full,
non-redacted detail and statement (REQ-BANK-014). Authorization is enforced in the seam, which then
reuses the bank's org-unit-blind read/PDF code; both ArchUnit pins stay green.

> **Amended (2026-07-03, owner decision):** the redaction is now **Halter-only**. The
> deposit/withdrawal counterparty (Einzahler/Empfänger) and the transfer counter-account are
> **shown** to org-unit viewers in the renamed **Quell-/Zielkonto** column (both the read-only history
> and the redacted Kontoauszug — see the REQ-BANK-044 / ADR-0054 amendments); only the holder /
> counter-holder handles stay nulled and the Halter column stays dropped from the PDF. The
> Begründung + Notiz of each booking move out of their own columns into an **expandable per-booking
> sub-row** on both account-detail tables (REQ-BANK-017/-045).
>
> **Amendment (design-system layout):** the drill-in header drops the always-"Aktiv" status pill (the
> page only ever lists active accounts), the facts render as a four-up `kpi-total` grid (balance,
> target with progress bar — or a muted "no target" note —, ±30-day delta, booking count), and the
> responsible-holder/OL visibility controls are quiet per-audience toggles (a `chip--success`
> "granted" badge + a ghost "remove" when granted, an outline "grant" otherwise) rather than a wall of
> filled CTAs. The only filled CTA on the page stays "Ziel speichern" (and the statement modal's "PDF
>
>> herunterladen"). Endpoints, methods, optimistic-lock versions and the `orgUnitBankSettings` /
>> `orgUnitBankBookings` swap seams are unchanged.
>
> **Amendment (two-tab layout):** the drill-in body is split into **two tabs** — *Buchungshistorie*
> (the paginated, Halter-redacted history) and *Verantwortung & Sichtbarkeit* (the responsible
> holder's target/visibility/approval-limit settings). The shared **info tiles** (`kpi-total` grid:
> balance, target, ±30-day delta, booking count) sit **above** the tab row and stay visible on both
> tabs. The *Verantwortung & Sichtbarkeit* tab is shown **only to the account's responsible holder /
> manager** (the manage-capable caller, `settings != null`) — **not** to a plain viewer. A viewer who
> is not the responsible holder still sees the **read-only approval-limit display** (REQ-BANK-041
> requires limits be shown read-only to every viewer), but rendered as a plain inline section below
> the info tiles, never as the responsibility tab; with no settings tab the history renders untabbed
> with its own heading. The default-active tab is *Buchungshistorie*. The two swap seams are preserved
> exactly: the facts + settings stay inside the `orgUnitBankSettings` fragment (so a target change
> refreshes the tile) and the history stays its own `orgUnitBankBookings` swap (so paging it never
> re-fetches the settings, and a settings write never resets the history page). A small closure-based
> tab controller re-asserts the active tab on `krt:swapped` so an in-place settings write does not
> bounce the user back to the history tab.
>
> **Amendment (settings tiles):** within the *Verantwortung & Sichtbarkeit* tab the three settings
> areas — **Sichtbarkeit**, **Freigabe-Limits** and **Kontostandsziel** (in that order, the
> fill-to-target goal **last**) — each render as their **own `.hud-box` tile** inside a responsive
> `.oud-settings-grid` (`repeat(auto-fill, minmax(min(540px, 100%), 1fr))`). The 540px track floor is
> wide enough that each tile's widest row stays on **one line**, so the tiles **never cram their
> content**: when two no longer fit side by side they **overflow to the next row** instead — two
> abreast on desktop / ultra-wide (`main` caps at 1600px, so three would force a sub-540px,
> content-wrapping width) and one per row on narrower desktops, tablets and phones. `min(540px, 100%)`
> keeps the lone column from overflowing a phone viewport, and `auto-fill` (not `auto-fit`) keeps the
> trailing Kontostandsziel tile at a single track width rather than stretching it across the row. Each
> tile renders only when its capability is granted (`canSetTarget` / `canConfigureVisibility` +
> `visibilityConfigurable` / the approval-limit editor's `canEdit`), and the grid keeps the
> `org-unit-bank-settings` testid so the `orgUnitBankSettings` swap target and the tests still resolve
> the region.

**Enforced by:** `OrgUnitBankAccessServiceTest` (canView gate; bookings redaction; read-only caps), `BankStatementReportServiceTest` (redacted variant omits Halter; both audit `STATEMENT_EXPORTED`), `OrgUnitBankPageControllerMvcTest` (two tabs for a manager; untabbed for a plain viewer with no limits) · **Code:** `service/OrgUnitBankAccessService` (`getViewableAccountDetail` / `getViewableAccountBookings` / `exportViewableStatement`), `service/BankStatementReportService#generateStatement(..., redactHolders)`, `model/dto/OrgUnitBankAccountDetailDto`, `controller/OrgUnitBankController`, frontend `controller/OrgUnitBankPageController` + `OrgUnitBankProxyController`, `templates/org-unit-bank-account-detail.html` · **ADR:** [ADR-0043](../adr/0043-bank-account-responsibility-and-visibility.md) · **Issues:** #556

### REQ-BANK-039 — Booking-request eligibility = view eligibility

> **Amended by REQ-BANK-042:** the view-eligibility gate below governs **withdrawal and transfer**
> requests only. A **deposit** is exempt — any authenticated user may request a deposit against *any*
> active account (every type, even one they cannot view), with no `canView` / `isRequestCapable`
> gate.

Any caller who may **view** a request-capable account (REQ-BANK-035/-037, `canView`) may raise a
booking request against it — **amending** the prior own-level-oversight gate of REQ-BANK-022/-027.
The request-capable account types are `ORG_UNIT`, `AREA` and `CARTEL` (active only); `SPECIAL` /
`CARTEL_BANK` never receive requests. The widening is deliberate (owner decision) so a member the
responsible holder granted view to (a squadron sub-rank, an all-members grant, a named user) can also
file requests, gated downstream by the approval limits of REQ-BANK-041 rather than by who may request
at all. The bank stays org-unit-blind: eligibility is decided in the `OrgUnitBankAccessService` seam
(`canView`), never in a `Bank*` class.

**Enforced by:** `OrgUnitBankAccessServiceTest` (canView gate replaces own-level on create; a
view-granted member may request), `ArchitectureTest` (`bankClassesMustNotConsultOrgUnitScope`) ·
**Code:** `service/OrgUnitBankAccessService#createBookingRequest` / `#isRequestCapable` ·
**ADR:** [ADR-0045](../adr/0045-bank-user-transfers-and-per-account-approval-limits.md) · **Issues:** —

### REQ-BANK-040 — User-initiated transfer requests

A requester may raise a `TRANSFER` booking request from a (source) account they may view to **any
active account** as destination (`bank_booking_request.target_account_id`, V193). The destination is
deliberately *any active account* — including a Sonderkonto or the bank-operating account that the
request mechanism forbids as a **source** (`isRequestCapable` is checked on the source only); this
source/destination asymmetry is intentional (the requester routes money *into* an account, and the
move is still gated by the two confirming parties below). The request is off-ledger and audited like
a deposit/withdrawal request (REQ-BANK-022/-024); on confirmation the bank employee records the
**source and destination holders** and books a real `TRANSFER` through the existing
`BankLedgerService.bookTransfer` path (REQ-BANK-011) — the in-game transfer fee (REQ-BANK-033) and
account/holder legs apply unchanged. Confirming a transfer requires the bank `can_transfer`
capability on the **source** (REQ-BANK-009); it does **not** require the employee to hold a grant on
the destination. The direct-transfer destination-visibility gate (`canSee(destination)`,
REQ-BANK-011) is bypassed for request confirmations — the requester (an authorized source viewer)
already chose the destination, so requiring the *employee's* own destination grant would make a
transfer request to an account they cannot see permanently unconfirmable rather than redacted.

**Enforced by:** `BankBookingRequestServiceTest` (transfer confirm books via `bookTransfer`;
confirmable even when the employee cannot see the destination; capability gate on the source),
`OrgUnitBankAccessServiceTest` (transfer carries its destination) · **Code:**
`model/BankBookingRequestType#TRANSFER`,
`model/BankBookingRequest#targetAccount`, `service/BankBookingRequestService` (`create` / `confirm`),
`db/migration/V193`, frontend `templates/org-unit-bank.html` + `bank-requests.html` ·
**ADR:** [ADR-0045](../adr/0045-bank-user-transfers-and-per-account-approval-limits.md) · **Issues:** —

### REQ-BANK-041 — Per-account approval limits & two-step owner approval

> **Amended by REQ-BANK-042:** approval limits — and the two-step owner approval below — apply to
> **withdrawal and transfer** requests only (money *leaving* an account). A **deposit** is never
> subject to an approval limit: `requires_owner_approval` is always `false` and `applicable_limit`
> always `null` for a deposit, whatever the amount and whoever the requester.
>
> **Amended (owner-approved):** the **missing-limit** semantics are **inverted** on every account that
> supports approval limits (`ORG_UNIT` / `AREA` / `CARTEL`). When **no** limit applies to the requester
> — no per-user limit, no role-tier limit (e.g. KL / `KOMMANDOLEITER`) and no all-members limit — a
> withdrawal/transfer request now **always** requires the responsible holder's approval
> (`requires_owner_approval = true`, `applicable_limit = null`); a **configured** limit still only
> triggers approval **above** its ceiling. Approval is thus the **safe default**: a requester acts
> approval-free only **within a limit explicitly granted** to them (directly or via a role). The
> snapshot computation is `requires_owner_approval = (applicable_limit == null) || amount >
> applicable_limit`. (`CARTEL_BANK` and `SPECIAL` stay non-request-capable, so this never reaches
> them.)
>
> **Amended (owner-approved, ADR-0123) — the responsible holder is exempt.** Approval limits bind
> every requester **except the account's responsible holder** (REQ-BANK-034): the Staffelleiter /
> SK-Leiter of an `ORG_UNIT` account, the Bereichsleiter of an `AREA` account and any OL member on the
> KRT (`CARTEL`) account dispose **freely** over the account they are responsible for. For them
> `requires_owner_approval` is always `false`, `applicable_limit` always `null` and
> `required_approver` always `null` — for **any** amount, whatever limits are configured, and
> **bypassing the KRT amount ladder** (REQ-BANK-047) as well. The exemption is evaluated **before**
> any limit or ladder resolution, so a holder's request never triggers a limit lookup. Rationale: a
> limit is an instrument the holder wields **against third parties**, not against themselves; making
> them counter-sign their own request was a no-op click that only produced noise. The snapshot
> computation therefore reads `requires_owner_approval = !isResponsibleHolder && ((applicable_limit ==
> null) || amount > applicable_limit)`.
>
> **This removes only the *second* signature, not the booking.** The request is still filed `PENDING`
> and still has to be confirmed and booked by a bank employee / the Bankleitung (REQ-BANK-023) — that
> is the step that moves the money and mirrors the in-game booking. What falls away is exclusively the
> two-step owner approval below: the holder's in-app "Freigabe erteilen" and the bank employee's
> mandatory *"Freigabe durch Kontoverantwortlichen erfolgt"* checkbox.
>
> **Amended by REQ-BANK-047/-048 (owner-approved, ADR-0066):** two refinements. (1) **"Alle Mitglieder"
> = the owning org unit, for limits too.** The `ALL_MEMBERS` limit tier now applies **only to an actual
> member of the account's owning org unit** (`currentUserIsMemberOfOrgUnit(owner)`) — the former
> catch-all-for-every-eligible-requester is retired: an outsider holding only an individual view grant
> matches no membership tier and falls through to *approval required* unless they hold their own `USER`
> limit. The new `AREA_MEMBERS` cascade tier (REQ-BANK-048) participates in the same most-permissive
> (maximum) resolution when the caller is anywhere in the Bereich cascade. (2) **The KRT account no
> longer uses per-audience limits at all** — it uses the amount-tiered 3-stage ladder (REQ-BANK-047);
> `configurable(CARTEL)` stays `true` for request-capability, but the per-audience limit editor is
> hidden for it (`audienceLimitsSupported = ORG_UNIT/AREA`), and its display "limit" is the
> bank-employee ceiling `T1`.

Each request-capable account may carry **per-tier approval limits** (`bank_account_approval_limit`,
V193): a whole-aUEC ceiling (>= 0) up to which a tier may request **without** the responsible holder's
explicit approval. The tiers mirror the configurable visibility buckets of REQ-BANK-035 (squadron /
Bereich sub-ranks, all-members, individual users). A **missing** limit for a requester's tier means
the request **always needs the responsible holder's approval** (amended above — approval is the safe
default; the pre-amendment "missing = unlimited" behaviour is retired). The limit a requester is
subject to is resolved **at request creation** in the seam: **the account's responsible holder is
exempt outright** (amended above — no limit binds them, no ladder, no approver); otherwise an
individual-user limit wins; otherwise
the maximum of the limits for the role tiers they hold; otherwise the all-members limit; otherwise
**none applies and approval is required**. The
**all-members tier is the catch-all ceiling for every eligible requester** who matches no more
specific tier — *not only* org-unit members: because request eligibility = view eligibility
(REQ-BANK-039), it applies equally to an outsider holding only a per-user view grant and to any KRT
member raising a request against the cartel account. (Resolution mirrors the visibility model's
four-kind grantee set; the `GLOBAL_ROLE` tier is reserved for parity and never produced for limits,
since it is the Sonderkonto role bucket and Sonderkonten are non-request-capable — see
`BankApprovalLimitService`.) The result is **snapshotted** onto the request
(`requires_owner_approval`, `applicable_limit`) so the org-unit-blind confirm path only reads the
boolean.

**Who configures limits — and where:** the account's responsible holder (REQ-BANK-034), **bank
management** and **admin** — never a plain bank employee. Configuration happens **exclusively on the
org-unit bank settings surface** (the *Verantwortung & Sichtbarkeit* tab of the org-unit
account-detail page, alongside the visibility settings). The **bank-staff account-detail page**
(`/bank/accounts/{id}`) shows limits **read-only to every viewer**, including management/admin — it
never offers the editor: the backend assembles that surface's limits with `canEdit = false`
unconditionally (`BankAccountService#getAccountDetail`). Limits are thus shown read-only in both
account-detail surfaces to everyone who may open them, and set/cleared only in the org-unit bank.
Setting/clearing a limit is audited (`APPROVAL_LIMIT_SET` / `APPROVAL_LIMIT_CLEARED`).

**Two-step approval.** When a request exceeds the requester's limit — **or when no limit applies to
the requester at all** (amended) — it is flagged `requires_owner_approval`; the requester sees a live
warning that it must be approved first. **A request by the account's responsible holder is never
flagged** (amended, ADR-0123), so neither step below runs for them and the request modal shows no
warning. (1) The
responsible holder may **grant approval in-app** from the new "Fremde Anträge" tab — the requests
raised against the accounts they are responsible for — recorded as `owner_approval_granted` (with
who/when) and audited (`BOOKING_REQUEST_OWNER_APPROVAL_GRANTED` / `…_REVOKED`). (2) The bank employee
sees an explicit warning in the confirmation dialog and must tick a mandatory
**"Freigabe durch Kontoverantwortlichen erfolgt"** checkbox (pre-filled when (1) happened) before the
request can be confirmed; ticking it is audited (`BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED`). A
flagged request cannot be confirmed without the checkbox (`BANK_OWNER_APPROVAL_REQUIRED`, 409).

**Queue markers (`/bank/requests`).** A flagged request carries a second chip next to its status: a
success chip **"Freigegeben"** once the holder granted approval in-app, otherwise the status-neutral
**"Über Limit"** marker — warning-toned while the request is `PENDING`, muted once it is decided. The
marker is deliberately *not* a "Freigabe nötig" call to action: a confirmed request whose approval
came from the employee checkbox alone (step (2) without step (1)) keeps `owner_approval_granted =
false` forever, so an action-worded chip would still demand an approval that is long settled. The
queue's last column is headed **"Entscheidung"** rather than "Aktionen" for the same reason — it holds
the confirm/reject buttons only while the request is `PENDING` and otherwise names the decider.

**The approver sees the Begründung too ("Fremde Anträge").** Each row of the approval tab carries the
same **expandable Begründung + Notiz sub-row** as the bank-staff queue (REQ-BANK-023/-045): a leading
chevron on rows that have either field, revealing an indented detail row with Begründung first. This
is not cosmetic parity — the approver is the one *deciding*, and on a `CARTEL` / `CARTEL_BANK` /
`SPECIAL` account the Begründung is the **mandatory** reason for the outflow (REQ-BANK-045), so
approving without it visible would mean signing off blind. It reuses the document-delegated
`bank-row-expand` handler and the account-detail chevron pattern; no new endpoint, JS module or i18n
key. The detail id is prefixed `ou-foreign-req-` because a responsible holder who raised the request
themselves sees that request in **both** tables of `/org-unit-bank` ("Meine Anträge" and "Fremde
Anträge"), and a shared `bank-req-<id>` would let one row's chevron toggle the other's sub-row.

The two approval acts (holder in-app grant, employee checkbox) are on **different surfaces seen by
different users**, so they are outside the same-surface peer-sync scope (REQ-FE-010 live multi-user
is Mission-only). An out-of-band grant/revoke bumps the request's `@Version`; an already-open
bank-staff queue still carrying the pre-grant version 409s with `OPTIMISTIC_LOCK` on the next
confirm and recovers on reload — this cross-user 409→reload is the **accepted** behaviour per the
project concurrency rules, not a defect.

**Enforced by:** `OrgUnitBankAccessServiceTest` (limit resolution incl. the all-members ceiling
applying to a non-member per-user view-grant holder; `canConfigureApprovalLimits` matrix —
holder/management/admin yes, employee/member no; set/clear audit; grant/revoke owner approval, incl.
a non-responsible-holder being rejected; **the no-limit case always flags approval**,
`createBookingRequest_withdrawalNoLimit_alwaysFlagsRequiresOwnerApproval`; **the responsible holder
is exempt** — `createBookingRequest_byResponsibleHolder_needsNoApproval` (no limit lookup happens at
all), `createBookingRequest_byResponsibleHolderAboveConfiguredLimit_stillNeedsNoApproval` and
`createBookingRequest_krtByOlMember_bypassesAmountLadder`),
`BankBookingRequestServiceTest` (snapshot at create;
confirm gate 409 + audit; pre-fill is UI-only), `OrgUnitBankControllerTest`,
`BankAccountServiceTest` (the bank-staff detail surface assembles limits read-only — `canEdit`
forced `false` even for management,
`getAccountDetail_assemblesApprovalLimitsReadOnlyEvenForManagement`) · **Code:**
`model/BankAccountApprovalLimit`,
`repository/BankAccountApprovalLimitRepository`, `service/BankApprovalLimitService`,
`service/OrgUnitBankAccessService`, `service/BankBookingRequestService`, `db/migration/V193`, frontend
`templates/fragments/bank-approval-limits.html` + `org-unit-bank.html` + `bank-requests.html` +
`static/js/bank.js` (the `data-exempt` short-circuit in the live warning) ·
**ADR:** [ADR-0045](../adr/0045-bank-user-transfers-and-per-account-approval-limits.md),
[ADR-0123](../adr/0123-responsible-holder-exempt-from-approval-limits.md) · **Issues:** —

### REQ-BANK-042 — Unrestricted deposit requests (any user, any active account, no limit)

> **Amended by REQ-BANK-043:** a deposit request (like a direct deposit) may additionally carry a
> split that distributes a percentage of the gross across all active squadron accounts. The
> percentage is snapshotted on the off-ledger request and resolved into concrete legs at
> confirmation; it never makes a deposit approval-limited. Withdrawals/transfers never carry a split.

A **deposit** booking request is the one movement kind a requester cannot abuse — it only *adds*
money to an account, and nothing moves until a bank employee confirms receipt in-game
(REQ-BANK-023). It is therefore deliberately unrestricted (owner decision), **amending**
REQ-BANK-039 and REQ-BANK-041 for deposits only:

- **Any authenticated user** may raise a deposit request against **any `ACTIVE` account** — every
  type, including `SPECIAL` and `CARTEL_BANK`, and accounts the requester may not even view. The
  `canView` view-eligibility gate (REQ-BANK-039) and the request-capable-type restriction
  (`isRequestCapable`) do **not** apply to deposits; only the account-active guard
  (`BANK_ACCOUNT_CLOSED`) remains, and a deposit must carry no destination account.
- **No approval limit applies.** A deposit is never flagged `requires_owner_approval` and carries no
  `applicable_limit`, whatever the amount and whoever the requester — the per-tier limits of
  REQ-BANK-041 govern only money *leaving* an account (withdrawals/transfers). The limit rows are
  not even consulted for a deposit.

Withdrawals and transfers are **unchanged**: still gated by `canView` + `isRequestCapable`
(REQ-BANK-039), restricted to `ORG_UNIT` / `AREA` / `CARTEL`, and subject to the per-tier approval
limits (REQ-BANK-041). The carve-out lives entirely in the `OrgUnitBankAccessService` seam, so the
bank stays org-unit-blind (REQ-BANK-008): the deposit branch of `createBookingRequest` consults
neither `OwnerScopeService` nor the limit rows. The org-unit bank page reflects this — the request
CTA + modal are shown whenever any active account exists, the (merged) source picker lists every
active account for a deposit but narrows to the caller's request-capable accounts for a
withdrawal/transfer, and no approval-limit warning is shown for a deposit.

**Acceptance**

- [x] A deposit request against an account the caller cannot view — including a `SPECIAL` /
  `CARTEL_BANK` account — succeeds (`PENDING`, audited `BOOKING_REQUEST_CREATED`), while a
  withdrawal/transfer on the same account is still rejected (`canView` / `isRequestCapable`).
- [x] A deposit request is never flagged `requires_owner_approval` and resolves no
  `applicable_limit`, regardless of amount or configured limits; the limit rows are not consulted.
- [x] A deposit request against a `CLOSED` account is rejected `BANK_ACCOUNT_CLOSED`; a deposit must
  carry no destination account.
- [x] The org-unit bank page shows the request CTA whenever an active account exists; the deposit
  source picker offers every active account and shows no approval-limit warning, while the
  withdrawal/transfer source picker stays limited to the caller's request-capable accounts.

**Enforced by:** `OrgUnitBankAccessServiceTest` (deposit on unviewable / `SPECIAL` account succeeds
without consulting oversight scope or the limit rows; deposit never flags approval; destination
rejected), `OrgUnitBankPageControllerMvcTest` (deposit picker lists all active accounts; CTA shown),
`ArchitectureTest` (`bankClassesMustNotConsultOrgUnitScope`), e2e `BankOrgUnitRequestsE2eTest` ·
**Code:** `service/OrgUnitBankAccessService#createBookingRequest`, `controller/OrgUnitBankController`,
frontend `controller/OrgUnitBankPageController`, `templates/org-unit-bank.html`, `static/js/bank.js` ·
**ADR:** [ADR-0045](../adr/0045-bank-user-transfers-and-per-account-approval-limits.md) (amendment) ·
**Issues:** —

### REQ-BANK-043 — Split deposit across squadron accounts

A deposit — both the direct bank-staff booking (REQ-BANK-004) and the confirm-before-post deposit
request (REQ-BANK-042) — may optionally **distribute a percentage of the gross evenly across all
active squadron accounts**, crediting the named account only the remainder (owner request). The
option is a single checkbox plus one whole-percent `P` (1–100); it is **deposit-only** (money
entering the bank) and never applies to a withdrawal or transfer.

- **Squadron accounts** = `ORG_UNIT` accounts whose owning org unit is a `SQUADRON`
  (`OrgUnitKind.SQUADRON`), `ACTIVE` only. SK / AREA / CARTEL / CARTEL_BANK / SPECIAL accounts are
  never split targets. The **named account is excluded** from the distribution set (even when it is
  itself a squadron account): it only ever receives the remainder. The enumeration reads the
  `org_unit.kind` via `OrgUnit#getKind()` (an owner *label*, not a scope), so the bank stays
  org-unit-blind (REQ-BANK-008) — no `OwnerScopeService`, both ArchUnit pins green.
- **Math (whole-aUEC, REQ-BANK-005).** `slice = round(gross × P / 100)` (HALF_UP, whole). The slice
  is split across the `N` squadron accounts with the **largest-remainder** rule: `base = floor(slice
  / N)`, and the leftover `slice − base·N` aUEC go one each to the first accounts by ascending id, so
  the per-account amounts are as even as possible, stay whole and sum to the slice exactly. The named
  account is credited `gross − slice`. Every account leg plus the named leg sums to the gross.
  Zero-amount legs are dropped (a 100 % split books no named leg; a slice smaller than `N` credits
  only the first `slice` accounts).
- **Ledger shape (amends REQ-BANK-004).** A split deposit is **one** `DEPOSIT` transaction with
  **one positive account leg per credited account** (the named remainder + each squadron share) and
  a **single** positive holder leg over the whole gross — the money physically landed once with one
  custodian (REQ-BANK-003); the split is a pure account-side allocation. Deposits are fee-free
  (REQ-BANK-033), so no fee applies. All affected accounts are pessimistically locked in ascending id
  order (the global lock order, REQ-BANK-006/-011/-013), so the fan-out cannot deadlock.
- **Authorization (unchanged).** A direct split deposit needs `BANK_EMPLOYEE` + `can_deposit` on the
  **named** account (REQ-BANK-009) — crediting the squadron accounts needs no further grant, because
  a deposit only adds money (the same rationale as the unrestricted deposit *request* of
  REQ-BANK-042). The request variant follows REQ-BANK-042 (any authenticated user, any active
  account, no approval limit; the split never makes a deposit approval-limited).
- **Request variant (amends REQ-BANK-042).** A deposit request snapshots `split_enabled` +
  `split_percent` on the off-ledger row (V196); the concrete per-squadron legs are (re)computed at
  **confirmation** against the squadron accounts active **then**, exactly like the approval-limit
  snapshot of REQ-BANK-041. The bank employee sees the split in the confirm modal before booking.
- **Failures (409).** `BANK_SPLIT_NO_TARGETS` when no active squadron account remains to distribute
  to (none exists, or the only one is the named account); `BANK_SPLIT_TOO_SMALL` when the slice
  rounds below 1 aUEC.
- **Audit (REQ-BANK-012, REQ-AUDIT-001).** One summarizing `DEPOSIT_SPLIT_BOOKED` event per split
  transaction — the `WIPE_RESET_EXECUTED` precedent (one event for a multi-account transaction, not
  one per leg); the PII-free details carry the gross, holder handle, percentage and target count
  (the holder handle is the same datum the plain `DEPOSIT_BOOKED` detail already records).
- **Integrity (amends REQ-BANK-020).** A split `DEPOSIT` carries `N` positive account legs summing
  to the gross against one holder leg of the gross; it is a `DEPOSIT`, so the
  transfer/holder-transfer zero-/`−fee`-sum invariants do not apply, and a reversal mirrors all its
  legs generically (REQ-BANK-004). It still carries exactly one audit row, so the
  audit-row-per-transaction sweep is unaffected.
- **Frontend (REQ-FE-001…010).** The deposit modal (bank staff) and the deposit-request modal
  (org-unit page) gain the checkbox + percentage + a live "slice / remainder" preview; the bank-staff
  confirm modal shows the split before booking. The split is submitted through the existing
  `krtFetch` deposit/request writes; the pages where these actions happen do not display the other
  squadron accounts' balances, so the standard in-place fragment swap covers every derived UI on the
  acting page (a separately-open dashboard refreshes on its own next load — cross-page peer sync is
  Mission-only, REQ-FE-010).

**Acceptance**

- [x] A split deposit books one `DEPOSIT` transaction crediting `gross − slice` to the named account
  and the slice evenly across the active squadron accounts (largest-remainder), with one holder leg
  over the gross; the legs sum to the gross.
- [x] The named account is excluded from the distribution even when it is a squadron account; a
  100 % split books no named leg.
- [x] `BANK_SPLIT_NO_TARGETS` / `BANK_SPLIT_TOO_SMALL` are returned (409) for the no-target /
  rounds-to-zero cases.
- [x] A deposit request snapshots the percentage and resolves the legs at confirmation; a
  withdrawal/transfer request rejects a split (DTO + V196 CHECK).
- [x] One `DEPOSIT_SPLIT_BOOKED` audit row per split transaction; it appears in the admin viewer's
  Bank event-type filter with DE/EN labels.

**Enforced by:** `BankLedgerSplitDepositTest` (split distribution, largest-remainder, exclude-named,
100 %, no-targets, too-small, single holder leg), `BankBookingRequestServiceTest` (split request
snapshot + confirm books a split), `OrgUnitBankAccessServiceTest` (deposit request carries the split)
· **Code:** `service/BankLedgerService#bookSplitDeposit`,
`model/dto/request/BankDepositRequest`, `model/dto/request/CreateBankBookingRequest`,
`model/BankBookingRequest`, `repository/BankAccountRepository#findByTypeAndStatusOrderById`,
`model/BankAuditEventType#DEPOSIT_SPLIT_BOOKED`, `db/migration/V196`, frontend
`templates/bank-account-detail.html` / `org-unit-bank.html` / `bank-requests.html`,
`static/js/bank.js` · **Issues:** —

### REQ-BANK-044 — Deposit/withdrawal counterparty (Einzahler / Empfänger + org unit)

A deposit and a withdrawal each record only the **holder** — the bank custodian who physically
received the money in (deposit) or paid it out (withdrawal). They do **not** record the *external
party* on the far side: who handed the money in (the **Einzahler**) or who received the payout (the
**Empfänger**), and which org unit they belong to. This requirement adds an optional
**counterparty** to deposits and withdrawals so the account history, the Kontoauszug PDF and the
admin audit log answer "von wem / an wen" the payment went. The counterparty is a distinct dimension
from the holder (a custodian receives a member's deposit; the depositing member is the counterparty)
and is captured on the **transaction header** (V197: `counterparty_user_id` FK `app_user`
`ON DELETE SET NULL`, plus deletion-proof `counterparty_handle` / `counterparty_org_unit_name`
snapshots and a `counterparty_org_unit_id` FK `org_unit`), set once at insert — the append-only
ledger contract (REQ-BANK-004) is unaffected. A **split** deposit (REQ-BANK-043) records the
counterparty on its single header too.

Design (owner-confirmed): the counterparty is a **tool user** (no free-text), selected from the
shared `GET /api/v1/users/lookup` picker; its **org unit is picked at booking** from the user's own
direct memberships across **all four kinds** (Staffel + SK + Bereich + OL —
`GET /api/v1/users/{id}/memberships?allKinds=true`), auto-preselected when the user has exactly one,
blank when none — membership is multi, so it must be chosen. Both fields are **optional**. The
backend validates the chosen org unit is one of the counterparty's memberships (else 400) and
snapshots its name via the `OrgUnitMembershipService.listDirectMembershipOptions` seam (kind-safe, no
polymorphic org-unit load). This stays org-unit-blind for **authorization** (REQ-BANK-008): the org
unit is used only to *record* a snapshot, never to gate a booking; `BankSecurityService` and every
bank gate are untouched. The `/lookup` and `/memberships` gates are widened to admit
`BANK_EMPLOYEE` (a bank employee need not hold any org-role) at both the URL and method layer.

Account↔account **transfers** are unchanged — they already record from/to account + from/to holder.
A single **"Gegenseite"** column unifies "the other side" on both PDFs (counterparty for
deposit/withdrawal, counter-account for a transfer), which also fixes the statement PDF's prior
omission of the transfer counter-account. The counterparty is **player-identifying**, so it is
**redacted** alongside the holder on the member-facing surfaces (REQ-BANK-038): the redacted
Kontoauszug omits the Gegenseite column and the org-unit read-only history nulls the counterparty.
Booking **requests** (REQ-BANK-023): a confirmed deposit/withdrawal records the **requester** as the
counterparty user (for a deposit request the requester *is* the depositor, REQ-BANK-042) together
with their **deterministic primary org unit** — the requester is not present to pick, so the primary
membership (name-sorted primary Staffel, or a leader's Bereich/OL) is recorded, null when they have
none. The audit detail names the counterparty handle + org-unit name (both system identifiers, not
user free text) and sets the structured `target_user_id` on `DEPOSIT_BOOKED` / `WITHDRAWAL_BOOKED`
(REQ-BANK-012) — no new event type.

> **Amended (2026-07-03, owner decision — see the [ADR-0054](../adr/0054-bank-transaction-counterparty.md)
> amendment):** the counterparty is **no longer redacted** on the member-facing org-unit surfaces —
> only the aUEC-custody **Halter** is (REQ-BANK-038). The org-unit read-only history keeps
> `counterpartyHandle`/`counterpartyOrgUnitName`, and the member-facing redacted Kontoauszug **keeps**
> the "other side" column (dropping only the Halter column). That unified column is **renamed**
> "Gegenseite"/"Gegenpartei" → **"Quell-/Zielkonto"** on both account-detail tables and in both PDFs
> (the `pdf.bank.col.counterparty` label becomes `QUELL-/ZIELKONTO`); on the tables it shows a
> transfer's counter-account with a direction arrow (→ to / ← from) or the deposit/withdrawal
> counterparty, unified exactly as the PDFs already do. The **Begründung + Notiz** move out of their own
> columns into an **expandable per-booking sub-row** (REQ-BANK-017/-045), reason first.
>
> **Amended (2026-07-05, #994, owner-approved — supersedes the "tool user, no free-text" clause of the
> [ADR-0054](../adr/0054-bank-transaction-counterparty.md) amendment; closes spec Open question #5):**
> a deposit/withdrawal counterparty may now also be an **external party without a basetool account**,
> recorded as a **free-text name**. The Kontobewegung modal's counterparty block gains a "kein
>
>> Tool-Account" toggle: off (default) is the unchanged registered-user path; on swaps the
>> `/users/lookup` picker for a free-text **`counterpartyExternalName`** input and **widens the unit
>> picklist to every active org unit** (`GET /api/v1/org-units/active-all-kinds`, all four kinds) with
>> the **membership check skipped** — there is no linked user. Server-side, `counterpartyUserId` and
>> `counterpartyExternalName` are **mutually exclusive** (both → 400); an external counterparty stores
>> the free-text name in the **`counterparty_handle` snapshot with `counterparty_user_id` NULL**, and
>> its org unit — any active one — in the existing `counterparty_org_unit_id` + name snapshot (resolved
>> via `OrgUnitMembershipService.listAllActiveOrgUnitOptionsAllKinds`, still org-unit-blind for
>> authorization). The snapshot columns are reused (no new column); **V205 relaxes the V197 check
>> constraint** so the *handle* is the presence marker (registered handle **or** external name) and the
>> user FK is optional (previously the constraint tied the handle 1:1 to the user id).
>> The audit detail records the external name + unit as **snapshot labels only** and leaves
>> `target_user_id` **null** (REQ-BANK-012 — a counterparty label is a system snapshot, not the
>> free-text note/justification the details-payload rule forbids). Only a **Bank Employee** records it
>> (the staff who already book deposits/withdrawals); external **Halter** stay out of scope. New
>> `bank.field.counterparty.external*` i18n keys (DE/EN); `openapi.json` regenerated
>> (`counterpartyExternalName` on the deposit/withdrawal request DTOs).

**Acceptance**

- [x] A deposit/withdrawal naming a counterparty stamps `counterparty_user_id` + handle snapshot on
  the header; a chosen org unit is validated to be one of that user's memberships (else 400) and its
  name snapshotted; transfers/holder-transfers/reversal/wipe leave every counterparty column null.
- [x] The `DEPOSIT_BOOKED` / `WITHDRAWAL_BOOKED` audit row carries the counterparty as
  `target_user_id` and names the handle + org unit in its detail; a booking without a counterparty
  leaves both null.
- [x] The account-detail history and both PDFs show the counterparty in the **Quell-/Zielkonto**
  column; it is shown to org-unit members too — only the aUEC-custody Halter is hidden from them
  (amended: previously the counterparty was redacted like the holder).
- [x] A confirmed deposit/withdrawal **request** records the requester as the counterparty user plus
  their deterministic primary org unit (null when the requester has no membership).
- [x](#994) An **external** counterparty is booked as a free-text `counterpartyExternalName` +
  **any** active org unit (membership check skipped), snapshotting the name with
  `counterparty_user_id` NULL and a null audit `target_user_id`; supplying both a registered user and
  an external name is a 400; the registered-user path (incl. the membership 400) is unchanged.

**Enforced by:** `BankLedgerServiceTest` (header snapshot, org-unit-membership validation, external
free-text name + any-org-unit + null user/target + both-set 400, audit
target + detail, transfers leave it null), `BankReportServiceTest` (Gegenseite column present in the
full statement, redacted out), `OrgUnitBankAccessServiceTest` (counterparty redacted for org-unit
viewers), `OrgUnitMembershipServiceTest` (`listDirectMembershipOptions` spans all four kinds; primary
resolution), `BankBookingRequestServiceTest` (confirmed request records the requester + their primary
org unit), `UserControllerTest`/`UserMembershipsSecurityTest` (`allKinds` delegation; `BANK_EMPLOYEE`
reaches `/memberships`) · **Code:**
`model/BankTransaction`, `model/dto/request/Bank{Deposit,Withdrawal}Request`, `service/BankLedgerService`
(`resolveCounterparty`), `service/BankBookingRequestService`,
`service/OrgUnitMembershipService#listDirectMembershipOptions`, `model/projection/BankBookingRow`,
`repository/BankPostingRepository`, `model/dto/BankBookingDto`, `service/Bank{Statement,Management}ReportService`,
`service/OrgUnitBankAccessService#redact`, `controller/UserController`, `config/SecurityConfig`,
`db/migration/V197`, `db/migration/V205` (#994 constraint relax),
`service/OrgUnitMembershipService#listAllActiveOrgUnitOptionsAllKinds` (#994),
frontend `controller/BankPageController`, `controller/BankRequestQueuePageController`,
`controller/UserProxyController`, `templates/fragments/bank-counterparty.html` (#994),
`templates/fragments/bank-movement-modal.html`, `templates/bank-account-detail.html`,
`static/js/bank.js` · **ADR:** [ADR-0054](../adr/0054-bank-transaction-counterparty.md) (amended by

# 994) · **Issues:** #994

### REQ-BANK-045 — Conditional Begründung (justification) on withdrawals & transfers

A **withdrawal** or **account↔account transfer** — both the direct bank-staff booking and the
confirm-before-post **request** — carries an optional free-text **Begründung** (`justification`,
`VARCHAR(500)`, V198), a sibling of the existing `note` captured **only** for these two debit kinds (a
**deposit never** captures one — it is neither shown nor stored, REQ-BANK-042). The field is
**required** (service-enforced, not a DB CHECK — the rule depends on the source account's *type*) when
the debited account is one of the cartel-wide accounts whose outflow needs an explicit reason — the
**KRT** account (`CARTEL`), the **bank's own** account (`CARTEL_BANK`) and every **Sonderkonto**
(`SPECIAL`) — and **optional** for `ORG_UNIT` (Staffel/SK) and `AREA` (Bereich) accounts (where the
note is likewise optional). The predicate is `BankAccountType.requiresDebitJustification()`; a blank
justification on a mandating account is rejected with **409 `BANK_JUSTIFICATION_REQUIRED`** at every
debit entry point (`BankLedgerService.bookWithdrawal` / `bookTransfer` and the request
`BankBookingRequestService.create`), surfaced inline at the `justification` field (frontend
`CODE_FIELD`).

The justification is **persisted** on the append-only ledger header (`bank_transaction.justification`)
and the off-ledger request (`bank_booking_request.justification`), **carried request → confirmation**
onto the booking, and **displayed everywhere the note is** — the account-detail booking history, the
account-statement PDF and the management-export PDF (a dedicated column carved out of the wide note
column so no other column narrows). It is **not** player-identifying, so — unlike the holder and the
counterparty — it is **kept** on the member-facing redacted Kontoauszug and the org-unit read-only
history (REQ-BANK-038), exactly like the note. Per REQ-BANK-012 it is **never** written into the audit
`details` payload (no user free text / no PII in audit details); it lives only as business data on the
transaction/request. Adding it introduces **no** new `AuditEventType` — it is a field on existing
movements (`WITHDRAWAL_BOOKED` / `TRANSFER_BOOKED` / `BOOKING_REQUEST_CREATED` / `…_CONFIRMED`), not a
new audited activity.

**Acceptance**

- [x] A withdrawal/transfer (direct booking **and** request) leaving a `CARTEL` / `CARTEL_BANK` /
  `SPECIAL` account with a blank justification is rejected with `BANK_JUSTIFICATION_REQUIRED`; the same
  from an `ORG_UNIT` / `AREA` account succeeds with a null justification.
- [x] A non-blank justification is persisted on the ledger transaction and the request, carried onto
  the booking at confirmation, and rendered in the booking history and both PDFs.
- [x] A deposit never captures, stores or shows a justification.
- [x] The justification survives the member-facing redaction (it is not player-identifying) and is
  never placed in the audit `details` payload.

**Enforced by:** `BankLedgerServiceTest` (direct withdrawal from a mandating account rejected when
blank, persisted when present), `BankBookingRequestServiceTest` (request rejected/optional/persisted by
source-account type), `OrgUnitBankAccessServiceTest` (justification kept through redaction),
`BankReportServiceTest` (statement renders it), frontend `BankAccountDetailFragmentMvcTest` (history
column) · **Code:** `model/BankAccountType#requiresDebitJustification`, `model/BankTransaction`,
`model/BankBookingRequest`, `model/dto/request/Bank{Withdrawal,Transfer}Request`,
`model/dto/request/CreateBankBookingRequest`, `service/BankLedgerService`,
`service/BankBookingRequestService`, `service/OrgUnitBankAccessService`,
`service/Bank{Statement,Management}ReportService`, `model/projection/BankBookingRow`,
`repository/BankPostingRepository`, `model/dto/Bank{Booking,BookingRequest}Dto`,
`exception/BankConflictException`, `db/migration/V198`, frontend `templates/org-unit-bank.html`,
`templates/bank-account-detail.html`, `templates/bank-requests.html`, `static/js/bank.js`,
`static/css/bank.css` · **Issues:** —

### REQ-BANK-046 — Client-side account-name filter (dashboard + org-unit list)

The bank **dashboard** (`/bank`, REQ-BANK-016) and the org-unit **account list** (`/org-unit-bank`
"Konten" tab, REQ-BANK-021) each carry a **client-side, live account-name filter**. A search box —
on the dashboard **beside the view-option checkboxes** in the header actions, on the org-unit page
**directly above the account list** — filters the rendered account tiles/rows **in place as the user
types**: an account whose **name** does not contain the entered term (case-insensitive substring) is
hidden, matching ones stay. Matching is on the account **name** only (each item carries a
`data-filter-name`); the canonical A→Z-by-name ordering (REQ-BANK-016) is untouched — the filter only
**hides**, it never reorders.

The filter is **purely visual and read-only**: it toggles the `hidden` attribute on the
already-rendered items with **no server round-trip, no `krtFetch`, no page reload and no CSRF**. It
therefore satisfies the live-update standard (REQ-FE-001) **by construction** and records **no audit
event** — it mutates nothing, so the REQ-BANK-012 audited activities are unaffected. On the dashboard
the box is shown to **every** viewer who sees at least one card (**bank employees included**), so it
is **not** behind the `BANK_MANAGEMENT` gate that still guards the three-month report button (the
Verwaltung / Berechtigungen links now live in the sidebar, not the dashboard header, REQ-BANK-016).
On the org-unit page the box lives **inside** the swapped `orgUnitBank` fragment, so a
booking-request create/cancel swap re-renders it empty, resetting the filter to "show all". When the
filter hides **every** account, a localized "no accounts match the filter" note
(`bank.filter.empty`) is revealed — distinct from the server-rendered "no accounts at all" empty
state, which a genuinely empty list keeps.

**Acceptance**

- [x] Typing a term on the dashboard hides every `.kpi-card` whose name lacks it (case-insensitive)
  and keeps the matches; clearing the box restores all cards.
- [x] The same filter above the org-unit "Konten" list hides/keeps the account items (card or table,
  scoped to `#ou-acc-results`) identically and resets to "show all" after a request create/cancel
  fragment swap.
- [x] The filter performs **no** backend call (no `krtFetch`, no reload, no CSRF) and adds **no**
  audit event.
- [x] Filtering every account down to none reveals the localized `bank.filter.empty` note; a
  genuinely empty account list keeps its own empty state instead.
- [x] The dashboard filter renders for bank employees (not gated behind `BANK_MANAGEMENT`), while the
  three-month report button stays management-only (Verwaltung / Berechtigungen live in the sidebar,
  REQ-BANK-016).

**Enforced by:** `BankPageControllerTest` (dashboard renders the filter box + `data-filter-name` +
filter-empty note, employee perspective included), `OrgUnitBankPageControllerMvcTest` (Konten list
renders the filter box + `data-filter-name` + filter-empty note) · **Code:** frontend
`templates/bank-dashboard.html`, `templates/org-unit-bank.html`, `static/js/bank.js`
(`applyAccountNameFilter`), `static/css/bank.css`, `messages{,_de,_en}.properties` (`bank.filter.*`) ·
**Issues:** —

### REQ-BANK-047 — KRT-account amount-tiered 3-stage approval ladder

> **Amended by ADR-0109 (owner decision):** two corrections to the original ADR-0066 shape. (1) The
> **middle band** (`T1 < amount ≤ T2`) routes to the **Bankleitung** (`BANK_MANAGEMENT`), **not** the
> Bereichsleiter Profit — the approver enum value is `BANK_MANAGEMENT` (renamed from `AREA_LEAD_PROFIT`,
> V222). (2) A plain employee's over-`T1` **direct** booking is **no longer refused** with `409
> BANK_CARTEL_APPROVAL_REQUIRED`; it is **auto-filed** as the band-routed `PENDING` request and the
> endpoint answers **`202`** with a `BankBookingOutcomeDto.pendingRequest`, so the employee is told to
> notify the Bankleitung rather than hitting a dead end. The band-routing text below is stated in its
> amended form.
>
> **Amended by ADR-0123 (owner decision) — the OL is exempt from the ladder.** The KRT account's
> responsible holder is *any OL member* (REQ-BANK-034), and per the REQ-BANK-041 holder exemption a
> responsible holder is bound by no approval gate on their own account. An **OL member's** request
> against the KRT account therefore **bypasses the ladder entirely** (`requires_owner_approval =
> false`, `applicable_limit = null`, `required_approver = null`), whatever the amount. The ladder is
> unchanged for **every other requester** — any KRT member may raise a request against the KRT account
> (`canView(CARTEL) = isMemberOrAbove()`), and for them the three bands below still route as stated.
> The `exceedsCartelDirectBookingCeiling` gate on a bank employee's **direct** booking is a separate
> ledger-side control and is **not** affected — it still routes an over-`T1` direct booking into a
> request; only the resulting request's approver is dropped when that employee is themselves an OL
> member.

The **KRT account** (`CARTEL`, **not** the bank's own `CARTEL_BANK`) uses an **amount-tiered approval
ladder** for money *leaving* it (a withdrawal or account↔account transfer request, and the direct
bank-staff booking) that **replaces** the per-audience approval limits of REQ-BANK-041 on this account
(owner decision, ADR-0066). Two thresholds `T1 ≤ T2` on the account row (`bank_account.
employee_approval_ceiling` / `area_lead_approval_ceiling`, V203, whole aUEC ≥ 0, shared row `@Version`
with rename/close/target) define three bands and their **approver class** (`BankRequestApprover`,
snapshotted per request as `bank_booking_request.required_approver`):

- `amount ≤ T1` → the **bank employee** self-approves (no external approval; `requires_owner_approval =
  false`).
- `T1 < amount ≤ T2` → the **Bankleitung** (any holder of `ROLE_BANK_MANAGEMENT`) must approve
  (`BANK_MANAGEMENT`).
- `amount > T2` → the **Organisationsleitung** (any `OL_MEMBER`) must approve (`ORGANISATIONSLEITUNG`).

An unset `T1` is treated as `0` (an employee self-approves nothing); an unset `T2` as `+∞` (the
Bankleitung covers everything above `T1`, the OL band stays empty). **Deposits are exempt**
(REQ-BANK-042 unchanged).

**Who configures — and where:** **only the Bankleitung** (`ROLE_BANK_MANAGEMENT`; admins via the
hierarchy) sets `T1`/`T2`, **exclusively** in a **new Verwaltung tab** (`/bank/manage?tab=krt-freigaben`,
rendered only for a management caller) via `PATCH /api/v1/bank/accounts/{id}/approval-tiers`
(`hasRole('BANK_MANAGEMENT')`) — audited `CARTEL_APPROVAL_TIERS_SET` / `…_CLEARED` (amounts only, no
PII). The threshold storage/edit is org-unit-blind (`BankAccountService`, two account columns); the
amount→approver **resolution** and the band→identity mapping stay in the `OrgUnitBankAccessService`
seam so the bank stays org-unit-blind (REQ-BANK-008, both ArchUnit pins green). The KRT ladder is shown
**read-only to every viewer** on the org-unit account-detail page (the bands + who approves, from
`employeeApprovalCeiling`/`areaLeadApprovalCeiling`).

**Approval surface & confirmation.** The two non-staff approvers act in **Org-Einheits-Bank → „Fremde
Anträge"**, band-routed: the Bankleitung sees only the `BANK_MANAGEMENT`-band requests, the OL only the
`ORGANISATIONSLEITUNG`-band ones (an admin sees **and approves all three bands** — `canApprove` /
`canSeeForeignRequest` short-circuit on `isAdmin()` before the band check); their in-app grant records
`owner_approval_granted`
and reuses the existing two-step machinery (`BOOKING_REQUEST_OWNER_APPROVAL_GRANTED/REVOKED`). A bank
employee then confirms with the mandatory `BANK_OWNER_APPROVAL_REQUIRED` checkbox exactly as
REQ-BANK-041. A `≤ T1` request needs no external approval — the employee confirms directly. Because
`BANK_MANAGEMENT > BANK_EMPLOYEE` in the role hierarchy, the Bankleitung both grants the approval **and**
sees the request in the bank-staff queue (`/bank/requests`) where they confirm and book it. **Direct
booking → auto-request (ADR-0109):** a plain bank employee's **direct** withdrawal/transfer from the KRT
account above `T1` is **not booked**; it is filed as the band-routed `PENDING` request (Bankleitung /
Organisationsleitung) and the endpoint answers **`202`** with a `BankBookingOutcomeDto.pendingRequest`
(the frontend then shows a „Antrag angelegt — der Bankleitung Bescheid geben" notice). Bank
management/admins book directly unrestricted, and the request-confirmation path (already approved) is
**not** capped. The holder and any Empfänger from the direct-booking form are not carried onto the
request — like every booking request they are (re-)recorded by the confirming bank employee
(REQ-BANK-040); only the amount, note and justification transfer.

**Notifications.** The responsible-holder notification (REQ-BANK-026) for a KRT request reaches all
`OL_MEMBER`s (the collegial owner + top-band approver). The middle-band approver is the Bankleitung — a
`BANK_MANAGEMENT` Keycloak realm role, not an org-unit membership, so it is not enumerable through the
membership-based notification seam; the Bankleitung instead picks the request up in its bank-staff queue
(reachable via `BANK_MANAGEMENT > BANK_EMPLOYEE`) and the requester notifies them directly (ADR-0109).

**Acceptance**

- [x] A KRT withdrawal/transfer request is stamped `required_approver = null` (≤ T1) /
  `BANK_MANAGEMENT` (T1..T2) / `ORGANISATIONSLEITUNG` (> T2) at creation; a ≤ T1 request needs no
  approval, an over-band request needs the band approver's grant + the confirm checkbox.
- [x] „Fremde Anträge" routes a KRT band request only to its band approver (Bankleitung / OL); the
  responsible-holder routing for every other account is unchanged.
- [x] `T1`/`T2` are settable only via `PATCH …/approval-tiers` gated `BANK_MANAGEMENT`, CARTEL-only,
  `T2 ≥ T1`, audited `CARTEL_APPROVAL_TIERS_SET/CLEARED`; the per-audience limit editor is hidden for
  the KRT account.
- [x] A plain employee's direct KRT withdrawal/transfer above `T1` is **auto-filed** as the band-routed
  `PENDING` request and answered `202` (`BankBookingOutcomeDto.pendingRequest`), not booked and not a
  `409`; management/admin and the request-confirmation path are uncapped (ADR-0109).
- [x] `bankClassesMustNotConsultOrgUnitScope` and `orgUnitAwareBankSeamIsContainedToOneClass` stay green.

**Enforced by:** `OrgUnitBankAccessServiceTest` (band routing at create, `canApprove` per band,
„Fremde Anträge" band filter, KRT notification union), `BankAccountServiceTest`
(`setCartelApprovalTiers` management-only/CARTEL-only/`T2≥T1`/audit), `BankLedgerServiceTest`
(direct-booking cap for employee, uncapped for management/confirmation), `BankControllerSecurityTest`
(`approval-tiers` gate), frontend `BankManagePageControllerMvcTest` (KRT tab management-only),
`OrgUnitBankPageControllerMvcTest` (read-only ladder), `ArchitectureTest` (both bank pins) · **Code:**
`model/BankAccount#employeeApprovalCeiling/#areaLeadApprovalCeiling`, `model/BankRequestApprover`,
`model/BankBookingRequest#requiredApprover`, `model/BankAuditEventType#CARTEL_APPROVAL_TIERS_*`,
`service/BankAccountService#setCartelApprovalTiers`, `service/OrgUnitBankAccessService`
(`createBookingRequest` KRT branch, `resolveCartelApprovalRouting`, `raiseCartelDirectBookingRequest`,
`canApprove`, `listRequestsForResponsibleAccounts`, `resolveResponsibleHolderUserIds` CARTEL OL set),
`service/BankBookingGuards#exceedsCartelDirectBookingCeiling`,
`controller/BankBookingController#bookWithdrawal/#bookTransfer` (202 auto-request),
`model/dto/BankBookingOutcomeDto`, `controller/BankAccountController#setCartelApprovalTiers`,
`db/migration/V203` + `V222` (approver rename), frontend `templates/bank-manage.html` +
`org-unit-bank-account-detail.html` + `static/js/bank.js` (202 `pendingRequest` notice) +
`exception/GlobalExceptionHandler` (bank-409 message map) ·
**ADR:** [ADR-0066](../adr/0066-krt-account-amount-tiered-approval-ladder.md) (supersedes the
single-approver assumption of [ADR-0045](../adr/0045-bank-user-transfers-and-per-account-approval-limits.md)),
amended by [ADR-0109](../adr/0109-krt-middle-band-bankleitung-and-over-ceiling-auto-request.md)
(middle band → Bankleitung; over-ceiling direct booking → auto-request) ·
**Issues:** —

### REQ-BANK-048 — "Mitglieder des Bereichs" audience for Bereichskonten

A **Bereichskonto** (`AREA`) may open its balance/read-only detail **and** its approval limit to the
**whole area cascade** — the Bereichsleitung **plus** every member of the Bereich's child Staffeln and
Spezialkommandos — via a new **`AREA_MEMBERS`** value of the shared `BankAccountViewGranteeKind` enum
(so it extends **both** `bank_account_view_grant` and `bank_account_approval_limit` one-for-one, V202).
It sits alongside the existing Bereich audiences (`BEREICHSKOORDINATOR` / `BEREICHSOPERATOR` each
separately, all-Bereichsleitung via `ALL_MEMBERS`, individual users) and is offered **only for AREA
accounts**. The cascade membership is resolved inside the `OrgUnitBankAccessService` seam
(`OwnerScopeService.currentUserIsMemberOfAreaCascade(bereichId)` = the caller has any membership on the
Bereich or one of its `findChildOrgUnitIds` children — the 3-level hierarchy makes the direct children
the whole subtree), so the bank stays org-unit-blind. Toggling the grant/limit is an idempotent
insert/delete audited `BALANCE_VISIBILITY_GRANTED/REVOKED` / `APPROVAL_LIMIT_SET/CLEARED` (tier code
`AREA_MEMBERS`, no PII).

This clarifies the **"Alle Mitglieder" = the owning org unit** rule (amending REQ-BANK-041): on an AREA
account `ALL_MEMBERS` means the **direct** members of the Bereich org unit (the Bereichsleitung), and
`AREA_MEMBERS` is the distinct whole-cascade audience — the two are separate. The KRT account's
all-member **visibility** (every Kartellmitglied, REQ-BANK-037) is **unchanged**.

**Acceptance**

- [x] An AREA account offers the `AREA_MEMBERS` toggle in both the visibility and the approval-limit
  editor (and its read-only display); a Staffel/SK/CARTEL/SPECIAL account does not.
- [x] A member anywhere in the Bereich cascade (a child Staffel/SK member, not only the Bereichsleitung)
  is granted view / covered by the `AREA_MEMBERS` limit when set; a foreign-Bereich member is not.
- [x] `ALL_MEMBERS` on an AREA account resolves to the Bereich's direct members only; the `AREA_MEMBERS`
  tier is the whole cascade — the two are independent.

**Enforced by:** `OrgUnitBankAccessServiceTest` (AREA_MEMBERS canView + limit tier; cascade vs direct
members), `OrgUnitBankControllerTest`, frontend `OrgUnitBankPageControllerMvcTest`,
`DatabaseIndexMigrationTest` (V202 CHECK + partial unique indexes) · **Code:**
`model/BankAccountViewGranteeKind#AREA_MEMBERS`, `service/OwnerScopeService#currentUserIsMemberOfAreaCascade`,
`service/OrgUnitBankAccessService` (`matchesOrgUnitGrant`, `resolveApplicableLimit`,
`setAreaMembersVisibility`, `setAreaMembersApprovalLimit`), `service/BankApprovalLimitService#areaMembersSupported`,
`model/dto/BankApprovalLimitsDto`, `model/dto/OrgUnitBankAccountSettingsDto`,
`controller/OrgUnitBankController`, `db/migration/V202`, frontend
`templates/fragments/bank-approval-limits.html` + `org-unit-bank-account-detail.html` ·
**ADR:** [ADR-0066](../adr/0066-krt-account-amount-tiered-approval-ladder.md) · **Issues:** —

### REQ-BANK-049 — Account balance-over-time chart

Both account-detail surfaces — the bank-staff `/bank/accounts/{id}` and the org-unit viewer
`/org-unit-bank/accounts/{id}` — render, **above** the booking history, an inline SVG line chart of
the account balance over a caller-chosen preset range (**30 Tage / 90 Tage / 1 Jahr / Gesamt**,
default 90 Tage). When a **balance target** is set (REQ-BANK-036) it is drawn as a dashed reference
line. The series is computed on demand from the append-only ledger — the opening balance before the
window (`accountBalanceBefore`) walked over the window's postings into an end-of-day series
(`BankBalanceSeriesCalculator`, mirroring the statement math REQ-BANK-014), evenly downsampled for
long ranges — and served by a new read-only endpoint on each surface
(`GET /api/v1/bank/accounts/{id}/balance-series?from&to`, gated by `canSee`, and its
`isAuthenticated` + view-scoped org-unit twin). Pure balance math with no holder/counterparty data,
so nothing is redacted: every viewer who may see the account (and thus its current balance) may see
how it moved. No external chart library (CSP): the SVG geometry is scaled server-side
(`BankBalanceChart`, the larger sibling of the dashboard sparkline). A range change re-renders only
the chart fragment in place (`fragment=balanceChart` / `orgUnitBalanceChart`, no reload,
REQ-FE-002); a money write resets it to the default range alongside the balance. Read-only, so **not
audited** and carries **no new `basetool_*` metric** (covered by the generic HTTP server metrics,
REQ-OBS-011).

**Acceptance**

- [x] The chart renders for every viewer of the account on both surfaces, with the preset range
  selector (default 90 days) and — when a target is set — the dashed target line + legend.
- [x] The balance series is the end-of-day running balance over the range (opening + window
  postings); an empty/no-data window renders the chart's empty state.
- [x] The `balance-series` endpoints are gated exactly like the booking history (`canSee` /
  org-unit view scope); a caller without access is forbidden.
- [x] A range change swaps only the chart fragment in place (no full reload); the series is never
  audited and adds no business metric.

**Enforced by:** `BankBalanceSeriesCalculatorTest`, `BankAccountServiceTest#getBalanceSeries*`,
`BankControllerSecurityTest#balanceSeries*`, `OrgUnitBankAccessServiceTest`, frontend
`BankAccountDetailFragmentMvcTest` (balanceChart fragment) + `OrgUnitBankPageControllerMvcTest` ·
**Code:** `service/BankBalanceSeriesCalculator`, `service/BankAccountService#getBalanceSeries`,
`service/OrgUnitBankAccessService#getViewableBalanceSeries`, `controller/BankAccountController`,
`controller/OrgUnitBankController`, `repository/BankPostingRepository#postingSlicesInRange`,
`model/dto/BankBalanceSeriesDto` + `BankBalancePointDto`, frontend `controller/BankBalanceChart`,
`controller/BankAccountDetailSupport`, `templates/fragments/bank-balance-chart.html` · **Issues:** —

### REQ-BANK-050 — Collapsible chart and booking-history panels (per-user state)

On both account-detail surfaces the balance chart (REQ-BANK-049) and the booking history are each
collapsible, **expanded by default**, with the collapsed/expanded state persisted **per user in
localStorage** (`bank_panel_collapse_<uid>`, keyed by the panel — `chart` / `history`) and replayed
on load and on every in-place fragment swap, so a money/settings write that re-renders a panel does
not lose the user's choice. Distinct from the always-default-collapsed, unpersisted Konto-Info tile
(REQ-BANK-017). Client-side view preference only — no server state, no new endpoint — in the same
family as the dashboard's per-user layout/group toggle (REQ-BANK-016).

**Acceptance**

- [x] Both panels render expanded by default; toggling a panel persists its state per user and
  survives a reload and an in-place fragment swap.
- [x] The persistence is per user (keyed by the authenticated user) and local (localStorage), never
  server-side.

**Enforced by:** frontend `BankAccountDetailFragmentMvcTest` / `OrgUnitBankPageControllerMvcTest`
(the collapse toggles render `aria-expanded="true"` by default) · **Code:** frontend
`static/js/bank.js` (persisted-collapse module), `templates/bank-account-detail.html`,
`templates/org-unit-bank-account-detail.html`, `static/css/bank.css` (`.bank-collapse-head`) ·
**Issues:** —

### REQ-BANK-051 — Booking-history period filter and page-size pagination

The account-detail booking history (both surfaces) gains a **from/to period filter** (default the
last **90 days**) and **page-size pagination** with the standard controls and **10 / 50 / 100**
entries per page (default **50**). The period narrows the history via optional inclusive
`from`/`to` bounds on the transactions endpoints (`(:from IS NULL OR …)`), reusing the shared
`PaginationUtil` size clamp. Changing the period or the page/size re-renders only the
booking-history fragment in place (`fragment=bookings` / `orgUnitBankBookings`,
REQ-FE-002/REQ-FE-005); the period rides the pagination base URL so paging keeps the filter, and the
no-JS form submit reloads the page with the same params. The org-unit surface stays Halter-redacted
(REQ-BANK-038).

**Acceptance**

- [x] The history defaults to the last 90 days at 50 entries/page; the user can set from/to and
  choose 10/50/100 per page.
- [x] Changing the period or the page size swaps only the history fragment in place and preserves
  the other setting across paging.
- [x] The optional `from`/`to` bounds narrow the transactions query on both the bank-staff and the
  org-unit endpoints; omitting them pages the whole history as before.

**Enforced by:** `BankControllerSecurityTest`, `BankAccountServiceTest`, `OrgUnitBankAccessServiceTest`,
frontend `BankAccountDetailFragmentMvcTest` (period filter + page-size picker) + `BankPageControllerTest`
(pagination base URL carries the period) · **Code:**
`repository/BankPostingRepository#findBookings(from,to)`, `service/BankAccountService#getBookings`,
`service/OrgUnitBankAccessService#getViewableAccountBookings`, `controller/BankAccountController`,
`controller/OrgUnitBankController`, frontend `controller/BankAccountDetailSupport`,
`templates/bank-account-detail.html`, `templates/org-unit-bank-account-detail.html` · **Issues:** —

### REQ-BANK-052 — Chart and booking history side by side on ultra-wide screens

On both account-detail surfaces the balance chart (REQ-BANK-049) and the booking history stack
vertically (chart above history) by default, but sit **side by side** (chart left, history right) on
ultra-wide screens (≥ 1800px viewport): the `.bank-detail-panels` grid switches to two columns and
`main.bank-detail` is allowed past its normal 1200px content cap to exploit the space (REQ-UI-009).
The two panels stay independently collapsible (REQ-BANK-050) in either layout, and the single-column
stack keeps the chart-first order on narrower screens.

**Acceptance**

- [x] Below the ultra-wide breakpoint the two panels stack (chart above history); at/above it they
  render side by side (chart left, history right) and the content region widens past 1200px.
- [x] Both layouts keep the panels independently collapsible and the chart-first order; the history
  table shrinks/scrolls inside its column rather than blowing out the grid.

**Enforced by:** the two panels render inside the `.bank-detail-panels` grid on both surfaces
(frontend `BankAccountDetailMovementModalMvcTest`, `OrgUnitBankPageControllerMvcTest`); the
two-column switch is the CSS media query (visual, REQ-UI-009) · **Code:** frontend
`static/css/bank.css` (`.bank-detail-panels` + the `main.bank-detail` ultra-wide media query),
`templates/bank-account-detail.html`, `templates/org-unit-bank-account-detail.html` · **Issues:** —

### REQ-BANK-053 — Server-side account search + paginated account surfaces (5000-account scale)

Every bank surface that offers a **list of accounts** must scale past the former unbounded
`?size=500` preload, which silently truncated: beyond 500 active accounts a transfer destination, a
grant target or a managed account became **unreachable with no search, no pagination and no "more"
indicator**. ADR-0085 plans for ~5000 members, so the bound is plausible to cross. This is the
account-side twin of the user-picker scaling switch (REQ-FE-011, ADR-0089) and is realised two ways
(ADR-0106):

- **The account listing endpoint gains search + status/type filters.** `GET /api/v1/bank/accounts`
  accepts an optional case-insensitive `query` substring over **name and account number**, plus
  repeatable `status` and `type` filters; an absent status/type means "all" (the full enum set). The
  filter runs in the repository (`findAllFiltered` / `findGrantedToFiltered`, the management and
  grant-scoped variants) as a `LOWER(name/accountNo) LIKE LOWER(CONCAT('%', :query, '%'))` predicate
  plus `status IN … AND type IN …`. The query is a **bound parameter** (SQL-injection-safe) and is
  deliberately **not** `LikePatterns`-escaped — plain `LIKE` does not honour the backslash escape in
  this Hibernate/PostgreSQL setup, so escaping would only *hide* an account whose name contains a
  literal `%`/`_`; a caller's `%`/`_` therefore act as harmless LIKE wildcards on this
  bank-employee-gated read (a blank query normalises to the match-all `LIKE '%%'`). The listing stays
  **caller-scoped** exactly as before
  (management sees all, an employee only their granted accounts, REQ-BANK-010) and **balances stay
  batch-joined** so the read is statement-bounded regardless of the account count (REQ-DATA-003).

- **The four account pickers become server-side search comboboxes** (`remote-bank-accounts`,
  REQ-FE-017): the transfer destination (REQ-BANK-040), the direct-booking **source** account, the
  grant-create account and the grants **per-account filter**. Each fetches matching **active**
  accounts on demand from the frontend proxy `GET /api/proxy/bank/accounts/search` instead of
  preloading a roster. Two deliberate, backend-safe UX deltas: the transfer-destination picker may
  list the current account (a same-account transfer is still rejected, REQ-BANK-006), and the grant
  pickers search active accounts only. The source-account picker's per-account **Begründung mandate**
  (REQ-BANK-045) — which used to ride the `<option>` — is carried by the `window.krtBankAccountMeta`
  map the search source populates, because combobox enhancement drops per-option metadata.

- **The `/bank/manage` account-management table is truly paginated** (page/size, name-sorted for a
  stable order via the `id` tiebreaker, default page size 25), with the shared page-size picker + pager
  re-rendering the `manageBody` fragment in place (REQ-FE-005); the accounts tab count is the page's
  **total** element count, not the current page size. The singleton `CARTEL` account the KRT-Freigaben
  tab needs (which may not sit on the current page) is fetched by its own `?type=CARTEL&size=1` lookup.

The change is a read-scaling one: **no new audit event and no new business metric** (REQ-OBS
unaffected — an authenticated read surface, no blackbox probe), and no new role or migration.

**Acceptance**

- [ ] `GET /api/v1/bank/accounts?query=…` filters by a case-insensitive name **or** account-number
  substring, honours repeatable `status`/`type` (absent = all), stays caller-scoped, and binds the
  query as a parameter (SQL-injection-safe; a `%`/`_` is a harmless LIKE wildcard).
- [ ] The transfer-destination, direct-booking source, grant-create and grants per-account pickers
  carry `data-krt-combobox="remote-bank-accounts"` and preload **no** account roster; typing finds an
  account by number or name; a booking/grant against a searched account submits its id.
- [ ] The source-account picker still marks the Begründung `required` for a CARTEL/CARTEL_BANK/SPECIAL
  source (mandate read from the search metadata, not the `<option>`).
- [ ] `/bank/manage` pages the accounts table with the standard controls; the tab count shows the
  total account count; paging swaps only the `manageBody` fragment; the KRT-Freigaben tab still finds
  the CARTEL account when it is off the current page.
- [ ] No surface issues the former `?size=500` preload; a 600-account org keeps every account
  reachable via search / the pager.

**Enforced by:** `BankAccountSearchTest` (name/accountNo/status/type filter, grant scoping,
injection-safety), `BankControllerSecurityTest` (query/status/type param binding + full-enum default),
`BankReadNoNPlusOneTest` (paged list stays statement-bounded), frontend `BankProxyControllerTest`
(the `/accounts/search` proxy forwards active/name-sorted + unwraps), `BankManagePageControllerTest`
(pagination + size clamp + CARTEL lookup), `BankPageControllerTest` / `BankInPlaceFragmentMvcTest` /
`BankDashboardMovementModalMvcTest` / `BankRequestQueuePageControllerMvcTest` (pickers preload no
roster, carry the `remote-bank-accounts` marker), `BankGrantsPageControllerTest` (selected-account
seed, no roster) · **Code:** `repository/BankAccountRepository#findAllFiltered/#findGrantedToFiltered`,
`service/BankAccountService#getAccounts`, `controller/BankAccountController#getAccounts`, frontend
`controller/BankProxyController#searchAccounts`, `controller/BankManagePageController`,
`controller/BankPageController`, `controller/BankGrantsPageController`,
`controller/BankRequestQueuePageController`, `static/js/krt-bank-account-search.js`, `static/js/bank.js`,
`templates/fragments/bank-movement-modal.html`, `templates/bank-grants.html`,
`templates/bank-manage.html` · **ADR:** ADR-0106 · **Issues:** —

### REQ-BANK-054 — "Notiz Bankmitarbeiter": the booking employee's own note

Every booking a **bank employee** performs carries an optional free-text **Notiz Bankmitarbeiter**
(`staff_note`, `VARCHAR(500)`, V231) recording internal context for the movement — *"in zwei
Tranchen übergeben"*, *"nach Rücksprache mit der SL"*. It is a **third** free-text field beside the
existing two, and the distinction is **authorship**, not wording: `note` and `justification`
(Begründung, REQ-BANK-045) come from the **requester / booking party**; `staff_note` comes from the
**employee doing the booking**.

**Where it is captured.** All four employee entry points: the direct **deposit**, **withdrawal** and
**transfer** in the unified movement modal (REQ-BANK-017), and the **confirmation of a booking
request** (REQ-BANK-023). Unlike the Begründung it is captured for a **deposit** too and is **never**
mandatory — no account type demands it, so there is no `BANK_*_REQUIRED` conflict code and no
per-account gate. The Halter-Umbuchung, reversals and the wipe reset carry none (they are not
employee-authored movements in this sense).

**Where it is stored.** On **both** tables, mirroring V198's justification rollout:
`bank_transaction.staff_note` is the booking's note (history, statements, management report), and
`bank_booking_request.staff_note` snapshots what the confirming employee recorded so the staff queue
and the approval tab render it **without joining the resulting transaction per row** (the no-N+1
rule). Confirmation writes the same value to both. On the request the column is deliberately **not**
`updatable = false` — unlike `note` / `justification`, which the requester supplies at creation,
this one is written at **confirmation**, and it stays `null` while `PENDING` and on a
rejected/cancelled request.

**Where it is shown — and where it is not.** Everywhere the note/Begründung already appear *for
bank-facing eyes*: the bank-staff account-detail booking history, the request queue's expandable
sub-row, the approval tab's sub-row (REQ-BANK-041), the account-statement PDF and the
management-export PDF. On each surface it is a third line of the existing detail sub-row, after
Begründung and Notiz, and its presence alone makes a row expandable.

It is **redacted for org-unit members**, and this is the one place it parts company with the other
two. `note` and `justification` survive the member-facing redaction because they are not
player-identifying and the requester wrote them; a staff note is an **internal bank remark**, so
`OrgUnitBankAccessService.redact()` nulls it exactly like the Halter columns (REQ-BANK-038), and the
Halter-redacted org-unit **statement PDF** drops it for the same reason (same generator,
`redactHolders = true`). The field's UI hint says so out loud, so an employee knows the audience
before typing. Bankleitung, OL and the account's responsible holder are **not** affected — they
read it through the bank-facing surfaces above.

Like the Begründung it is **never** written into the audit `details` payload (REQ-BANK-012: no user
free text / no PII there) and introduces **no** new `AuditEventType` — it is a field on existing
movements, not a new audited activity. It adds no metric, endpoint or scheduled job.

**Acceptance**

- [x] A direct deposit, withdrawal and transfer each persist a supplied staff note on the ledger
  transaction; a deposit accepts one although it accepts no Begründung.
- [x] Confirming a booking request writes the employee's note onto **both** the request and the
  booked transaction.
- [x] The note is optional everywhere: omitting it books normally and stores `null`.
- [x] An org-unit member's redacted booking row carries `note` and `justification` but **never**
  `staffNote`; the Halter-redacted statement PDF likewise omits it.
- [x] A request row whose *only* detail is a staff note still renders as expandable.
- [x] The confirm modal and the movement modal both offer the field, and a reused modal never
  carries a previous booking's staff note into the next one.

**Enforced by:** `BankLedgerServiceTest` (deposit + withdrawal persist it alongside the untouched
note/Begründung), `BankBookingRequestServiceTest` (confirmation snapshots it on the request *and*
hands it to the ledger), `OrgUnitBankAccessServiceTest` (redaction keeps note/justification and nulls
the staff note), frontend `BankRequestQueuePageControllerMvcTest` (both entry points offer the field)
and `OrgUnitBankPageControllerMvcTest` (the approval tab renders it, and it alone makes the row
expandable) • **Code:** `model/BankTransaction`, `model/BankBookingRequest`,
`model/dto/request/Bank{Deposit,Withdrawal,Transfer}Request`,
`model/dto/request/ConfirmBankBookingRequest`, `model/projection/BankBookingRow`,
`model/dto/Bank{Booking,BookingRequest}Dto`, `service/BankPostingWriter`, `service/BankLedgerService`,
`service/BankBookingRequestService`, `service/OrgUnitBankAccessService`,
`service/Bank{Statement,Management}ReportService`, `service/pdf/KrtPdfSupport`, `db/migration/V231`

## Out of scope

- **Accounts for individual players** — an explicit owner decision: players appear only
  as holders (custody dimension, REQ-BANK-003), never as account owners.
- **Automated money flows** from missions/operations/orders into the bank (REQ-BANK-019)
  — a possible future epic, requires its own spec.
- **Interest, fees, loans, currencies other than aUEC.**
- **Full bank access for org-unit members** — plain members still see nothing. Epic #666
  grants officers/leads only a **balance-only view** and **confirm-before-post booking
  requests** for their own org unit's account (REQ-BANK-021/-022); the transaction history,
  holder distribution and audit log stay a bank-staff surface.
- **Confirmation/rejection notifications & request auto-expiry** — only request *creation*
  notifies (REQ-BANK-026); a decided request shows its status in the requester's list, and
  requests do not expire automatically (they are confirmed, rejected or cancelled).
- **English PDF variants** — v1 statements/exports render German labels (from the
  message bundles, so a locale switch stays cheap later).
- ~~**A "Bereich" (area) entity** — area accounts carry a free-form name; the org chart
  stays purely descriptive (REQ-ORG-010).~~ **Superseded by epic #692:** Bereich and OL are now
  first-class `org_unit` kinds (REQ-ORG-014); `AREA` accounts link to a Bereich and `CARTEL` to the OL
  (REQ-BANK-027). The org chart stays descriptive, but is widened to multi-Bereich + OL (org-chart spec).

## Open questions

1. **Final role naming** — `Bank Employee` / `Bank Management` are proposals; the owner
   names the Keycloak roles before Phase 1 freezes `DataInitializer` codes.
2. **Transfer destination rule** — v1 requires the destination account to be *visible*
   to the employee (grant row). Alternative (any active account as destination) would be
   a one-line spec change; decide at Phase 1 review.
3. **Reversal permission** — v1 proposal: reversals require `BANK_MANAGEMENT` (employees
   ask management to fix mistakes). Confirm at Phase 1 review.
4. **Statement number/archival** — statements are generated on demand and not persisted;
   if the org wants numbered, archived statements, that becomes a follow-up requirement.
5. **Holders without a basetool account** — v1 requires every holder to be a registered
   tool user (picked via the user lookup, handle snapshotted). Allowing free-text
   external holders would be a small spec change; decide at Phase 1 review.
   **Partly resolved (2026-07-05, #994):** external free-text parties are now allowed for the
   deposit/withdrawal **counterparty** (Einzahler/Empfänger, REQ-BANK-044). External **Halter**
   remain out of scope — this question stays open only for the custody dimension.


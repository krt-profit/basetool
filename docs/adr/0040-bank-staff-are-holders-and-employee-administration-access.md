# ADR-0040 — Bank staff are holders: auto-registration from bank roles + employee bank-administration access

- **Status:** Accepted (epic #556 follow-up, owner-approved 2026-06-22)
- **Date:** 2026-06-22
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-BANK-001/-002/-009/-029/-030/-031 (`docs/specs/bank.md`) ·
  [ADR-0011](0011-bank-authorization-model.md) (extended) ·
  [ADR-0039](0039-bank-holder-ledger-decoupled-from-accounts.md) · epic
  [#556](https://github.com/krt-profit/basetool/issues/556)

## Context

The decoupled holder ledger (ADR-0039) makes the bank staff the players who physically move
the organization's money among themselves and reconcile the resulting imbalances via
holder→holder Umbuchung (REQ-BANK-031). For that to work operationally:

- every bank employee and manager must exist as a **holder** without manual bookkeeping —
  the holder set should track the bank roster automatically;
- bank **employees** — not just management — must reach the **holder menu** to perform
  reconciliation Umbuchungen, and must be able to spin up ad-hoc **special accounts**
  (Sonderkonten) themselves;
- management must keep **exclusive** control of the account-relationship lifecycle (create
  non-special / rename / close / reopen) and of grants.

ADR-0011's grant model and its org-unit independence (REQ-BANK-008) stay intact: bank-role
membership drives everything; no `OwnerScopeService` consultation is added. The substrate
already exists — Keycloak realm roles sync into `user_roles` on every login
(`UserService.syncUser`) and every five minutes (`UserSyncTask`), and
`UserRepository.findUserIdsByRoleCode` lists the holders of a role.

## Decision

1. **Auto-registration.** Every user holding `ROLE_BANK_EMPLOYEE` or
   `ROLE_BANK_MANAGEMENT` is reconciled into an **active** `bank_holder` row, idempotently,
   at the existing role-sync points. A new `bank_holder.role_managed` flag marks
   role-derived holders. When a user loses **all** bank roles, their `role_managed` holder is
   **auto-deactivated** (blocks new incoming money; the balance — which under ADR-0039 may be
   negative — survives and must be reconciled to zero by a `HOLDER_TRANSFER`). Holders that
   were **manually** registered (`role_managed = false`, e.g. a non-staff custodian) are left
   untouched by the reconcile; manual registration by management is retained
   (REQ-BANK-003/-029).

2. **Employee bank-administration access.** `/bank/manage` and its sidebar entry open to
   `ROLE_BANK_EMPLOYEE`. Inside the page, actions are gated **individually**:

   - an employee may create **only `SPECIAL`** accounts and is **auto-granted** full
     capability (`can_deposit`/`can_withdraw`/`can_transfer`) on the account they create
     (audited `GRANT_CREATED`), so the new account is immediately usable by them;
   - creating any non-`SPECIAL` type, and **rename / close / reopen**, stay
     `ROLE_BANK_MANAGEMENT`;
   - the **holder menu** (view global balances + perform holder→holder Umbuchung) opens to
     `ROLE_BANK_EMPLOYEE`; **manual** holder register / (de)activate and grant management stay
     `ROLE_BANK_MANAGEMENT`;
   - the audit log stays admin-only (REQ-BANK-012).
     The `SPECIAL`-only restriction is enforced **server-side** (the create service rejects a
     non-`SPECIAL` type for a non-management caller), not just hidden in the UI.
3. **Holder→holder Umbuchung gate.** The endpoint is gated `hasRole('BANK_EMPLOYEE')`, books
   a `HOLDER_TRANSFER` (ADR-0039), carries **no account** and needs **no** per-account grant.
   It **ignores the holder `active` flag in both directions** so a deactivated holder's
   residual (positive or negative) can be reconciled to zero.

## Consequences

- **Easier:** staff never hand-register themselves; the holder set tracks the roster; the
  people who reconcile can reach the tool.
- **Easier:** ad-hoc special accounts without involving management; the creator is
  operational immediately via the auto-grant.
- **Harder / accepted:** the reconcile must be idempotent and must tolerate a holder having a
  (possibly negative) balance at role loss — it **deactivates, never deletes**. The
  `role_managed` flag keeps manual custodians out of the auto-deactivation path.
- **Harder / accepted:** action-level gating inside one management page (employee vs
  management) rather than a single page-level role gate; expressed via `@PreAuthorize` plus a
  service-side type restriction so the backend enforces the `SPECIAL`-only rule.
- **Unchanged:** org-unit independence (ADR-0011, REQ-BANK-008) and the grant model; the
  ArchUnit pins `bankClassesMustNotConsultOrgUnitScope` /
  `orgUnitAwareBankSeamIsContainedToOneClass` stay green.

## Alternatives considered

- **A dedicated "holder" Keycloak role distinct from the bank roles** — rejected: the owner's
  rule is precisely "all bank staff are holders"; a third role would drift from the roster and
  add Keycloak administration for no gain.
- **Block bank-role removal while the holder still holds money** — rejected: role membership
  lives in Keycloak, outside the app's transaction boundary; the app cannot veto it, so it
  deactivates the holder and surfaces the residual for reconciliation instead.
- **Open the whole `/bank/manage` to employees** — rejected: the account-relationship
  lifecycle (rename/close/create-non-special) and grant management must stay management-only;
  only the `SPECIAL`-create and holder-menu slices open up.
- **Keep holder rebooking per-account (the status-quo intra-account rebook)** — rejected:
  superseded by the global holder ledger (ADR-0039); custody is no longer per-account, so a
  single global Umbuchung replaces it.

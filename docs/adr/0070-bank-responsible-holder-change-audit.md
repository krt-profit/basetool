# ADR-0070 — Bank: audit a change of an account's derived responsible holder (Kontoverantwortliche/r)

- **Status:** Accepted
- **Date:** 2026-07-03
- **Deciders:** @greluc
- **Related:** spec REQ-BANK-034 (derived responsible holder) · REQ-BANK-012 (immutable bank audit
  log) · REQ-AUDIT-001 (audited areas) · builds on ADR-0020 (the org-unit-aware bank seam is the sole
  bridge) / ADR-0043 (responsibility & visibility) · org-unit-blindness rule in `CLAUDE.md`,
  ADR-0011

## Context

An org-unit bank account has a **responsible holder** (Kontoverantwortliche/r, REQ-BANK-034) who may
set its balance target and configure its visibility. That holder is **never freely assigned** — it is
**derived** from org-unit leadership: the `STAFFELLEITER` of a Staffel, the `SK_LEAD` of a
Spezialkommando, the `BEREICHSLEITER` of a Bereich, all `OL_MEMBER`s collegially for the `CARTEL`
account, and the Profit-Bereichsleiter for `CARTEL_BANK` (and, per REQ-BANK-047, as a co-holder of the
collegial `CARTEL` set). The value is materialised on read by
`OrgUnitBankAccessService.resolveResponsibleHolderUserIds(accountId)`; nothing is stored on
`bank_account`.

The owner wants the **bank audit log** (REQ-BANK-012, admin-only) to record **when the responsible
holder of an account changes**, capturing the **old** holder(s), the **new** holder(s), and **who
caused** the change. Because the holder is derived, "a change" happens implicitly when the underlying
org-unit leadership changes — there is no direct "assign responsible holder" action to hook.

Constraints: (1) the bank's **authorization** must stay org-unit-blind (REQ-BANK-008, ArchUnit
`bankClassesMustNotConsultOrgUnitScope`), and only `OrgUnitBankAccessService` may bridge
`OwnerScopeService`/org-unit membership and the bank accounts repository
(`orgUnitAwareBankSeamIsContainedToOneClass`); (2) the audit `details` payload must carry **no user
free text / no PII** (REQ-BANK-012); (3) the initiator ("Veranlasser") must be the human who performed
the leadership change, not a background job; (4) no optimistic-locking trap may be introduced
(`CLAUDE.md` concurrency rules).

## Decision

1. **Hook the membership-mutation choke points, not a stored value.** Every path that can change an
   org unit's leadership **brackets** its mutation: it snapshots the affected accounts' responsible
   holders **before** the write and re-diffs **after** it, on the **same transaction**. No snapshot
   column is added to `bank_account`, so there is no `@Version` writeback trap. The hooked paths are:
   - the seven direct leadership mutations in `OrgUnitMembershipService` — `assignSquadronRank`,
     `removeSquadronRank`, `toggleLead`, `addBereichLeader`, `removeBereichLeader`, `addOlMember`,
     `removeOlMember`;
   - the two **indirect membership-removal** paths that can drop a leader — `removeMember` (an SK
     member who is the SK-Lead) and `reconcileStaffelMemberships` (a removed Staffel membership that
     carried the Staffelleiter rank; each removed Staffel is snapshotted);
   - **`UserService.deleteUser`** — deleting a user removes their memberships via the DB
     `ON DELETE CASCADE`, so it snapshots the accounts of **all** the user's org units up front
     (`snapshotResponsibleHoldersForUser`), then **flushes** the user delete before the re-diff so the
     recompute observes the post-cascade state.
2. **Keep all bank access in the seam.** The bracket operations live on the sanctioned
   `OrgUnitBankAccessService`: `snapshotResponsibleHolders(orgUnitId)` (returns, per affected account,
   its current responsible-holder set), `snapshotResponsibleHoldersForUser(userId)` (the same across
   every org unit a user belongs to, for the deletion path) and `recordResponsibleHolderChanges(before)`
   (recomputes each account, and for every account whose set changed records one
   `ACCOUNT_RESPONSIBLE_CHANGED` event). The membership and user services only call these methods; they
   never touch a bank repository, so both ArchUnit pins stay green.
3. **Resolve the affected accounts through the seam, including the Profit ripple.** For a leadership
   change on org unit *X* the affected accounts are the account *X* owns (`findByOrgUnitId`) plus —
   when *X* is a `Department.PROFIT` Bereich — the `CARTEL` and `CARTEL_BANK` singletons, whose
   responsible sets include the Profit-Bereichsleiter (REQ-BANK-034/-047). This is the full dependency
   graph of the derivation, so a Bereichsleiter change on a Profit Bereich is audited against all three
   accounts it actually moves.
4. **The recompute sees the post-mutation state.** `recordResponsibleHolderChanges` resolves the new
   sets via a JPQL membership query, which auto-flushes the pending `setRole`/`delete`, so the "after"
   side reflects the mutation without an explicit `flush()`.
5. **Capture the initiator automatically; keep the payload PII-free.** `BankAuditService.record`
   already snapshots the acting user (JWT `sub` → actor id + handle) — that is the Veranlasser. The
   new `ACCOUNT_RESPONSIBLE_CHANGED` event stores the affected `accountId`, sets `targetUserId` to the
   sole new holder when the set is a singleton (else null, for the collegial `CARTEL`/`CARTEL_BANK`),
   and carries the old and new **user-id sets** in the details payload (`old=<uuid,…> new=<uuid,…>`).
   User UUIDs are system identifiers, not free text or PII, consistent with the existing
   `target_user_id` FK and the ADR-0054 handle-in-detail precedent.
6. **Break the DI cycle with an `ObjectProvider`.** The seam transitively depends on
   `OrgUnitMembershipService` (via `BankBookingRequestService`), so injecting the seam directly into
   `OrgUnitMembershipService` would form a constructor cycle. It is injected as
   `ObjectProvider<OrgUnitBankAccessService>` and resolved lazily at each call site — no eager bean is
   needed at construction, so the context still starts.

## Consequences

- **Easier:** the admin bank audit now answers "who became / stopped being responsible for this
  account, and who caused it" for every change of the derived responsible holder — whether by a direct
  leadership mutation, an indirect membership removal (SK member / Staffel reconcile) or a user
  deletion — without a stored snapshot, without a migration, and without weakening the append-only
  ledger or the org-unit-blind authorization. The audit write shares the mutation's transaction, so it
  either both commit or both roll back (REQ-BANK-012: no silent gaps).
- **Harder / trade-offs:** every membership-removal path must remember to bracket (there is no single
  choke point), and the user-deletion path must **flush** the delete before the re-diff because the
  membership rows disappear via the DB `ON DELETE CASCADE`, not through Hibernate. The extra
  before/after resolution adds a couple of lightweight membership queries per (rare) admin action.
  A change of the derivation inputs from **outside** the app (a raw SQL membership edit, a restore)
  would still not be observed — the derivation is materialised on read, and only app-driven mutations
  are hooked.
- **Rejected alternatives:** (a) a stored `responsible_user_id` snapshot on `bank_account` + a
  reconciliation job — dropped: the reconciliation job cannot attribute the initiator, and a snapshot
  on the versioned account row risks the optimistic-lock writeback trap; (b) a Spring
  `ApplicationEvent` from the membership service — dropped: a post-mutation event cannot observe the
  "before" state without either the seam (reintroducing the cycle) or a stored snapshot; (c)
  duplicating the resolution into a new `Bank*`-named component — dropped: it would either re-bridge
  org-unit scope and the bank (violating the single-seam ArchUnit pin) or duplicate the derivation.


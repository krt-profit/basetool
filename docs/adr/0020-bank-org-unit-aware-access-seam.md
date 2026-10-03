# ADR-0020 — Org-unit officer/lead bank access via a single non-`Bank*` seam

- **Status:** Accepted — amended 2026-10-02 (the two seam rules re-keyed, see below)
- **Date:** 2026-06-17
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-BANK-021 · REQ-BANK-022 · REQ-BANK-008 (amended) · ADR-0011 · issue #666

## Context

The bank was designed to be **completely independent of org-unit membership** in both
directions (REQ-BANK-008, ADR-0011): `BankSecurityService` decides access solely from the
two bank Keycloak roles and the `bank_account_grant` table, and an ArchUnit rule
(`bankClassesMustNotConsultOrgUnitScope`) forbids every `Bank*`-named class from depending on
`OwnerScopeService`. Epic #666 adds two features that are inherently org-unit-relative:
officers/leads must see the **balance** of (F1) and raise **booking requests** against (F2)
the account of an org unit they oversee. These need the exact input the bank is forbidden to
consult — *who oversees which org unit* — so they collide head-on with the constitutional
invariant. Per the project's binding-requirements rule, relaxing REQ-BANK-008 required prior
owner approval, which was granted.

## Decision

We will **keep `BankSecurityService` and the ledger 100% org-unit-blind** and isolate *all*
org-unit logic in a single, deliberately **non-`Bank*`-named** service,
`OrgUnitBankAccessService`. Because the ArchUnit rule keys on the `Bank` name prefix, this
seam may inject `OwnerScopeService` (using the existing oversight scope,
`currentOversightScope()`) without weakening the rule for any bank class. A
complementary positive ArchUnit pin — `orgUnitAwareBankSeamIsContainedToOneClass` — asserts
that the seam is the **only** class that depends on both `OwnerScopeService` and the bank
accounts repository, so a future accidental bridge fails the build. The officer/lead surface
lives outside the bank URL/role space, under `/api/v1/org-units/bank/**` (authenticated; the
oversight scope decides the result), so reaching it grants no other bank surface and needs no
bank role.

## Consequences

- The bank's independence invariant is preserved *by construction* and is now pinned by two
  ArchUnit rules (the original negative one and the new containment one).
- All org-unit-aware bank logic has exactly one home; reviewers know where to look.
- A scope-mismatched request is rejected **before** the account is resolved, so the endpoint
  never leaks whether an out-of-scope org unit owns an account.
- Cost: a thin extra layer — the seam resolves the org unit's account and delegates the
  bank-domain work to the org-unit-blind `BankBookingRequestService`. The split (officer-side
  in the seam, staff-side in a `Bank*` service) mirrors the two audiences.

## Alternatives considered

- **Let `BankSecurityService` consult org-unit scope** — rejected: directly violates
  REQ-BANK-008/ADR-0011 and would erase the independence the ArchUnit rule protects.
- **A new bank role for officers/leads** — rejected: org-unit oversight is dynamic
  (membership/officer/lead), not a static Keycloak role; would duplicate the oversight model
  already in `OwnerScopeService` and drift from it.
- **Several bridging classes** — rejected: the containment pin keeps the blast radius to one
  auditable seam.

## Amendment — 2026-10-02: the seam rules are re-keyed

Owner-approved on 2026-10-02 (domain modularisation plan §5.4, guard G-01, ADR-0231/0233). Both
ArchUnit rules stop depending on names that a package move changes:

- `bankClassesMustNotConsultOrgUnitScope` selects the bank's classes by package and module
  membership — the domain map's `bank` module, and its package once the bank has moved — instead of
  the `Bank` simple-name prefix, and names `OwnerScopeService` by class literal instead of an FQCN
  string. `OrgUnitBankAccessService` is the one class it exempts, named by class literal.
- `orgUnitAwareBankSeamIsContainedToOneClass` names `OwnerScopeService` and
  `BankAccountRepository` by class literal and the permitted bridge as
  `OrgUnitBankAccessService.class`.

Each rule asserts a selection floor and is proven able to fail once. The bridge set stays exactly
`OrgUnitBankAccessService`; a second bridge still needs its own decision. The seam's non-`Bank*`
name is no longer what keeps it outside the first rule; the explicit exemption is, and a class
literal follows the seam through a move or a rename where a string would silently stop matching.

# ADR-0028 — Bereich/OL bank access (AREA/CARTEL) via the OrgUnitBankAccessService seam

- **Status:** Accepted — implemented (epic #692 Phase 6, PR #699)
- **Date:** 2026-06-19
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-BANK-027 · REQ-BANK-028 · REQ-BANK-008 · REQ-BANK-021 · REQ-BANK-022 · ADR-0011 · ADR-0020 · ADR-0026 · issue #692 · #699
- **Amended:** 2026-06-22 — special-account (Sonderkonto) view for Bereich/OL + active-only listing (REQ-BANK-028, see the Amendment section below)

## Context

The bank is **org-unit-blind** (REQ-BANK-008, ADR-0011): `BankSecurityService` and the ledger decide
access only from the two bank roles and `bank_account_grant`, pinned by ArchUnit
(`bankClassesMustNotConsultOrgUnitScope`). ADR-0020 added the *only* sanctioned bridge,
`OrgUnitBankAccessService` (a deliberately non-`Bank*` name; a second ArchUnit pin
`orgUnitAwareBankSeamIsContainedToOneClass` keeps it the sole bridge), through which officers/leads see
their org unit's balance and file confirm-before-post booking requests (REQ-BANK-021/022). The
restructure (epic #692, Q4) needs **Bereich members to reach their Bereich's `AREA` account and the OL
the `CARTEL`/Kartell account** with that same non-bank-staff function, plus **view-only drill-down** into
subordinate accounts. `BankAccountType` already has `ORG_UNIT`, `AREA` (free-form `areaName`, no FK),
`CARTEL` (singleton). The ORG_UNIT drill-down already cascades for free once the oversight scope expands
(ADR-0026).

## Decision

We will **link `AREA` accounts to a Bereich and `CARTEL` to the OL**, and extend **only**
`OrgUnitBankAccessService`:

- **View cascades down:** `listOverseenBalances()` returns the caller's own-level account **and** all
  subordinate accounts in their oversight scope (Bereichsleitung → its `AREA` account + all child
  Staffel/SK `ORG_UNIT` accounts; OL → `CARTEL` + all `AREA` + all `ORG_UNIT` accounts).
- **Requests are own-level only (Q4 asymmetry):** `createBookingRequest()` is permitted only on the
  caller's own-level account (officer → squadron `ORG_UNIT`; Bereich → `AREA`; OL → `CARTEL`).
  Subordinate accounts reached by drill-down are **view-only** — no request.
- `BankSecurityService`, `BankLedgerService`, the ledger and the grant model stay **untouched and
  org-unit-blind**; both ArchUnit pins stay green. The confirm-before-post flow, overdraft/holder checks
  and `ACCOUNT_GRANT` notifications are unchanged.
- The officer flow from epic #666 is preserved exactly (no regression).

## Consequences

- Bereich/OL get balance view + own-level requests + subordinate-account drill-down with **zero**
  bank-domain change; all new logic lives in the one auditable seam.
- The view⊇request asymmetry is explicit and deliberate (money is sensitive; only the owning level may
  initiate a request on an account).
- Cost: the seam learns to match `AREA`/`CARTEL` accounts by the caller's Bereich/OL membership in
  addition to the existing `ORG_UNIT`-by-oversight match. **As implemented** the link reuses the
  existing `bank_account.org_unit_id` FK (Bereich/OL are `org_unit` rows now), so `V168` only relaxes
  the `chk_bank_account_owner_ref` CHECK — **no new column and no backfill**, the legacy `areaName`
  form stays valid during the soak, and the existing `uq_bank_account_org_unit` index gives the
  one-account-per-Bereich/OL cardinality for free. The view⊇request split is realised as two
  `OwnerScopeService` scopes (cascading `currentOversightScope` vs own-level
  `currentOwnLevelOversightScope`), surfaced to the UI via a `canRequest` flag on the balance DTO.
- Soak limitation — reconciliation is a **manual operator step, not a migration**. V168 does **no**
  automatic backfill: in the general case there is no reliable `areaName`→Bereich mapping, and
  `area_name` is `updatable = false` with no app write path that sets `org_unit_id` on an existing
  account. A legacy `areaName`-only `AREA` account (`org_unit_id = NULL`) is therefore invisible to the
  Bereich cascade and can coexist undetected with an FK-linked account for the same conceptual Bereich
  (the partial unique index does not constrain a `NULL org_unit_id`). The prod soak check found one such
  `AREA` account (`area_name = 'Profit'`) and one unlinked singleton `CARTEL` — but **neither target
  org_unit exists in prod yet**: there is no `ORGANISATIONSLEITUNG` row and no Bereich named `Profit`,
  because the new hierarchy tier (REQ-ORG-014) has not been populated in prod. A Flyway migration is
  therefore unsuitable — it runs exactly once, would no-op against the absent targets at deploy time,
  and never re-fire. Reconciliation belongs to the **Phase 7 (#700) soak cleanup** and is operator-run
  once the hierarchy exists: create the OL + Bereich rows (admin UI), then link the `AREA` account to
  its Bereich (clearing `area_name` in the same statement, CHECK-safe) and the `CARTEL` to the OL via a
  one-off `UPDATE`. Until then both accounts stay bank-staff-only — no regression.

## Alternatives considered

- **Allow requests on subordinate accounts too** — rejected by Q4: drill-down into a subordinate account
  is view-only; only the owning level initiates requests on it.
- **Keep `AREA` free-form `areaName`** — rejected: duplicate/typo AREA accounts per Bereich are a silent
  data-corruption risk; a Bereich FK fixes cardinality (a unique index + autocomplete is the fallback if
  the FK is deferred).
- **Put the admin-drill-down logic in a `Bank*` class** — rejected: it would violate
  `bankClassesMustNotConsultOrgUnitScope`; any new bridge must be non-`Bank*` and added to the
  containment pin.

## Amendment (REQ-BANK-028) — special-account view for Bereich/OL & active-only listing

- **Status:** Accepted — implemented
- **Date:** 2026-06-22

Two owner-approved refinements of the org-unit bank page, both realised **inside the same seam** so
the org-unit-blindness of the bank and both ArchUnit pins are untouched:

- **Bereich/OL (and admin) additionally see the cartel-wide `SPECIAL` accounts (Sonderkonten),
  view-only.** Special accounts belong to no org unit, so they cannot be reached by the oversight
  cascade; the seam admits them **by type** for callers who hold a Bereich-/OL-level oversight seat,
  decided by a new membership-only predicate `OwnerScopeService.currentUserHasAreaOrOlOversight()`
  (admin short-circuits to `true`; SK-leads and officers do **not** qualify — they oversee only their
  own unit's account). The accounts are strictly view-only: `canRequest` stays `false` and the
  own-level F2 create path rejects them (no owning org unit to scope), so this widens the *view* only
  and adds no booking capability. The balance DTO's org-unit fields become nullable and it gains the
  account `type` so the UI can label a Sonderkonto that carries no org-unit identity.
- **The page lists only `ACTIVE` accounts.** `listOverseenOrgUnitBalances()` filters out `CLOSED`
  accounts (org-unit and special alike), so the org-unit bank page always shows live accounts. The
  bank-staff surface and the closing rules are unchanged.

Consequence: zero bank-domain change again — the new predicate lives in `OwnerScopeService` (a
non-`Bank*` class, already the seam's collaborator) and the seam consumes it; `BankSecurityService`,
the ledger and the grant model stay org-unit-blind. The asymmetry from the base decision holds: the
special-account view, like the subordinate drill-down, is view-only.

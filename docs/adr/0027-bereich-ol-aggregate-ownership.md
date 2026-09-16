# ADR-0027 — Bereich and Organisationsleitung as direct owners of org-unit-scoped aggregates

- **Status:** Accepted — implemented in Phase 4 (#697). See *Implementation note*.
- **Date:** 2026-06-19
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-ORG-016 · REQ-ORG-004 (amended) · REQ-ORG-009 · REQ-ORG-011 (#701) · ADR-0025 · ADR-0026 · issue #692 · #697

## Context

The owner decided (epic #692, Q2) that a Bereich and the OL **own their own data** — their own Lager
(inventory), hangar, missions, operations, job orders and refinery orders — *in addition* to overseeing
their subordinate units, and that leadership can **create on behalf of** a subordinate Staffel/SK (e.g.
a Bereichsleitung raising a job order or refinery order "for a squadron in their Bereich"; the OL doing
so anywhere). Today an aggregate's `owning_org_unit_id` references a Squadron or SK, or is `NULL` — the
ownerless "Bereichsleitung" leadership path (REQ-ORG-009), which is visible to members-or-above. Create-
time stamping flows through `resolveOrgUnitForPickerOutput[ Nullable]`, validated against the creator's
**direct** memberships.

## Decision

We will allow `owning_org_unit_id` to reference a `BEREICH` or `ORGANISATIONSLEITUNG` org_unit, and:

- **Stamping validation widens** to `(direct memberships ∪ oversight descendants)` — a Bereichsleitung
  may stamp its own Bereich or any descendant Staffel/SK; the OL may stamp anything. **Auto-stamp stays
  on a single DIRECT membership** (a leader's default owner = their own Bereich/OL; >1 direct forces an
  explicit pick; a foreign/out-of-oversight pick is a 400).
- **Create-on-behalf is authorised by `canEditOrgUnit(target)`** (which cascades after ADR-0026), not by
  admin-ness. Every create/update path that accepts a target owning-org-unit (incl.
  `JobOrderService.resolveResponsibleOrgUnit`) gains this scope gate.
- **Visibility reuses the existing rules unchanged** — a Bereich/OL-owned row is seen by that level's
  leadership and (per the predicate) descendants in scope, with the public/internal escape intact;
  strict silo (ADR-0026) means a squadron member does not see the Bereich/OL above them.
- **The ownerless `NULL` path (REQ-ORG-009) is preserved unchanged** — no backfill; it keeps its
  "org-wide, visible to members-or-above" semantics and remains available for genuinely org-wide work.
- **Promotion stays Squadron-only** (`PromotionTopic.owningSquadron` stays typed `Squadron`); Bereich/OL
  never own promotion data.
- **The #701 `isCurrentUserOwner` bypass (REQ-ORG-011) stays ahead of the scope check** for personal
  aggregates, so a per-user owner keeps see/edit on their own row regardless of the new owning-kind.

## Implementation note (Phase 4, #697)

The decision holds; the change was smaller than anticipated because Phase 1 (owning columns already
accept any kind) and Phase 3 (the cascade) did most of the work. The entire delivery is **one method**,
`OwnerScopeService.resolveStampedOrgUnit`:

- **Validation widening** — an explicit pick is accepted when it is a DIRECT membership of the target
  user **or** `canEditOrgUnit(pick)` for the current **caller** (the create-on-behalf gate, cascade-aware
  since Phase 3). Auto-stamp / `>1 → force a choice` stay keyed on the target's DIRECT memberships.
  Because the gate keys `canEditOrgUnit` on the caller while the membership set is the target user's,
  the two coincide for every **self-service** create (caller = target) — ordinary-member and officer
  stamping is byte-identical there — and diverge only on the two **create-on-behalf** paths where
  caller ≠ target (inventory book-out/transfer, refinery store): there the accepted set is `(target's
  memberships) ∪ (caller's editable scope)` by design, letting a leader place the recipient's row in any
  unit the leader already controls. This never widens what the caller can see (`canEditOrgUnit` only
  admits units already in the caller's scope), and the REQ-ORG-011 owner-escape keeps the recipient's own
  visibility of the row.
- **Resolution** — a non-Squadron / non-SK id falls through to the polymorphic `OrgUnitRepository` and is
  accepted iff it resolves to a `BEREICH` / `ORGANISATIONSLEITUNG` row.

No per-aggregate change was needed: all five aggregates (`Ship`, `InventoryItem`, `RefineryOrder`,
`Mission`, `Operation`) already stamp through the shared resolver for authenticated callers and their
`owningOrgUnit` field is typed `OrgUnit`. The legacy `owningSquadron` mirror columns were already dropped,
so a `BEREICH` / `OL` owner is just a polymorphic FK with no dual-write to break. **Refinement to the
decision:** the
`canEditOrgUnit` gate is **not** added to the create-time `JobOrderService.resolveResponsibleOrgUnit` /
`resolveRequestingOrgUnit` — those intentionally accept any profit-eligible / any org unit so the
shared-SK-queue intake (#343) is not regressed; the nuanced, already-cascade-aware
`reassignResponsibleOrgUnit` gate remains the only scope check on the responsible field. REQ-ORG-016 was
amended to record this.

## Consequences

- Leadership gets first-class own data and a clean "create for a subordinate" flow, gated by the same
  cascade as their read/edit reach.
- The stamping matrix gains one owning-kind case; the picker offers own-level + descendants for leaders.
- Cost: the legacy `owning_squadron_id` mirror (sync trigger / any residual NOT NULL) must tolerate a
  Bereich/OL-owned row having **no** squadron mirror — an additive relax in the same migration family.

## Alternatives considered

- **Oversight-only (no Bereich/OL-owned data)** — rejected by Q2 (the owner explicitly described "das
  Lager der Bereichsleitung").
- **Only inventory is Bereich/OL-owned** — rejected: the owner chose the full set, not just Lager.
- **Reuse the ownerless `NULL` owner for all leadership data** — rejected: `NULL` means "org-wide,
  visible to all members-or-above", which violates the strict Bereich silo; a Bereich's own data must be
  scoped to that Bereich, which needs a concrete owner.

# ADR-0025 — Org hierarchy data model: Bereich + Organisationsleitung as org_unit kinds with a parent FK

- **Status:** Accepted — implemented (epic #692)
- **Date:** 2026-06-19
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-ORG-014 · REQ-ORG-017 · ADR-0026 · ADR-0027 · issue #692 · #694

## Context

The Kartell is a three-tier organisation — **Organisationsleitung (OL) > Bereich (area, e.g.
Profit / Sub-Radar / Raumüberlegenheit) > Staffel + Spezialkommando** — but the data model is flat.
The `org_unit` table holds two kinds via single-table inheritance (`SQUADRON`, `SPECIAL_COMMAND`,
abstract `OrgUnit` base) with **no parent/child link**. Every scope decision in the app radiates from
one seam — `OwnerScopeService.currentScopePredicate()` → `ScopePredicate(adminAllScope,
activeOrgUnitId, memberOrgUnitIds)` — which `ScopePredicate.permits()` and every list-query JPQL
fragment (`owning_org_unit_id IN :memberOrgUnitIds`) read identically. Membership is already
many-to-many via `org_unit_membership`. We must represent two new levels **and** a parent relationship
so the cascade (ADR-0026) and Bereich/OL ownership (ADR-0027) can build on the existing seam rather
than forking it.

## Decision

We will **extend the existing single-table `org_unit`** with two new `OrgUnitKind` values, `BEREICH`
and `ORGANISATIONSLEITUNG`, plus a **nullable self-referential `parent_org_unit_id` FK**, and keep a
**fixed three-level depth** (OL → Bereich → Staffel/SK). Concretely:

- New JPA `@DiscriminatorValue` subclasses `Bereich` and `Organisationsleitung`, with promotion
  permanently disabled (like `SpecialCommand`).
- `parent_org_unit_id` is nullable and additive; a Staffel/SK's parent (if set) must be a `BEREICH`, a
  Bereich's parent the `ORGANISATIONSLEITUNG`, and `ORGANISATIONSLEITUNG` has no parent — enforced by a
  CHECK that pins the parent kind by the child kind, which also makes cycles impossible at fixed depth.
- `org_unit_membership` carries the new level memberships unchanged; new leadership flags ride on it
  (REQ-ORG-017, ADR-0026).
- Roll out additively (mirror the V97–V100 dual-write/soak pattern): the parent column is NULL until an
  admin assigns Bereiche, and the descent helper treats a NULL parent as "no ancestor expansion", so
  the system degrades to today's flat behaviour while the hierarchy is being populated.

## Consequences

- **One scope seam serves all four levels.** `ScopePredicate`, the `IN :memberOrgUnitIds` JPQL, the
  pin/relay/picker, and the membership table are reused; the cascade (ADR-0026) is the only new logic.
- The discriminator/enum/CHECK trio must stay in lockstep when adding the kinds (a known cost of
  single-table inheritance, already true for SQUADRON/SPECIAL_COMMAND).
- Service code that does `instanceof Squadron`/`SpecialCommand` or casts an `OrgUnit` must be audited to
  use `getKind()`/abstract methods so the new subclasses don't trip it.
- `chk_org_unit_promotion_only_squadron` widens to keep promotion FALSE for the two new kinds (Bereich
  and OL never promote, like SK).
- Fixed depth is a deliberate ceiling; deeper nesting would need a closure table — out of scope for v1.

## Alternatives considered

- **Separate `bereich` / `organisationsleitung` tables with FKs from `org_unit`** — rejected: creates a
  second scope-enforcement path and duplicates the membership/pin/picker machinery the single-table
  approach reuses for free.
- **Arbitrary nesting via a closure table** (`org_unit_closure(ancestor, descendant, depth)`) — rejected
  for v1: the org is fixed three-level; a parent FK + a bounded descent query is simpler and the closure
  table can be introduced later if the descent ever needs to be deep or hot.
- **Bereich as a free-form tag/attribute** (as the bank's `AREA.areaName` is today) — rejected: a tag
  cannot own aggregates (ADR-0027) or anchor a cascade; the free-form `areaName` is exactly the
  cardinality/typo weakness ADR-0028 removes.

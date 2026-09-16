# ADR-0026 — Cascading org-unit scope without admin rights, computed in one descent helper

- **Status:** Accepted — implemented. Phase 3 (#696) delivered the aggregate scope + authorities;
  the `currentOversightScope()` half landed in Phase 6 (#699). See *Implementation note*.
- **Date:** 2026-06-19
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-ORG-015 · REQ-SEC-015 · ADR-0025 · REQ-ORG-011 (#701) · ADR-0024 (#702) · issue #692 · #696

## Context

Authorisation cascades today: `ADMIN > OFFICER > LOGISTICIAN/MISSION_MANAGER`, with an officer reaching
their one Staffel and an SK-lead their SK via `OwnerScopeService.currentOversightScope()`, and
an admin reaching everything via the `adminAllScope=true` branch. The restructure (ADR-0025) needs the
**Bereichsleitung** (Bereichsleiter/-koordinator/-operator) to reach **all Staffeln + SKs of their
Bereich**, and the **OL** to reach **everything** — but with **no admin rights**: `isAdmin()` unlocks
ADMIN-only carve-outs (SK lifecycle, system settings, stammdaten, the promotion-topic guards). The scope
seam computes `memberOrgUnitIds` as a flat read of `org_unit_membership`. Two recently-merged PRs add
branches the cascade must not break: #701 (`REQ-ORG-011`) runs an `isCurrentUserOwner(owner)` bypass
*before* the scope check on personal aggregates; #702 (`ADR-0024`) adds a consented global-blueprint
union into the oversight/coverage path.

## Decision

We will compute the cascade in **exactly one** private helper,
`OwnerScopeService.expandWithDescendants(...)`, consumed by **both** `currentMemberOrgUnitIds()` and
`currentOversightScope()`. For a `BEREICH`-leadership membership it unions in all
`SQUADRON`+`SPECIAL_COMMAND` descendants of that Bereich; for an `is_ol_member` membership it unions in
everything (all Bereiche + their descendants + the OL's own id). The result is a **concrete
`memberOrgUnitIds` union** — the cascade **must never** route through the `adminAllScope=true` branch,
and an OL/Bereich principal **must never satisfy `isAdmin()`**. `ScopePredicate.permits()` and the
`IN :memberOrgUnitIds` JPQL are kind-agnostic, so feeding them the expanded set cascades lists **and**
per-row gates together with no per-query edits.

Further:

- **Strict silo:** a Bereichsleitung's expanded union contains only its own Bereich's descendants; only
  the OL crosses Bereiche. Cross-Bereich access is impossible by construction.
- **SK-lead is not expanded:** an SK-lead keeps SK-only reach (Q1); their Bereichsleitung membership is
  organisational only.
- **Authorities:** the JWT converter mints, for each Bereich/OL leadership membership, the flat
  `ROLE_LOGISTICIAN`/`ROLE_MISSION_MANAGER` (so `hasRole(...)` menu gates work) **and** a contextual
  `OrgUnitContextualAuthority(role, descendantId)` per descendant (so existing
  `@PreAuthorize("@ownerScopeService.hasRoleInOrgUnit(#id, '…')")` resolves without SpEL rewrites).
- **Stamping stays DIRECT:** `collectMemberOrgUnitIds()` (create-time auto-stamp) is *not* expanded;
  create-on-behalf is handled separately (ADR-0027).
- **Composition:** the cascade only widens the *scope* branch; the #701 `isCurrentUserOwner` bypass stays
  ahead of it, and the #702 global-blueprint union in the overview/coverage services is preserved.

## Implementation note (Phase 3, #696)

The decision holds; three refinements landed during implementation:

- **The helper lives in a dedicated `OrgUnitCascadeService`**, not as a private method on
  `OwnerScopeService`. It exposes `expandWithDescendants(memberships)` (full reach = direct ids ∪
  cascade) for the scope path and `cascadedOfficerReach(memberships)` (leadership-only reach) for the
  authority path, so `OwnerScopeService` and `CustomJwtGrantedAuthoritiesConverter` share one
  independently-tested definition. Routing the cascade through an injected collaborator (rather than a
  body change) also let every pre-#692 `OwnerScopeServiceTest` scenario stay green with a single
  identity-default stub — concrete evidence of the zero-regression property.
- **`currentOversightScope()` is NOT cascaded yet.** That method is shared by the bank seam
  (`OrgUnitBankAccessService`), where Q4 (REQ-BANK-027) requires the balance **view** to cascade but
  deposit/withdrawal **requests** to stay own-level. Widening it atomically with that read/write split
  belongs to the bank phase (#699), so Phase 3 wires the cascade into `currentMemberOrgUnitIds()` only
  (aggregate lists + per-row gates + the Job-Order profit gate) plus the authority minting. A
  Bereichsleitung/OL therefore reaches descendant **aggregates** but not yet the descendant
  **blueprint-availability overview** — strictly fail-closed.
- **`cascadedOfficerReach(...)` is memoised per request.** Both consumers run for the same
  authenticated principal in a request (the converter at authentication time, `OwnerScopeService` at
  query time), so the leadership reach is cached on the bound `HttpServletRequest` — keyed by the
  membership-id set — to collapse the two otherwise-identical hierarchy reads (`findAllOrgUnitIds()`
  for OL, `findChildOrgUnitIds(...)` per Bereich seat) into one. The cache is transparent (same
  inputs ⇒ same set, defensive copies handed out, direct computation when no request is bound), so
  the helper's pure-function contract is unchanged. This is the cheap first answer to the OL-union
  cost flagged under *Consequences*; the closure-table swap stays available if the `IN` list itself
  bites.

## Implementation note (Phase 6, #699)

The deferred half landed with the bank phase: `OwnerScopeService.currentOversightScope()` now unions
in `cascadedOfficerReach(...)`, so a Bereichsleitung/OL's **view** cascades down to descendant
accounts and the blueprint-availability overview, while the new **own-level** companion
`currentOwnLevelOversightScope()` keeps deposit/withdrawal **requests** at the caller's own level
(the Q4 read/write split, REQ-BANK-022). The whole cascade decision is therefore fully implemented.

## Consequences

- Lists and detail gates can never diverge — they read the same expanded set through one helper.
- Bereich/OL get officer-equivalent reach while every `hasRole('ADMIN')` carve-out stays locked.
- Cost: an OL union is potentially *all* units — benchmark the `IN :memberOrgUnitIds` list queries on a
  prod-like snapshot; if it bites, swap to a closure-table JOIN / materialised path (the helper is the
  single place to change). Per-descendant contextual-authority fan-out is the chosen trade-off over a
  parent-walk at gate time (which would add a DB read per gate and break the pure value-equality match).

## Alternatives considered

- **Model OL via `adminAllScope=true`** — rejected: it would make OL satisfy `isAdmin()` and silently
  unlock every ADMIN-only carve-out — the exact privilege escalation the restructure forbids.
- **Inline the descent into each repository query / per-row gate** — rejected: scatters the security-
  critical logic, and risks list-vs-detail divergence; the one-helper rule makes the cascade auditable.
- **Contextual authority at the Bereich id + parent-walk at evaluation time** — rejected for v1: adds a
  DB read per `hasRoleInOrgUnit` call and breaks its current pure value-equality match; pre-expanded
  authorities keep the gate cheap.

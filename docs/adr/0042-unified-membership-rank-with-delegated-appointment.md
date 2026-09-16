# ADR-0042 — Unified membership rank with a delegated appointment ladder

- **Status:** Accepted
- **Date:** 2026-06-23
- **Deciders:** @greluc
- **Related:** spec [`role-model.md`](../specs/role-model.md) (REQ-ROLE-001..006) · qualifies [ADR-0026](0026-cascading-scope-without-admin.md), [ADR-0027](0027-bereich-ol-aggregate-ownership.md), [ADR-0029](0029-org-chart-visibility-decoupled-from-profit-eligibility.md) · epic #800

## Context

Squadron leadership was a single flat Keycloak `OFFICER` role; there was no way to model the real
structure (Staffelleiter, up to four Kommandoleiter each leading a Kommandogruppe with an optional
deputy, up to four Ensigns) or to delegate its administration. Area leadership and OL membership were
already modelled, but as **five mutually-exclusive boolean flags** on `org_unit_membership`
(`is_lead`, `is_bereichsleiter`, `is_bereichskoordinator`, `is_bereichsoperator`, `is_ol_member`) —
an unscalable "flag soup" that allows illegal combinations and has no room for squadron ranks.
Assignment was ADMIN-only everywhere (no delegation), and the org chart and the functional flags
could drift. These ranks must also become consumable by other features — the first being bank-account
visibility / booking-request permissions, in a later session.

## Decision

1. **One rank enum, kind-scoped.** A single `@Enumerated(STRING)` `role` column (`MembershipRole`)
   on `org_unit_membership` replaces the five booleans (`MEMBER`, the four squadron ranks, the three
   area ranks, `OL_MEMBER`, `SK_LEAD`), confined to the matching `OrgUnitKind` by a DB CHECK.
   `is_logistician` / `is_mission_manager` stay **orthogonal** capability flags (a member can be a
   logistician without a rank). Migrated additively (V184 backfill; booleans kept during a soak,
   dropped in the Phase-5 cleanup) so every existing area/OL/SK grant is behaviour-identical.
2. **Baseline grant.** Squadron leadership ranks mint own-squadron contextual
   `LOGISTICIAN`/`MISSION_MANAGER` exactly as `SK_LEAD` does for an SK — own-unit only, no cascade,
   no flat `ROLE_OFFICER`, no promotion. Per-rank / per-feature differences are deferred.
3. **Kommandogruppe as a first-class entity** (`kommando_group`, ≤4 per squadron); a member's
   `kommando_group_id` is required for Kommandoleiter / stellv. and optional for Ensign.
4. **Delegated appointment ladder, no self-promotion.** Admin → OL members; OL → Bereichsleiter;
   Bereichsleiter → Staffelleiter/SK-Lead/Koordinator/Operator (own Bereich); Staffelleiter →
   KL/stellv./Ensign/Kommandogruppen (own squadron). To grant a rank you must hold a strictly-higher
   rank, so no tier can clone itself — generalising the "`is_lead` toggle is ADMIN-only" guard.
5. **Roles are the source of truth; the org chart mirrors** account-linked seats and still grants
   nothing (REQ-ORG-010); free-text holders stay chart-only.
6. **Bereichsleiter auto-OL membership is organisational only** — rights stay Bereich + children
   (strict silo from epic #692); only pure `OL_MEMBER` reaches org-wide.

## Consequences

- The rank read replaces five boolean reads in `confersFlatOfficerRole`, `OrgUnitCascadeService`,
  `OwnerScopeService.isOversightSeat`/`isAreaOrOlSeat`, the per-row contextual loop, and the V164/V165
  triggers — a wide but mechanical surface. Behaviour-equivalence for the shipped ranks is the
  migration's burden of proof (`MembershipRoleMigrationEquivalenceTest`).
- **Qualifies ADR-0026:** `cascadedOfficerReach` is preserved — squadron ranks add own-unit reach via
  the per-row loop (like `is_lead`), NOT via the cascade, and a Bereichsleiter-in-OL does not widen the
  cascade. The hard invariant (officer-equivalent, never admin; cascade ignores the SecurityContext)
  holds; the three bank/cascade ArchUnit pins stay green by construction.
- **Qualifies ADR-0027:** the create-on-behalf `canEditOrgUnit` gate is the basis for the
  appointment-target scope check.
- **Qualifies ADR-0029:** the chart stays descriptive and grants nothing; it now mirrors account-linked
  rank seats.
- Per-rank / per-feature differentiation (including bank) is explicitly deferred; the bank seam is
  untouched.

## Alternatives considered

- **Keep adding booleans** — rejected: flag soup, allows illegal combinations, no single "one rank per
  seat" truth.
- **A second enum just for squadron ranks** — rejected: two columns to kind-scope, migrate and keep in
  sync.
- **Fold the capability flags into the rank** — rejected: logistician/mission-manager are orthogonal
  to leadership rank.
- **Model Kommandogruppe as chart-only `COMMAND_LEAD` rows** — rejected: it needs a real entity with
  cardinality and optimistic locking that other features can reference.
- **Promote the org chart to grant rights** — rejected: breaks the ArchUnit-pinned grant-nothing
  invariant and would rewire the security cascade onto a descriptive surface.

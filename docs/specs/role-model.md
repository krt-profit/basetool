> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-23.
> **Owner area:** ROLE · **Related ADRs:** ADR-0042 (qualifies ADR-0026, ADR-0027, ADR-0029)

# Functional rank & permission model (squadron / area / OL)

## Context & goal

The single flat Keycloak `OFFICER` role is no longer enough to model squadron leadership. Each
Staffel has exactly one **Staffelleiter**, up to four **Kommandoleiter** (each leading a
Kommandogruppe, optionally with a **stellvertretender Kommandoleiter**), and up to four **Ensigns**
(assigned to a Kommandogruppe or generally to the Staffelleitung). Each Bereich has one
**Bereichsleiter**, plus zero-or-more **Bereichskoordinatoren** and **Bereichsoperatoren**. Members
can belong to the **Organisationsleitung (OL)**; every Bereichsleiter is automatically part of the OL
body.

This spec defines the **functional rank model** — the source of truth for "what may this user do in
this org unit" — so that other features (first the bank, in a later session) can key permissions off
these ranks. It is the anchor for the rank-related amendments in
[`org-unit-tenancy.md`](org-unit-tenancy.md), [`security-and-access.md`](security-and-access.md) and
[`org-chart.md`](org-chart.md), and for the role matrix in
[`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md). Tracked by epic #800, now complete
(Phases 1–3: the additive rank column + Kommandogruppe, the authorisation layer reads the rank with
the squadron-rank baseline grant, and the delegated appointment ladder + Kommandogruppe CRUD + ROLE
audit; Phase 4 mirrors the ranks onto the org chart (REQ-ROLE-006) and adds the delegated Leitung UI;
Phase 5 (V187) drops the five legacy boolean leadership flags so `role` is the sole source of truth).

Every role and membership mutation (assign / change / revoke a rank, grant / revoke a membership,
toggle the Logistician / Mission-Manager capability flags) is recorded in a dedicated **`ROLE`
activity audit log** — the "Rollen & Mitglieder" tab of the unified admin viewer — per
[`audit.md`](audit.md) (REQ-AUDIT-001).

## Requirements

### REQ-ROLE-001 — Unified membership rank enum

A single `@Enumerated(STRING)` `role` column on `org_unit_membership` (`MembershipRole`) is the
functional rank a user holds in an org unit. It supersedes the five mutually-exclusive boolean
leadership flags (`is_lead`, `is_bereichsleiter`, `is_bereichskoordinator`, `is_bereichsoperator`,
`is_ol_member`). Values: `MEMBER` (default), `STAFFELLEITER`, `KOMMANDOLEITER`,
`STELLV_KOMMANDOLEITER`, `ENSIGN`, `BEREICHSLEITER`, `BEREICHSKOORDINATOR`, `BEREICHSOPERATOR`,
`OL_MEMBER`, `SK_LEAD`. `is_logistician` / `is_mission_manager` stay as **orthogonal** capability
flags — they are NOT folded into the rank.

The rank is kind-scoped by the DB CHECK `chk_org_unit_membership_role_kind`: squadron ranks only on
`SQUADRON` rows, area ranks only on `BEREICH`, `OL_MEMBER` only on `ORGANISATIONSLEITUNG`, `SK_LEAD`
only on `SPECIAL_COMMAND`; `MEMBER` on any kind. The additive soak (Phases 1-4) is over: the five
boolean columns were dropped in the Phase-5 cleanup (V187) and `role` is now the **sole source of
truth**. The few wire shapes that still expose a boolean (`OrgUnitMembershipDto.isLead`, the
Bereich/OL appointment responses) derive it from `role` at the controller / mapper boundary.

**Acceptance**

- [x] `org_unit_membership.role` exists, defaults `MEMBER`, and is kind-scoped by CHECK (V184).
- [x] The rank is backfilled 1:1 from the five booleans (mutually exclusive ⇒ unambiguous).
- [x] The authorisation layer reads the rank instead of the booleans, behaviour-identical for every
  existing area/OL/SK row (Phase 2).
- [x] The five boolean columns + their three CHECK constraints are dropped (V187), the
  `enforce_leader_excludes_squadron` trigger is rewritten onto `role` (squadron ranks exempt), and no
  code reads a boolean flag.

**Enforced by:** `OrgHierarchyMigrationTest` (V184 + `v187DropsBooleanFlagsAndConstraints` + `v187LeaderExclusionTriggerReadsRole_squadronRanksExempt`), `MembershipRoleMigrationEquivalenceTest`, `SpecialCommandSecurityServiceTest`, `OrgUnitMembershipServiceTest` · **Code:** `MembershipRole`, `OrgUnitMembership#role`, `CustomJwtGrantedAuthoritiesConverter`, `OrgUnitCascadeService`, `OwnerScopeService`, `SpecialCommandSecurityService`, `OrgHierarchyController`, `OrgUnitMembershipMapper`, `V184__add_org_unit_membership_role_and_backfill.sql`, `V187__drop_org_unit_membership_boolean_flags.sql` · **Decision:** ADR-0042 · **Issues:** #800

### REQ-ROLE-002 — Baseline grant for squadron leadership ranks

Each squadron leadership rank (`STAFFELLEITER` / `KOMMANDOLEITER` / `STELLV_KOMMANDOLEITER` /
`ENSIGN`) confers officer-equivalent reach over its **own squadron only**, by minting contextual
`LOGISTICIAN@<squadronId>` + `MISSION_MANAGER@<squadronId>` exactly as `SK_LEAD` does for an SK. The
JWT authorities converter derives **no** flat `ROLE_OFFICER` from the rank, **no** promotion rights,
and **no** cascade beyond the squadron. Area ranks keep their current cascading reach (Bereich +
children, identical across the three area ranks for now). Per-rank / per-feature differentiation
(including the bank) is deferred to later work.

**"Not `ROLE_OFFICER`" describes what the rank auto-mints, not the roles the person actually
holds.** Squadron leaders **do** hold the `OFFICER` Keycloak realm role — it is granted
**operationally / manually** in Keycloak, exactly like OL / Bereich / SK leads (see the "Operational
OFFICER grant" section of [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)), which is
what lets them pass `OFFICER`-gated surfaces such as the Leitung page. The converter never
*synthesises* that realm role from the functional rank — `OFFICER` stays a separate,
manually-granted authority, enforced by `CustomJwtGrantedAuthoritiesConverterTest`'s
`staffelleiter_getsFlatRolesAndOwnSquadronContextualOnly_noCascade` (which drives a role-less user).
So the realm-role assignment is managed operationally, not derived from — and, per "Out of scope"
below, not retired by — the rank model.

Like `SK_LEAD` and the area/OL ranks, a squadron rank also carries the flat back-compat
`ROLE_LOGISTICIAN` / `ROLE_MISSION_MANAGER` (the `confersFlatOfficerRole` surface) so existing
`hasRole('LOGISTICIAN')` gates keep working. Every such gate is **owner-scoped**, so the effective
reach stays own-squadron: the two previously-unscoped per-user refinery endpoints (`GET` / `POST
/api/v1/refinery-orders/users/{userId}`) were scoped with `@ownerScopeService.canViewUserRefineryOrders`
/ `canManageUserRefineryOrders` (PR #808 security review) so the flat role can no longer act org-wide
there.

The `canView/canManageUserRefineryOrders` gate is a **coarse user-level pre-check**: it passes when
the caller shares *any one* of the target user's org units (`anyMatch`). Because a member may belong
to up to two Staffeln (REQ-ORG-017), the gate alone does **not** bound which of the target's rows the
caller sees — so it must be paired with per-row org-unit filtering. **Read path (finding SEC-01):**
`GET /users/{userId}` lists through the strict-staffel scoped query `findByOwnerIdScoped`
(`getUserRefineryOrdersScoped`), so a logistician sharing only one of a multi-Staffel target's units
never reads the target's orders stamped to the other, foreign unit — the list can never return a row
the per-order `canSeeRefineryOrder` gate would individually deny. **Write path:** `POST
/users/{userId}` constrains the new order's `owningOrgUnit` through `resolveStampedOrgUnit` (the stamp
must be a membership of the target *or* a unit the caller may edit), which is not a disclosure (a row
stamped outside the caller's scope is one the caller cannot read back).

**Acceptance**

- [x] A squadron leadership rank mints own-squadron logistician + mission-manager authorities (plus
  the flat back-compat roles, exactly as `SK_LEAD`); area/OL/SK grants are byte-identical to before
  the migration (Phase 2).
- [x] The flat `ROLE_LOGISTICIAN` does not grant org-wide reach: the per-user refinery endpoints are
  owner-scoped to the caller's strict org-unit scope (admin / self / a unit the target user belongs
  to), for every oversight rank — squadron ranks included.
- [x] The `GET /users/{userId}` list is filtered **per row** to the caller's org-unit scope via
  `findByOwnerIdScoped`, so a caller sharing only one of a multi-Staffel target's units cannot read
  the target's orders stamped to the other unit (finding SEC-01) — the coarse `anyMatch` gate is not
  relied on for per-row isolation.

**Enforced by:** `CustomJwtGrantedAuthoritiesConverterTest` (incl. `staffelleiter_getsFlatRolesAndOwnSquadronContextualOnly_noCascade`), `OwnerScopeServiceTest` (`CanActOnUserRefineryOrdersTests`), `RefineryOrderServiceLifecycleTest` (`getUserRefineryOrdersScoped_*`), `RefineryOrderRepositoryScopedTest`, `MembershipRoleMigrationEquivalenceTest` · **Code:** `CustomJwtGrantedAuthoritiesConverter`, `OrgUnitCascadeService`, `OwnerScopeService#canViewUserRefineryOrders` / `#canManageUserRefineryOrders`, `RefineryOrderService#getUserRefineryOrdersScoped`, `RefineryOrderRepository#findByOwnerIdScoped`, `RefineryOrderController` · **Decision:** ADR-0042 · **Issues:** #800 · **Security:** SEC-01

### REQ-ROLE-003 — Kommandogruppe entity

A Kommandogruppe is a first-class named sub-structure of a Staffel (`kommando_group`: squadron
`org_unit_id` FK, name, sort index, `@Version`); at most four per squadron. A membership with role
`KOMMANDOLEITER` / `STELLV_KOMMANDOLEITER` / `ENSIGN` carries a nullable `kommando_group_id`:
`KOMMANDOLEITER` / `STELLV_KOMMANDOLEITER` MUST reference a group; `ENSIGN` MAY (null = "allgemein
der Staffelleitung"); every other rank must have a null group. A group's parent must be a `SQUADRON`.

**Acceptance**

- [x] `kommando_group` exists, caps at four per squadron, and rejects a non-`SQUADRON` parent (V185).
- [x] `chk_org_unit_membership_kommando_group_role` confines `kommando_group_id` to the in-group
  squadron ranks (V185).
- [x] Kommandogruppe CRUD + member assignment honour ≤1 KL and ≤1 stellv. per group and ≤4 Ensigns
  per squadron (Phase 3).
- [x] The three singleton caps (≤1 Staffelleiter/squadron, ≤1 KL + ≤1 stellv./group) are
  DB-backstopped by partial unique indexes (V188), so a concurrent double-assign fails on the
  constraint rather than committing a duplicate; the ≤4 Ensign cap stays service-layer-only (a count,
  like the org chart's own ≤4 ENSIGN cap).

**Enforced by:** `OrgHierarchyMigrationTest` (V185, `v188SquadronRankSingletonIndexes`), `KommandoGroupServiceTest`, `OrgUnitMembershipServiceTest` (squadron-rank cardinality) · **Code:** `KommandoGroup`, `KommandoGroupRepository`, `KommandoGroupService`, `KommandoGroupController`, `OrgUnitMembershipService#assignSquadronRank`, `SquadronRoleController`, `V185__create_kommando_group.sql`, `V188__squadron_rank_singleton_indexes.sql` · **Decision:** ADR-0042 · **Issues:** #800

### REQ-ROLE-004 — Delegated appointment ladder, no self-promotion

Rank assignment is delegated down a strict ladder, with admin able to do everything:

- Admin → OL members (pure `OL_MEMBER`);
- OL members → Bereichsleiter;
- Bereichsleiter → Staffelleiter + SK-Lead + Bereichskoordinator + Bereichsoperator, for units in
  their own Bereich;
- Staffelleiter → Kommandoleiter + stellv. Kommandoleiter + Ensign + Kommandogruppen, for their own
  squadron.

Invariant: a leader can **never** assign the rank they themselves hold at that level (to grant rank
R you must hold a strictly-higher rank, which you cannot grant to yourself) — preserving the
"`is_lead` toggle is ADMIN-only" no-self-escalation property at every tier. The delegated verdict is
computed from the caller's own membership ranks only (never from the admin-pin header, contextual
authorities, or `isAdmin()` inside the verdict); admin short-circuits only at the `@PreAuthorize`
layer.

The frontend Leitung page (`/organisation/leitung`) and its write proxies are role-gated to
`ADMIN` / `OFFICER` only (`Roles.ADMIN_OR_OFFICER`). Every functional leader carries the operative
`OFFICER` grant (see the "Operational OFFICER grant" section of
[`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)), so the coarse page gate never denies
a delegated leader; `LOGISTICIAN` / `MISSION_MANAGER` are deliberately **not** accepted, so a
capability-only holder with no appointment reach (empty view) cannot open the surface. The per-unit
appointment authority remains the delegated verdict above, re-checked on every backend write.

**Acceptance**

- [x] Each tier can appoint exactly the rung below it, within its own scope; a foreign-unit or
  same-tier appointment is denied; the verdict never satisfies `isAdmin()` (Phase 3).

**Enforced by:** `OrgRoleManagementSecurityServiceTest`, `DelegatedAppointmentControllerSecurityTest`, `ArchitectureTest` (`delegatedRoleAuthoriserMustNotConsultOwnerScope`) · **Code:** `OrgRoleManagementSecurityService`, `SquadronRoleController`, `KommandoGroupController`, `OrgHierarchyController` (Bereich gates), `SpecialCommandMembershipController` (SK-lead gate) · **Decision:** ADR-0042 · **Issues:** #800

### REQ-ROLE-005 — Bereichsleiter auto-OL membership is organisational only

A Bereichsleiter is part of the OL body (appears in OL contexts and counts for OL governance /
appointments), but their **rights stay Bereich + children** (the strict-silo decision from epic #692,
REQ-ORG-015). Only a **pure** `OL_MEMBER` row confers org-wide reach. `OrgUnitCascadeService` is NOT
widened for a Bereichsleiter.

**Acceptance**

- [x] A Bereichsleiter's reach is its Bereich + children, never org-wide; a pure OL member reaches
  every org unit (Phase 2).

**Enforced by:** `OrgUnitCascadeServiceTest`, `OwnerScopeServiceTest` · **Code:** `OrgUnitCascadeService` · **Decision:** ADR-0042 · **Issues:** #800

### REQ-ROLE-006 — Roles are the source of truth; the org chart mirrors

The functional rank on `org_unit_membership` is the source of truth. The org chart
(`org_chart_position`) only **mirrors** account-linked seats descriptively and grants nothing
(REQ-ORG-010); free-text / account-less chart holders stay chart-only. Account-linked chart seats are
derived from the ranks; the authority cascade never reads the chart.

The mirror is written in the same transaction as the rank change, by `OrgChartService.mirror*`
called from the appointment flow (never by giving the chart scope awareness): the flat seats
(Bereichsleiter / -koordinator / -operator, OL member, SK-Leiter, Staffelleiter) map 1:1 onto a
chart position keyed by org unit (singletons are reassigned, not duplicated, so the partial unique
indexes hold), while the in-Kommando ranks project onto the Kommando sub-tree — a `COMMAND_LEAD`
node tied to its Kommandogruppe via the V186 `kommando_group_id` link carries the Kommandoleiter, and
the stellv. Kommandoleiter / Ensigns hang off it. A Kommandogruppe create / rename / delete mirrors
the leaderless node, and revoking a rank vacates a led Kommando (keeping the node, REQ-ORG-011) or
removes the other seats. Legacy admin-authored Kommandos (no `kommando_group_id`) stay chart-only.

The mirror is the **single writer** of account-linked chart seats: the chart editor itself is
account-free — its write API rejects setting an account holder, and editing / vacating / deleting a
mirror-managed seat (account-held or `kommando_group`-linked), with
`problem.org_chart.account_managed_in_leitung`. So an admin can no longer manually set an
account-linked seat on the chart (no drift); only free-text holders stay editable there
(org-chart.md REQ-ORG-010 / -011 / -020 amendments). A `kommando_group`-linked Kommando is read-only
as a whole subtree: `CommandChartDto.kommandoGroupId` drives the editor to suppress every affordance
on it (rename / remove / assign-lead / add-child) and the backend additionally **rejects creating a
child** (Stv. / Ensign) under a `kommando_group`-linked parent — so no chart-only seat can be bolted
onto a Leitung-managed Kommando.

When the appointment mints a **new** account seat for a multi-holder post (OL member, SK-Leiter,
Bereichskoordinator/-operator, Ensign), the mirror first **consumes a matching free-text
placeholder**: a free-text seat of the same type/unit/parent whose typed name matches the appointee's
effective name is converted in place instead of leaving a duplicate, so an admin who typed a name and
later appoints that person's account need not delete the placeholder by hand. The singleton posts
(Staffelleiter, Bereichsleiter, Kommandoleiter, Stv.) already reuse their single seat; the match is
name-scoped, so it never touches a different member's placeholder.

The **Grand Admiral** (org-chart.md REQ-ORG-021) is a **title on top of the `OL_MEMBER` rank, not a
separate rank**: the holder is an ordinary OL member with unchanged rights, and the chart mirrors the
OL's `grand_admiral_user_id` designation by rendering that one member at the top of the OL. No
rights-read point changes — reusing `OL_MEMBER` is what keeps the Grand Admiral's reach identical to
an OL member's by construction.

**Acceptance**

- [x] Granting / revoking a rank updates the account-linked chart seat in the same transaction; the
  chart still grants nothing and the ArchUnit chart pins stay green.
- [x] A `kommando_group`-linked Kommando renders read-only in the chart (no rename / remove /
  assign-lead / add-child), and creating a child under it is rejected with
  `problem.org_chart.account_managed_in_leitung`.

**Enforced by:** `OrgChartServiceTest` (the `mirror*` cases, `getOrgChart_groupLinkedCommand_projectsKommandoGroupId`, `createPosition_childUnderGroupLinkedKommando_isRejected`), `OrgChartPageRenderTest#groupLinkedCommand_admin_rendersReadOnlyHeadWithNoEditAffordances`, `OrgChartDtoDeserializationTest`, `OrgUnitMembershipServiceTest` / `KommandoGroupServiceTest` (mirror wiring), `OrgHierarchyMigrationTest` (V186), `ArchitectureTest` · **Code:** `OrgChartService#mirror*`, `OrgChartService#buildCommand` / `#createPosition`, `CommandChartDto#kommandoGroupId`, `OrgUnitMembershipService`, `KommandoGroupService`, `OrgChartPosition#kommandoGroup`, `V186__org_chart_kommando_group_link.sql` · **Decision:** ADR-0042 · **Issues:** #800

## Out of scope

- **Bank** consumption of these ranks (who sees / requests on which account) — a later session; the
  `OrgUnitBankAccessService` / `OwnerScopeService` seam is kept clean for it.
- Per-rank / per-feature differentiation of rights beyond the SK-lead-equivalent baseline.
- Retiring the flat Keycloak `OFFICER` role for squadrons.

## Open questions

None outstanding — the six owner decisions (D1–D6) that shaped this spec are recorded in ADR-0042.

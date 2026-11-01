> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-22.
> **Owner area:** ROLE · **Related ADRs:** ADR-0042 (qualifies ADR-0026, ADR-0027, ADR-0029),
> ADR-0300 (planned)

# Functional rank & permission model (squadron / area / OL)

## Context & goal

The single flat Keycloak `OFFICER` role is no longer enough to model squadron leadership. Each
Staffel has exactly one **Staffelleiter**, up to four **Kommandoleiter** and four **stellvertretende
Kommandoleiter**, and up to four **Ensigns**, each assigned to a Kommandogruppe or generally to the
Staffelleitung (a group has at most one Kommandoleiter and one stellv.). Each Bereich has one
**Bereichsleiter**, plus zero-or-more **Bereichskoordinatoren** and **Bereichsoperatoren**. Members
can belong to the **Organisationsleitung (OL)**; every Bereichsleiter is automatically part of the OL
body.

This spec defines the **functional rank model** — the source of truth for "what may this user do in
this org unit" — so that other features can key permissions off these ranks. The bank is the first
consumer: an account's responsible holder (Staffelleiter, SK lead, Bereichsleiter, the OL members) is
derived from the rank inside the `OrgUnitBankAccessService` seam ([`bank.md`](bank.md) REQ-BANK-034,
ADR-0043). It is the anchor for the rank-related amendments in
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

Like `SK_LEAD` and the area/OL ranks, a squadron rank also carries the flat back-compat
`ROLE_LOGISTICIAN` / `ROLE_MISSION_MANAGER` (the `confersFlatOfficerRole` surface) so existing
`hasRole('LOGISTICIAN')` gates keep working. Every such gate is **owner-scoped**, so the effective
reach stays own-squadron: the two previously-unscoped per-user refinery endpoints (`GET` / `POST
/api/v1/refinery-orders/users/{userId}`) were scoped with `@ownerScopeService.canViewUserRefineryOrders`
/ `canManageUserRefineryOrders` (PR #808 security review; since 2026-10-10 the refinery module's
`@refineryAccessPolicy`, same verdicts) so the flat role can no longer act org-wide
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

#### Officer reach is derived from the seats (ADR-0300)

**Officer reach is derived only from the seats.** The rank grants above stay; what changes is that
the officer standing they stood next to is no longer a Keycloak role.

- **Who has reach where.** The squadron leadership ranks and `SK_LEAD` reach their own unit, the
  three area ranks the Bereich and its direct children, `OL_MEMBER` every unit
  (`confersOwnUnitOfficerReach`, `OrgUnitCascadeService.cascadedOfficerReach`). An admin passes
  every officer check.
- **No manual grant.** There is no per-membership flag and no global override. Bank management
  confers no reach; a bank manager with a seat has reach there like anyone else.
- **`KRT Member` is required for every seat.** Appointing a member without the Keycloak role
  `KRT Member` is refused. A seat whose holder loses the role later stays in the org chart and
  confers no reach until the role is back (REQ-ORG-029).
- **Gates read the target's owning unit.** An officer check asks for reach in the owning unit of the
  mission, job order, promotion topic, ship or other target; lists are filtered to the caller's
  officer units. The active context only narrows what navigation and lists show, with the union
  when nothing is pinned. Hangar owner details, the Leitung page, promotion management, job-order
  writes, mission finance and owner change, the operation's unpaid reset and the purchasing
  `leadsUnit` check move to this per-unit reach.
- **The Keycloak `OFFICER` role is retired** (REQ-SEC-004). The paragraph above on the operational
  `OFFICER` grant no longer applies: no one holds `OFFICER`, and nothing reads it.
- **No new audit event.** Reach follows the seat changes, which `ROLE_GRANTED` / `ROLE_CHANGED` /
  `ROLE_REVOKED` already record (REQ-AUDIT-001).

**Acceptance**

- [x] A Staffelleiter has officer reach in their Staffel and none in a second Staffel they are a
  plain member of.
- [x] A Bereichskoordinator reaches the Bereich and its direct children; an OL member reaches every
  unit; a bank manager without a seat reaches none.
- [x] An appointment of a member without `KRT Member` is refused; a seat holder who loses the role
  keeps the seat and loses the reach.
- [x] Every officer gate reads that reach in the target's owning unit, and nothing reads `OFFICER`.

**Enforced by:** `OwnerScopeServiceTest` (`OfficerReachTests`), `OrgUnitCascadeServiceTest`,
`OrgUnitMembershipServiceTest` (`*_withoutKrtMember_isRefused`),
`CustomJwtGrantedAuthoritiesConverterTest` (no `ROLE_OFFICER`), `PurchasingAccessPolicyTest`
(`leadsUnit` by reach) · **Related:** REQ-SEC-004, REQ-SEC-015, REQ-ORG-029, ADR-0300

### REQ-ROLE-003 — Kommandogruppe entity

A Kommandogruppe is a first-class named sub-structure of a Staffel (`kommando_group`: squadron
`org_unit_id` FK, name, sort index, `@Version`); at most four per squadron. A membership with role
`KOMMANDOLEITER` / `STELLV_KOMMANDOLEITER` / `ENSIGN` carries a nullable `kommando_group_id`: each
of the three MAY reference a group of its own squadron (null = "allgemein der Staffelleitung");
every other rank must have a null group. A group's parent must be a `SQUADRON`. A squadron holds at
most four Kommandoleiter, four stellv. Kommandoleiter and four Ensigns, counted with and without a
group, and a group at most one Kommandoleiter and one stellv. (ADR-0322).

**Acceptance**

- [x] `kommando_group` exists, caps at four per squadron, and rejects a non-`SQUADRON` parent (V185).
- [x] `chk_org_unit_membership_kommando_group_role` confines `kommando_group_id` to the in-group
  squadron ranks and lets each of them go without one (V313).
- [x] Kommandogruppe CRUD + member assignment honour ≤1 KL and ≤1 stellv. per group and ≤4 KL,
  ≤4 stellv. and ≤4 Ensigns per squadron.
- [x] A Kommandoleiter or stellv. Kommandoleiter can be appointed without a Kommandogruppe.
- [x] The three singleton caps (≤1 Staffelleiter/squadron, ≤1 KL + ≤1 stellv./group) are
  DB-backstopped by partial unique indexes (V188), so a concurrent double-assign fails on the
  constraint rather than committing a duplicate; the per-squadron caps of four stay
  service-layer-only (a count, like the org chart's own ≤4 ENSIGN cap).

**Enforced by:** `OrgHierarchyMigrationTest` (V185, `v188SquadronRankSingletonIndexes`, `v313AllowsKommandoleiterWithoutGroup`), `KommandoGroupServiceTest`, `OrgUnitMembershipServiceTest` (squadron-rank cardinality, ungrouped ranks) · **Code:** `KommandoGroup`, `KommandoGroupRepository`, `KommandoGroupService`, `KommandoGroupController`, `OrgUnitMembershipService#assignSquadronRank`, `SquadronRankCaps`, `SquadronRoleController`, `V185__create_kommando_group.sql`, `V188__squadron_rank_singleton_indexes.sql`, `V313__allow_kommandoleiter_without_group.sql` · **Decision:** ADR-0042, ADR-0322 · **Issues:** #800

### REQ-ROLE-004 — Delegated appointment ladder, no self-promotion

Rank assignment is delegated down a strict ladder, with admin able to do everything:

- Admin → OL members (pure `OL_MEMBER`);
- OL members → Bereichsleiter;
- Bereichsleiter → Staffelleiter + SK-Lead + Bereichskoordinator + Bereichsoperator, for units in
  their own Bereich;
- Staffelleiter → Kommandoleiter + stellv. Kommandoleiter + Ensign + Kommandogruppen, for their own
  squadron.

Invariant: a leader can **never** assign the rank they themselves hold at that level (to grant rank
R you must hold a strictly-higher rank, which you cannot grant to yourself) — the no-self-escalation
property the ADMIN-only `is_lead` toggle used to provide, now held at every tier (the SK lead itself is
appointed by the Bereichsleiter of the SK's parent Bereich, `canAppointSkLead`). The delegated verdict is
computed from the caller's own membership ranks only (never from the admin-pin header, contextual
authorities, or `isAdmin()` inside the verdict); admin short-circuits only at the `@PreAuthorize`
layer.

**No write on one's own seat.** Every delegated rank write — assign / clear a squadron rank, add /
remove a Bereich role, toggle an SK lead — additionally requires that the target is not the caller
(`OrgRoleManagementSecurityService.targetsAnotherUser`, ANDed into each endpoint's delegated
branch). Only an admin sets, changes or clears their own rank; a Bereichsleiter cannot make
themselves Staffelleiter, a Staffelleiter cannot demote themselves to Kommandoleiter. (Since
2026-09-27.)

**What the view shows.** `GET /api/v1/org-chart/leadership` lists the units the caller **leads and those
below them**, independent of what they may change: a squadron rank or `SK_LEAD` shows its own unit,
an area rank its Bereich with the Bereich's direct children (`OrgUnitCascadeService`), an
`OL_MEMBER` seat every unit including the OL, an admin everything; a plain `MEMBER` seat shows
nothing. The capability flags `canAppointLead` / `canManageRoster` alone decide what renders as
editable; each roster row carries `self`, and the caller's own row is never editable for a
non-admin. A row whose current rank the caller may not change renders as a read-only rank chip, so
a Staffelleiter sees their own seat as „Staffelleiter" rather than an unselectable dropdown that fell
back to „Mitglied". (Since 2026-09-27; before that a unit was listed only when a capability flag
held, an OL member saw no Staffel or SK, and the rank dropdown showed „Mitglied" for any rank the
caller could not assign.)

The frontend Leitung page (`/organisation/leitung`) and its write proxies are role-gated to
`ADMIN` / `OFFICER` only (`Roles.ADMIN_OR_OFFICER`). Every functional leader carries the operative
`OFFICER` grant (see the "Operational OFFICER grant" section of
[`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)), so the coarse page gate never denies
a delegated leader; `LOGISTICIAN` / `MISSION_MANAGER` are deliberately **not** accepted, so a
capability-only holder with no appointment reach (empty view) cannot open the surface. The per-unit
appointment authority remains the delegated verdict above, re-checked on every backend write.

**The page is a master-detail.** On the left a unit tree grouped „Organisationsleitung · Bereiche ·
Staffeln · Spezialkommandos", each unit with its Bereich colour square and member count, and a
search over unit names, shorthands and member names; on the right the selected unit — its kind
and Bereich as eyebrow, its name, „Mitglied hinzufügen" where the caller may add members (OL,
Bereich, SK — a Staffel's members come from its memberships and have no add here), and the tabs
„Mitglieder (n)" and, for a Staffel, „Kommandogruppen (n)". `?unit=<id>` selects the unit (an
unknown id selects the first), `?tab=groups` the second tab; the selection rewrites the address in
place and the tree moves with the arrow keys. An editable rank or Kommandogruppe is a select that
**saves on change** — one versioned write per change, a toast on success, the conflict dialog on a
`409` — with no save button per row; appointing a Grand Admiral, revoking a rank, removing a member
or deleting a group sits in the row's „⋯" menu behind a confirmation.

In the page's **Spezialkommandos** section the two flags mean different people: `canAppointLead`
is the Bereichsleiter rung above, `canManageRoster` is
`SpecialCommandSecurityService.canManageMembers` — admin, or the SK's own `SK_LEAD`, REQ-ORG-005.
The SK's roster renders **in place** in the selected unit (the `organisation/unit-detail ::
skRoster` fragment the SK member page `/organisation/special-commands/{id}` uses as well): name,
the Logistiker / Einsatzleiter flags as toggles and the lead as a read-only flag. With
`canManageRoster` the caller adds members, toggles the flags and removes members there; the lead
toggle is offered in the row menu only with `canAppointLead`. So an SK lead manages their own SK
here without being able to touch its lead seat. (Since 2026-09-22 an SK lead sees their SK; until
2026-10-03 `canManageRoster` rendered a „Mitglieder verwalten" link to the SK member page instead
of the roster.)

Amended 2026-10-03 (website overhaul phase 3): the Leitung page became the master-detail above,
rank and group selects save on change, and the SK roster moved onto the page; the „Mitglieder
verwalten" link is gone. Guarded by `LeitungPagePatternRenderTest`, `LeitungPageControllerMvcTest`
and `SpecialCommandMembersPageControllerMvcTest`.

**Acceptance**

- [x] Each tier can appoint exactly the rung below it, within its own scope; a foreign-unit or
  same-tier appointment is denied; the verdict never satisfies `isAdmin()` (Phase 3).
- [x] A delegated write targeting the caller's own seat is denied; an admin may write their own.
- [x] The Leitung view shows a Staffelleiter only their Staffel, a Bereichsleiter their Bereich with
  its Staffeln and SKs, an OL member every unit; the caller's own seat renders read-only.

**Enforced by:** `OrgRoleManagementSecurityServiceTest`, `DelegatedAppointmentControllerSecurityTest`, `LeitungViewServiceTest`, `LeitungPageControllerMvcTest`, `ArchitectureTest` (`delegatedRoleAuthoriserMustNotConsultOwnerScope`) · **Code:** `OrgRoleManagementSecurityService`, `LeitungViewService`, `SquadronRoleController`, `KommandoGroupController`, `OrgHierarchyController` (Bereich gates), `SpecialCommandMembershipController` (SK-lead gate) · **Decision:** ADR-0042 · **Issues:** #800

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

The mirror is written in the same transaction as the rank change, by `OrgChartService` through
the org-unit module's `orgunit.api.MembershipChangeObserver` SPI (`MANDATORY`), called from the
appointment flow (never by giving the chart scope awareness): the flat seats
(Bereichsleiter / -koordinator / -operator, OL member, SK-Leiter, Staffelleiter) map 1:1 onto a
chart position keyed by org unit (singletons are reassigned, not duplicated, so the partial unique
indexes hold), while the in-Kommando ranks project onto the Kommando sub-tree — a `COMMAND_LEAD`
node tied to its Kommandogruppe via the V186 `kommando_group_id` link carries the Kommandoleiter, and
the stellv. Kommandoleiter / Ensigns hang off it. A Kommandoleiter without a group holds an own
unnamed `COMMAND_LEAD` node without a group link; a stellv. or Ensign without a group reports
straight to the Staffelleiter (`SquadronChartDto.directDeputies` / `directEnsigns`). A Kommandogruppe
create / rename / delete mirrors the leaderless node, and revoking a rank vacates a led Kommando
(keeping the node, REQ-ORG-025) or removes the other seats; an unnamed ungrouped Kommando that
nothing reports into is removed with its leader's rank (ADR-0322). Legacy admin-authored Kommandos
(no `kommando_group_id`) stay chart-only.

The mirror is the **single writer** of account-linked chart seats: the chart editor itself is
account-free — its write API rejects setting an account holder, and editing / vacating / deleting a
mirror-managed seat (account-held or `kommando_group`-linked), with
`problem.org_chart.account_managed_in_leitung`. So an admin can no longer manually set an
account-linked seat on the chart (no drift); only free-text holders stay editable there
(org-chart.md REQ-ORG-010 / -025 / -020 amendments). A `kommando_group`-linked Kommando is read-only
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

**Enforced by:** `OrgChartServiceTest` (the `mirror*` cases of the `MembershipChangeObserver` methods, `getOrgChart_groupLinkedCommand_projectsKommandoGroupId`, `createPosition_childUnderGroupLinkedKommando_isRejected`), `OrgChartPageRenderTest#groupLinkedCommand_admin_rendersReadOnlyHeadWithNoEditAffordances`, `OrgChartDtoDeserializationTest`, `OrgUnitMembershipServiceTest` / `KommandoGroupServiceTest` (mirror wiring), `OrgHierarchyMigrationTest` (V186), `ArchitectureTest` · **Code:** `OrgChartService#mirror*`, `OrgChartReadService#buildCommand`, `OrgChartService#createPosition`, `CommandChartDto#kommandoGroupId`, `OrgUnitMembershipService`, `KommandoGroupService`, `OrgChartPosition#kommandoGroup`, `V186__org_chart_kommando_group_link.sql` · **Decision:** ADR-0042 · **Issues:** #800

### REQ-ROLE-007 — The member rank follows officer reach

Besides the functional rank of REQ-ROLE-001, every account carries a numeric **member rank** from 1
to 20 (`app_user.rank`), which an admin sets on the member-edit page
(`PUT /api/v1/users/{id}/attributes`). Its permitted range follows the account's standing:

- an **admin** may hold any rank **1–20**: the admin's pass on officer checks counts for this range;
- for everyone else, an account with officer reach in **at least one** org unit, derived from a
  leadership seat held with `KRT Member` (REQ-ROLE-002), holds ranks **1–12**;
- an account with `KRT_MEMBER` and no officer reach holds ranks **13–20**;
- an account with none of these is not range-checked; a ranked account's rank outside 1–20 is
  `ILLEGAL_ARGUMENT`.

A stored `OFFICER` role no longer sets a range. Because reach follows the seats the tool itself
writes, a former lead can be given a rank above 12 as soon as their last seat is removed; no
Keycloak change and no reconciliation is needed. The identity module asks the org-unit module through
its own SPI (`OfficerReachDirectory`).

**Officer surfaces read only the caller's reach.** The Beförderung management pages (evaluation
matrix, topic and rank-requirement editors) and the Leitung page list and edit only the units where
the caller holds officer reach: a seat in Staffel A with plain membership of Staffel B shows A's
members, evaluations, topics, categories, level texts, rank requirements and eligibility, and
never B's. A pin narrows this to the pinned unit when it is within reach, widened by the units the
caller's seat on it reaches through the cascade (a Bereich or OL pin covers the units below it), and
to nothing when it is not within reach (the pages are then hidden); unpinned, the units of reach are
the union. The Leitung page follows the same pin rule. Admins are not
restricted beyond their pin. The management catalogue reads take `view=MANAGEMENT`
(`PromotionReadView`); without it they answer the caller's own squadron, which is what members see
of their progress and is not an officer surface.

A rank that does not fit the range is refused with `400 RANK_OUTSIDE_ROLE_RANGE`
(`identity.api.RankOutsideRoleRangeException`). Its `detail` is localized and names the basis of the
range (a leadership seat, or `KRT Member`) and what has to change in the tool. The problem body
carries `role` (the basis: `LEADERSHIP_SEAT` or `KRT_MEMBER`), `minRank` and `maxRank`. The admin-only
`GET /api/v1/users/{id}/rank-range` returns the basis (`ADMIN`, `LEADERSHIP_SEAT`, `KRT_MEMBER`, or
none) and the bounds; the member-edit page shows them as a hint under the rank select, a refusal is
shown on the rank field (the AJAX save answers `422 {rank: detail}`, the form post flashes
`error.member.rank.outsideRoleRange`), so the admin can reset the rank and save the Staffel change in
the same form. A rank that differs from the stored one records `MEMBER_RANK_CHANGED` in the Rollen &
Mitglieder audit area, with only the old and new rank in its details (REQ-AUDIT-001); an unchanged or
refused rank records nothing.

**Acceptance**

- [x] A rank above 12 for an account with officer reach, or below 13 for a `KRT_MEMBER` without it,
  is refused with `RANK_OUTSIDE_ROLE_RANGE`, its basis-specific detail and the range; the stored rank
  does not change.
- [x] An admin may hold any rank 1–20; a stored `OFFICER` role without a seat sets no range.
- [x] The member-edit page names the refusal on the rank field and no membership change is sent with
  it; a save with an unchanged, fitting rank sends the Staffel set as usual.
- [x] The page shows the basis and the permitted range under the rank select.
- [x] A changed rank writes one `MEMBER_RANK_CHANGED` audit row; an unchanged or refused rank writes
  none.

**Enforced by:** `UserServiceRankTest`, `UserServiceAttributesTest`, `OrgUnitOfficerReachDirectoryTest`,
`UserControllerTest`, `DomainProblemContractTest`, `ProblemCodeRegistryTest`,
`MemberManagementControllerTest` · **Code:** `UserService#updateUserAttributes`,
`UserService#findRankRange`, `RankRange`, `RankOutsideRoleRangeException`, `OfficerReachDirectory`,
`IdentityProblemCode`, `MemberManagementController#updateMemberAjax`, `identity/member-edit.html` ·
**Decision:** ADR-0264, ADR-0300 · **Issues:** #2636, #2844
## Out of scope

- Per-rank / per-feature differentiation of rights beyond the SK-lead-equivalent baseline.

## Open questions

None outstanding — the six owner decisions (D1–D6) that shaped this spec are recorded in ADR-0042.

> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-12.
> **Owner area:** ORG · **Related:** [`security-and-access.md`](security-and-access.md) · issues #214, #340–#344, #500

# Multi-org-unit tenancy & scope (CRITICAL)

## Context & goal

The system supports multiple OrgUnits in parallel. Four kinds coexist under a shared
`org_unit` table with a `kind` discriminator (`OrgUnitKind`): `SQUADRON` (the Staffel),
`SPECIAL_COMMAND` (SK), and — above them since epic #692 — `BEREICH` and `ORGANISATIONSLEITUNG`
(REQ-ORG-014). Scope (who can see/edit what) is enforced in the service layer. The historical plans
behind this spec are archived: [`MULTI_SQUADRON_PLAN.md`](../archive/MULTI_SQUADRON_PLAN.md) and
[`SPEZIALKOMMANDO_PLAN.md`](../archive/SPEZIALKOMMANDO_PLAN.md) (history only — this spec is the
living truth).

> **Numbering note.** The ORG ids are shared with the retired [`org-chart.md`](org-chart.md). Four
> of them had each named one requirement here and a different one there; on 2026-09-22, on the
> owner's decision, they were renumbered once: this file keeps `REQ-ORG-011` (owner retains see/edit)
> and `REQ-ORG-018` (mission owning-unit reassignment), while its active-context surfacing (was
> `REQ-ORG-010`) is now `REQ-ORG-024` and its participant escape (was `REQ-ORG-021`) is now
> `REQ-ORG-027`. See the renumbering table in [`INDEX.md`](INDEX.md). The Grand Admiral
> (`REQ-ORG-021`) moved here when the org chart was removed (ADR-0331); `REQ-ORG-010`, `-012`, `-013`,
> `-020`, `-025` and `-026` are retired with it.

## Requirements

### REQ-ORG-001 — OrgUnit model & dual-write soak

Two kinds under `org_unit.kind`: `SQUADRON` and `SPECIAL_COMMAND`. The IRIDIUM Squadron is
the canonical UUID `00000000-0000-0000-0000-000000000001`. During the dual-write soak every
staffel-scoped aggregate carries both the legacy `owning_squadron_id` and the new
`owning_org_unit_id`, kept in lockstep by JPA `@PrePersist`/`@PreUpdate`/`@PostLoad` hooks.
Repository queries read the new column; the legacy column drops in the destructive cleanup
release.

> **Completed (2026-09-22 review):** the soak is over. The dual-write lifecycle hooks were removed
> (R9 Step 2) and `V103` dropped the legacy `owning_squadron_id` columns from `mission`,
> `operation`, `ship`, `inventory_item`, `refinery_order` and `job_order` (plus the job order's two
> squadron columns); `owning_org_unit_id` is the only owner column.

> **Amended by epic #692 (REQ-ORG-014):** `org_unit.kind` gains `BEREICH` and `ORGANISATIONSLEITUNG`, and
> a nullable self-referential `parent_org_unit_id` introduces the fixed three-level hierarchy
> OL → Bereich → Staffel/SK.

### REQ-ORG-002 — Scope is enforced in the service layer

Use [`RequestScopeResolver.currentScopePredicate()`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/scope/api/RequestScopeResolver.java)
for list-endpoint filters (three-component record `ScopePredicate`: `boolean adminAllScope`,
`UUID activeOrgUnitId`, `Set<UUID> memberOrgUnitIds`) and `AccessGateService.canSee*` /
`canEdit*` for `@PreAuthorize` SpEL on detail/write endpoints. Admins without an active pin
get all-scope visibility; admins with a pin get the same restrictive view as a member;
non-admins see the union of their memberships unless they pin one. Only a pin that REQ-ORG-032
honours counts; any other pin is no pin.

### REQ-ORG-003 — Aggregate scope kinds

> **Approved planned JobOrder amendment (not implemented):** For all three JobOrder kinds, [orders-core.md](orders-core.md) replaces the independent eligibility flag, private-Staffel/SK-public distinction and requester redaction with the agreed complete order read set. Actual active Profit members, requesting/responsible/supporting units, relevant leadership and personal Bearbeiter qualify as specified there; aUEC adds properly linked holders/payees. Linked stock owner/location is visible, while blueprint ownership, unreadable missions, unrelated inventory and private bank data keep their separate gates. The external exchange retains reduced direct-membership scope; web-wide readability is not a gateway expansion.
>
> For JobOrders, the following existing clauses, exclusions and test references are the current implementation baseline; they do not override the approved planned replacement or prove its acceptance. Requirements for other aggregates remain binding.

> The JPQL implementing every scope-predicate triple/escape below is centralised in
> the owning module's `api` (`MissionScope`, `ShipScope`, `JobOrderScope`, `InventoryScope`; the refinery's and the operation's sit in their access policies; one compile-time-constant fragment per aggregate) rather than
> hand-copied per query — see [`data-persistence.md`](data-persistence.md) REQ-DATA-010. This is an
> implementation detail; the scope semantics documented here are unchanged.
>
> [!important] `InventoryItem`'s mission link is **not** a cross-staffel escape
> `GET /api/v1/inventory/allocations?missionId=` (formerly `…/inventory/mission/{missionId}`) was unscoped, and because a mission is visible
> cross-staffel whenever `is_internal = false`, that made the mission link a third, undocumented
> widener: any member could read a foreign Staffel's mission stock — owner callsign, location,
> material, quantity, quality, linked job-order ids — by enumerating public missions. It now carries
> the standard scope triple (`findByMissionIdScoped`), so the two named wideners below (the
> REQ-ORG-011 owner escape and the job-order link) remain the only ones.

- **Strict-staffel** (no cross-staffel escape): `Ship`, `InventoryItem` (direct Lager-View),
  `RefineryOrder`. List/CRUD filter by `owning_org_unit_id`; detail gates on
  `canSee*`/`canEdit*`. `InventoryItem` rows are append-only and collapsed into display
  *stacks* only at read time (see [`inventory-lager.md`](inventory-lager.md), `REQ-INV-*`);
  the grouping is a display concern keyed within a single `owning_org_unit_id` pool and never
  widens this scope. **All three of these personal aggregates (`Ship`, `InventoryItem`,
  `RefineryOrder`) carry the per-user owner escape of REQ-ORG-011** — the row's own owner
  (`ship.owner` / `inventory_item.user` / `refinery_order.owner`) may always see/edit it regardless
  of the `owning_org_unit_id` stamp; this is a per-owner detail/edit carve-out only and does not
  widen the shared Lager-/Hangar-/refinery views. A non-owner stays strictly scoped.
- **Managed unit only:** `MissionTemplate` has a required, immutable owning unit. The active
  context limits owner choices and every list, detail and write operation to active units where
  the caller has officer reach or a contextual Mission Manager grant (REQ-MISSION-035).
- **Strict-staffel with two read-only escapes:** `Operation` — owned by a Staffel/SK and filtered
  by `owning_org_unit_id` like the strict aggregates, but visible beyond the owning scope in two
  cases (view only; editing stays role+scope via `canEditOperation`): (1) an **ownerless** operation
  (`owning_org_unit_id IS NULL`, V145) is a leadership/"Bereichsleitung" operation visible to
  organisation members-or-above (`viewerIsMemberOrAbove`; no organisation-wide escape — operations
  carry no `is_internal` flag); (2) any member who **participated** in one of the operation's
  linked missions may see it (`viewerUserId` matches a `mission_participant.user_id` of a mission
  whose `operation_id` is this operation), so participants can view the operation and their payout
  regardless of owning Staffel. Both escapes are gated in the service layer and mirrored across the
  scoped read queries (`findAllScoped`, `searchOperations`) and `canSeeOperation`. They open
  reading only: linking a mission to an operation needs `canEditOperation`, and the mission's
  operation picker (`findAllEditableReference`) lists only the operations the caller may edit
  (REQ-MISSION-034). See REQ-ORG-009.
- **Cross-staffel with public escape:** `Mission` — visible to other OrgUnits iff
  `is_internal = false` (`searchMissions` enforces
  `owning_org_unit.id IN (:memberOrgUnitIds) OR is_internal = false`); editable only by the owning
  OrgUnit and by admins inside the active context (REQ-ORG-032). A mission may
  also be **ownerless** (`owning_org_unit_id IS NULL`, V144) — a leadership / "Bereichsleitung"
  mission created by a user who belongs to no OrgUnit; see REQ-ORG-009.
- **Shared order readership with independent processing scope:** `JobOrder` and its order-owned
  material, item, handover and participation reads follow [orders-core.md](orders-core.md),
  REQ-ORDERS-052. Actual current Profit members read the permitted MATERIAL/ITEM workspace across
  responsible-unit kinds. Current requesting/responsible units, personal Bearbeiter entries and
  verifiable retained unit relations add concrete order reads through current legitimate membership,
  leadership or contextual unit reach. Former individual membership grants nothing.
  `JobOrderScope.READ_PREDICATE` is shared by SQL list/count, number lookup, active reference and
  material-demand queries; the Java detail gate decides the same current readership. Display
  filters never widen it. Responsible and requester writes remain distinct current-writer gates,
  and requester edits retain the delivery restriction until the requester-workflow conversion.
  Blueprint producers, Mission details, unrelated Inventory, Bank and Exchange remain independently
  authorized. The narrow general Profit capability used by external consumers is unchanged.

  Stock linked to the concrete order is readable inside its order UI without opening a foreign
  Lager view or unrelated stock rows.
- **Membership-only, anonymised:** the exchange's org-demand feed (`GET /exchange/v1/me/org-demand`,
  REQ-XCH-018, ADR-0220) sums the open and in-progress `JobOrder`s whose responsible unit is one of
  the member's **direct memberships** — never a unit the member merely oversees through the
  REQ-ORG-015 cascade or reaches as an admin, and without the SK-public escape above. It names no
  requester, assignee, order or free text (`ExchangeDemandService`). The member must first pass
  the viewer-side profit gate (`canViewJobOrders`); otherwise the feed is empty with
  `reason: NOT_PERMITTED`.

> **Amended by epic #692 (REQ-ORG-016):** `owning_org_unit_id` (and `responsible_org_unit_id`) may now
> reference a `BEREICH` or `ORGANISATIONSLEITUNG` org_unit. Such rows participate in **these same** scope
> kinds and escape rules unchanged — the only new behaviour is that the descendant cascade (REQ-ORG-015)
> lets a higher level see/edit its subordinate units' rows; a subordinate never sees the level above
> (strict silo).

#### Concrete personal assignment read (ADR-0301)

A `JobOrderAssignee` entry grants its current account holder the complete permitted order facts,
independent of membership, the general Profit capability or the pin (REQ-ORDERS-047). It contributes
to Alle and Ich bearbeite, including reference and material representations of that same order;
it does not create a current unit-work relationship for Zu bearbeiten. A removed personal entry
ends this grant. Account erasure removes the entries through `JobOrderMemberUnlink`.

An existing entry permits its holder to maintain their own note and remove themselves while the
order permits those commands. Enrollment, including self-enrollment, requires the current
responsible-unit writer (`canManageAssignees`); read access alone never creates another entry.
Other processing and requester commands retain their independent authorities. An assignee grant
opens no private producer panel, unrelated Mission, Inventory or Bank object, and no Exchange feed.

**Enforced by:** `JobOrderAssigneeGrantIntegrationTest`,
`JobOrderHierarchyAuthorityIntegrationTest`, `JobOrderScopeQueryIntegrationTest`,
`JobOrderLinkedStockMissionAllocationIntegrationTest`.
**Related:** REQ-ORDERS-040, REQ-ORDERS-047, REQ-ORDERS-052, REQ-ORG-011, ADR-0301.

### REQ-ORG-004 — Create-time OrgUnit stamping

> **Implemented JobOrder authority:** JobOrder creation requires a contextual writer of either the requesting unit or proposed responsible Profit unit. Valid responsibilities are the unique active Profit Bereich and its active subordinate Staffeln/SK. Any permitted requester kind remains distinct from that responsible set. Do not copy generic aggregate stamping or the old authentication-only/flag-only intake into this approved replacement; see [orders-core.md](orders-core.md).
>
> For JobOrders, the following existing clauses, exclusions and test references are the current implementation baseline; they do not override the approved planned replacement or prove its acceptance. Requirements for other aggregates remain binding.

Stamp the OrgUnit via the central picker resolvers on `OrgUnitStampingService` — never read
`user.getSquadron()` directly. A new row's **stampable units** are its owner's (ship, Lager row,
refinery order) or its creator's (mission, operation) direct memberships on active units, and none
while that user lacks the stored role `KRT Member` (REQ-SEC-015); a deactivated unit never counts.
The resolvers share the §5.5.1 picker matrix over that set (1 + no output → auto-stamp; valid
pick → honoured; foreign pick → 400; >1 + no output → 400 (force choice)), where a pick is valid
when it is a stampable unit or a unit the caller may edit (REQ-ORG-016). They differ on the pin
and on the **0-unit** row:

- `resolveOrgUnitForPickerOutputNullable` — the personal aggregates that may exist without an
  OrgUnit: `Ship`, `RefineryOrder`, `InventoryItem` (V132). Without a pick it stamps the honoured
  pin only when the pin is one of the owner's stampable units (REQ-ORG-032); an owner without a
  stampable unit gets **null** (ownerless), and a *foreign* pick still 400s.
- `resolveOrgUnitForUnitOwnedAggregate` — `Mission` (V144) and `Operation` (V145, see
  REQ-ORG-009). Without a pick it stamps the honoured pin, which is always a unit the creator may
  edit (REQ-ORG-032); without a pin the creator's only stampable unit, and a creator without one
  gets **null** (ownerless).
- `resolveOrgUnitForAdminOnBehalf` (an admin's ship for a member, REQ-HANGAR-011),
  `resolveOrgUnitForClientCreate` (a connected application, REQ-XCH-017) and
  `resolveOrgUnitForImport` (a hangar import, REQ-HANGAR-014) read the member's stampable units as
  well. The admin's own pin never decides an on-behalf row, and an on-behalf pick must be one of
  the member's direct memberships.
- `resolveOrgUnitForPickerOutput` and `resolveSquadronForPickerOutput` (strict) → **400** for a
  user without a membership. Wired to no aggregate — retained as the strict counterparts
  (`Operation` moved off them in V145; `JobOrder` uses its own resolvers, see below).

**Refinery-store output stamping (#596).** Storing a refinery order creates one `InventoryItem`
per output, owned by the receiving member (`RefineryOrderStoreItemDto.userId`, else the order
owner). The store dialog carries a per-item owning-OrgUnit picker whose options are the *receiver's*
memberships and whose default selection is the order's own `owningOrgUnit`; the chosen id rides
`RefineryOrderStoreItemDto.owningOrgUnitId` into `resolveOrgUnitForPickerOutputNullable`. This closes
the gap where a multi-membership receiver was hard-rejected with the `>1 + no output → 400` branch
because the form offered no choice — the picker is always shown so the receiver's OrgUnit pool is
explicit, while the inherited default keeps a same-OrgUnit self-store one-click.

Job Order uses its own resolvers: `responsible_org_unit_id` is required and must be profit-eligible
— the guest fallback onto a configured intake SK went with the anonymous create (ADR-0149);
`requesting_org_unit_id` accepts any OrgUnit and is freely editable.

**Personal Lager rows can be re-stamped by their owner** (REQ-INV-052): a member may move their own
personal row onto one of their direct memberships or onto **no** unit after the booking.
Re-stamping gives or takes access for that unit's editors, and the dialog says so. A ship's later
„Einheit ändern" (REQ-HANGAR-009) validates against its owner's direct memberships too, not against
the stampable units.

**The exchange stamps on its own paths** (REQ-XCH-016/-017). A stock book-in through the exchange
creates a personal row with **no** unit, outside the picker resolvers — REQ-INV-052 is how it gets
one. A ship created through the exchange runs the Hangar's own create: without a named unit on
`resolveOrgUnitForClientCreate` (the member's only stampable unit, else none), with one on the
member's direct memberships (`resolveOwnerMembershipTarget`).

> **Amended by epic #692 (REQ-ORG-016):** stamping **validation** widens to `(direct memberships ∪
> oversight descendants)` so a Bereichsleitung/OL can create on behalf of a subordinate Staffel/SK and
> own their own Bereich/OL data; the **auto-stamp** default and the `>1 → force a choice` rule stay keyed
> on **DIRECT** membership, so ordinary-member stamping is unchanged.
>
> **Amended by REQ-ORG-018:** the create-time stamp is no longer the final word for `Mission` — its
> `owning_org_unit_id` is **reassignable after creation** via the dedicated reassignment endpoint
> (a separate resolver, `resolveReassignTargetOrgUnit`, with no auto-stamp / home-Staffel fallback).
> Every other aggregate's owning OrgUnit remains create-time-only, except the owner's re-stamp of a
> personal Lager row above (REQ-INV-052).
>
> **Amended by ADR-0374 (REQ-ORG-032):** the direct memberships of the auto-stamp are the stampable
> units above, and a mission or operation created without a pick is stamped on the honoured pin
> first, which may be a unit below a leader's own; a personal row keeps to its owner's stampable
> units.

### REQ-ORG-005 — Admin area carve-outs

Admin area is admin-only (post-Phase-4 lockdown), with carve-outs: Stammdaten,
user-management, announcement writes, system settings and **SK lifecycle**
(`/admin/special-commands`) are `hasRole('ADMIN')`. **SK delete is a soft-delete
(deactivate): it flips `active=false`, never hard-deletes** — memberships and any aggregate
already owned by the SK survive; only the active-list dropdowns and owner picker hide the row.
It is reversible via the activate endpoint, and `includeInactive=true` (ADMIN-only) surfaces
deactivated rows with a reactivate control. The list page's per-row trash button confirms
through a KRT modal (no native `confirm()`, per [`ui-design-system.md`](ui-design-system.md))
before POSTing the deactivate. SK **member management** is open to ADMIN
or a `role = SK_LEAD` member of that SK who holds `KRT Member`
(`SpecialCommandSecurityService.canManageMembers`; epic #800 replaced the legacy `is_lead` flag with
the unified rank, V187) — the member list, add / remove, the Logistiker / Einsatzmanager flags, and
the SK read `GET /api/v1/special-commands/{id}` that heads the page. A non-admin changes the flags
of, or removes, neither their own row nor a row that holds `SK_LEAD`
(`SpecialCommandSecurityService.canManageMember`, REQ-ROLE-004), and every one of these endpoints,
the lead toggle included, acts only on an SK inside the active context, an admin's included
(REQ-ORG-032). Because it is not admin-only, the web page for it lives **outside** the admin area, at
`/organisation/special-commands/{id}` (frontend `SpecialCommandMembersPageController`, gated by
`OfficerSurfaceGate.HAS_OFFICER_REACH` — the officer reach in the active context — plus the backend's
per-SK verdict, which also requires the SK in the active context); `/admin/special-commands/{id}` only redirects
there. An SK lead reaches it from the „Leitung" page, which lists every SK the caller may manage
members of (`LeitungUnitDto.canManageRoster`). Setting the **lead** is never open to the SK lead
itself (no self-escalation): it is ADMIN or the Bereichsleiter of the SK's parent Bereich
(`OrgRoleManagementSecurityService.canAppointSkLead`, REQ-ROLE-004). Until 2026-09-22 this sentence
said the lead toggle was ADMIN-only, and the member page was admin-only in the frontend, so the SK
lead's right existed in the API alone.

> **Amended by epic #692 (REQ-ORG-015):** membership in a Bereich's Bereichsleitung or the OL grants
> **officer-equivalent reach** over the subordinate units, **never admin rights** — every carve-out in
> this requirement stays `hasRole('ADMIN')`, and an OL/Bereich principal must never satisfy `isAdmin()`.

### REQ-ORG-006 — ArchUnit guards for new staffel-scoped aggregates

[`ArchitectureTest`](../../backend/src/test/java/de/greluc/krt/profit/basetool/backend/ArchitectureTest.java)
breaks the build if a staffel-scoped service stops injecting `AuthHelperService` /
a scope-kernel service (`staffelScopedServicesMustWireOwnerScopeOrAuthHelper` — update the
whitelist when adding an aggregate), and `noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities`
prevents new `@JoinColumn(name = "squadron_id")`. Its allow-list names only `User`, and that
entity no longer maps the column at all (V104 dropped `app_user`'s legacy squadron fields);
`MissionParticipant`'s affiliation moved to the `mission_participant_org_unit` join table.

> **Amended by REQ-ORG-028 (2026-10-03):** the scope rules now also select by the `@TenantScoped`
> marker on the aggregate rather than only by class names. The list-keyed write-gate rule
> `staffelScopedWriteEndpointsMustGateOnOwnerScopeService` is retired (2026-10-03): the marker-based
> rule in `TenancyGuardTest` selects every controller it named (asserted) and more.

### REQ-ORG-007 — Audit MDC field

`CorrelationIdFilter` emits MDC `orgUnitId` on every request. Logback patterns must include
`%X{orgUnitId}`. See [`observability.md`](observability.md). (The pre-R5.e legacy `squadronId`
MDC alias was removed in the rename-soak cleanup release.)

### REQ-ORG-008 — Active-context relay

The frontend sends `X-Active-Org-Unit-Id` on every outbound call; the backend honours it under
the one pin rule of REQ-ORG-032. The selection lives in the Redis-backed Spring Session under
`basetool.activeOrgUnitId`, set via `POST /me/active-org-unit`, stored per member and restored at
the start of a session (REQ-ORG-031). `GET /api/v1/org-units/me/active` (fields `context` and
`orgUnitId`) and `GET /api/v1/me/layout` (fields `activeContext` and `activeOrgUnitId`) report the
**effective** context the backend applies (`ORG_UNIT` with its unit, `ALL_MY_ORG_UNITS`,
`ALL_ORG_UNITS` or `NONE`), never the raw session value. (The pre-R5.e
`X-Active-Squadron-Id` header, the `basetool.activeSquadronId` session key, and the
`/active-squadron` endpoints were removed in the rename-soak cleanup release.)

### REQ-ORG-009 — Ownerless leadership ("Bereichsleitung") missions & operations

Organisation leadership sits above every Staffel and SK and belongs to no OrgUnit, yet must be
able to plan org-wide missions. A mission created without a pick by a user who has no stampable
unit (REQ-ORG-004) and no honoured pin (REQ-ORG-032) is stamped `owning_org_unit_id = NULL` (V144
relaxed the NOT NULL; the row stays attributable through `mission.owner_id`) instead of being
rejected with 400. Visibility layers on top of the
`owning_org_unit IS NULL` state, mirroring the Staffel-internal rule with the whole organisation
as the owning scope:

- **Organisation-wide** (`is_internal = false`) → visible to every member, whichever unit they
  belong to (the create-time default). It meant "and to the internet" until REQ-SEC-052; the escape
  itself is unchanged, its audience is now the organisation (ADR-0159).
- **Internal** (`is_internal = true`) is **not available** to an ownerless mission: internal
  visibility is defined by the owning unit, so an ownerless mission is always organisation-wide
  (REQ-MISSION-008; check constraint `ck_mission_internal_has_owning_org_unit`, V329 migrated the
  existing ownerless internal rows to organisation-wide).

Editing follows the mission-management gate with the ownerless rule of REQ-SEC-005:
`MissionAccessPolicy.canManageMission` admits the owner, co-managers, an admin without a pin and a
caller with a Mission Manager grant in an Organisationsleitung or Bereich unit of the active
context; neither a squadron Mission Manager nor a pinned admin manages an ownerless mission
(REQ-ORG-032). The list queries
(`searchMissions`, `findAllActiveReference`) carry a `viewerIsMemberOrAbove` flag that guards the
ownerless branch of the scope predicate.

`Operation` gains the same carve-out (V145, #500) — `OperationService.createOperation` routes through
`resolveOrgUnitForUnitOwnedAggregate` — with two differences, because an operation has no per-user
creator column and no `is_internal` flag:

- **Attribution.** An ownerless operation has no `owner_id` to point at; it is attributable only as
  an organisation-wide leadership operation (audited via `created_at`/`updated_at`).
- **Visibility.** Operations have no `is_internal` flag, so there is no organisation-wide-vs-internal
  split: an ownerless operation is the org-wide analogue of a Staffel-internal operation — visible to
  organisation members-or-above (`AuthHelperService.isMemberOrAbove()`), so not to an account below
  member.
  `canSeeOperation` defers to `isMemberOrAbove()` on the null-owner branch, and the scoped
  operation read queries (`findAllScoped`, `searchOperations`) carry a `viewerIsMemberOrAbove` flag
  so the list and the per-row detail gate stay consistent. Editing
  follows the same grant rule as an ownerless mission (REQ-SEC-005): `canEditOperation` admits, for
  an ownerless operation, a Mission Manager grant in an Organisationsleitung or Bereich unit of the
  active context and an admin without a pin, and deletion stays with admins.
- **Participation — all operations (#500).** Independently of ownership, any member who
  participated in one of an operation's linked missions
  may see that operation — so participants can view the operation and their payout even when it is
  owned by another Staffel or is ownerless. Implemented as a `viewerUserId` EXISTS branch on the
  scoped read queries plus a `canSeeOperation` participant check
  (`OperationRepository.existsParticipantUserInOperation`). This escape is view-only and never
  grants edit or a mission link (REQ-MISSION-034).

Only `Mission` and `Operation` gain this carve-out; `JobOrder` stays NOT NULL (org-owned by
construction, no creator-owner fallback and no ownerless-leadership use case).

> **Amended by epic #692 (REQ-ORG-016):** this ownerless (`owning_org_unit_id = NULL`) path is
> **preserved unchanged** — it remains the "org-wide, visible to members-or-above" semantic. It is
> distinct from Bereich/OL *ownership*: a `NULL` owner is org-wide, whereas a `BEREICH`/`ORGANISATIONSLEITUNG`
> owner scopes the row to that level's leadership (strict silo). No existing `NULL`-owner row is
> backfilled to a concrete owner.

### REQ-ORG-024 — Active-context surfacing in the UI

> **Renumbered 2026-09-22:** this requirement was `REQ-ORG-010` until 2026-09-22; that id also named the descriptive, ADMIN-edited org chart in [`org-chart.md`](org-chart.md), retired with the chart.

The active OrgUnit context is surfaced to the user **only** by appending it to the application
title (`appTitle`, resolved in
[`LayoutMiscAdvice`](../../frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/kernel/layout/LayoutMiscAdvice.java)),
which renders in both the browser `<title>` tag and the sidebar brand logo text. The suffix is:

- the active OrgUnit's **shorthand** (falling back to its name) for an active pin of **either**
  kind — `SQUADRON` *or* `SPECIAL_COMMAND`. `appTitle` reads the merged `activeOrgUnit` catalogue,
  not the Squadron-only `activeSquadron`, so an SK pin shows in the title;
- the localised "Alle Staffeln" label for an admin in all-OrgUnits mode (no pin);
- the localised „Alle meine Org-Einheiten" label for a non-admin whose context is all of their
  units (no honoured pin, several units in reach);
- nothing (plain "Profit Basetool") when no context applies (a member with no org unit).

The title, the switcher's selected entry and the owner pickers' preselection read the **effective**
context the backend reports (REQ-ORG-008, REQ-ORG-032), never the raw session value, so they always
name the context the data on the page was scoped by. In „Alle meine Org-Einheiten" the pooled lists
that mix several units carry a notice: the Lager overview, the „Einheitslager" and the Hangar's unit
overview that they hold the data of all of the member's units (`orgUnit.context.allMine.notice`,
one shared fragment `components :: allMyOrgUnitsNotice`; „Mein Lager" does not follow the selection
and carries none), the blueprint overview that it holds the
blueprints of the members of every unit the viewer oversees
(`blueprintOverview.overseen_orgunits_notice`). The lists of missions and operations show the
owning unit of every row; with a unit selected the notices are gone.

There is **no separate context chip**. An always-on top-right context chip used to render the same
information (and was the only surface that distinguished/showed an SK pin); it was removed as
redundant once `appTitle` carried the context for every kind. Do not reintroduce a parallel
always-on context badge — the title is the single source. The Staffel-vs-SK *kind* distinction the
chip carried is intentionally not reproduced in the title; the shorthand identifies the unit.

**Enforced by:** `OrgUnitSwitcherLabelMvcTest`, `BlueprintOverviewPageControllerMvcTest`
(`view_allMyOrgUnits_namesTheOverseenOrgUnits`), `LagerAllMyOrgUnitsNoticeRenderTest`,
`ActiveOrgUnitContextE2eTest` (the Lager notice, a chosen SK staying selected)

### REQ-ORG-011 — Personal-aggregate owner retains see/edit across org-unit changes

The per-user owner of a **personal aggregate** — `InventoryItem` (`inventory_item.user`), `Ship`
(`ship.owner`) and `RefineryOrder` (`refinery_order.owner`) — may **always see and edit their own
row**, independent of the row's `owning_org_unit_id` stamp. This must hold in both boundary cases the
product owner called out:

- the owner **switched org units** — the row stays stamped to the former unit (a unit-switch is
  not a `NULL`↔non-`NULL` transition, so the [`InventoryOrgUnitReconciler`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/inventory/internal/InventoryOrgUnitReconciler.java)
  does not re-stamp inventory, see `REQ-INV-004`; ships and refinery orders have no reconciler at
  all), so the owner is no longer a member of the row's owning unit;
- the owner **has no org unit at all** yet the row is **still stamped to one** (the demote to
  `NULL` has not happened / does not apply).

Enforced in the scope kernel's `permitsOwnedRow`
([`AccessGateService`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/scope/api/AccessGateService.java)),
which the module access policies call (`canSee/canEditInventoryItem`, `canSee/canEditShip`,
`canSee/canEditRefineryOrder`; corrected 2026-10-10, the gates had moved out of the scope hub): a shared
`isCurrentUserOwner(owner)` check runs **before** the strict `owning_org_unit_id` scope check (and
before the ownerless-row branch), so the `@PreAuthorize` gate can never deny a write the service
layer would accept. The service layers already authorise the owner with no org-unit narrowing —
`InventoryItemService` on every owner action (book-out, note, delivered toggle, association change,
bulk-checkout) `item.user == currentUser`; `HangarService.updateShip`/`deleteShip` reject any
non-owner; `RefineryOrderService.updateRefineryOrder`/`deleteRefineryOrder`/`storeRefineryOrder`
admit `owner == currentUser` (or a logistician). Before this, the gates delegated org-owned rows
straight to `canEditSquadron(owning_org_unit_id)`, which 403'd the owner the moment they left the
row's unit even though the service would have allowed the edit.

**Visibility surface is unchanged.** Each aggregate's personal view already lists the owner's rows
regardless of org unit (`/inventory/my` is a pure `inventory_item.user` filter; the Hangar and
refinery "my" lists are owner-scoped), exactly as ownerless rows already behave; the strict shared
views (`/inventory/all`, the squadron Hangar/refinery lists) still filter by `owning_org_unit_id`
and never surface a foreign-org row, so this carve-out does **not** widen cross-org visibility of the
shared pools. A **non-owner** stays bound by the strict `owning_org_unit_id` scope.

**Enforced by:** `ScopeKernelTest` (`PersonalAggregateOwnerRetainsAccessTests`),
`InventoryTenancyE2eTest` (`ownerRetainsEditAfterLeavingOwningOrgUnit`) · **Code:**
`InventoryAccessPolicy#canSeeInventoryItem` / `#canEditInventoryItem`, `HangarAccessPolicy#canSeeShip` /
`#canEditShip`, `RefineryAccessPolicy#canSeeRefineryOrder` / `#canEditRefineryOrder` (corrected
2026-10-10: the refinery, inventory and ship gates left the scope hub with their modules).

### REQ-ORG-014 — Three-tier org hierarchy (Organisationsleitung > Bereich > Staffel/SK)

> **Approved planned JobOrder amendment (not implemented):** For planned JobOrders, the existing Bereich designation Department.PROFIT identifies exactly one root, independent of name/shorthand. Prevent a second designation and treat missing/contradictory root data as a prerequisite to resolve, not a first-match fallback. Active hierarchy determines eligibility; retire the independent Staffel/SK processing flag. See [orders-core.md](orders-core.md) and [ADR-0356](../adr/0356-job-orders-use-profit-hierarchy-responsibility-and-participation.md).
>
> For JobOrders, the following existing clauses, exclusions and test references are the current implementation baseline; they do not override the approved planned replacement or prove its acceptance. Requirements for other aggregates remain binding.

The org unit world gains two levels **above** Staffel and SK, modelled on the existing single-table
`org_unit` (ADR-0025): two new `OrgUnitKind` values `BEREICH` (area/division, e.g. Profit, Sub-Radar,
Raumüberlegenheit) and `ORGANISATIONSLEITUNG` (OL), plus a nullable self-referential
`parent_org_unit_id`. The hierarchy is **fixed three levels**: OL → Bereich → (Staffel | SK). A CHECK
constraint pins the parent kind by the child kind (Staffel/SK parent is a `BEREICH`; Bereich parent is
the `ORGANISATIONSLEITUNG`; OL has no parent), which also forbids cycles at fixed depth.
`Bereich`/`Organisationsleitung` are JPA subclasses, like `SpecialCommand`. Roll-out is **additive** (mirror the V97–V100 soak): `parent_org_unit_id` is NULL until an admin
assigns Bereiche, and the descent helper (REQ-ORG-015) treats a NULL parent as "no ancestor expansion",
so the system is byte-identical to today's flat behaviour while the hierarchy is being populated.

**Acceptance**

- [x] `OrgUnitKind` has `BEREICH` and `ORGANISATIONSLEITUNG`; `chk_org_unit_kind` and the discriminator
  accept them; `ddl-auto=validate` passes.
- [x] `parent_org_unit_id` is nullable with the kind-pairing CHECK; no row can parent a kind other than
  the one its level allows; the OL row has a NULL parent.
- [x] With every `parent_org_unit_id` NULL and no leadership flags set, scope-kernel scope output
  is byte-identical to pre-change (snapshot test).
- [x] An ADMIN-only management UI (`/admin/org-structure`) creates Bereiche and the Organisationsleitung
  and sets the parent edges (Staffel/SK → Bereich, Bereich → OL) over the existing `/api/v1/org-units`
  API, reading the whole structure — each unit's current parent and optimistic-lock version — from a
  single `GET /api/v1/org-units`. Leadership seats are appointed under Organisation → Leitung (REQ-ROLE-004).
- [x] Setting a Staffel's parent bumps its optimistic-lock version and evicts the cached squadron list,
  so the squadron admin page never echoes a superseded version and a later edit does not 409.

**Enforced by:** `OrgHierarchyMigrationTest` (V164: the two new kinds, the `parent_org_unit_id` column +
its kind-pairing parent trigger, the OL-has-no-parent CHECK, `ddl-auto=validate` at boot), and
`ScopeKernelTest` — the cascade is delegated to `OrgUnitCascadeService` with a no-leadership default
stub so every pre-#692 scenario stays byte-identical (the degrade-to-flat proof), pinned structurally by
`ArchitectureTest#cascadeServiceMustNotConsultTheSecurityContext`; the admin management UI by
`AdminOrgStructurePageControllerMvcTest` (ADMIN gate + AJAX create/set-parent relay) and the
`org-units` listing by `OrgHierarchyControllerSecurityTest` +
`OrgHierarchyServiceTest#listAllOrgUnits_delegatesToRepository`; the squadron-cache eviction by
`OrgHierarchySquadronCacheTest` · **ADR:**
[ADR-0025](../adr/0025-org-hierarchy-data-model.md) · **Issues:** #692, #694.

### REQ-ORG-015 — Cascading oversight without admin rights, via one descent helper

Leadership reach cascades, mirroring `ADMIN > OFFICER > LOGISTICIAN/MISSION_MANAGER`: a **Bereichsleitung**
(rank `BEREICHSLEITER` / `BEREICHSKOORDINATOR` / `BEREICHSOPERATOR`; the boolean flags this text
originally named were dropped by V187, REQ-ROLE-001) gets
officer-equivalent reach over **all Staffeln + SKs of its Bereich** (and its Bereich's own data); the
**OL** (rank `OL_MEMBER`) over **everything**. This reach is computed in **exactly one** helper
`OrgUnitCascadeService.expandWithDescendants(...)` (with `cascadedOfficerReach(...)` for the
leadership-only subset), consumed by `RequestScopeResolver.currentMemberOrgUnitIds()`;
`ScopePredicate.permits()` and the `IN :memberOrgUnitIds` JPQL cascade automatically once fed the
expanded set, so lists and per-row gates widen together (ADR-0026).

> **Implemented (epic #692, Phase 3 / #696):** the cascade is wired into
> `currentMemberOrgUnitIds()`, so it flows into `currentScopePredicate()` (all org-scoped aggregate
> lists + per-row `canSee*`/`canEdit*` gates: mission, hangar, inventory, refinery, operation,
> job-order) and the Job-Order profit gate `canViewJobOrders()`. The JWT-authority cascade ships in
> the same phase. **Phase 6 (#699, #711) then cascaded `currentOversightScope()`** together with the
> bank's read/write split: the balance **view** and the blueprint-availability overview cascade down
> (`cascadedOfficerReach`), while bank booking **requests** use the separate, non-cascading
> `currentOwnLevelOversightScope()` (REQ-BANK-027). *(Updated 2026-09-22: this note used to describe
> the oversight scope as not yet cascaded. Corrected 2026-10-10: REQ-BANK-039 replaced the own-level
> request scope with the view gate, and the unused method left the scope hub.)*

Hard invariants:

- The reach is a **concrete `memberOrgUnitIds` union — never** the `adminAllScope=true` branch (one
  narrow, read-only exception below: ADR-0048). An OL/Bereich principal **must never satisfy
  `isAdmin()`**, so all `hasRole('ADMIN')` carve-outs (SK lifecycle, system settings, stammdaten in
  REQ-ORG-005) stay ADMIN-only.
- **Strict silo:** a Bereichsleitung's union contains only its own Bereich's descendants; only the OL
  crosses Bereiche. No peer-Bereich visibility.
- An **SK-lead is not expanded** — they keep SK-only reach; their Bereichsleitung membership is
  organisational only (REQ-ORG-017).
- The JWT converter (`CustomJwtGrantedAuthoritiesConverter`) mints, for a leadership membership, the
  flat `ROLE_LOGISTICIAN`/`ROLE_MISSION_MANAGER` (so `hasRole(...)` menu gates work) **and** a contextual
  `OrgUnitContextualAuthority(role, x)` for every `x` in `cascadedOfficerReach(...)` — the Bereich itself
  plus its descendants, or every org unit for the OL — so existing
  `@PreAuthorize("@accessGateService.hasRoleInOrgUnit(#id, '…')")` gates (e.g. the SK material-claim
  gate) resolve for the units a leader reaches.
- The cascade **composes with** the personal-aggregate owner-bypass (REQ-ORG-011) and the opt-in global
  blueprint union ([ADR-0024](../adr/0024-opt-in-global-blueprint-sharing.md)); neither is weakened —
  both short-circuit ahead of or alongside the `memberOrgUnitIds` membership check, which the cascade
  only ever enlarges.

> **Amended by [ADR-0048](../adr/0048-ol-sees-every-ship-in-the-unit-overview.md) (REQ-HANGAR-003):**
> the hangar **unit overview** (`/hangar/squadron`, the hangar tab „Org-Einheit", until 2026-10-03 the page "Org-Einheitsübersicht") is the single, deliberate
> exception to the first hard invariant above. A non-pinned **OL member** is upgraded to
> `adminAllScope=true` **for that one aggregation read alone** (`RequestScopeResolver.currentUnitOverviewScope()`),
> so the OL's fleet view also includes ownerless personal ships (`owningOrgUnit == null`) of
> membership-less members — which a concrete id union can never reach. The exception is read-only and
> confined to that method: it grants **no** `isAdmin()` and leaves every other `can*` gate, scoped list
> and the `hasRole('ADMIN')` carve-outs ADMIN-only, and it does not apply when a single unit is pinned
> (a pin still narrows the overview). A plain or Bereichsleitung member is unaffected — they keep their
> exact membership/cascade reach.

**Acceptance**

- [ ] For a Bereichsleitung and the OL, list-query scope equals `permits()` for every descendant
  **and** denies every non-descendant; a foreign pin counts as no pin (REQ-ORG-032).
- [x] The cascade holds only while the leader holds `KRT Member`; without it no membership confers
  reach, not even the direct memberships (ADR-0300, REQ-ORG-032).
- [x] A Bereichsleitung is denied another Bereich's data in both lists and detail gates (strict silo).
- [x] An OL/Bereich principal fails `isAdmin()` and every `hasRole('ADMIN')` carve-out.
- [x] An SK-lead's reach is SK-only (no Bereich expansion).
- [ ] The REQ-ORG-011 owner-bypass and the ADR-0024 global-sharing union still hold for a leadership
  principal.

**Enforced by:** `OrgUnitCascadeServiceTest` (cascade math + strict silo + no-flag short-circuit +
SK-lead no-cascade + per-request memoisation), `ScopeKernelTest` (cascade routed into the
scope predicate; `adminAllScope` never set),
`CustomJwtGrantedAuthoritiesConverterTest` (flat + cascaded contextual authorities),
`OrgHierarchyVisibilityMatrixE2eTest` (strict silo, no admin escalation, a plain member sees no Bereich
data) · **ADR:** [ADR-0026](../adr/0026-cascading-scope-without-admin.md) · also pins
[REQ-SEC-015](security-and-access.md) · **Issues:** #692, #696.

### REQ-ORG-016 — Bereich/OL as direct owners of org-unit-scoped aggregates

> **Approved planned JobOrder amendment (not implemented):** The approved [JobOrder contract](orders-core.md) admits direct Profit-Bereich responsibility for MATERIAL, ITEM and aUEC and immediate transfer by current responsible-unit writers to another eligible unit, without target acceptance or automatic stock/money movement. Invalid legacy or later ineligible responsibility permits only reading/transfer before any processing, correction, settlement or coupled posting. Preserve real history; do not reparent units automatically. End ineligible support/open promises immediately, with history retained; automatic eligibility maintenance is separate from the blocked user writes. Other aggregates keep their existing ownership rules.
>
> For JobOrders, the following existing clauses, exclusions and test references are the current implementation baseline; they do not override the approved planned replacement or prove its acceptance. Requirements for other aggregates remain binding.

A Bereich and the OL **own their own data** — `owning_org_unit_id` may reference a `BEREICH` or
`ORGANISATIONSLEITUNG` org_unit — in addition to overseeing subordinate units (ADR-0027). Leadership may
also **create on behalf of** a subordinate Staffel/SK. The create-time stamping matrix (REQ-ORG-004) is
extended:

- **Validation** of a picker choice widens to `(direct memberships ∪ oversight descendants)`: a
  Bereichsleitung may stamp its own Bereich or any descendant; the OL anything.
- **Auto-stamp stays on a single DIRECT membership** (a leader's default owner = their own Bereich/OL;
  `>1` direct forces an explicit pick; a foreign / out-of-oversight pick is a 400) — ordinary members'
  stamping is unchanged.
- **Create-on-behalf is authorised by `canEditOrgUnit(target)`** (cascades per REQ-ORG-015), not by
  admin-ness. This gate lives in the shared **owning-org-unit picker resolver**
  (`OrgUnitStampingService.resolveStampedOrgUnit`, reached directly): an explicit pick is accepted when it is a DIRECT
  membership of the **target user** *or* an org unit the current **caller** may edit. The gate keys
  `canEditOrgUnit` on the caller while the membership set is the target user's, so the two coincide for
  every **self-service** create (caller = target) — there an ordinary member, whose `canEditOrgUnit`
  reach equals their own memberships, is byte-identical to today — and diverge only on the two
  **create-on-behalf** paths where caller ≠ target (inventory book-out/transfer, refinery store, and
  the Einbuchen create `POST /api/v1/inventory` — a third path this clause did not name until the
  2026-08-30 audit found it ungated): there
  the accepted set is `(target's memberships) ∪ (caller's editable scope)` by design, so a leader may
  place the recipient's row in any unit the leader already controls (never widening what the caller can
  see; the REQ-ORG-011 owner-escape keeps the recipient's own visibility). The same `canEditOrgUnit` gate
  already governs the job-order **reassign** path (`reassignResponsibleOrgUnit`, escalate-only for
  non-admins).

  > **Implementation note (Phase 4, #697):** the gate is **not** added to the create-time
  > `JobOrderService.resolveResponsibleOrgUnit` / `resolveRequestingOrgUnit`. Those intentionally accept
  > **any profit-eligible** (responsible) / **any** (requesting) org unit so the shared-SK-queue intake
  > model (#343) keeps working — a squadron must be able to direct work to an SK it does not edit.
  > Tightening them would regress that flow, so create-time responsible/requesting stay open and the
  > nuanced reassign gate (already cascade-aware) remains the only scope check on the responsible field.

- Visibility reuses the existing scope + public/internal escape rules unchanged; strict silo holds —
  no per-aggregate code change was needed: a `BEREICH`/`OL`-owned row's `owning_org_unit_id` flows
  through the same `canSee*`/`canEdit*` gates and `IN :memberOrgUnitIds` list queries, which the Phase-3
  cascade already widens for the owning leadership and keeps closed for subordinates. The ownerless
  `NULL` leadership path (REQ-ORG-009) is **preserved unchanged** (no backfill). The REQ-ORG-011 owner-bypass stays ahead of the scope check.

> **Amended by ADR-0374 (REQ-ORG-032):** the direct memberships here are the stampable units of
> REQ-ORG-004 (active units, `KRT Member`), the units a caller may edit lie inside the active
> context, and a mission or operation created without a pick is stamped on the honoured pin, so a
> leader pinned to a unit below them creates it there; a personal row keeps to its owner's
> stampable units.

**Acceptance**

- [ ] A Bereich-owned and an OL-owned aggregate can be created, read and edited by that level's
  leadership; a subordinate cannot see the level above (strict silo).
- [ ] Create-on-behalf of a descendant succeeds; of a non-descendant fails (400/403).
- [ ] Self-service stamping (caller = target user) is byte-identical to today for ordinary members and
  officers; the only widening is the caller ≠ target create-on-behalf paths (book-out/transfer, store).
- [ ] The ownerless `NULL` path behaves exactly as REQ-ORG-009.
- [x] The create-form pickers surface the Bereich/OL tiers in the UI: the Mission/Operation/Refinery/
  Inventory **owning** picker (cascade-scoped via `/api/v1/org-units/me/pickable`, Phase 5) and
  the Job Order **requesting** (Auftraggeber) picker (every active unit via
  `/api/v1/org-units/active-all-kinds`; since ADR-0149 the order form has no anonymous caller, so
  the Staffel/SK-only `/api/v1/org-units/active` catalogue is its degradation path rather than a
  second audience's). The Job Order **responsible** picker
  stays profit-eligible Staffel/SK only — Bereiche/OL are never profit-eligible, so they can be the
  customer but never the processor.

**Enforced by:** `ScopeKernelTest` (the `BereichOlOwnershipStampingTests` nest — Bereich **and OL**
resolved as owners, create-on-behalf of a descendant **Staffel and SK** via `canEditOrgUnit`, the
caller ≠ target divergence keyed on the caller's scope, foreign-to-both pick still 400, strict-silo
read/edit lock); existing picker-resolver + per-aggregate stamping/visibility tests stay green
(self-service stamping unchanged); the Job Order requesting-picker surfacing by
`JobOrderPageControllerResponsiblePickerMvcTest` (the picker offers Bereich/OL, responsible excludes them); visibility-matrix e2e (`OrgHierarchyVisibilityMatrixE2eTest`, Phase 7) · **ADR:**
[ADR-0027](../adr/0027-bereich-ol-aggregate-ownership.md) · **Issues:** #692, #697.

### REQ-ORG-017 — Membership cardinality & exclusivity rules

`org_unit_membership` gains leadership flags `is_bereichsleiter` / `is_bereichskoordinator` /
`is_bereichsoperator` (valid only on `BEREICH`-kind rows) and `is_ol_member` (only on
`ORGANISATIONSLEITUNG`-kind rows), each pinned to its kind by a CHECK (mirroring
`chk_org_unit_membership_lead_only_on_special_command`). The cardinality/exclusivity rules:

1. A member may belong to **up to two Staffeln** (possibly from different Bereiche) and **any number of
   SKs** — the V95 `uq_org_unit_membership_one_squadron` "≤1 squadron" guard relaxes to "≤2".
2. An **SK-lead** (`is_lead=true`) belongs to **no Staffel** and is **always** an (organisational,
   reach-less per REQ-ORG-015) member of the **Bereichsleitung of the Bereich its SK belongs to**.
   This Bereichsleitung membership is **derived, not stored**: it is computed from `is_lead` + the
   SK's `parent_org_unit_id` wherever it is needed, so there is no separate
   membership row to keep in sync and no stale seat on demotion or SK re-parenting. (An admin may
   still grant such a user an *explicit, flagged* Bereichsleitung role via
   `POST /api/v1/org-units/bereiche/{id}/members` — that is an additive, reach-bearing grant,
   independent of the derived organisational seat.)
3. **Bereichsleitung members** (`is_bereichsleiter`/`koordinator`/`operator`) belong to **no Staffel**.
4. **OL members** (`is_ol_member`) belong to **no Staffel**, but **may** belong to a Bereich.
5. A user may hold leadership in **more than one** Bereich (the reach unions per REQ-ORG-015).

Rules 1 and the flag-kind pairing are expressible at the DB layer (a relaxed guard + CHECKs). The
cross-row rules (2–4: "leadership/OL/SK-lead hold no Staffel", "≤2 Staffeln") cannot be a single-row
CHECK and are enforced by a **trigger AND a service-layer guard** (defence in depth); raw-SQL/curl paths
must not be able to violate them. `membership.kind` and the trigger-synced flags stay
`insertable=false, updatable=false`.

An admin assigns the (up to two) Staffeln on the **member-edit page**: two fixed Staffel slots, each
with its own Logistician / Mission-Manager flags, are folded into the `staffeln` list of the single
membership-delta `PATCH /api/v1/org-units/members/{id}/memberships` and **reconciled** against the user's current
Staffel memberships by `OrgUnitMembershipService.reconcileStaffelMemberships` — squadrons are added /
removed and per-squadron flags patched in one transaction (removals are deleted before any insert so
the ≤2 counting trigger never miscounts a re-point). The legacy per-flag query-param toggle endpoints
(`PATCH /api/v1/users/{id}/logistician` / `…/mission-manager`) and the member-list flag toggles were
removed; the membership-delta reconcile is the single Staffel-flag write path. The `UserDto` exposes
the complete Staffel set as `squadrons` (with `squadron` retained as the primary for API stability).
When a reconcile grants a previously membership-less user their first Staffel(n) the
ownerless-personal inventory adopts the **name-sorted primary** of the newly added Staffeln — not
whichever the client listed first — matching the same primary every other surface derives. The
admin member-edit form only sends the `staffeln` set when the authoritative per-Staffel detail
(`GET /api/v1/org-units/members/{id}/memberships/detail`, which carries each Staffel's own flags) loaded on the
edit-form GET; if that fetch failed the two slots render blank with a notice and the save **skips**
the Staffel reconcile entirely, so a transient detail outage can never be misread as "remove every
Staffel" and silently strip the member's memberships.

Two-Staffel support is then honoured **project-wide** so a dual-Staffel member is never silently
dropped to one:

- **Active context** — current-context surfaces show the effective context of REQ-ORG-032
  (`RequestScopeResolver.currentActiveContext()`): the honoured pin, else the member's only unit in
  reach, else „Alle meine Org-Einheiten". The create-time auto-stamp honours the same pin
  (`currentPin()`) and otherwise follows "pin, else choose" below. The deterministic
  **name-sorted primary** Staffel (`UserDto.squadron` /
  `UserMapper.resolveSquadron`) describes the member and is never their active context. Its single
  owner is `StaffelMembershipResolver` (ADR-0047); it drops a dangling Staffel membership (one whose
  squadron no longer resolves) **identically whether the user holds one Staffel or two**, so a
  corrupt row never leaks into one branch while being filtered from the other.
- **Member directory** — the user list, the name searches and the `/lookup` typeahead
  (`UserDirectoryService`, `UserReferenceSearchService`, `UserService#findAllReference`) cover
  **every** member, whatever the caller's units or pin: anyone finds anyone, as the owner decided.
  What a row shows is the caller's projection (`AdminOrSelfUserProjection`): the full record to an
  admin and to the member themself, the peer shape of REQ-SEC-007 to everyone else.
- **Oversight** covers the units of an officer's leadership seats and, for a Bereich or OL seat,
  the units below it (`RequestScopeResolver.currentOversightScope()`, ADR-0300) — not every
  Staffel the officer belongs to: a plain membership of a second Staffel adds no oversight there.
- **Order-aligned audit** — a Job-Order handover's `executingSquadron` snapshot records the executor's
  Staffel that matches the order's responsible org unit, else their primary
  (`findExecutingStaffelForOrder`).
- **Pin, else choose** at create time — the create-time owner-stamping matrix (REQ-ORG-004
  §5.5.1) honours the active pin first, per entry point (REQ-ORG-032). A personal row
  (`resolveOrgUnitForPickerOutputNullable`) is stamped on the honoured pin only when the pin is one
  of its owner's stampable units, otherwise on the owner's only stampable unit. A mission or
  operation (`resolveOrgUnitForUnitOwnedAggregate`) is stamped on the honoured pin, which its
  creator may always edit — a membershipless admin's pin included —, otherwise on the creator's
  only stampable unit. Several stampable units without such a pin still 400 "owningOrgUnitId is
  required" so the create form forces an explicit choice (the owner picker lists **both** Staffeln,
  sourced from `/api/v1/org-units/members/{id}/memberships` / `/me/pickable-org-units`). A
  single-stamp is never silent.
- The single-Staffel `PATCH /api/v1/users/{id}/squadron` admin endpoint (and its destructive
  `syncStaffelMembership`) were **removed** — it could silently drop a member's second Staffel; all
  Staffel assignment now flows through the additive membership-delta reconcile.

**Acceptance**

- [x] A third Staffel membership is rejected; a second is accepted.
- [x] An admin assigns up to two Staffeln on the member-edit page, each with its own Logistician /
  Mission-Manager flags, in one membership-delta reconcile; clearing a slot drops that Staffel
  membership and an empty set removes every Staffel membership.
- [x] A duplicate squadron in the desired set, or more than two squadrons, is rejected with 400.
- [x] Making a user an SK-lead while they hold a Staffel membership is rejected. The lead's
  reach-less Bereichsleitung seat is **derived** from the `SK_LEAD` rank + the SK's parent Bereich
  (rule 2), never stored as a membership row. *(Corrected 2026-09-22: this line used to say the seat
  was auto-added as a membership, which contradicted rule 2 and the code.)*
- [x] A Bereichsleitung or OL flag on a user who holds a Staffel membership is rejected.
- [x] A leadership flag on the wrong `kind` is rejected by CHECK.

**Enforced by:** `OrgUnitMembershipServiceTest` (the service-layer guards incl.
`reconcileStaffelMemberships` add / remove / flag-patch / duplicate / >2 / leadership),
`UserServiceMembershipDeltaTest` (the delta orchestrator forwards `staffeln` to the reconcile
through the identity-owned `MembershipOperations` seam), `UserServiceMembershipSeamDifferentialTest`
(the delta paths through the seam leave the same rows and audit entries and fail alike as the direct
calls) and `OrgUnitMembershipOperationsTest` (the seam is a pass-through that also sets the initial flags),
`MemberManagementControllerTest` (the member-edit two-slot fold and the detail-load wipe-guard),
`UserDirectoryQueriesDataTest` (the directory queries return the members of every Staffel and the
users without one, against the real dialect) and `OrgHierarchyMigrationTest`
(the ≤2-Staffel counting triggers on INSERT+UPDATE, the leader-excludes-Staffel trigger, and the
`chk_org_unit_membership_bereich_flags_only_on_bereich` / `chk_org_unit_membership_ol_flag_only_on_ol`
CHECKs — DB-side defence in depth) · **Issues:** #692, #695.

> **Amended by epic #800 (REQ-ROLE-001, role-model.md):** the five boolean leadership flags
> (`is_lead` / `is_bereichsleiter` / `is_bereichskoordinator` / `is_bereichsoperator` / `is_ol_member`)
> were replaced by the unified `role` enum and **dropped** in the Phase-5 cleanup (V187), so every
> rule above now reads `role`: "SK-lead" is `role = SK_LEAD`, "Bereichsleitung member" is a Bereich
> rank, "OL member" is `OL_MEMBER`. The leader-excludes-Staffel trigger
> (`enforce_leader_excludes_squadron`) was rewritten onto `role` with the silo set
> `{SK_LEAD, BEREICHSLEITER, BEREICHSKOORDINATOR, BEREICHSOPERATOR, OL_MEMBER}`; the new **squadron
> ranks** (Staffelleiter / Kommandoleiter / stellv. / Ensign) are EXEMPT — they ARE Staffel members.
> The trigger now pre-empts the kind CHECK for a silo-rank-on-Staffel write (both reject; only the
> message differs). The kind pairing is now the single `chk_org_unit_membership_role_kind` CHECK
> (V184). Proven by `OrgHierarchyMigrationTest#v187LeaderExclusionTriggerReadsRole_squadronRanksExempt`.

### REQ-ORG-018 — Mission owning-OrgUnit reassignment

A mission's `owning_org_unit_id` is **no longer immutable after creation** (it is still create-time
stamped per REQ-ORG-004). The mission edit mode (the former Verwaltung tab, REQ-MISSION-004) exposes a "Verantwortliche Einheit" control that
re-homes an existing mission to a different OrgUnit — Staffel, Spezialkommando, Bereich or
Organisationsleitung (REQ-ORG-016) — or to **ownerless** (`owning_org_unit_id = NULL`, the
public-leadership form of REQ-ORG-009). The reassignment is `PUT
/api/v1/missions/{id}/owning-org-unit` (`UpdateMissionOwningOrgUnitRequest{owningOrgUnitId, version}`),
served by `MissionService.updateOwningOrgUnit` and recorded as the audited
`MISSION_OWNING_ORG_UNIT_CHANGED` event (org-unit `kind:id` from/to refs only — no PII, REQ-AUDIT-001).

**Permission — two orthogonal gates.**

1. Per-mission write gate `@missionAccessPolicy.canChangeOwner` — the same gate as the user-owner
   change beside it: the mission owner, or officer reach in the mission's owning unit inside the
   active context, which an admin holds within their own scope; a mission without a unit is left
   to an admin without a pin (REQ-SEC-065, REQ-ORG-032).
2. Target gate `OrgUnitStampingService.resolveReassignTargetOrgUnit` — an **admin** may assign to any
   OrgUnit or to ownerless; a **non-admin** may only pick a target that is one of their direct
   memberships or within their editable scope (`canEditOrgUnit`, the REQ-ORG-016 cascade), and may
   pick **ownerless only when membershipless** (mirroring who may create an ownerless mission). An
   unknown target id is a 400; a disallowed target is a 403.

**Re-scoping semantics.** Re-homing **retroactively** changes who may see/edit the mission via the
existing `owningOrgUnit` + `is_internal` gates (REQ-ORG-003 and the `canSeeMission` / `holdsMissionManagerGrantFor`
matrix) — there is no frozen create-time ACL. Moving `NULL → org` *narrows* a public-leadership
mission to that org (plus the `is_internal = false` cross-staffel escape); `org → NULL` *widens* it to
the public/members default; `org A → org B` re-points the scope. `is_internal` is **orthogonal** and
is **not** auto-adjusted — hiding a previously public mission still requires flipping `is_internal`
separately. The change **does not cascade** to the mission's participants, finance entries, units or
linked operation, which keep their own ownership.

**Concurrency.** Guarded by the dedicated section-scoped counter `Mission.owningOrgUnitVersion`
(`@OptimisticLock(excluded = true)`, V195), in the same family as `coreVersion` / `partyLeadVersion` —
a reassignment never bumps `Mission.version` and never 409s a concurrent edit of an unrelated section;
two managers racing on the assignment surface a 409 against each other. The frontend control lives in
the `#mission-mgmt-results` swap container and updates live (no reload), patching the sticky header
badge in place (REQ-FE-001).

**Acceptance**

- [x] A manager / scope-officer / admin reassigns a mission to an org unit they may assign to; the
  change is audited and visibility re-scopes per the gates.
- [x] A non-admin picking a target outside their assignable scope is rejected (403); an unknown target
  id is 400.
- [x] A non-admin who holds a membership cannot set the mission ownerless; an admin (or a
  membershipless leadership caller) can.
- [x] A stale `owningOrgUnitVersion` yields 409; a concurrent edit of another section (core / schedule)
  does **not** 409.

**Decided by:** ADR-0050. **Enforced by:** `MissionServiceSectionPatchTest` /
`MissionServiceLifecycleTest` (happy path, 409, target validation, audit), `ScopeKernelTest`
(`resolveReassignTargetOrgUnit`), `MissionControllerSecurityTest` (the `canChangeOwner` gate).

### REQ-ORG-019 — Bereich and OL own their bank accounts through the org-unit FK

Since Bereiche and the OL are `org_unit` rows (REQ-ORG-014), the bank's `AREA` account is owned by
its Bereich and the `CARTEL` account by the Organisationsleitung through the same `org_unit` foreign
key an `ORG_UNIT` (Staffel/SK) account uses — one account per org unit, no free-form area names
(`V168`). The org-unit pickers that feed account creation read every active kind from
`GET /api/v1/org-units/active-all-kinds`. The id was allocated by epic #692 Phase 6 and is cited by
the code; the full requirement — the cascading view and own-level requests built on this linkage —
is specified in [`bank.md`](bank.md) REQ-BANK-027 (ADR-0028). *(Section added 2026-09-22: the id was
referenced from the code and `INDEX.md` but had no entry in any spec.)*

**Enforced by:** `V168BankAreaCartelLinkageMigrationTest`, `BankAccountServiceTest`,
`OrgUnitControllerTest`, `BankManagePageControllerOrgUnitPickerMvcTest` · **Code:**
`BankAccountService`, `BankAccount`, `OrgUnitController#listActiveOrgUnitsAllKinds`,
`V168__link_bank_area_cartel_to_org_unit.sql` · **Issues:** #692, #699.

### REQ-ORG-021 — The Organisationsleitung has a single Grand Admiral

The OL may designate **exactly one** of its members as the **Grand Admiral** (label untranslated,
DE = EN). The designation is a **title only**: the holder keeps the `OL_MEMBER` rank, so their rights
are identical to any OL member — the org-wide officer reach and CARTEL-account responsibility of
REQ-ORG-015 are unchanged. Source of truth is the OL row's nullable `grand_admiral_user_id` (`V215`; the free-text column went with `V332`);
because the OL is a singleton tier, that one column is the org-wide "at most one" guarantee, and
`chk_org_unit_grand_admiral_only_ol` (`V332`) keeps it on the OL row.

Appointment is **ADMIN-only** via `PUT /api/v1/org-units/organisationsleitung/{id}/grand-admiral`
with a required `userId`: a user who is not yet an OL member is **auto-added** as one first.
`DELETE …/grand-admiral` vacates the post and leaves the OL membership intact; removing the holder's
OL membership vacates it too. Setting and clearing are audited as `ROLE_CHANGED` with a
`grandAdmiral` detail. The Leitung view (REQ-ROLE-004) returns the holder as
`LeitungUnitDto.grandAdmiralUserId` and marks them in the OL roster.

**Acceptance**

- [x] `PUT …/grand-admiral` designates the user (auto-adding OL membership when needed), is
  idempotent and holds the org-wide singleton; a request without `userId` is a 400.
- [x] `DELETE …/grand-admiral` vacates and leaves the OL membership intact.
- [x] Removing the Grand Admiral's OL membership vacates the post, also when the OL is loaded as a
  Hibernate proxy.
- [x] The holder's rights are exactly an OL member's.

**Enforced by:** `OrgUnitMembershipServiceTest` (`setGrandAdmiral*`, `removeGrandAdmiral*`),
`OrgUnitMembershipServiceProxyIntegrationTest`, `LeitungPagePatternRenderTest` · **Code:**
`OrgUnitMembershipService#setGrandAdmiral` / `#removeGrandAdmiral`,
`Organisationsleitung#grandAdmiralUserId`, `OrgHierarchyController` · **Issues:** #2987

### REQ-ORG-027 — The participant escape opens the operation, not its ledger

> **Renumbered 2026-09-22:** this requirement was `REQ-ORG-021` until 2026-09-22; that id names the single Grand Admiral of the Organisationsleitung, now [REQ-ORG-021](#req-org-021--the-organisationsleitung-has-a-single-grand-admiral) in this file.

`OperationAccessPolicy.canSeeOperation` (bean `operationAccessPolicy`, the operation module's
access policy since 2026-10-05; the scope hub's `OwnerScopeService.canSeeOperation` before) admits
a caller either through org-unit scope (or the
ownerless-leadership case of REQ-ORG-009) **or** through the participant escape of ADR-0006. The
second key is **self-issuable**: `POST /api/v1/missions/{id}/join` is gated only on
`isAuthenticated() and canSeeMission(#id)`, and `canSeeMission` is `true` for every non-internal
mission of every org unit, so one request enrols a member of Staffel A in a public mission of
Staffel B and unlocks that Staffel's operation.

The operation's **financial** surface MUST therefore be gated on scope alone, through the sibling
predicate `canSeeOperationLedger` (the same two branches **minus** the participant escape):

- `GET /api/v1/operations/{id}/finances`, `/{id}/finances/{missionId}` and `/{id}/finance-summary`
  require `canSeeOperationLedger`. They are org-unit financial records with no per-caller
  projection, and `/finance-summary` additionally aggregates the operation's **internal** child
  missions, which `canSeeMission` would refuse the same caller directly. The ledger lists the
  refinery orders of every linked mission, those a participant of another unit filed included
  (REQ-SEC-042): by owner decision this is an explicitly cross-unit part of the ledger
  (ADR-0374 D9).
- `GET /api/v1/operations/{id}/payouts` keeps `canSeeOperation`, and a caller who passes **only**
  through the escape receives **their own payout row and nothing else**. `totalDonations` is
  recomputed over the reduced list, so the operation-wide aggregate does not leak either.

Until this requirement, the escape carried the full breakdown: per participant across every mission
of the operation the callsign, participation percentage, `personalExpenses`, `shareAmount`,
`donatedAmount`, `transferFee`, `payoutAmount` and the confirming officer. ADR-0006 rejected "make
every operation visible to all organisation members" because *participation is the right, minimal
key* — reasoning that assumes participation is granted rather than claimed.

**Acceptance**

- [x] A member who joined a foreign unit's public mission can open the operation and read their own
  payout row.
- [x] The same caller sees no other participant's payout row, and `totalDonations` reflects only
  what they can see.
- [x] The same caller is refused `/finances`, `/finances/{missionId}` and `/finance-summary` (403).
- [x] The same caller cannot link a mission to the operation and does not find it in the mission's
  operation picker (REQ-MISSION-034).
- [x] A caller inside the operation's scope is unaffected on all five endpoints.

**Decided by:** ADR-0150. **Enforced by:** `OperationPayoutServiceTest`
(`summary_escapeOnlyCaller_seesOnlyTheirOwnRow`), `OperationAccessPolicy#canSeeOperationLedger`,
`OperationModuleContractTest`.

### REQ-ORG-023 — The owner-picker rejection is a stable code, never English prose

REQ-ORG-017's "pin, else choose" ends in a `400` when a member of more than one org unit supplies no
`owningOrgUnitId` and carries no honourable active-context pin. That rejection is correct and stays.
What was wrong is how it reached the member.

It was thrown as a generic `BadRequestException`, so the RFC 7807 body carried the catch-all code
`BAD_REQUEST` and the frontend had nothing to branch on. Every picker surface therefore fell through
to its last resort — render `problem.detail` — and `detail` is the backend's own developer-facing
English:

> User belongs to multiple org units; owningOrgUnitId is required

which a member on the German UI read in a toast on 2026-09-16. That is two defects at once: an i18n
violation (a user-visible string that comes from neither `messages_de` nor `messages_en`), and an
instruction nobody can act on — it names a JSON field, not the control on the screen.

**The rule:** a service-layer rejection the member can fix carries a **stable problem code**, and the
client renders its own localized wording from that code. Concretely for this one:

- The backend throws `OwnerOrgUnitRequiredException` — a `DomainProblem` of the scope module
  (`scope.api`), code **`OWNER_ORG_UNIT_REQUIRED`** from `ScopeProblemCode`, still `400`. A pick the caller may *not*
  make stays an `AccessDeniedException`; this code means no pick was made at all.
- The frontend renders it **centrally**, in `krt-fetch.js`, from `window.krtOwnerPickerI18n.required`
  (bundle key `ownerPicker.error.required`, DE + EN). The failure can arrive on any of the five
  picker surfaces — book-in, hangar, mission, operation, refinery order — and one sentence localized
  once beats five copies drifting apart.
- A page-local `onError` handler bypasses `handleProblem` entirely, so the branch is **exposed** as
  `krtFetch.ownerOrgUnitRequiredMessage(problem)` rather than re-derived. `inventory-input.js`,
  `mission-detail.js` and `refinery-orders-create.js` are the three such handlers today; each calls
  it before its own fallback. The refinery create form shows its „Materialien ungültig" message only
  for a `400` without a problem code, its own empty-goods check, never for a backend rejection.

> [!note] What this requirement does **not** do
> It does not make the picker a required field. The picker renders whenever the option list has more
> than one entry, but that list is the caller's **pickable** set — direct memberships *plus* a
> leader's cascading reach (REQ-ORG-016) — while the backend forces a choice on the count of
> **direct** memberships and on whether a pin applies. A client cannot tell the two apart without
> either duplicating the stamping rule or widening `/me/pickable-org-units`, and a picker marked
> required on a leader with one membership would refuse a submit the backend would have accepted. So
> the form still lets the member submit, and the rejection now tells them what to do.

**What the picker shows.** It names no outcome it cannot promise. When the active org unit of the
sidebar (`activeSquadronId`) is one of its options and nothing else is selected, that unit is
**preselected** and the empty choice is left out, so the stamp is the unit the sidebar shows.
`activeSquadronId` is the unit of the effective context the backend reports (REQ-ORG-032), so in
„Alle meine Org-Einheiten" nothing is preselected.
Otherwise the empty choice reads „-- Bitte wählen --" / „-- Please choose --". *Amended 2026-10-02 by
the owner: the empty choice read „-- Heimat-Staffel beibehalten --", although an empty pick resolves
to the only direct membership or the pin, and is refused otherwise.* The rule lives in the shared
`kernel/fragments/owner-picker.html` and so holds on all five surfaces.

On the **on-behalf book-in** (`/inventory/input?source=admin`) the picker follows the member being
booked for: it is rendered even with one option (hidden), and choosing a member refills it from
`GET /users/{id}/memberships?allKinds=true` — that member's **direct** memberships, which is exactly
the set the backend stamps from. Because the set is the direct one, the client may require a choice
there: with several memberships and the active unit not among them, the picker is shown and
required; with one it is hidden and preselected; with none it is hidden and empty. Choosing the own
entry again restores the caller's options.

On the **refinery order create form** the picker is built for the order's owner from
`GET /api/v1/org-units/members/{id}/memberships?allKinds=true` — the owner's direct memberships of
all four kinds, the set the backend counts. Built from Staffel and SK alone, a member of one Staffel
and one Bereich got no picker and a refusal on every submit.

On the **production book-in** of an item order (the „Herstellung erfassen" modal, REQ-ORDERS-025)
`orders-detail-production.js` builds the picker from the owner's direct memberships
(`GET /users/{id}/memberships?allKinds=true`) and reads the active org unit from the wrapper's
`data-owner-picker-active`, as the shared fragment does. It preselects the active org unit when the
owner is a direct member of it, else the owner's only membership; otherwise it leads with the empty
choice and is required, because the list is the owner's direct set, as on the on-behalf book-in.
The booking stays disabled until a unit is chosen and while the memberships load.

**Acceptance**

- [ ] With the active org unit among the options, the picker preselects it and offers no empty
  choice; without it, the empty choice reads „Bitte wählen".
- [ ] On the on-behalf book-in, choosing a member replaces the options with that member's direct
  memberships and requires a pick when there are several and none is preselected.
- [ ] On the production book-in, the picker preselects the active org unit when the owner belongs to
  it, else the owner's only membership, and otherwise requires a pick before the booking.
- [ ] A member of more than one org unit who submits a picker form with no choice and no pin gets a
  `400` whose problem `code` is `OWNER_ORG_UNIT_REQUIRED`, from **both** stamping entry points.
- [ ] The member sees a localized instruction naming the control, in DE and EN — never the backend's
  `detail`.
- [ ] A member with an honourable pin is stamped and sees no message at all.
- [ ] The rejection stays a `400`; it is neither a conflict nor a permission failure.
- [ ] A page-local `onError` handler renders the same wording as the central path.

**Enforced by:** `OrgUnitStampingServiceOwnerRequiredTest` (both entry points, the status, and the
pinned negative), `InventoryPageControllerMvcTest` (preselect, placeholder, hidden on-behalf picker),
`InventoryOperationsE2eTest#onBehalfOwnerPickerFollowsTheChosenMember`,
`orders-detail-production.test.mjs` (the production book-in's preselection and required pick) ·
**Code:** `kernel/fragments/owner-picker.html`, `inventory-input.js`, `orders-detail-production.js`,
`OrgUnitStampingService#resolveStampedOrgUnit` /
`#resolveSquadronForPickerOutput`, `scope/api/OwnerOrgUnitRequiredException`, `ScopeProblemCode`,
`krt-fetch.js#ownerOrgUnitRequiredMessage`, `kernel/fragments/head.html` ·
**Related:** REQ-ORG-016, REQ-ORG-017, REQ-API-* (RFC 7807), the i18n rule in the root `AGENTS.md`

### REQ-ORG-028 — A scoped aggregate carries `@TenantScoped`, and the scope rules select by it

Every entity whose rows belong to an owning or responsible org unit carries the marker
`backend.platform.api.TenantScoped`, naming the field that holds that unit. Today that is `Mission`,
`Operation`, `Ship`, `InventoryItem` and `RefineryOrder` (`owningOrgUnit`), `JobOrder`
(`responsibleOrgUnit`).

- **Every org-unit reference is classified.** Every persistent field of an entity that refers to an
  org unit — an association to `OrgUnit` or a subtype, a collection of them, or a `UUID` column
  named `…org_unit_id` / `…squadron_id`, embedded keys included — is either named by the entity's
  marker or listed, with its reason, as no tenancy boundary. Order-scoped participation units,
  observed unit-read history and retained provision-credit units are relationships within the order,
  not aggregate ownership; reads still follow the order's current access policy.
  Other examples include the job order's customer unit, the executing unit of the two handovers, the material
  claim's claiming unit, the participant's affiliation, the Materialbörse's offer and request
  attribution (the board is organisation-wide), the bank account holder and the two bank
  counterparties, the Kommandogruppe's Staffel, the hierarchy parent, a member's stored choice of the
  active context (REQ-ORG-031) and the membership key. A marker naming a field that is no org-unit reference, a reference both
  marked and listed, and a list entry that matches nothing fail the build too.
- **Tenant data** is every marked entity and, transitively, every entity with a mandatory to-one
  association to one (an aggregate part: mission participants, job-order materials, refinery goods, …).
- **The write gate is selected by what a controller writes, not by its name.** A controller that
  injects a bean writing tenant data or the org-unit tree (`save*`, `delete*` or a `@Modifying`
  query on its repository) has each write mapping with a `UUID` path variable checked: the effective
  `@PreAuthorize` must gate **every** top-level `or` branch on a scope bean (`requestScopeResolver`, `accessGateService`,
  `accessGateService`, `missionAccessPolicy`, `specialCommandSecurityService`,
  `orgRoleManagementSecurityService`) or on `hasRole('ADMIN')` alone, read from the SpEL syntax tree.
  A handler whose scope check lives in the service layer is listed with its reason and must reach a
  `can*` call on a scope service, or a method whose own `@PreAuthorize` is scope-gated, within three
  calls; a selected handler that writes no tenant data is listed with its reason.

This selects strictly more than the seven controller names the earlier rule listed, which therefore
can be retired once both rules are merged.

**Acceptance**

- [x] Every org-unit reference of every entity is marked or listed with a reason (22 references
  today, a floor).
- [x] The eight aggregates above carry the marker.
- [x] Every selected write handler is scope-gated, service-gated with a reachable scope check, or
  listed as no tenant write (24 controllers, 126 handlers today, floors); the seven controllers of
  the earlier whitelist are among them.
- [x] Each rule fails on planted fixtures: an unmarked owning entity, a marker on a plain column, a
  stale list entry, an `or isAuthenticated()` branch beside a scope call, a class-level gate only,
  and a service-gated handler whose service never checks scope.

**Enforced by:** `TenancyGuardTest`, `TenancyGuardRules`, `SpelScopeGate` · **Code:**
`TenantScoped` · **Related:** REQ-ORG-002, REQ-ORG-003, REQ-ORG-006, REQ-SEC-077

### REQ-ORG-031 — A member's choice of the active org unit is stored and restored

A member who picks an org unit under **„Aktive Org-Einheit"** finds it again at the next login:
every switch is stored per member, and a new session starts from the stored choice (ADR-0374).

- **Storage.** The scope module's table `member_active_org_unit` holds one row per member (`user_id`
  key, own `version`); `org_unit_id = NULL` is an explicit „Alle meine Org-Einheiten". Deleting the
  account deletes the row; deleting the org unit clears the choice.
- **API.** `GET /api/v1/org-units/me/active-choice` returns `{orgUnitId, version}`; a stored unit
  the caller may no longer select under REQ-ORG-032 reads as `null`. `PUT` with `{orgUnitId,
  version}` stores the choice: a unit the caller may not select is a `400`, a stale `version` a
  `409`, and an unchanged choice writes nothing. The switcher sends no version, so the latest
  switch wins: an attempt that loses a concurrent insert or update of the member's row is retried
  in a fresh transaction, up to five attempts, and overlapping switches answer no `409`; only a
  race that outlasts every attempt does.
- **Counted, not audited.** A changed choice increments
  `basetool_active_org_unit_choice_writes_total{target}` (`unit` or `all`, REQ-OBS-011) and writes
  no audit entry, like the navigation preferences (REQ-AUDIT-001, „Deliberately not audited").
- **Switch.** `POST /me/active-org-unit` stores the choice first and then sets the session to what
  the backend stored. A refused choice leaves the session as it was and says so; when only the
  store fails, the session still switches and a toast says the choice was not saved.
- **Restore.** The first authenticated page request of a session reads the stored choice into the
  session, once per session and principal (`ActiveOrgUnitRestore`, run by
  `ActiveSquadronContextFilter`); requests for static assets, public documents and the pages of the
  access gates (the terms consent page, the approval waiting page and its status poll) never
  restore. A failed read leaves the session without a pin, which is all of the member's units, and
  is retried after a minute; a session that already holds a pin and was never restored keeps it. An
  access gate's refusal of the read (pending approval, terms not accepted, no role) and a lost
  OAuth2 session are no failure: the session keeps its state, no wait starts, and a later request
  reads again, so the first request after the gate clears restores the choice.
- **Personal data.** The row is part of the member's data export (section `activeOrgUnitChoice`),
  is erased with the account and stays with the source account when two accounts are merged
  (REQ-DATA-021).

**Acceptance**

- [x] A switch stores the choice, and a new session starts in the stored unit.
- [x] „Alle meine Org-Einheiten" is stored as `null`, and the session starts without a pin.
- [x] A stored unit the member may no longer select reads as `null`.
- [x] A unit the caller may not select is refused with `400`, and the session keeps its pin.
- [x] An unchanged choice writes no row and counts nothing; a change is counted once and writes no
  audit entry.
- [x] A switch that loses a concurrent write of the same member is retried and the latest choice
  wins; a race that outlasts every attempt answers `409` and counts nothing.
- [x] A failed read of the stored choice starts the session without a pin and is retried later.
- [x] A session that starts behind an access gate starts in the stored unit once the gate clears
  (`ActiveOrgUnitRestoreTest`).

**Enforced by:** `ActiveOrgUnitChoicesTest`, `ActiveOrgUnitChoiceMetricsTest`,
`ActiveOrgUnitRestoreTest`, `MeFrontendControllerTest`, `ActiveOrgUnitContextE2eTest` · **Code:**
`scope.internal.ActiveOrgUnitChoices`, `scope.internal.ActiveOrgUnitChoiceMetrics`,
`scope.web.ActiveOrgUnitChoiceController`, `kernel.web.ActiveOrgUnitRestore`,
`orgunit.web.MeFrontendController`, `V409__create_member_active_org_unit.sql` · **ADR:** ADR-0374 ·
**Related:** REQ-ORG-008, REQ-ORG-032, REQ-AUDIT-001, REQ-DATA-021, REQ-OBS-011

### REQ-ORG-032 — One pin rule for every surface

A member's active org unit (the pin of REQ-ORG-008) means the same on every surface: the lists, the
detail gates, create-time stamping, role powers and the oversight reach (ADR-0374).

- **Honoured or no pin.** A pin counts when it names a unit the caller may select: for an admin an
  existing active org unit, for everyone else a unit of their reach — direct memberships plus the
  leadership cascade (REQ-ORG-015), the units the switcher lists. Any other pin — stale, foreign,
  unknown, inactive or malformed — is **no pin**, on every surface alike.
  `RequestScopeResolver.currentPin()` is the only reading of the pin. Outside an HTTP request, as
  for background work run as a caller, there is no pin, so such work meets the same gates unpinned
  (ADR-0236 §6; `RequestMemo#readable`).
- **No membership right without `KRT Member`.** A member who does not hold `KRT Member` reaches no
  unit: no reach, no pin, an empty switcher and owner picker, no seat or plain-membership right, no
  explicit Logistiker or Einsatzleiter flag, no bank membership audience and no membership-derived
  notification (ADR-0300, REQ-SEC-015, owner decision). Bank roles stay a separate axis. One check
  answers it for the caller, `AuthHelperService#holdsKrtMember()`: the role, or the exchange marker
  of an acting member who holds it (REQ-XCH-009); the scope resolver, the switcher list, the owner
  picker, the Leitung page and the SK and org-role security services read it. The owner or creator
  of a new row is judged by their stored role (REQ-ORG-004).
- **A deactivated unit confers nothing.** A membership on a deactivated Staffel, SK, Bereich or OL
  stays stored for the unit's reactivation and meanwhile confers nothing (owner decision): no reach,
  so the unit's data leave „Alle meine Org-Einheiten"; no flat or contextual role from its flags or
  seat; no leadership cascade into it or out of it, so neither its own seats nor a parent
  Bereichsleiter or the OL appoint, change or clear a seat in it; no recipient of its unit notices,
  its bank account's holder notices or the seat-holder broadcasts; and it takes no new or moved row,
  a first membership's adoption of unowned Lager rows included. One read gives every access decision
  the memberships on active units, `OrgUnitMembershipRepository#findAllOnActiveOrgUnitsByIdUserId`
  (ids: `#findActiveOrgUnitIdsByUserId`); the cascade reads the active units only, and the recipient
  queries skip a deactivated unit. Nothing is written by the rule: an admin without a pin still sees
  the unit's data, its owners keep their personal rows (REQ-ORG-011), the administration's member
  and roster views show the stored memberships, and the bank's responsible-holder audit compares the
  stored seats (REQ-BANK-034).
- **The effective context.** `RequestScopeResolver.currentActiveContext()` names what a request
  covers: the honoured pin (`ORG_UNIT`); else for an admin every unit (`ALL_ORG_UNITS`); else the
  member's only unit in reach, all of their units when they reach several (`ALL_MY_ORG_UNITS`), or
  none (`NONE`). `/me/layout` and `/org-units/me/active` report it, and the web renders the
  switcher, the title, the owner-picker preselection and the context notices from it alone
  (REQ-ORG-024).
- **A pin is exactly its unit.** A pinned request is scoped to the pinned unit, a Bereich or OL pin
  included; no pin is the caller's whole reach. The named cross-unit escapes of REQ-ORG-003 and the
  cross-unit features listed below stay.
- **Everything follows the context.** Reads, writes, role powers (Logistician, Mission Manager,
  officer, supply and purchasing managers, oversight, the Leitung page and its rank, seat, roster
  and Kommandogruppe writes, the bank's view grants and its account holders' rights and approvals)
  and create-time stamping without an explicit owner pick act only in the pinned unit, and without
  a pin in all of the caller's units. Out of context is nothing, never a fall back to the union.
  The supply and purchasing tab flags are the one exception (below).
- **Admins act within their own scope** — every unit unpinned, the pinned unit pinned — on the same
  surfaces: managing a mission (core, roster, schedule, structure, check-in, sub-missions),
  changing its owner and editing its finance entries (`MissionAccessPolicy`); managing a unit's
  supply (needs, incoming donations, the need form's job-order options and every manage flag, the
  admin page's included — the page itself lists and changes every unit, REQ-SUP-002); and the
  balance target, view grants and approval limits of an org-unit or Bereich account, like its view
  (REQ-BANK-064). The Bankleitung's approval-limit right does not read the pin. A mission's owner
  and co-managers keep their personal rights under any pin, and a participant keeps editing their
  own finance entry.
- **The supply and purchasing tabs stay.** The flags of „Eingehende Spenden", „Statistik" and
  „Bericht" do not depend on the pin, because a flag that reads false deletes the member's stored
  favourite, phone slot and start page of that tab (ADR-0329, REQ-UI-032): they count the units a
  caller leads or is a Logistician of in any context (`SupplyAccessPolicy#managedUnitsInAnyContext`,
  `PurchasingAccessPolicy#canReadReport`), every unit for an admin. Every manage flag, list and
  write of a unit's managers in both modules follows the context (`SupplyAccessPolicy#managedUnits`,
  REQ-SUP-003, REQ-PUR-021), so a manager pinned to a unit they do not manage keeps the tabs, whose
  pages then list nothing of their units.
- **Targets without an owning unit.** An officer, Logistician or Mission Manager gate on a target
  that has no owning unit passes only an admin without a pin (`AccessGateService`), like an
  ownerless personal row; a Mission Manager grant in an Organisationsleitung or Bereich unit of the
  active context still manages a mission or operation without a unit (REQ-SEC-005).
- **Stamping without a pick.** A mission or operation is stamped on the honoured pin — every
  honoured pin is a unit its creator may edit —, else on the creator's only stampable unit
  (`OrgUnitStampingService#resolveOrgUnitForUnitOwnedAggregate`, REQ-ORG-004); with several and no
  pick the create answers `400 OWNER_ORG_UNIT_REQUIRED` (REQ-ORG-023). A personal row — ship, Lager
  row, refinery order — is stamped on the pin only when the pin is one of its owner's stampable
  units, otherwise on the owner's only stampable unit, so it never lands in a unit its owner does
  not belong to (REQ-HANGAR-009, REQ-INV-052). The stampable units are the direct memberships on
  active units of an owner or creator who holds the stored role `KRT Member`; an admin's row for a
  member uses the member's units, never the admin's pin.
- **Earmarks on missions.** A Lager row's earmark names its mission only to a reader who may see
  the mission (REQ-MISSION-008); to anyone else — an internal mission of another unit, for a pinned
  admin as well — it reads „Unbekannter Einsatz" (REQ-INV-027). The earmark writes and undos look
  the mission up through the JPQL projection `MissionRepository#findEarmarks`, which does not depend
  on the caller and loads no mission entity, so neither a write nor a connected application's bulk
  undo fails on an earmark the caller cannot see; only `EarmarkTargets.Missions#shownMissions` loads
  the missions, to apply the visibility rule to what a reader sees.
- **Cross-unit by design.** These features do not follow the pin, as decided by the owner: the
  member directory (REQ-ORG-017); the personal notification inbox (REQ-NOTIF-004); the bank's KRT,
  cartel bank and Sonderkonto accounts, the transfer targets and deposits into any active account
  (REQ-BANK-037, REQ-BANK-040, REQ-BANK-042) and the whole bank-staff side (REQ-BANK-008); the
  operation a mission shows to everyone who may see the mission (REQ-MISSION-034); the refinery
  orders of every linked mission in an operation's ledger (REQ-ORG-027); connected applications,
  which carry no active context and act on the member's whole reach (REQ-BXCH-001, REQ-XCH-009);
  and the administration tools of admins (audit logs, member management, person search, data
  export, account merge and the admin views of one member's hangar, inventory and blueprints).
- **Personal views are not narrowed.** „Mein Lager", „Mein Hangar", the own refinery orders and the
  own bank requests list every own row under any pin, each marked with its unit (REQ-ORG-011,
  REQ-BANK-022).
- **Other tabs follow.** Every page announces its effective context on the `BroadcastChannel`
  `krt-active-org-unit`. A tab of the same browser that shows another context reloads
  (REQ-FE-001); when it holds unsaved input, a KRT dialog asks first. A page that a followed reload
  rendered ignores the followed announcements of the context it reloaded for, which ends a reload
  loop between two tabs that disagree; the announcement of a switch always reloads.

**Acceptance**

- [x] A stale, foreign, unknown or malformed pin behaves as no pin on the list scope, the detail
  gates, the stamping, the grants, the officer reach and the bank's pin narrowing.
- [x] An admin's pin on an existing active unit is honoured; on an unknown or inactive unit it is no
  pin.
- [x] An unpinned member of several units gets `ALL_MY_ORG_UNITS`: the switcher selects „Alle meine
  Org-Einheiten" and the title says so.
- [x] Picking a unit shows it selected and narrows the Lager to that unit's rows.
- [x] Another tab of the same browser reloads after a switch and asks first when it holds unsaved
  input (`org-unit-context-sync.test.mjs`, `ActiveOrgUnitContextE2eTest`).
- [x] A page that a followed reload rendered does not reload again on a followed announcement of the
  same context, while a later switch to that context still reloads it
  (`org-unit-context-sync.test.mjs`).
- [x] Without `KRT Member` a member reaches no unit, gets an empty switcher, and no membership —
  seat, plain membership or explicit flag — confers an authority or a bank right.
- [x] A membership on a deactivated unit confers no reach, no authority, no cascade and no notice,
  and its SK lead manages no roster (`DeactivatedOrgUnitConfersNothingIntegrationTest`,
  `ScopeKernelTest#member_seesNothingOfAUnitThatIsDeactivated`,
  `SpecialCommandSecurityServiceTest`).
- [x] A personal row created without a pick lands in the pin only when it is one of its owner's
  stampable units; a mission or operation lands in the honoured pin. A deactivated unit is no
  stampable unit, and an owner without `KRT Member` keeps a new personal row without a unit.
- [x] Role powers act only in the pinned unit.
- [x] A pinned admin manages, re-owns and edits the finance entries of only the pinned unit's
  missions and of no mission without a unit, an unpinned admin of every mission; a mission's
  owner and co-managers keep their rights under any pin (`MissionAccessPolicyPinnedAdminTest`,
  `MissionAccessPolicyGateTest`).
- [x] An officer, Logistician or Mission Manager gate on a target without an owning unit passes
  only an admin without a pin (`ScopeKernelTest#pinnedAdmin_passesNoOwnerlessTarget`,
  `LogisticianPerUnitSurfacesTest`, `MissionAccessPolicyPinnedAdminTest`).
- [x] A pinned admin reads and changes the balance target, view grants and approval limits only of
  the pinned unit's accounts, and manages only the pinned unit's supply (`BankAccessRulesTest`,
  `SupplyAccessPolicyTest`, `SupplyMapperTest`, `SupplyNeedServiceTest`,
  `SupplyUnitSettingServiceTest`).
- [x] A lead or Logistician pinned to a unit they do not manage, and a pinned admin, keep the tabs
  „Eingehende Spenden", „Statistik" and „Bericht" (`SupplyTabAccessServiceTest`,
  `SupplyAccessPolicyTest`, `PurchasingAccessPolicyTest`).
- [x] An earmark on a mission the reader may not see reads „Unbekannter Einsatz", and no write or
  undo fails on it (`MissionEarmarkTargetsTest`, `InventoryEarmarkTargetCacheTest`,
  `InventoryPoolEarmarkWithoutRequestIntegrationTest`).
- [x] A Bereich or OL pin narrows the Leitung page to that unit.
- [x] „Mein Lager", „Mein Hangar" and the own refinery orders list every own row under any pin,
  each marked with its unit (the Lager on every stack row).

**Enforced by:** `RequestScopeResolverPinRuleTest`, `ScopeKernelTest`, `RequestMemoTest`,
`MeControllerTest`, `MemberMembershipControllerTest`, `CustomJwtGrantedAuthoritiesConverterTest`,
`RetainedSeatWithoutKrtMemberIntegrationTest`, `DeactivatedOrgUnitConfersNothingIntegrationTest`,
`SpecialCommandSecurityServiceTest`, `BankAccessRulesTest`, `GrantCascadeTest`,
`LeitungViewServiceTest`, `DelegatedAppointmentControllerSecurityTest`,
`SpecialCommandRosterGateIntegrationTest`, `OrgUnitStampingStampableUnitsTest`, `MissionAccessPolicyPinnedAdminTest`,
`MissionAccessPolicyGateTest`, `LogisticianPerUnitSurfacesTest`, `AuthHelperServiceTest`,
`SupplyAccessPolicyTest`, `SupplyMapperTest`, `SupplyNeedServiceTest`,
`SupplyUnitSettingServiceTest`, `SupplyTabAccessServiceTest`, `PurchasingAccessPolicyTest`,
`MissionEarmarkTargetsTest`, `InventoryEarmarkTargetCacheTest`,
`InventoryPoolEarmarkWithoutRequestIntegrationTest`,
`OrgUnitContextAdviceTest`, `OrgUnitSwitcherLabelMvcTest`, `org-unit-context-sync.test.mjs`
(`:frontend:testOrgUnitContextSyncJs`), `ActiveOrgUnitContextE2eTest` ·
**Code:** `scope.api.RequestScopeResolver`, `scope.api.ActiveOrgUnitContext`,
`scope.api.AccessGateService`, `scope.api.OrgUnitStampingService`,
`scope.api.CustomJwtGrantedAuthoritiesConverter`, `platform.api.AuthHelperService#holdsKrtMember`,
`mission.internal.MissionAccessPolicy`, `supply.internal.SupplyAccessPolicy`,
`purchasing.internal.PurchasingAccessPolicy`, `mission.api.MissionRepository#findEarmarks`,
`inventory.api.EarmarkTargets`, `kernel.layout.OrgUnitContextAdvice`,
`static/js/kernel/org-unit-context-sync.js` · **ADR:** ADR-0374, ADR-0329 · **Related:**
REQ-ORG-002, REQ-ORG-008, REQ-ORG-017, REQ-ORG-024, REQ-ORG-031, REQ-SEC-005, REQ-SEC-015,
REQ-SEC-065, REQ-INV-027, REQ-SUP-003, REQ-PUR-023, REQ-UI-026, REQ-FE-001

### JobOrder hierarchy foundation

REQ-ORG-003/-004/-014/-016 use the unique `Department.PROFIT` area and its current active
Staffel/SK children for JobOrder responsibility and general Profit capability. The independent
processing switch and its internal projections/admin mutations are retired. Creation requires a
current contextual writer of either proposed side. Invalid current responsibility retains concrete
authorised reading and current-unit recovery transfer; all other order and coupled stock writes
wait for valid responsibility. Legacy unknown original boundaries remain visible as unknown without
requiring a transfer when current responsibility is valid. The shared/exclusive transaction protocol
and preserved promise endings implement REQ-ORDERS-050/-053/-055; subsequent shared workflow
amendments remain governed by [orders-core.md](orders-core.md).

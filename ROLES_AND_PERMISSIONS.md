# Role and Permission Matrix (Profit Basetool)

> **As of 2026-06-27 (after job-order rebuild #340, operations/payouts, material claims, personal blueprints, blueprint availability #364, Bereichsleitung operations + participant visibility #500/#501, processor notes #520, blueprint coverage for item orders #526, refinery screenshot import #439, Kartellbank #556/#666, Bereichsleitung & Organisationsleitung #692, Discord login #720, unified rank + delegated granting + Leitung page + Kommandogruppen #800/ADR-0042, Kartellbank holder responsibility/visibility/target/transfer requests/approval limits REQ-BANK-034..041, multi-domain activity audit + retention cleanup #795/ADR-0037/0038, promotion audit #844, two Staffeln per member #845, Hangar Org-Einheitsübersicht #847/ADR-0048).**
>
> This matrix was verified against the actual implementation:
> the `@PreAuthorize` annotations of all 69 backend controllers, the
> URL matrix in
> [`backend/.../config/SecurityConfig.java`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/config/SecurityConfig.java)
> and
> [`frontend/.../config/SecurityConfig.java`](frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/config/SecurityConfig.java),
> the role seeds in
> [`DataInitializer`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/config/DataInitializer.java)
> and the authority converter
> [`CustomJwtGrantedAuthoritiesConverter`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/config/CustomJwtGrantedAuthoritiesConverter.java).
> **When this document and the code disagree, the code always wins**
> (`@PreAuthorize` + `SecurityConfig`).

This document summarizes **who may do what** — from completely anonymous,
unauthenticated visitors up to the administrator.

---

## 0. Two enforcement layers (important for reading the matrix)

Every request passes through **two** mutually independent gates. An access is
only permitted if it passes **both**:

1. **URL matrix (`SecurityConfig.authorizeHttpRequests`)** — the outer gate.
   Defines per path: `permitAll()` (even anonymous), `authenticated()` or
   a concrete role. Evaluated *first*.
2. **Method-level `@PreAuthorize`** on controller/service — the inner gate.
   Refined via Spring Security SpEL, often with the beans
   `@ownerScopeService`, `@missionSecurityService`,
   `@specialCommandSecurityService`.

The two layers can only **tighten, never loosen**:

- URL `authenticated()` beats method `permitAll()` → the endpoint is
  *not* reachable anonymously, even if the method carries `permitAll()`
  (e.g. `/api/v1/system/ping`).
- URL `permitAll()` + method `isAuthenticated()` → effectively **login
  required** (e.g. *create mission*, `POST /api/v1/missions`).

Whoever judges a permission must therefore read **both** layers.

---

## 1. Anonymous (unauthenticated) users

The Basetool has a deliberately public surface so that job-order requesters and
guests can interact with the organization **without a login**. In the frontend,
the routes `/`, `/missions/**`, `/operations/**`, `/orders/**`, the legal pages
(`/impressum`, `/privacy`, `/terms`) and static assets are set to `permitAll()`;
in the backend, an explicitly enumerated list of `permitAll()` endpoints. Everything
else requires authentication.

### 1.1 What anonymous users may do

| Capability                                                                                                                                                                                                | Endpoint(s)                                                                                                                                | Gate                                                                                                                                    |
|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------|
| **Read master data** (materials, locations, ship types, manufacturers, refining methods, star systems, job types, frequency types, system settings, Staffel list)                                         | `GET /api/v1/{materials,locations,ship-types,manufacturers,refining-methods,star-systems,job-types,frequency-types,settings,squadrons}/**` | URL `permitAll`, no method gate (exception: the location subreads `/refineries` and `/home-locations` carry method-`isAuthenticated()`) |
| **Read active org units** (name, abbreviation, kind, profit flag — fills the selection fields of the public job-order form)                                                                               | `GET /api/v1/org-units/active`                                                                                                             | URL `permitAll` + method `permitAll()`                                                                                                  |
| **Page through missions** — only **non-internal** missions, detail view **redacted** (without description + PII; organization, participant list, units, frequencies, payout preference visible; see §1.3) | `GET /api/v1/missions`, `/search`, `/next`, `/{id}`                                                                                        | `@ownerScopeService.canSeeMission` (internal = invisible)                                                                               |
| **Create a job order** (material order)                                                                                                                                                                   | `POST /api/v1/orders`                                                                                                                      | `permitAll()`                                                                                                                           |
| **Create an item order** (finished-part order with auto-derived materials)                                                                                                                                | `POST /api/v1/orders/items`                                                                                                                | `permitAll()`                                                                                                                           |
| **Search the orderable item catalog**                                                                                                                                                                     | `GET /api/v1/orders/item-catalog/**`                                                                                                       | `permitAll()`                                                                                                                           |
| **Sign up to a (non-internal) mission as a guest** — with a freely chosen `guestName`                                                                                                                     | `POST /api/v1/missions/{id}/participants/add`, `/participants/slim`                                                                        | `@ownerScopeService.canSeeMission`                                                                                                      |
| **Check in / out** of the mission                                                                                                                                                                         | `POST /api/v1/missions/{id}/participants/{pid}/check-in[/slim]`, `…/check-out[/slim]`                                                      | `@missionSecurityService.canAccessParticipant`                                                                                          |
| **Edit own guest participant** (job type, ship, comment, times)                                                                                                                                           | `PUT /api/v1/missions/{id}/participants/{pid}[/slim]`                                                                                      | `canAccessParticipant`                                                                                                                  |
| **Change payout preference** (e.g. `DONATE`)                                                                                                                                                              | `PUT /api/v1/missions/{id}/participants/{pid}/payout-preference[/slim]`                                                                    | `canAccessParticipant`                                                                                                                  |
| **Remove own guest participant**                                                                                                                                                                          | `DELETE /api/v1/missions/{id}/participants/{pid}[/slim]`                                                                                   | `canAccessParticipant`                                                                                                                  |

**Why the participant endpoints work anonymously:** A **guest participant is not
linked to a user account** (`participant.user == null`).
[`MissionSecurityService.canAccessParticipant`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/MissionSecurityService.java)
returns **`true` for everyone** for such unlinked participants — this is the
deliberate construction seam that makes the sign-up flow usable without a login.
As soon as a participant is linked to a real user, only that user themselves or an
elevated role (Mission-Manager/Officer/Admin) may edit them.

**Where anonymous orders go:** When created without a login, the order is
mandatorily stamped onto the configured **intake Spezialkommando** (system setting
`job_order.intake_special_command_id`, introduced with V128). This way every guest
order lands in a defined SK queue instead of nowhere.

### 1.2 What anonymous users may **not** do

- **Create or manage missions/operations** — `POST /api/v1/missions`
  is indeed URL-`permitAll`, but method-`isAuthenticated()` → login required.
  Operations (`/api/v1/operations/**`) are fully authenticated.
- **View the order list or order details** — `GET /api/v1/orders`
  and `/orders/{id}` fall under `isAuthenticated()` + `canSeeJobOrder`. A
  guest can therefore *submit* an order but cannot track it
  afterwards.
- **Read or create finance entries of a mission** — the finance-ledger surface
  (`GET`/`POST /api/v1/.../finance-entries`) is the payout view of the mission and
  requires member-or-above (`isMemberOrAbove`). Anonymous → `401`, a logged-in
  **Guest** → `403` (see "Anonymous ≈ Guest role" below). Creating finance entries
  is thus **no longer anonymous**.
- **View the description of a mission** — the free-text description is stripped
  server-side from the public mission response (§1.3). Organization, participant list
  (without PII), units and frequencies remain visible, by contrast; the payout
  preference and the free-text comment of individual participants, however, are also
  stripped for outsiders (ADR-0034).
- **View participant PII** (email, real name) — stripped from every outsider response;
  only the public callsign (username/displayName/rank) remains visible.
- **Material claims, refinery, hangar, inventory, personal inventory/blueprints,
  user directory, promotion system, admin area** — all authenticated
  or role-gated.

### 1.3 Data redaction for outsiders (two redaction levels)

Mission responses are cleaned up server-side in [`MissionController`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/controller/MissionController.java)
in **two levels**:

- **Member peer** (`cleanupMissionForGuest` / `cleanupParticipantForGuest`) — for a
  logged-in member below Logistician: owner/manager/internal inventory/refinery
  references are emptied and, for participants, email, real name and roles are stripped
  — but the roster view stays intact.
- **Outsider** (`cleanupOutsiderMissionForGuest`) — for **anonymous AND Guest** callers
  (`isMemberOrAbove() == false`): like member peer, **additionally the description as
  well as, per participant, the payout preference and the free-text comment** stripped
  (ADR-0034). What remains visible (on non-internal missions) are organization
  (`owningSquadron`), participant list (PII-cleaned), units and frequencies — plus name,
  schedule, status, calendar link, participant count and party lead. The finance ledger
  (`/finance-entries`) is a separate surface and stays member-only. Internal and past
  (`COMPLETED`/`CANCELLED`) missions are not visible at all to outsiders (`403`).

**Names, emails and tokens never end up in an outsider response.** The naming
convention `cleanup…ForGuest` is structurally enforced by the ArchUnit rule
`anonymousReadableMissionEndpointsMustRedactGuestPii`.

> **Anonymous ≈ "Guest" role on the missions.** "Anonymous" = not logged in at all (no JWT).
> The **`GUEST` role** is an *authenticated* Keycloak user with no authorities at all (see §2).
> Both are "mission outsiders" (`AuthHelperService.isMemberOrAbove() == false`) and are
> **treated identically on the mission surface**: same redacted detail view (§1.3),
> the same sign-up/guest-participant rights and **no** access to the finance ledger
> (anonymous `401`, Guest `403`). Outside the missions the difference persists: a
> Guest passes `isAuthenticated()` gates (so they see, e.g., their own — empty —
> inventory) but fails every `hasRole(...)`/`hasAuthority(...)` check; an anonymous
> caller reaches only the `permitAll` list.
>
> **Discord registration `PENDING`/`REJECTED` ≈ even less than Guest.** A new
> Discord sign-up lands without approval in `PENDING` (REQ-SEC-017): the entire
> authority assembly is short-circuited to the single authority `ROLE_PENDING_APPROVAL`
> — no realm roles, no permissions, no OrgUnit roles, **and no `ROLE_GUEST`**.
> Such users are routed in the frontend to an "approval pending" page until
> an admin approves them under `/admin/discord-registrations` (then `ACTIVE`) or rejects
> them (`REJECTED`, stays without access). Roles/units are assigned **manually** after approval
> (Track 1).

---

## 2. Roles & base permissions

Roles are derived from the Keycloak realm roles (`ROLE_<GROSS_SNAKE>`) and seeded
with authorities in the
[`DataInitializer`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/config/DataInitializer.java).
In addition, a **role hierarchy** applies.

### Role hierarchy (backend + frontend identical)

```
ROLE_ADMIN   > ROLE_LOGISTICIAN
ROLE_OFFICER > ROLE_LOGISTICIAN
ROLE_ADMIN   > ROLE_MISSION_MANAGER
ROLE_OFFICER > ROLE_MISSION_MANAGER
ROLE_ADMIN           > ROLE_BANK_MANAGEMENT
ROLE_BANK_MANAGEMENT > ROLE_BANK_EMPLOYEE
```

Admins and Officers therefore automatically satisfy every `LOGISTICIAN` and
`MISSION_MANAGER` check. Admins additionally satisfy every bank check
(`BANK_MANAGEMENT` and transitively `BANK_EMPLOYEE`); Bank Management satisfies
every `BANK_EMPLOYEE` check (REQ-BANK-007).

### Seeded authorities

| Role                | Authorities (DataInitializer)                                                                                                                                    |
|:--------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Admin**           | `HANGAR_READ`, `HANGAR_WRITE`, `MISSION_READ`, `MISSION_WRITE`, `MISSION_MANAGE`, `USER_MANAGE`, `ROLE_MANAGE` (+ `LOGISTICIAN`/`MISSION_MANAGER` via hierarchy) |
| **Officer**         | `HANGAR_READ`, `HANGAR_WRITE`, `MISSION_READ`, `MISSION_WRITE`, `MISSION_MANAGE`, `USER_MANAGE` (+ `LOGISTICIAN`/`MISSION_MANAGER` via hierarchy)                |
| **KRT Member**      | `HANGAR_READ`, `HANGAR_WRITE`, `MISSION_READ`                                                                                                                    |
| **Guest**           | *(none — empty set)*                                                                                                                                             |
| **Bank Employee**   | *(none — the fine-grained rights are app-managed grant rows, REQ-BANK-009)*                                                                                      |
| **Bank Management** | *(none — "see everything" visibility comes from the role itself, ADR-0011)*                                                                                      |

`USER_MANAGE` remains in the Officer set for historical reasons, but is no longer
checked by any endpoint (effectively inert — all member-management endpoints have
been `hasRole('ADMIN')` since the Phase-4 lockdown).

### Contextual roles from OrgUnit memberships

`LOGISTICIAN` and `MISSION_MANAGER` are **not** Keycloak roles, but **flags per
OrgUnit membership** (`org_unit_membership.is_logistician` /
`is_mission_manager`). The
[`CustomJwtGrantedAuthoritiesConverter`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/config/CustomJwtGrantedAuthoritiesConverter.java)
promotes these into two authority surfaces:

- **Flat** `ROLE_LOGISTICIAN` / `ROLE_MISSION_MANAGER`, as soon as **any**
  membership (Staffel or SK) carries the flag — so all existing
  `hasRole('LOGISTICIAN')` gates keep working.
- **Contextual** `ROLE_LOGISTICIAN@<orgUnitId>` per (membership, flag) pair — so
  that the per-OrgUnit scoping at the `@PreAuthorize` call site
  (`@ownerScopeService.canEdit…`) can be resolved without a service roundtrip.

> The **old** `app_user.is_logistician` / `app_user.is_mission_manager` columns
> were removed with **V104** — the single source of truth is exclusively
> `org_unit_membership`. Membership-less accounts (admins, guests) carry no
> Logistician/Mission-Manager flag.

### SK-Lead (special case)

A membership with `is_lead = true` (which, per DB CHECK, exists only on
**Spezialkommando** rows) automatically makes the user **both `LOGISTICIAN` and
`MISSION_MANAGER`** within *that one SK* (flat + contextual) — the lead stands
above both roles of their SK, analogous to an Officer being Logistician +
Mission-Manager of their own Staffel. Additionally, a lead may **manage the
members of their SK** (add/remove/toggle the `is_logistician`/`is_mission_manager`
flags) via `@specialCommandSecurityService.canManageMembers`. Setting the
**lead flag itself** remains **Admin-only** (a lead cannot escalate themselves).
No carry-over to other SKs.

### Functional rank & delegated appointment (`MembershipRole`, ADR-0042)

Since V187, leadership is **one** rank enum `MembershipRole` on
`org_unit_membership` instead of the five old boolean flags
(`is_lead`/`is_bereichsleiter`/`is_bereichskoordinator`/`is_bereichsoperator`/`is_ol_member`,
removed with V187). Values: `MEMBER`, the Staffel ranks `STAFFELLEITER` /
`KOMMANDOLEITER` / `STELLV_KOMMANDOLEITER` / `ENSIGN`, the Bereich ranks
`BEREICHSLEITER` / `BEREICHSKOORDINATOR` / `BEREICHSOPERATOR`, `OL_MEMBER` and
`SK_LEAD` (scoped per `OrgUnitKind` via DB CHECK). `is_logistician` /
`is_mission_manager` remain **orthogonal** flags and are **not** folded into the
rank. Every Staffel leadership rank grants officer-equivalent reach **only over
its own Staffel** (contextual `LOGISTICIAN`/`MISSION_MANAGER` like `SK_LEAD`), no
admin rights, no cascade (REQ-ROLE-002).

Appointment runs through a **delegated ladder without self-promotion**
(REQ-ROLE-004), operable on the **Organisation → Leitung** page: Admin →
OL members; OL → Bereichsleiter; Bereichsleiter → Staffelleiter / SK-Leiter /
Bereichskoordinatoren / -operatoren (own Bereich); Staffelleiter →
Kommandoleiter / Stellvertreter / Ensigns + Kommandogruppen (own Staffel, at most
four Kommandogruppen). To appoint a rank, you must hold a **strictly higher**
rank — nobody promotes themselves. Every appointment is logged in the audit log
„Rollen & Mitglieder" and is reflected in the same transaction in the
account-bound org-chart seat (REQ-ROLE-006).

### Operational OFFICER grant (leadership & bank)

The `OFFICER` Keycloak role is granted **operatively** (manually, cf. §1) in
addition to the OrgUnit membership:

- **All members of the Organisationsleitung, the Bereichsleitung and every
  Spezialkommando receive `OFFICER`.** This way they satisfy the frontend's
  `hasRole('OFFICER')` gates (e.g. the „Leitung" page). The backend still
  evaluates reach **membership-based** via the `OwnerScopeService` (oversight
  scope, responsible holder) — `OFFICER` only opens the role-gated surfaces,
  **not** the contextual data reach.
- **Bank Management (`BANK_MANAGEMENT`) also holds `OFFICER`.** **Bank Employees
  (`BANK_EMPLOYEE`)** have **at least `KRT Member`** (MEMBER), but **not
  necessarily `OFFICER`** — the bank gates rely exclusively on `BANK_*`
  (REQ-BANK-007), never on `OFFICER`.
- **Consequence for the bank:** Because Officers **and** SK-Leads carry `OFFICER`,
  the Sonderkonto auto-visibility (`SPECIAL`, REQ-BANK-037) decides **by
  membership** (OL member or Bereichsleiter), **never** via the `OFFICER` role —
  Officers/SK-Leads therefore do **not** see Sonderkonten automatically, only via
  an explicit approval.

---

## 3. Access matrix by functional area

Columns: **Anonymous** = not logged in · **Member** = KRT Member ·
**Log.** = Member + Logistician flag · **MM** = Member + Mission-Manager flag ·
**Officer** · **Admin**.

> Logistician/Mission-Manager are **add-on flags** on a Member: they inherit
> all Member rights and add their flag-specific capability, **scoped to
> their OrgUnit(s)** (`@ownerScopeService.canEdit…`). Officer ⊇ Log.+MM of the
> **own Staffel**; Admin ⊇ everything, cross-Staffel.

### 3.1 Auth / Context

| Function (gate)                                                                    | Anonymous | Member | Log. | MM | Officer | Admin |
|:-----------------------------------------------------------------------------------|:---------:|:------:|:----:|:--:|:-------:|:-----:|
| Be logged in (`isAuthenticated()`)                                                 |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Own profile / `GET /me`, active OrgUnit context (`/me/active-org-unit`)            |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Read user directory (`/users`, `/search`, `/lookup`, `/{id}`, `/{id}/memberships`) |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |

### 3.2 Hangar & Personal Data

| Function (gate)                                                                                    | Anonymous | Member | Log. | MM | Officer | Admin |
|:---------------------------------------------------------------------------------------------------|:---------:|:------:|:----:|:--:|:-------:|:-----:|
| Read hangar (`HANGAR_READ`)                                                                        |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Maintain own ships / import (CCU, HangarXPLOR, Fleetyards, StarJump) (`isAuthenticated()` + Owner) |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Manage other members' ships (`hasRole('ADMIN')`)                                                   |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| `resetAllFittedStatus` (`hasAnyRole('ADMIN','OFFICER')`)                                           |     ❌     |   ❌    |  ❌   | ❌  |    ✅    |   ✅   |
| Read „Org-Einheitsübersicht" (unit overview) in the hangar (`/hangar/squadron`, `HANGAR_READ`)     |     ❌     |   ✅²   |  ✅²  | ✅² |   ✅²    |   ✅   |
| Personal inventory / personal blueprints (own) (`isAuthenticated()`)                               |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Manage personal inventory/blueprints of **others** (`/admin/...`, `hasRole('ADMIN')`)              |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| Read blueprint availability of the org unit (`/blueprint-overview`, `canAccessBlueprintOverview`)  |     ❌     |   ❌    |  ❌¹  | ❌  |    ✅    |   ✅   |

¹ SK-Leads additionally view the overview **for their SK** (via the `is_lead` flag, not via the plain Logistician flag). Officers view only their Staffel; Admins without a pin all org units, with a pin only the pinned one.

² The „Org-Einheitsübersicht" (formerly „Staffelübersicht") spans **all** visible org units: without an actively pinned unit a Member sees the ships of all their own Staffeln and SKs, a Bereichsleitung additionally those of the subordinate units of their Bereich (REQ-ORG-015), and an OL member **every** ship in the system — including the ownerless ships of members entirely without an org unit (`owningOrgUnit == null`); this OL extension is read-only and limited to this one overview (ADR-0048). With a pinned unit the overview shows only that unit for every caller. The per-ship breakdown (owner/location/fitted) stays ADMIN/OFFICER-only — Member/BL/OL see only the counters.

### 3.3 Inventory (Lager) & Job Orders

| Function (gate)                                                                                                                                   | Anonymous | Member | Log. | MM  | Officer | Admin |
|:--------------------------------------------------------------------------------------------------------------------------------------------------|:---------:|:------:|:----:|:---:|:-------:|:-----:|
| View inventory view (`/inventory`, Member+)                                                                                                       |     ❌     |   ✅    |  ✅   |  ✅  |    ✅    |   ✅   |
| Edit inventory / check items in/out / rebook personal marker (Umbuchung, REQ-INV-007) (`isAuthenticated()` + `canEditInventoryItem`, owner scope) |     ❌     |   ✅¹   |  ✅   | ✅¹  |    ✅    |   ✅   |
| **Create** job order (material & item order)                                                                                                      |     ✅     |   ✅    |  ✅   |  ✅  |    ✅    |   ✅   |
| Read job-order list / detail (`isAuthenticated()` + `canViewJobOrders` + `canSeeJobOrder`)                                                        |     ❌     |   ✅³   |  ✅³  | ✅³  |   ✅³    |   ✅   |
| Add/remove **yourself** as an editor, maintain your own editor note (`canSeeJobOrder` + self-or-Logistician)                                      |     ❌     |  ✅³⁵   |  ✅³  | ✅³⁵ |   ✅³    |   ✅   |
| Read the **blueprint coverage** of an item order (`canSeeJobOrderBlueprintOwners`)                                                                |     ❌     |   ✅⁴   |  ✅⁴  | ✅⁴  |   ✅⁴    |   ✅   |
| **Edit** job order (status, priority, materials, handover) (`hasRole('LOGISTICIAN')` + `canEditJobOrder`)                                         |     ❌     |   ❌    |  ✅³  |  ❌  |   ✅³    |   ✅   |
| Reassign the responsible unit (`PATCH /{id}/responsible-org-unit`)                                                                                |     ❌     |   ❌    |  ✅²  |  ❌  |   ✅²    |   ✅   |
| Add/withdraw material claims on SK job orders (`hasRole('LOGISTICIAN')` + `canViewJobOrders`)                                                     |     ❌     |   ❌    |  ✅³  |  ❌  |   ✅³    |   ✅   |
| **Delete** job order (`hasRole('ADMIN')`)                                                                                                         |     ❌     |   ❌    |  ❌   |  ❌  |    ❌    |   ✅   |

¹ Only via the own object / the owner-scope check — not in general. The **owner** of a
personal aggregate (inventory entry `inventory_item.user`, ship `ship.owner`, refinery order
`refinery_order.owner`) may **always** view and edit their object, independent of the
`owning_org_unit_id` stamp — even after an OrgUnit change or without any membership, as long as
the object is still booked to an OrgUnit (REQ-ORG-011). A **non-**owner stays bound to the
strict OrgUnit scope.
² Admin unrestricted; Staffel logistician/officer only **escalation** of their own
Staffel job order to an SK.
³ **Only members of a profit-eligible org unit** (`is_profit_eligible`
on Staffel or SK) are part of the job-order workflow: only they may **view** job orders
(list/detail), **edit** them (status/priority/materials/handover,
reassign) and set/withdraw **material claims** — the profit gate
(`canViewJobOrders`) is folded into `canSeeJobOrder` and `canEditJobOrder` and
also applies to the role-only claim endpoints. Anyone who is exclusively in
non-profit-eligible units can **only create** job orders — nothing else,
analogous to anonymous guests. Admins always have access (`canViewJobOrders`
is always true for them). In the frontend the „Aufträge" link is replaced by „Auftrag
anlegen", and a direct call to `/orders` or `/orders/{id}` is redirected to the
create form; the backend returns an empty list or `403` for non-profit members
(also for write/claim endpoints).
⁴ Stricter than `canSeeJobOrder` — **no** SK-public escape: the coverage view
names members by name together with their blueprints and is therefore limited to members
of the **responsible** org unit. Anyone who sees the SK job order only
via the public queue gets `403`; the frontend hides the section. Admins without a pin always,
with a pin only for the matching unit.
⁵ Self-or-Logistician rule (`verifyAssigneeAccess`): anyone who sees the job order
may add/remove **themselves** and maintain **their own** note (max. 500
characters); other people's entries/notes may only be changed by a Logistician+.
Notes are visible to everyone who sees the job order.

### 3.4 Refinery

| Function (gate)                                                                                                                   | Anonymous | Member | Log. | MM | Officer | Admin |
|:----------------------------------------------------------------------------------------------------------------------------------|:---------:|:------:|:----:|:--:|:-------:|:-----:|
| Read/create own refinery orders, incl. screenshot import (`POST /import-extract`) (`isAuthenticated()` [+ `canSeeRefineryOrder`]) |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Edit/delete/store refinery order (`isAuthenticated()` + `canEditRefineryOrder`: Owner **or** Logistician)                         |     ❌     |   ✅¹   |  ✅   | ✅¹ |    ✅    |   ✅   |
| Create/manage refinery orders **for others** (`/users/{id}`, `hasRole('LOGISTICIAN')`)                                            |     ❌     |   ❌    |  ✅   | ❌  |    ✅    |   ✅   |

¹ Only as owner of the respective order.

### 3.5 Missions

| Function (gate)                                                                                                 | Anonymous | Member | Log. | MM | Officer | Admin |
|:----------------------------------------------------------------------------------------------------------------|:---------:|:------:|:----:|:--:|:-------:|:-----:|
| Read non-internal missions (`canSeeMission`; outsider-redacted, §1.3)                                           |    ✅⁴     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| **Create** mission (`isAuthenticated()`)                                                                        |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Register as (guest) participant / check in/out / change payout preference / unregister (`canAccessParticipant`) |    ✅¹     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| **Manage** mission (edit, participants/units/crew/frequencies, party lead) (`canManageMission`)                 |     ❌     |   ✅²   |  ✅²  | ✅³ |   ✅³    |   ✅   |
| Set manager / owner (`canManageManagers` / `canChangeOwner`)                                                    |     ❌     |   ✅²   |  ✅²  | ✅² |   ✅³    |   ✅   |
| Reassign **owning org unit** ("Zugeordnete Einheit"; `canChangeOwner` + assignable-target scope⁵, REQ-ORG-018)  |     ❌     |   ✅²   |  ✅²  | ✅² |   ✅³    |   ✅   |
| **Delete** mission (`hasRole('ADMIN')`)                                                                         |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |

¹ Anonymous only on **unlinked guest participants**; logged-in users only on
their own linked participant. ² Only as **owner/co-manager** of the mission.
³ Mission-Manager/Officer additionally only within their own Staffel scope
(`canEditMission`). ⁴ Outsiders (anonymous **and** roleless guest, `isMemberOrAbove() == false`)
see the detail view without description + PII; organization, participant list, units,
frequencies and payout preference remain visible (§1.3). The finance ledger stays member-only,
and a guest is treated here like an anonymous visitor. ⁵ The owning-org-unit reassignment shares the
`canChangeOwner` gate but additionally validates the **target**: an admin may assign to any org unit
or to ownerless; a non-admin may only pick a direct membership or a unit within their editable scope
(`canEditOrgUnit`), and may pick ownerless only when membershipless (REQ-ORG-018, ADR-0050).

> **Mission without org unit (Bereichsleitung).** „Mission anlegen" (create mission) is
> `isAuthenticated()` — that includes a logged-in user **without** Staffel/SK membership (e.g. the
> Bereichsleitung superordinate to the SKs and Staffeln). Their mission is created **ownerless**
> (`owning_org_unit_id = NULL`, V144) instead of being rejected with `400`, and stays attributable
> via `mission.owner_id`. Visibility: **non-internal → visible to everyone** (anonymous too);
> **internal → only for member-or-above** (`isMemberOrAbove()`), invisible to guests/anonymous.
> Editing follows the usual mission-management gate (owner, co-manager, Mission-Manager/Officer,
> admins), without Staffel-scope narrowing. Details: REQ-ORG-009 in
> [`docs/specs/org-unit-tenancy.md`](docs/specs/org-unit-tenancy.md).

### 3.6 Operations (mission bracket, finances & payouts)

| Function (gate)                                                                            | Anonymous | Member | Log. | MM | Officer | Admin |
|:-------------------------------------------------------------------------------------------|:---------:|:------:|:----:|:--:|:-------:|:-----:|
| Read operations (list/detail/finances/payouts) (`isAuthenticated()` [+ `canSeeOperation`]) |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Create/edit operation (`hasRole('MISSION_MANAGER')` [+ `canEditOperation`])                |     ❌     |   ❌    |  ❌   | ✅  |    ✅    |   ✅   |
| **Mark payout as paid-out** (`hasRole('MISSION_MANAGER')` + `canEditOperation`)            |     ❌     |   ❌    |  ❌   | ✅  |    ✅    |   ✅   |
| **Revoke** paid-out (additionally `hasAnyRole('ADMIN','OFFICER')`)                         |     ❌     |   ❌    |  ❌   | ❌  |    ✅    |   ✅   |
| **Delete** operation (`hasRole('ADMIN')` + `canEditOperation`)                             |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |

> Payout asymmetry: every Mission-Manager may set `paidOut=true`,
> but only Officer/Admin may reset a confirmed paid-out back to
> `false`.
>
> **Visibility (`canSeeOperation`) has three paths since #500/#501** (one suffices):
> **(1)** the normal Staffel scope (operation of one's own org unit);
> **(2)** an **ownerless Bereichsleitung operation** (`owning_org_unit_id = NULL`,
> V145, ADR-0005) is visible to **all member-or-above** — operations have
> no public escape, it stays invisible to guests/anonymous;
> **(3)** **participant visibility** (ADR-0006): whoever took part in one of the linked
> missions sees the operation and their payout even across Staffeln
> (anonymous callers never — no `currentUserId`).
> Creating an ownerless operation is open to any Mission-Manager **without**
> org unit (Bereichsleitung); editing it is allowed for any Mission-Manager,
> deleting it for any admin (`canEditOperation` is a no-op for ownerless
> operations, the endpoint's role gate carries the restriction).

### 3.7 Mission finances & profit

| Function (gate)                                                               | Anonymous | Member | Log. | MM | Officer | Admin |
|:------------------------------------------------------------------------------|:---------:|:------:|:----:|:--:|:-------:|:-----:|
| Read a mission's finance entries (`isMemberOrAbove` + `canSeeMission`)        |    ❌²     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| **Create** finance entry (`isMemberOrAbove` + `canSeeMission`)                |    ❌²     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Edit/delete finance entry (`canEditFinanceEntry`: owner **or** Officer/Admin) |     ❌     |   ✅¹   |  ✅¹  | ✅¹ |    ✅    |   ✅   |
| Read profit calculation (`hasAnyRole('KRT_MEMBER','OFFICER','ADMIN')`)        |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Material overview / material collection of a job order (`isAuthenticated()`)  |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |

¹ Only one's own entry and only while still a participant of the mission.
² The finance ledger is the payout view and requires member-or-above: anonymous → `401`,
roleless guest → `403` (a guest is treated like anonymous for missions, §1.3). Creating job orders
is unaffected by this (possible for everyone).

### 3.8 Promotion system

All promotion controllers are class-level `isAuthenticated()`. The promotion system
is **Staffel-scoped throughout**: every Staffel runs its own system
(topic areas, categories, level contents, rank prerequisites, evaluations) and
sees exclusively its own data. Read **and** write access are filtered in the
service layer via the active Staffel context
(`OwnerScopeService.currentSquadronId()` for lists/eligibility,
`canSeeSquadron`/`canEditSquadron(topic.owningSquadron.id)` for detail and maintenance).

- **Member/Officer** see only their home Staffel's system.
- **Admins** see that of the actively pinned Staffel; without a pin (all-Staffeln mode)
  the pages show a „Staffel wählen" (select Staffel) hint instead of mixing all Staffeln.
- A **user without Staffel affiliation who is not an admin** has no promotion
  system of their own: the menu item is hidden, every list/eligibility read
  returns empty, and a direct page call is blocked with 403 (`hasPromotionReadAccess()`).
- The **evaluation matrix** (the member list of the evaluation management) lists exclusively the
  **plain members** of a Staffel: whoever holds the `ADMIN` or `OFFICER` role **never** appears
  there as a row to be evaluated — admins are Staffel-less, and officers carry out the evaluation
  instead of being evaluated themselves. The promotion system considers only plain members
  of a Staffel and is not available to any other OrgUnit kind (#817).

| Function (gate)                                                                                                 | Anonymous | Member | Log. | MM | Officer | Admin |
|:----------------------------------------------------------------------------------------------------------------|:---------:|:------:|:----:|:--:|:-------:|:-----:|
| Read topic areas/categories/level contents/rank prerequisites (`isAuthenticated()`, **own Staffel only**)       |     ❌     |   ✅¹   |  ✅¹  | ✅¹ |   ✅¹    |  ✅²   |
| …**maintain** (service: admin **or** officer of the owning Staffel)                                             |     ❌     |   ❌    |  ❌   | ❌  |   ✅³    |   ✅   |
| View own evaluations / eligibility (`/my`, JWT sub, **own Staffel**)                                            |     ❌     |   ✅¹   |  ✅¹  | ✅¹ |   ✅¹    |  ✅²   |
| View **others'** evaluations/eligibility, member list (`hasAnyRole('ADMIN','OFFICER')`, officer Staffel-scoped) |     ❌     |   ❌    |  ❌   | ❌  |   ✅⁴    |  ✅⁴   |
| Enable/disable promotion subsystem per Staffel (`PATCH /squadrons/{id}/promotion-enabled`, `hasRole('ADMIN')`)  |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |

¹ Only the **own home Staffel**; a user with no Staffel at all (and without admin rights) sees nothing — `hasPromotionReadAccess()` returns empty, the menu is hidden, direct call 403.
² Admin: the actively pinned Staffel; in all-Staffeln mode a „Staffel wählen" (select Staffel) hint instead of mixing.
³ Only for one's own Staffel. **SKs are permanently excluded from the promotion system via DB CHECK/trigger + ArchUnit rule.**
⁴ The **member list** to be evaluated (`GET /api/v1/promotion/evaluations/members`) contains only the **plain members** of the Staffel — holders of the `ADMIN` and `OFFICER` roles are filtered out (#817), since they carry out the evaluation instead of being evaluated themselves. Officer/Admin therefore **read** the matrix but do **not appear** as a row in it.

### 3.9 Organisation (Staffeln & Spezialkommandos)

| Function (gate)                                                                                                                                                     | Anonymous | Member | Log. | MM | Officer | Admin |
|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------|:---------:|:------:|:----:|:--:|:-------:|:-----:|
| Read Staffel list / active OrgUnit list (`/org-units/active`)                                                                                                       |    ✅¹     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Read SK list (`isAuthenticated()`; inactive ones **and** the detail view `GET /special-commands/{id}` admin only)                                                   |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| Switch the active OrgUnit context (sidebar switcher)                                                                                                                |     ❌     |   ✅²   |  ✅²  | ✅² |   ✅²    |   ✅   |
| Staffel lifecycle (create/rename/delete/activate, `promotion-enabled`, `profit-eligible`) (`hasRole('ADMIN')`)                                                      |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| Set Staffel membership flags (`PATCH /squadrons/{id}/members/{uid}`, `hasRole('ADMIN')`)                                                                            |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| SK lifecycle (create/rename/delete/activate, `profit-eligible`) (`hasRole('ADMIN')`)                                                                                |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| **Read SK member list** & **manage members** (add/remove/flags) (`@specialCommandSecurityService.canManageMembers` — applies also to the plain `GET /{id}/members`) |     ❌     |   ❌    |  ❌   | ❌  |   ❌³    |   ✅   |
| Set SK **lead flag** (`PATCH /special-commands/{id}/members/{uid}/lead`, `hasRole('ADMIN')`)                                                                        |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |

¹ Master-data read, anonymous allowed. ² Non-admins switch between their
memberships; admins additionally „Alle Staffeln" (all Staffeln). ³ SK member management
is **admin or SK-Lead of this SK** — not tied
to the global Officer role.

### 3.10 Master data, announcements, system

| Function (gate)                                                                                                                                                                                                                            | Anonymous | Member | Log. | MM | Officer | Admin |
|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:---------:|:------:|:----:|:--:|:-------:|:-----:|
| **Publicly** readable master data (materials, locations, ship types, manufacturers, star systems, refining methods, frequency/job types, Staffeln, system settings)                                                                        |     ✅     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| **Logged-in** readable master data (terminals, material categories)                                                                                                                                                                        |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| **Admin-only** master data – also to read (cities, space stations, outposts, POIs, material aliases, blueprints) (`hasRole('ADMIN')`)                                                                                                      |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| **Write** master data (create/change/delete/visibility/overrides) (`hasRole('ADMIN')`)                                                                                                                                                     |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| UEX location typeahead / blueprint product search (`isAuthenticated()`)                                                                                                                                                                    |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| **Read** announcement (`GET /announcement`, `isAuthenticated()`)                                                                                                                                                                           |     ❌     |   ✅    |  ✅   | ✅  |    ✅    |   ✅   |
| **Write/delete** announcement (incl. raw read view `GET /announcement/admin`) (`hasRole('ADMIN')`)                                                                                                                                         |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| Read/clean up sync reports (`hasRole('ADMIN')`)                                                                                                                                                                                            |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| Read **audit logs** (Bank/Lager/Aufträge/Raffinerie/Mein Inventar/Missionen/Operationen/Rollen/Beförderung) + time-range PDF/JSON + retention cleanup (`/admin/audit-log`, `/api/v1/audit/**`, URL **and** method gate `hasRole('ADMIN')`) |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| Write system setting (`PUT /settings/{key}`, `hasRole('ADMIN')`)                                                                                                                                                                           |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |
| Role/permission management, member attributes/rank, flag granting (`/admin/**`, `/users/*/...`, `hasRole('ADMIN')`)                                                                                                                        |     ❌     |   ❌    |  ❌   | ❌  |    ❌    |   ✅   |

Which master data is anonymously readable is determined solely by the `permitAll`
list in `SecurityConfig` (see §1.1) — everything else is at least logged-in,
some tables (cities, stations, outposts, POIs, aliases, blueprints) are
admin-only already to **read**. **Writing is admin-only for all master
data.**

### 3.11 Kartellbank (epic #556)

The bank hinges on two dedicated Keycloak roles (`Bank Employee` →
`ROLE_BANK_EMPLOYEE`, `Bank Management` → `ROLE_BANK_MANAGEMENT`) plus
app-managed **grant rows** per (employee, account)
(`bank_account_grant`: row = read right; flags = deposit / withdraw /
transfer). **Bank staffing is entirely independent of
OrgUnit memberships** (REQ-BANK-008): `BankSecurityService` evaluates
exclusively bank roles + grants — `OwnerScopeService`, contextual
roles and the admin pin have no influence whatsoever, in either direction.
Columns here: **Member** = any org role without a bank role · **Bank Empl.** =
`Bank Employee` (with grants) · **Bank Mgmt.** = `Bank Management`.

| Function (gate)                                                                                                                                                                | Anonymous | Member | Bank Empl. | Bank Mgmt. | Admin |
|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:---------:|:------:|:----------:|:----------:|:-----:|
| Enter the bank area, dashboard, view accounts **with a grant row** (`hasRole('BANK_EMPLOYEE')` + grant)                                                                        |     ❌     |   ❌    |     ✅      |     ✅      |   ✅   |
| View **all** accounts/holders/grants (`hasRole('BANK_MANAGEMENT')`)                                                                                                            |     ❌     |   ❌    |     ❌      |     ✅      |   ✅   |
| Deposit / withdraw / transfer (`@bankSecurityService.canDeposit/Withdraw/Transfer`, per account flag)                                                                          |     ❌     |   ❌    | ✅ per flag |     ✅      |   ✅   |
| Enter Bankverwaltung, create a **Sonderkonto** (`SPECIAL`) (+ auto-grant), holder menu + **holder-to-holder Umbuchung** (`HOLDER_TRANSFER`, `BANK_EMPLOYEE`, no account grant) |     ❌     |   ❌    |     ✅      |     ✅      |   ✅   |
| Create / rename / close / reopen accounts **(except Sonderkonto)**, **manual** holder registry, manage grants                                                                  |     ❌     |   ❌    |     ❌      |     ✅      |   ✅   |
| Reversal (`POST /bank/transactions/{id}/reversal`, `hasRole('BANK_MANAGEMENT')`)                                                                                               |     ❌     |   ❌    |     ❌      |     ✅      |   ✅   |
| Account statement PDF (viewed accounts) / 3-month report (`BANK_MANAGEMENT`)                                                                                                   |     ❌     |   ❌    |   ✅ / ❌    |     ✅      |   ✅   |
| Read **audit log** + retention cleanup (`/api/v1/bank/admin/audit`, URL **and** method gate `hasRole('ADMIN')`)                                                                |     ❌     |   ❌    |     ❌      |     ❌      |   ✅   |
| **Wipe reset** (`/api/v1/bank/admin/wipe-reset`, `hasRole('ADMIN')`)                                                                                                           |     ❌     |   ❌    |     ❌      |     ❌      |   ✅   |

Bank Management does **not** see the audit log — it is deliberately admin-only
(REQ-BANK-012). Grants can only be assigned to users holding the `Bank Employee`
role (409 `BANK_GRANTEE_MISSING_ROLE`); whether the person is additionally in a
Staffel/an SK plays no role.

**Holders are decoupled from accounts** (ADR-0039, REQ-BANK-003/-006): the
holder balance is a **global** quantity in its own ledger
(`bank_holder_posting`), not account-bound, and **may go negative** — a
holder fronts their own money if needed and is later settled via a **holder-to-holder Umbuchung**
(`HOLDER_TRANSFER`, REQ-BANK-031). Only **accounts** are hard-protected against
overdraft. When the bank sends money in-game on a member's behalf, the **in-game transfer fee**
(default 0.5 %, the same setting `operation.transfer_fee_rate` as for the operations payout) is added
**on top** and borne by the debited account: it applies to `WITHDRAWAL` and a holder-changing
`TRANSFER` — the source is debited the gross (amount + fee), the destination/recipient gets the
**full** entered amount, and the account-overdraft guard runs against the gross (a booking that
cannot cover amount + fee is refused, so a fee never drives an account negative). `DEPOSIT`,
same-holder transfers and the internal holder-to-holder Umbuchung (`HOLDER_TRANSFER`) are
**fee-free** — for the Umbuchung the bank staff bear the in-game fee personally
(REQ-BANK-033, ADR-0052). **All bank
employees and Bank Management members are
automatically registered as holders** (REQ-BANK-029, ADR-0040); if someone loses all
bank roles, their role-driven holder is automatically deactivated (the remaining balance
stays and must be settled). Manually registered (non-staff) holders are
left untouched by this.

#### 3.11.1 Org-unit access for officers/leads (epic #666)

Epic #666 adds **a single, narrowly scoped** org-unit capability, **without**
softening the independence from REQ-BANK-008: `BankSecurityService` and the
ledger stay 100 % OrgUnit-blind. The entire OrgUnit logic lives in exactly
one seam deliberately **not** named with `Bank`, `OrgUnitBankAccessService`
(ADR-0020), which is the only one allowed to connect `OwnerScopeService` and the bank
(ArchUnit-pinned). This surface lies **outside** the bank URL/role space
under `/api/v1/org-units/bank/**` and needs **no** bank role. „Off./Lead" =
officer of one's own Staffel or lead of one's own Spezialkommando (oversight scope
`currentOversightScope()`); a plain member still sees nothing.

> **Note:** This table shows the **baseline from Epic #666** (officer/lead only).
> Later stages widen the **Member** column: through configurable visibility
> (REQ-BANK-035, §3.11.2) and the rule *request eligibility = view eligibility*
> (REQ-BANK-039, §3.11.3) it is **no longer generally ❌** — a KRT Member made
> visible via approval may view the balance **and** submit requests.

| Function (gate)                                                                                                                | Anonymous | Member | Off./Lead | Bank Empl. | Bank Mgmt. | Admin |
|:-------------------------------------------------------------------------------------------------------------------------------|:---------:|:------:|:---------:|:----------:|:----------:|:-----:|
| View **only the balance** of one's own OrgUnit account (`GET /api/v1/org-units/bank/balances`, oversight scope)                |     ❌     |   ❌    |     ✅     |    (✅)*    |    (✅)*    |   ✅   |
| Create a deposit/withdrawal **request** / view one's own requests / withdraw one's own request (`/org-units/bank/requests/**`) |     ❌     |   ❌    |     ✅     |    (✅)*    |    (✅)*    |   ✅   |
| **Confirm** a request (books it, records holders) / **reject** it (`/api/v1/bank/requests/**`, `BANK_EMPLOYEE` + account flag) |     ❌     |   ❌    |     ❌     | ✅ per flag |     ✅      |   ✅   |

\* Bank Empl./Bank Management reach the officer endpoints only as far as the oversight
scope covers them itself (as bank staff they are not automatically officer/lead) —
their actual bank work runs through the bank area above. A request moves money only
at confirmation; overdraft/holder checks then apply as for a direct booking
(REQ-BANK-023). On creation, Bank Management + the Bank Empl. authorized for the account
are notified via in-app notification (REQ-BANK-026, `ACCOUNT_GRANT` selector); **the account's
responsible holder is also notified — on creation AND on confirmation/rejection** — via the
`ACCOUNT_RESPONSIBLE` selector (REQ-BANK-026/-034), with the deciding/creating actor excluded so a
holder who raised or decided the request is not notified about their own action. An account with an
open request cannot be closed
(409 `BANK_ACCOUNT_HAS_PENDING_REQUESTS`). The audit log stays admin-only. The page shows
exclusively **active** accounts (REQ-BANK-028).

#### 3.11.2 Account responsibility, visibility, target & read-only detail (REQ-BANK-034..038)

Building on 3.11.1 and still solely via the seam `OrgUnitBankAccessService`
(the bank stays OrgUnit-blind, ADR-0011/0043, both ArchUnit pins green). The
org-unit bank page is now reachable for **every KRT Member**; the seam
decides per account what is visible.

- **Responsible holder (derived, REQ-BANK-034):** Staffel account → Staffelleiter,
  SK account → SK-Leiter, Bereich account → Bereichsleiter, OL/KRT account (`CARTEL`) → all
  OL members, Kartellbank account (`CARTEL_BANK`) → Bereichsleiter of the Profit Bereich;
  Sonderkonto: none. Derived from the role, not assigned.
- **Configure visibility (REQ-BANK-035):** the responsible holder additionally grants
  sub-roles (each individually), all members or individual users. Sonderkonten:
  global roles / all members / individual users — configured by **OL members
  or Bank Management**.
- **Fixed visibility (REQ-BANK-037):** `CARTEL`/KRT account always for **all KRT Members**;
  `CARTEL_BANK` only the responsible holder + bank staff; Sonderkonten automatically for
  bank staff **+ all OL members + all Bereichsleiter** (Bereichskoordinatoren/-operatoren
  and officers **no longer** — tightening of REQ-BANK-028).
- **Balance target (REQ-BANK-036):** set by the responsible holder **and** access-authorized
  Bank Empl. (for Sonderkonten only bank staff).
- **Read-only detail + account statement (REQ-BANK-038):** whoever may view an account opens the
  read-only detail view **with history** and can retrieve an **account statement** — no
  deposit/withdrawal/Umbuchung; the **holder column and the deposit/withdrawal counterparty**
  (Einzahler/Empfänger, REQ-BANK-043 — likewise player-identifying) **are redacted** (in the table
  and the PDF). Bank staff keep the full view incl. holders and counterparty.

| Function (gate)                                                                                                |  Member (if granted)  | Responsible | Bank Empl. (access) | Admin |
|:---------------------------------------------------------------------------------------------------------------|:---------------------:|:-----------:|:-------------------:|:-----:|
| View balance + read-only detail + account statement (`/api/v1/org-units/bank/accounts/{id}`, holders redacted) | ✅ (as far as visible) |      ✅      |    ✅ (bank page)    |   ✅   |
| Configure visibility (`/api/v1/org-units/bank/accounts/{id}/visibility/**`)                                    |           ❌           |      ✅      |         ❌ †         |   ✅   |
| Set/remove balance target (`…/balance-target` org-unit or `/api/v1/bank/accounts/{id}/balance-target`)         |           ❌           |      ✅      |     ✅ (access)      |   ✅   |

† Configuring **Sonderkonto** visibility is restricted to **OL members or Bank Management**
(REQ-BANK-035/-037, `canConfigureVisibility`) — **not** a plain Bank Empl. (the column
has no dedicated Bank Mgmt. column); `ORG_UNIT`/`AREA` is configured by the responsible holder,
`CARTEL`/`CARTEL_BANK` are fixed (REQ-BANK-037).

#### 3.11.3 Requesting for all view-eligible users, transfer requests, approval limits & unrestricted deposits (REQ-BANK-039..042)

Building on 3.11.1/3.11.2, still solely via the seam `OrgUnitBankAccessService` (the bank stays OrgUnit-blind, ADR-0045, both ArchUnit pins green):

- **Request eligibility = view eligibility (REQ-BANK-039), for withdrawals/transfers:** whoever may **view** a requestable account (`ORG_UNIT` / `AREA` / `CARTEL`, active only) may submit a withdrawal/transfer request on it — no longer only the leadership ranks from 3.11.1. A member made visible via approval (REQ-BANK-035) (sub-rank, all members, individual user) can therefore likewise request. `SPECIAL` / `CARTEL_BANK` never receive withdrawals/transfers. (Deposits are unrestricted — see REQ-BANK-042 below.)
- **Transfer requests (REQ-BANK-040):** in addition to deposit/withdrawal, a `TRANSFER` from the (visible) source account to **any active account** as target can be requested. The bank employee books it on confirmation as a real `TRANSFER` (recording source/target holders, in-game fee REQ-BANK-033); requires `can_transfer` on the **source** — a grant on the target is not required.
- **Approval limits per account/level + two-stage approval (REQ-BANK-041), for withdrawals/transfers:** per level (Squadron/Bereich sub-ranks, all members, individual user) a limit (>= 0 aUEC) may apply, up to which one may request without approval; a missing limit = unlimited. Above the limit the request is marked `requires_owner_approval`: (1) the **responsible holder** grants the approval in the „Fremde Anträge" tab (audited `BOOKING_REQUEST_OWNER_APPROVAL_GRANTED/REVOKED`), (2) the **bank employee** confirms only after the mandatory checkbox „Freigabe durch Kontoverantwortlichen erfolgt" (`BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED`; without the checkbox 409 `BANK_OWNER_APPROVAL_REQUIRED`). Configuring limits is allowed for the **responsible holder, Bank Management and Admin** — never a plain Bank Empl. — and only on the **org-unit bank** settings surface (the „Verantwortung & Sichtbarkeit" tab); the Bank account-detail page (`/bank/accounts/{id}`) shows limits **read-only for everyone**, including Bank Management and Admin. They are read-visible in the account details for all view-eligible users (audited `APPROVAL_LIMIT_SET/CLEARED`). Deposits are never approval-limited (REQ-BANK-042).
- **Unrestricted deposits (REQ-BANK-042):** a **deposit** request is exempt from both the view-eligibility gate and the approval limits above — **any authenticated user** may request a deposit against **any active account** (every type incl. `SPECIAL` / `CARTEL_BANK`, even one they cannot view), and it is never marked `requires_owner_approval`. The bank employee confirms it on in-game receipt as usual; only the account-active guard remains (`BANK_ACCOUNT_CLOSED`).
- **Split deposit across squadron accounts (REQ-BANK-043):** a **deposit** — direct booking (Bank Empl.) or request (any user) — may optionally distribute a whole-percent (1–100) of the gross evenly across all active squadron accounts, crediting the named account the remainder. No extra capability is required: a direct split deposit still needs only `BANK_EMPLOYEE` + `can_deposit` on the **named** account (crediting the squadron accounts adds money only, like the unrestricted request), and the request variant follows REQ-BANK-042. DEPOSIT-only; the split never makes a deposit approval-limited. Audited once as `DEPOSIT_SPLIT_BOOKED`.

| Function (gate)                                                                                                                              | Member (if visible) | Responsible | Bank Empl. | Bank Mgmt. | Admin |
|:---------------------------------------------------------------------------------------------------------------------------------------------|:-------------------:|:-----------:|:----------:|:----------:|:-----:|
| Submit a **deposit** request on **any active account** (`createBookingRequest`, REQ-BANK-042 — any authenticated user, no limit)             |       ✅ (any)       |      ✅      |     ✅      |     ✅      |   ✅   |
| Submit a **withdrawal / transfer** request on a **visible** requestable account (`OrgUnitBankAccessService#createBookingRequest`, `canView`) |          ✅          |      ✅      |    (✅)*    |    (✅)*    |   ✅   |
| Set/remove approval limits per level (`canConfigureApprovalLimits`)                                                                          |          ❌          |      ✅      |     ❌      |     ✅      |   ✅   |
| **Approve/revoke** an over-limit request („Fremde Anträge" tab, account in one's own responsibility)                                         |          ❌          |      ✅      |     ❌      |     ❌      |   ✅   |
| **Confirm** an over-limit request (mandatory checkbox, otherwise 409 `BANK_OWNER_APPROVAL_REQUIRED`)                                         |          ❌          |      ❌      | ✅ per flag |     ✅      |   ✅   |

\* Bank Empl./Bank Management reach the request endpoint only as far as they may view the account themselves (view/oversight scope). The two approval acts lie on **different surfaces of different users**; an out-of-band approval bumps the `@Version` of the request — an open bank queue with an old version runs into 409 `OPTIMISTIC_LOCK` on the next confirmation and recovers via reload (intended behavior, REQ-BANK-041).

#### 3.11.4 KRT-account 3-stage approval ladder & the "Mitglieder des Bereichs" audience (REQ-BANK-047/-047)

- **KRT-account (`CARTEL`) amount ladder (REQ-BANK-047):** the KRT account replaces the per-audience limits of 3.11.3 with an **amount-tiered** approval on withdrawals/transfers leaving it. Two thresholds `T1 ≤ T2` define who approves: **≤ T1** the **bank employee** (self-approve), **T1..T2** the **Bereichsleiter Profit**, **> T2** the **Organisationsleitung**. The thresholds are set **only by the Bankleitung** (`ROLE_BANK_MANAGEMENT`; Admin via hierarchy) in the **new „KRT-Freigaben" tab** of the bank Verwaltung (`PATCH …/approval-tiers`, audited `CARTEL_APPROVAL_TIERS_SET/CLEARED`); the account-detail pages show the ladder read-only to every viewer. The **Bereichsleiter Profit** and **OL** approve their band's requests in Org-Einheits-Bank → „Fremde Anträge" (band-routed: each sees only its own band; Admin sees all), then a bank employee confirms with the same mandatory checkbox as 3.11.3. A plain bank employee's **direct** KRT withdrawal/transfer above `T1` is refused `BANK_CARTEL_APPROVAL_REQUIRED` (management/admin uncapped).

| Function (gate)                                                                         | Member | Bereichsl. Profit | OL | Bank Empl. | Bank Mgmt. | Admin |
|:----------------------------------------------------------------------------------------|:------:|:-----------------:|:--:|:----------:|:----------:|:-----:|
| Set/remove the KRT thresholds `T1`/`T2` (`setCartelApprovalTiers`, „KRT-Freigaben" tab) |   ❌    |         ❌         | ❌  |     ❌      |     ✅      |   ✅   |
| **Approve** a KRT `T1..T2`-band request („Fremde Anträge", `AREA_LEAD_PROFIT`)          |   ❌    |         ✅         | ❌  |     ❌      |     ❌      |   ✅   |
| **Approve** a KRT `> T2`-band request („Fremde Anträge", `ORGANISATIONSLEITUNG`)        |   ❌    |         ❌         | ✅  |     ❌      |     ❌      |   ✅   |
| **Direct** KRT withdrawal/transfer above `T1` (blocked → use the request flow)          |   —    |         —         | —  |   ❌ 409    |     ✅      |   ✅   |

- **"Mitglieder des Bereichs" audience (REQ-BANK-048):** a Bereichskonto (`AREA`) may additionally open its balance/limit to the **whole area cascade** (Bereichsleitung + all child Staffel/SK members) via the new `AREA_MEMBERS` bucket — alongside the existing Bereich sub-ranks, all-Bereichsleitung (`ALL_MEMBERS`) and individual users. Only the responsible holder / Bank Management / Admin configure it.
- **"Alle Mitglieder" = the owning org unit, for limits (REQ-BANK-047):** the `ALL_MEMBERS` limit tier now applies **only to actual members of the account's owning org unit** (Staffel→Staffelmitglieder, SK→SK, Bereichskonto→Bereichsleitung, KRT→OL); an outsider who only holds an individual view grant is not covered and needs their own `USER` limit, else approval is required. The KRT account's all-member **visibility** (every Kartellmitglied, REQ-BANK-037) is unchanged.

---

## 4. Multi-OrgUnit visibility (scoping)

Read and write paths are filtered through
[`OwnerScopeService`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/OwnerScopeService.java)
(formerly `SquadronScopeService`; today it covers Staffeln **and**
Spezialkommandos). Baseline rule: non-admins view the union of their
memberships; admins without an active pin view everything, with a pin the same
restrictive view as a Member.

- **Strictly staffel-scoped** (no cross-Staffel): `Ship`, `InventoryItem`
  (inventory view), `RefineryOrder` — lists filter on
  `owning_org_unit_id`, detail/write endpoints gate on
  `canSee*`/`canEdit*`. Personal rows with no org unit at all
  (`owning_org_unit_id = NULL`, V132 — e.g. the ship of a user without
  Staffel/SK) are **owner-only**: visible/editable only to the owner
  themselves and to admins (`canAccessOwnerlessPersonalRow`). **Owner escape
  (REQ-ORG-011):** the per-user owner (`ship.owner` / `inventory_item.user` /
  `refinery_order.owner`) may **always** view/edit their row, regardless of
  the `owning_org_unit_id` stamp — `isCurrentUserOwner` takes effect before the
  scope check in all six `canSee*/canEdit*` gates. A non-owner stays strictly
  scoped; the shared list views remain unchanged.
- **Cross-Staffel with public escape**: `Mission` — visible to other OrgUnits
  exactly when `is_internal = false`; editable only by the owning
  OrgUnit + admins. Ownerless Bereichsleitung missions (V144) follow
  REQ-ORG-009 (see the box in §3.5).
- **Staffel-scoped with two escapes**: `Operation` — no public escape, but
  (a) **ownerless Bereichsleitung operations** (V145) are visible to all
  members-or-above and (b) **participants** of the linked
  missions view the operation across Staffeln (#500; details in the box in §3.6).
- **Conditionally staffel-scoped**: `JobOrder` (+ `JobOrderMaterial` /
  `JobOrderHandover` / `MaterialClaim`). A job order carries
  `responsible_org_unit_id` (the **processing** unit — drives the
  visibility, changeable only via `PATCH /{id}/responsible-org-unit`) and
  `requesting_org_unit_id` (the **requester** — grants **no**
  visibility). Responsible = **SK** → public to all Staffeln
  (shared queue that Staffeln join via a Material-Claim);
  responsible = **Staffel** → private to that Staffel + admins. SK job order *edits*
  run through the role gate (Logistician+), not through the Staffel scope.
- **Oversight overview** (no aggregate of its own): the blueprint availability
  (`/blueprint-overview`) aggregates the per-user `personal_blueprint` rows across
  the members of the org units the caller **oversees** — officers their
  Staffel, SK-Leads their SK(s), admins all of them or the pinned one
  (`OwnerScopeService.currentOversightScope()`, narrower than the
  membership union of the normal lists). Owners are delivered only as a
  display name, never as sub/email.

### 4.1 Bereichsleitung & Organisationsleitung — cascading responsibility (epic #692)

> **Status:** implemented — epic #692, phases 3–6 shipped (cascade #708, Bereich/OL ownership #709,
> picker + organigram #710, bank access #711); phase 7 (#700) is the security/regression gate
> (cumulative security review with no finding, ArchUnit hardening, spec reconciliation, visibility-matrix e2e).
> Binding specification: [REQ-ORG-014..019](docs/specs/org-unit-tenancy.md),
> [REQ-SEC-015](docs/specs/security-and-access.md), [REQ-BANK-027](docs/specs/bank.md), ADR-0025..0028.

Above Staffeln and Spezialkommandos come two new levels: the **Bereich** (e.g. Profit, Sub-Radar,
Raumüberlegenheit) and the **Organisationsleitung (OL)** at the very top. Responsibility **cascades**
analogously to `ADMIN > OFFICER > LOGISTICIAN/MISSION_MANAGER`:

- **Bereichsleitung** (`MembershipRole` `BEREICHSLEITER` / `BEREICHSKOORDINATOR` / `BEREICHSOPERATOR`;
  the old boolean flags were removed with V187, ADR-0042) has **officer-equivalent** responsibility
  over **all Staffeln + SKs of their Bereich** and over their own Bereich data.
- **OL** (`MembershipRole` `OL_MEMBER`) has the same responsibility over **everything**.
- **No admin rights:** the reach is a concrete `memberOrgUnitIds` union, **never** the
  `adminAllScope` branch. An OL/Bereich principal **never** satisfies `isAdmin()`; all
  `hasRole('ADMIN')` gates (admin area, SK lifecycle, system settings, master data,
  promotion topic guards, bank admin/audit) stay closed.
- **Strict separation:** a Bereichsleitung views/edits **only** their own Bereich; **only** the
  OL is cross-Bereich.
- **SK-Leiter stays SK-only:** their Bereichsleitung membership is purely organizational (a seat in the
  organigram), it does **not** extend the rights to the Bereich.
- **Own data + creating on behalf:** Bereich/OL own their own aggregates (inventory, missions,
  operations, job orders, refinery orders) and can create for subordinate units
  (e.g. a job order or refinery order for a Staffel of their Bereich); gated via
  `canEditOrgUnit(target)`, not via adminship (REQ-ORG-016).
- **Selection picker:** Bereichsleitung/OL get an admin-like drill-down picker, but only into
  units **subordinate to them** (Bereichsleitung: Staffeln/SKs of their Bereich; OL: everything).
- **Bank (REQ-BANK-027/-028):** the view cascades (own level account **and** subordinate
  accounts), but deposit/withdrawal **requests** only **on the own level account** (Bereich →
  `AREA` account, OL → `CARTEL`/Kartell account); subordinate accounts are only viewable via the picker.
  On the `/org-unit-bank` page, Bereich/OL (and admins) additionally view the organization-wide
  **Sonderkonten** (`SPECIAL`) — read-only, no request —; officers/SK-Leads do not. The page also shows
  **only active accounts**. The bank stays OrgUnit-blind (logic only in the
  `OrgUnitBankAccessService` seam).
- **Membership rules (REQ-ORG-017):** up to **two** Staffeln (also from different Bereiche)
  and any number of SKs; SK-Leiter, Bereichsleitung and OL belong to **no** Staffel; SK-Leiter
  **always** belong to the Bereichsleitung of the Bereich of their SK; OL members may belong to a Bereich.

---

## 5. Implementation specifics

1. **Keycloak sync / fallback:** If JWT claims (`realm_access.roles`)
   cannot be fully synchronised, the system falls back to the plain
   role names from the token (prefix `ROLE_`, uppercase,
   spaces → `_`).
2. **Default role:** If no known role is supplied, the user
   receives **Guest** (no authorities).
3. **Ranks:** The `UserService` logic dictates that `OFFICER` may only receive
   ranks 1–12 and `KRT_MEMBER` ranks 13–20.
4. **Logistician/Mission-Manager flags** are maintained by admins **per Staffel** on the
   member-edit page: the (up to two) Staffel slots, each with their own
   flags, feed into the membership delta `PATCH /api/v1/users/{id}/memberships`
   and are reconciled in a single transaction (REQ-ORG-017). The old toggle endpoints
   `UserController#patchLogistician` / `#patchMissionManager` and the member-list switches
   were removed. For an SK, the SK-Lead still sets the flags via `canManageMembers`.
   The old `app_user` flag columns have not existed since V104.
5. **Phase-4 lockdown:** The entire admin area (master data,
   member management, announcements, UEX, system settings,
   SK/Staffel lifecycle) is `hasRole('ADMIN')`. Officers retain their
   Staffel-internal functions (mission management, hangar write incl.
   `resetAllFittedStatus`, refinery, Logistician via hierarchy, the
   job-order workflow and — as the only Officer carve-outs — promotion maintenance of
   their own Staffel as well as SK member management **only** as SK-Lead).
6. **Architecture guards (ArchUnit):** Every `@RestController` carries at least
   one `@PreAuthorize`; Staffel-scoped services must inject `OwnerScopeService` /
   `AuthHelperService`; controllers never return JPA entities. A
   new violation breaks the build (`./gradlew test`).


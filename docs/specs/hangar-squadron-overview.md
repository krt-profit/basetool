> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-22.
> **Owner area:** HANGAR/UI · **Related ADRs:** [ADR-0048](../adr/0048-ol-sees-every-ship-in-the-unit-overview.md)

# Unit hangar overview (Org-Einheitsübersicht) — pagination, scope & server-side filter

> The page lives at `/hangar/squadron` (route unchanged) but is titled **"Org-Einheitsübersicht"** in
> the UI (`hangar.squadron.title`), because it spans every org unit the caller can see — not a single
> Staffel. The "Org-" prefix deliberately sets these organisational units apart from the dynamic
> units created inside a mission (Einsatz).

## Context & goal

The unit hangar page (`/hangar/squadron`, "Org-Einheitsübersicht") aggregates the scoped fleet into one
row per ship type (count + fitted count, with an ADMIN/OFFICER-only per-ship drill-down). The page
originally fetched up to 1000 rows in one request and filtered them client-side, which stops scaling
once a fleet grows past a screenful and silently truncates beyond the fetch cap. This spec pins the
listing contract after the rework to true server-side pagination: the table pages across **all**
entries that exist in the caller's scope, and the filter is applied by the backend so it spans the
whole fleet rather than the currently rendered rows. It also pins the **scope** of that "all" — the
cross-unit visibility and the OL widening (REQ-HANGAR-003).

The general OrgUnit scope triple, the cascade and the role-shaped owner drill-down are defined in
[`org-unit-tenancy.md`](org-unit-tenancy.md) and `HangarService`; REQ-HANGAR-003 below pins only how
this page selects its scope on top of them.

## Requirements

### REQ-HANGAR-001 — Squadron overview paginates and filters server-side

The squadron overview must be a true server-side paginated listing: page metadata counts
ship **types** (the grouped rows), the user chooses between 10, 50 and 100 entries per
page, and the optional ship-type filter is evaluated by the backend across every type in
scope. No fetch cap may silently truncate the fleet.

**Acceptance**

- [ ] `GET /api/v1/hangar/squadron-overview` honours `page`/`size` and returns page
  metadata (`totalElements`/`totalPages`) that counts distinct ship types — never the
  underlying ships (a GROUP-BY count-query pitfall).
- [ ] The endpoint's optional `search` parameter filters case-insensitively on ship-type
  *or* manufacturer name; types without a manufacturer still match on their own name
  (LEFT-JOIN semantics). Blank input means "no filter".
- [ ] The frontend page offers exactly the page sizes 10 / 50 / 100 (shared
  `pageSizePicker` fragment, same trio and default 50 as the blueprint availability
  overview's REQ-INV-013); any other client-supplied `size` snaps back to the default
  before reaching the backend. The picker hides while the total fits the smallest size,
  where switching could never change anything.
- [ ] Changing the page size re-enters at page 0, and pagination/page-size links preserve
  an active search term — switching the size never silently drops the filter.
- [ ] The filter input submits to the server (GET form); the page renders distinct empty
  states for "no ships in scope" vs. "no match for this search", and the filter stays
  clearable when a search yields nothing.
- [ ] The scope rules and the role-shaped owner drill-down of
  [`org-unit-tenancy.md`](org-unit-tenancy.md) are unaffected: filtered and paginated
  results pass through the same `ScopePredicate` as before.

**Enforced by:** `HangarIntegrationTest`, `HangarControllerTest`, `HangarServiceTest`,
`HangarPageControllerMvcTest` · **Code:** `HangarController`, `HangarService`,
`ShipRepository#countShipsByType`, `HangarPageController`,
`frontend/src/main/resources/templates/hangar-squadron.html` · **Issues:** —

### REQ-HANGAR-003 — Unit overview spans every unit the caller can see, OL sees all ships

The unit overview selects its scope through the dedicated
`OwnerScopeService#currentUnitOverviewScope()` (not the bare `currentScopePredicate()`), so that —
with **no single unit pinned** — it shows ships across **all** the units the caller can see, not a
single Staffel:

- A **plain member** sees the ships of every org unit they belong to (all their Staffeln **and** all
  their SKs).
- A **Bereichsleitung** member additionally sees the ships of every subordinate unit of their Bereich
  (its Staffeln + SKs) — the REQ-ORG-015 cascade.
- An **OL** member sees **every** ship in the system, including the ownerless personal ships
  (`owningOrgUnit == null`) of members who belong to no unit at all — the owner-approved,
  read-only widening of REQ-ORG-015 recorded in
  [ADR-0048](../adr/0048-ol-sees-every-ship-in-the-unit-overview.md). The widening is confined to this
  one read and grants no admin rights elsewhere.
- An **admin** keeps the unchanged admin-all / admin-pin behaviour.

An **active unit pin** still narrows the overview to the pinned unit for every caller (owner
decision) — the cross-unit/OL widening applies only when no unit is pinned, exactly like every other
scoped surface. The per-ship owner/location/fitted drill-down stays ADMIN/OFFICER-only, so a member /
BL / OL sees the complete counts but not the per-owner breakdown.

**Acceptance**

- [ ] Without a pin, a multi-unit member's overview counts ships from every Staffel and SK they belong
  to; a Bereichsleitung's also from their Bereich's subordinate units.
- [ ] Without a pin, an OL member's overview includes ships whose `owningOrgUnit` is `null` (members in
  no unit); a plain/BL member's never does.
- [ ] The OL widening grants no `isAdmin()` and no `hasRole('ADMIN')` carve-out — every other scoped
  list / `can*` gate still routes OL through `currentScopePredicate()`.
- [ ] With a single unit pinned, every caller (incl. OL) sees only the pinned unit's ships.

**Enforced by:** `OwnerScopeServiceTest` (`CurrentUnitOverviewScopeTests`), `HangarServiceTest` ·
**Code:** `OwnerScopeService#currentUnitOverviewScope`, `HangarService#getSquadronOverview`,
`ShipRepository#countShipsByType` · **ADR:**
[ADR-0048](../adr/0048-ol-sees-every-ship-in-the-unit-overview.md) · amends
[REQ-ORG-015](org-unit-tenancy.md) · **Issues:** —

## Out of scope

- The personal hangar (`/hangar`) — its own server-side pagination/sort/filter contract is
  [`personal-hangar-overview.md`](personal-hangar-overview.md) (REQ-HANGAR-002) — and the admin
  per-user hangar, which keeps its own listing behaviour.
- The drill-down rendering mechanics (details-row class toggle) — a UI implementation
  detail, kept consistent with the blueprint overview's REQ-INV-012 fix.
- The shared pagination fragment's look — governed by the design system
  ([`ui-design-system.md`](ui-design-system.md)).

## Open questions

None.

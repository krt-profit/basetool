> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-03.
> **Owner area:** HANGAR/UI · **Related ADRs:** [ADR-0048](../adr/0048-ol-sees-every-ship-in-the-unit-overview.md)

# Unit hangar overview (Org-Einheitsübersicht) — pagination, scope & server-side filter

> The overview lives at `/hangar/squadron` (route unchanged). Since 2026-10-03 it is the second tab
> **„Org-Einheit <Kürzel>"** of the one hangar page (`hangar.html`, `hangar.tab.unit`; the active
> unit's shorthand, or „Org-Einheit" alone when no unit is active); `/hangar` is the first tab
> „Meine Schiffe" and both routes render the same template with their tab active. Until then it was a
> page of its own titled „Org-Einheitsübersicht". It spans every org unit the caller can see — not a
> single Staffel. The "Org-" prefix deliberately sets these organisational units apart from the
> dynamic units created inside a mission (Einsatz).

## Context & goal

The unit hangar tab (`/hangar/squadron`, formerly the page „Org-Einheitsübersicht") aggregates the scoped fleet into one
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

Ships a connected application writes through the exchange (REQ-XCH-017) are ordinary `Ship` rows
written by the Hangar's own create, update and delete, so they are counted here exactly like web
writes; the overview does not tell them apart.

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
- [ ] The filter input filters server-side as you type (GET form, swapped in place); the page renders
  distinct empty states for "no ships in scope" vs. "no match for this search", and the filter
  stays clearable when a search yields nothing.
- [ ] Each ship type is one tree row — chevron, type and manufacturer, count, a readiness bar
  „fitted of total"; for ADMIN/OFFICER the chevron opens indented owner rows (owner, readiness,
  location) without a table head of their own. The tab count is the number of ship types in scope
  (`totalElements`; on this tab narrowed by an active search, on „Meine Schiffe" unsearched), because
  a ship total in scope has no backend read.
- [ ] The scope rules and the role-shaped owner drill-down of
  [`org-unit-tenancy.md`](org-unit-tenancy.md) are unaffected: filtered and paginated
  results pass through the same `ScopePredicate` as before.

**Enforced by:** `HangarIntegrationTest`, `HangarControllerTest`, `HangarServiceTest`,
`HangarPageControllerMvcTest` · **Code:** `HangarController`, `HangarService`,
`ShipRepository#countShipsByType`, `HangarPageController`,
`frontend/src/main/resources/templates/hangar.html` · **Issues:** —

*Amended 2026-10-03 (website overhaul phase 3):* the overview became the tab „Org-Einheit <Kürzel>" of
`hangar.html` (`hangar-squadron.html` is gone, `/hangar/squadron` renders `hangar` and swaps its
`squadronResults` fragment), its rows became a tree with a readiness bar „n von m", and the
„Details" disclosure and the „Zurück zum Hangar" link are gone (the chevron and the tab bar replace
them).

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

A ship created through the exchange (REQ-XCH-017) belongs to the member's single direct membership,
or to no unit for a member of none or of several (owner decision 2026-09-27), and a ship without a
unit is counted only by the OL widening and an admin until the member assigns one. It then enters
these scopes like a ship created in the web.

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
- The drill-down rendering mechanics (a hidden owner `tbody` per type, toggled by the chevron's
  `aria-expanded`) — a UI implementation detail.
- The shared pagination fragment's look — governed by the design system
  ([`ui-design-system.md`](ui-design-system.md)).

## Open questions

None.

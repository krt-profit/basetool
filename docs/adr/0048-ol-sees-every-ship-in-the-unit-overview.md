# ADR-0048 — OL sees every ship (incl. ownerless) in the hangar unit overview

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** @greluc
- **Related:** spec [REQ-HANGAR-003](../specs/hangar-squadron-overview.md) · narrowly amends the hard invariant of [REQ-ORG-015](../specs/org-unit-tenancy.md) · builds on the cascade of [ADR-0026](0026-cascading-scope-without-admin.md) (epic #692)

## Context

The hangar overview at `/hangar/squadron` — renamed in the UI to **Org-Einheitsübersicht** ("org
unit overview"; the "Org-" prefix sets it apart from the dynamic units created inside a mission) —
aggregates the scoped fleet into one row per ship type. Its scope came from the general
[`OwnerScopeService#currentScopePredicate()`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/OwnerScopeService.java),
which for a non-admin returns the union of their org-unit memberships widened by the REQ-ORG-015
leadership cascade: a plain member sees their own Staffeln/SKs, a Bereichsleitung the Staffeln/SKs of
their Bereich, and an OL member **every org unit** — but as a *concrete, materialised id set*, never
the `adminAllScope=true` marker.

That concrete-set OL reach has one gap the repository owner wants closed: a ship with
`owningOrgUnit == null` — an **ownerless personal ship** added by a member who belongs to no org unit
at all (V132 made that column nullable) — is **excluded** by the scope predicate's `owningOrgUnit.id
IN :memberOrgUnitIds` clause, because `null` is in no id set. Only the `adminAllScope=true` branch
includes such ships. So today an OL member's unit overview is *almost* complete but silently omits the
ships of membership-less members — exactly the members an OL is meant to oversee.

REQ-ORG-015 deliberately forbids OL/Bereich leadership from inheriting any admin carve-out, and lists
**ownerless-row access** among them: the cascade "widens membership scope; it never grants admin
rights." Closing the gap therefore requires an explicit, owner-approved exception to that hard
invariant.

## Decision

The hangar **unit overview** (and only that surface) resolves its scope through a dedicated
`OwnerScopeService#currentUnitOverviewScope()`, which is identical to `currentScopePredicate()` for
every caller **except** one widening:

- A non-admin **OL member** with **no single unit pinned** is upgraded to `adminAllScope=true`, so the
  unit overview surfaces **every** ship — including the ownerless personal ships of members who belong
  to no org unit.
- The widening applies **only** when no unit is pinned. An active pin still narrows the overview to the
  pinned unit, like every other scoped surface (owner decision).
- A plain member keeps their exact membership reach; a Bereichsleitung keeps the Staffeln/SKs of their
  Bereich (the REQ-ORG-015 cascade, unchanged); an admin keeps the unchanged admin-all / admin-pin
  behaviour.
- The widening is **read-only and confined to this one aggregation method**. It grants no `isAdmin()`
  and touches no other `can*` gate or scoped list — an OL member's mission, inventory, refinery, order
  and per-ship detail access all still route through the concrete-membership-union
  `currentScopePredicate()`. OL therefore remains officer-equivalent everywhere else.
- The per-ship **owner/location/fitted drill-down** stays ADMIN/OFFICER-only (an OL member is neither),
  so OL sees the complete *counts* but not the per-owner breakdown — the widening is about ship
  visibility, not about exposing every owner's name.

## Consequences

- An OL member's Org-Einheitsübersicht is complete: every member's ships are counted, regardless of which
  unit (or no unit) the owner belongs to — which is what "die OL sieht die Schiffe aller Member" means.
- REQ-ORG-015's hard invariant is narrowly amended: ownerless-row access is no longer strictly
  admin-only — it is also granted to a non-pinned OL member, **for the unit-overview read alone**. The
  invariant otherwise stands in full; this is the single, documented exception, and the dedicated
  method keeps the blast radius to one call site.
- The change is concentrated in `currentUnitOverviewScope()`; the repository `countShipsByType` /
  `findByShipTypeInScoped` queries already handle `adminAllScope=true` (the admin path), so no query
  changes were needed.

## Alternatives considered

- **Materialise "every org unit + a synthetic null bucket" for OL instead of `adminAllScope`** —
  rejected: the scope predicate keys on `owningOrgUnit.id IN :ids`, which cannot express "or
  owningOrgUnit IS NULL" without a query change at every scoped call site; `adminAllScope=true` already
  means exactly "no org-unit filter" and is the minimal, single-method change.
- **Leave the gap (OL sees only ships that belong to some unit)** — rejected by the owner: it omits the
  ships of membership-less members, leaving the OL's fleet view incomplete.
- **Grant OL `adminAllScope` everywhere (drop the REQ-ORG-015 invariant)** — rejected: it would hand OL
  the SK-lifecycle / promotion / system-settings carve-outs too. The exception is deliberately scoped
  to the one read surface the owner asked about.

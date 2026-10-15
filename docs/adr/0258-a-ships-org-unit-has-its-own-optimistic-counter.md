# ADR-0258 — A ship's org unit has its own optimistic counter

- **Status:** Accepted
- **Date:** 2026-10-15
- **Deciders:** @greluc
- **Related:** spec [`personal-hangar-overview.md`](../specs/personal-hangar-overview.md)
  (REQ-HANGAR-009, REQ-HANGAR-010) · [`external-exchange.md`](../specs/external-exchange.md) (REQ-XCH-017) ·
  [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) · epic #2595, issue #2581

## Context

A ship gets its owning org unit once, when it is created. A ship a connected application creates
for a member of several units gets none (REQ-XCH-017), and nothing can assign one afterwards, so the
ship never appears in any unit's hangar (#2581). The owner, an admin and a connected application
are each to change the unit later.

A unit change is a different edit from a change of name, location or fitted flag: the web offers it
as its own row action, a connected application sends it beside a field sync, and an admin changes it
for a member while the member edits the same ship. Under the ship's one row `@Version` each of these
would 409 the other although they touch different data, which the fine-grained locking rule forbids.

## Decision

1. **Own counter.** `Ship` carries `owningOrgUnitVersion`, a plain business counter beside the row
   `@Version`. A unit change checks and bumps only this counter, in one conditional statement
   (`UPDATE … SET owning_org_unit_id = ?, owning_org_unit_version = owning_org_unit_version + 1
   WHERE id = ? AND owning_org_unit_version = ?`); zero rows is a 409. It never bumps `@Version`.
   An edit of the other fields never bumps the counter.
2. **No clobbering.** `Ship` is `@DynamicUpdate`, and the unit and its counter are excluded from the
   row lock: an edit flushed from an entity loaded before a unit change writes only its own columns,
   so it neither overwrites the unit nor collides with it.
3. **Targets.** On every path the target is one of the ship owner's **direct** memberships, of any
   of the four kinds, or none. Neither the caller's scope nor an active-context pin widens it. An
   unchanged unit writes and records nothing.
4. **Exchange shape.** The Exchange carries the counter as `orgUnitVersion`. In a ship `upsert`, an
   absent `orgUnit` keeps the stored unit and an explicit `null` clears it.
5. **Selections lock, they do not echo.** A change of several ships at once (REQ-HANGAR-010) takes
   pessimistic row locks in ascending id order and carries no counters, like the Lager bulk actions
   (REQ-INV-036 / REQ-INV-052). It still bumps the counter of every ship it moves, so a single-ship
   change prepared before it 409s.

## Consequences

- A unit change and a concurrent field edit of the same ship both succeed; two concurrent unit
  changes still 409 one of them.
- The ship's `version` does not move on a unit change. The Exchange feed still announces the ship,
  with the new unit and `orgUnitVersion`, and announces every ship of a unit again when the unit's
  name, shorthand or kind changes (REQ-XCH-017).
- The create dialog keeps the „Zuordnen zu" picker; the edit dialog no longer shows it, because it
  saved nothing.
- A further editable part of `Ship` that must not collide with field edits takes its own counter the
  same way.

## Alternatives considered

- **One row `@Version` for the unit too.** Simple, but a unit change by an admin or a connected
  application would 409 the member's concurrent rename, and the reverse.
- **The unit as a field of the existing edit.** Keeps one endpoint, but the edit dialog would have to
  echo a unit it does not show, and the same collision remains.

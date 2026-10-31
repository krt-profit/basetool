# ADR-0302 — Minimum stock levels allocate free stock exclusively, top tier down

- **Status:** Accepted — partly implemented: the table, material levels, the allocation, the API
  and the „Mindestbestände“ tab are built; game items, the requested amount, the lifecycle, the
  notification and the gauge are pending.
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** REQ-INV-067 · REQ-INV-068 · REQ-NOTIF-030 · spec [`supply.md`](../specs/supply.md)
  (REQ-SUP-003) · REQ-AUDIT-001 · [ADR-0299](0299-the-home-page-is-a-personalisable-widget-dashboard.md)
  · map #2800

## Context

A unit wants to know when its pool runs low on a material or item it relies on. Materials come in
quality tiers, and a unit may care about several tiers of one material at once, for example a
little top-quality ore for crafting and more of any quality for refining. Stock of a high tier also
satisfies a need for a lower tier. Part of the pool is already promised to job orders, missions and
donations, and open supply needs and job orders ask for more that has not arrived.

## Decision

1. **A minimum stock level belongs to one org unit's pool** and to a material with an optional
   quality tier, or to a game item. A material may have one level per tier. Each level has a
   minimum and an optional target (defaults to the minimum); target ≥ minimum > 0.
2. **Counted stock is free stock:** the unit's pool rows, not personal and not stolen, minus the
   job-order, mission and donation earmarks, summed over all locations.
3. **Free stock is allocated exclusively, from the highest tier floor down.** Each level takes
   not-yet-allocated stock of a quality at or above its floor, up to its **target**; the lowest
   level takes the whole remainder.
4. **A level is low when its allocated stock is below its minimum.** Each level shows the
   shortfall, the fill ratio and the requested amount — open supply needs and open or in-progress
   job orders of the unit for the same material or item at the same or a higher tier — shown only,
   never subtracted.
5. **The unit's Logisticians maintain the levels,** the lead included; everyone who can see the
   unit's pool sees them. Each level row carries its own `@Version`.
6. **A low-stock notification fires once** when a level drops below its minimum and re-arms only
   after it has reached its target again; it goes to the unit's direct Logisticians and direct
   leadership seats, without cascade, and can be switched off.
7. **Lifecycle:** a level is deleted with its catalogue entry or its unit, with an audit event; a
   deactivated tier leaves the level in place, flagged „Stufe inaktiv". None of these notifies.

## Consequences

- Every unit of stock counts for exactly one level, so the levels of one material add up to the
  stock that is really there, and a shortage at a lower tier is not hidden by top-tier stock.
- Filling a higher level to its target before the next level gets anything means a lower level can
  turn low while the material's total looks ample; the per-level shortfall makes it visible.
- REQ-INV-067 (concept, counting, allocation, low and requested) and REQ-INV-068 (maintenance,
  access, API, the „Mindestbestände" tab, the „Bestand niedrig" widget, lifecycle, audit) are
  added, REQ-NOTIF-030 adds the notification, REQ-SUP-003 is reworded to "a Logistician of the unit,
  the lead included"; all planned.
- `AuditDomain.INVENTORY` gains `MIN_STOCK_LEVEL_SET`, `…_CHANGED`, `…_REMOVED` and
  `…_REMOVED_BY_CASCADE`; `basetool_inventory_minimum_levels_below` counts low levels without a unit
  label. Low stock is a notification, not an alert.

## Alternatives considered

- **Cumulative counting** (each level counts all free stock at or above its floor): the same unit of
  top-tier stock satisfies every level of the material at once, so a material looks covered at
  every tier while only the top tier has stock. Rejected.
- **Exclusive allocation up to the minimum only:** a higher level never shows a fill above its
  minimum, so the target and the fill ratio lose their meaning, and the hysteresis has nothing to
  re-arm on. Rejected.
- **Subtracting the requested amount:** treats a request as if it were delivered or spent and
  double-counts demand that is already an earmark. Rejected for showing it beside the level.
- **Counting the members' personal stock:** the unit cannot dispose of it. Rejected.

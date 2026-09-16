# ADR-0029 — Org chart visibility is decoupled from `is_profit_eligible`

- **Status:** Accepted — implemented
- **Date:** 2026-06-20
- **Deciders:** @greluc, Claude
- **Related:** spec REQ-ORG-018 · REQ-ORG-010 · ADR-0025 · ADR-0027 · issue #692

## Context

The org chart (`/org-chart`, "Organigramm") was born as the **Profit-Bereich** chart: its unit tier
was loaded by `OrgUnitRepository.findActiveProfitEligible()` (`active = true AND is_profit_eligible =
true`), and `OrgChartService.resolveScopeOrgUnit()` additionally rejected staffing a Staffel/SK that
was not profit-eligible. Epic #692 (REQ-ORG-014, ADR-0025) grew the real hierarchy
(OL > Bereich > Staffel/SK) and REQ-ORG-018 widened the chart to render the OL plus **every** active
Bereich side by side — but the leaf tier kept the legacy profit-eligibility filter.

`is_profit_eligible` is a **Job-Order** flag: it marks the org units that may be the *responsible
(processing)* unit of a Job Order, and gates Job Orders, material claims and the responsible-unit
picker (`OrgUnit.isProfitEligible`, `JobOrderService`, `MaterialClaimService`,
`OwnerScopeService.canViewJobOrders`). It defaults to `false` and is toggled only through the
dedicated `PATCH /squadrons|special-commands/{id}/profit-eligible` admin endpoints (surfaced in
Admin-Einstellungen as "Auftragsbearbeitung pro Staffel/SK").

This produced a confusing result once an admin wired non-Profit Staffeln/SKs under their Bereich
(e.g. Orion → Forschung, Cerberus → Marinekorps): the **Bereich box rendered** (Bereiche are loaded
unfiltered) but **its Staffeln/SKs did not**, because they are not profit-eligible. The unit was
invisible both under its Bereich and in the ungrouped tier, and even if shown could not be staffed
(`resolveScopeOrgUnit` rejected it). The flag was thus **overloaded**: "processes Profit Job Orders"
*and* "is visible on the org chart" — two unrelated concerns.

## Decision

We **decouple org-chart visibility from `is_profit_eligible`.** The chart is a descriptive,
organisation-wide view (REQ-ORG-010 / REQ-ORG-018), so its unit tier is **every active Staffel/SK**,
regardless of the Job-Order flag.

- `OrgUnitRepository.findActiveProfitEligible()` is replaced (for the chart) by
  `findActiveSquadronsAndSpecialCommands()` (`active = true AND TYPE(o) IN (Squadron,
  SpecialCommand)`). `OrgChartService.getOrgChart()` loads the leaf tier from it.
- `OrgChartService.resolveScopeOrgUnit()` no longer requires profit-eligibility for a Staffel/SK
  create; the sole create-time gate is the **active** flag, uniform across all four tiers
  (Staffel/SK/Bereich/OL). The `problem.org_chart.unit_not_profit_eligible` error is removed.
- `is_profit_eligible` keeps its **only** remaining meaning — Job-Order processing — and is never
  consulted by the chart again.

## Consequences

- A non-Profit Bereich's active Staffeln/SKs now render under that Bereich and can hold functional
  ranks. The chart reflects the full organisation an admin builds in `/admin/org-structure`.
- An admin no longer has to make a unit a Job-Order processor merely to show it on the chart — the
  side effect that motivated this ADR. The two concerns are now independent.
- The chart still grants **no** permission (REQ-ORG-010); only its *visibility* widened. No
  authorization, tenancy or Job-Order behaviour changes — `is_profit_eligible` still gates those.
- No migration: the change is query-and-guard only. `findActiveProfitEligible()` had a single caller
  (the chart) and is removed; `countProfitEligibleByIdIn` (the Job-Order path) is untouched.
- Soft-deleted (`active = false`) units stay hidden, and `resolveScopeOrgUnit` still rejects staffing
  an inactive unit with `problem.org_chart.unit_inactive`.

## Alternatives considered

- **Keep the filter; tell admins to mark units profit-eligible** — rejected: it forces an unrelated,
  consequential change (enrolling the unit as a Job-Order processor) just to make it visible, exactly
  the overload we are removing. The Admin-Einstellungen SK description even states that SKs of other
  Bereiche must *not* process orders.
- **Add a second per-unit "show on chart" flag** — rejected as needless state. The chart is
  descriptive and org-wide by definition; "active" is the right and only gate.
- **Filter the chart's *Bereiche* to Profit only instead** — rejected: REQ-ORG-018 deliberately
  renders every Bereich; the goal is a complete organisation chart, not a narrower one.

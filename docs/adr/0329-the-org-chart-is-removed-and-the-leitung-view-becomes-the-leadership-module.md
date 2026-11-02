# ADR-0329 — The org chart is removed and the Leitung view becomes the leadership module

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** @greluc
- **Related:** REQ-ROLE-004 · REQ-ROLE-006 · REQ-ORG-021 · REQ-UI-026 · REQ-UI-033 · REQ-DATA-001 ·
  supersedes [ADR-0029](0029-org-chart-visibility-decoupled-from-profit-eligibility.md) · issue #2987

## Context

The org chart (`/org-chart`, „Organigramm") was a descriptive view of the functional ranks. It
granted nothing: authority always came from the `org_unit_membership` ranks, and the chart's
account-linked seats were only a mirror of them, written by the org chart module as the org-unit
module's one `MembershipChangeObserver`. The chart is no longer used. It cost a module, a page with
its own editor, script and stylesheet, two quick-access entity types and a live-sync section.

Three other features leaned on it:

- the Leitung page, whose read service and controller lived in the `orgchart` module, read the
  chart only to show each unit's Bereich and department colour;
- the quick access found org units and people only through the chart;
- the org-chart module owned the `org_chart_position` table, which the data export, the person
  search and the account merge cover.

## Decision

1. **The chart goes completely:** its REST endpoints, page, editor, script, stylesheet, messages,
   navigation entry, the live-sync section `chart` of the `org-structure` room, and the
   org-unit module's `MembershipChangeObserver` SPI, whose only implementation was the chart's
   mirror. The free-text Grand Admiral, which only the chart editor could set, goes with it; the
   Grand Admiral stays an account designation made under Leitung (REQ-ORG-021).
2. **The `orgchart` backend module becomes the `leadership` module.** It keeps rank 7 and its
   allowed dependencies and holds the Leitung view: `GET /api/v1/leadership` replaces
   `GET /api/v1/org-chart/leadership`. The view reports each unit's Bereich name and department
   itself, so the Leitung page reads no other source.
3. **The quick access drops the org-unit and member entity types**, their prefixes (`@`,
   `mitglied:`, `einheit:`) and the hit's administration-mode link, which only member hits used.
4. **The unit bank (
5. **The stored data is dropped in the same change**, an owner-approved single-phase exception to
   REQ-DATA-001: `V331` drops `org_unit.grand_admiral_display_name` (keeping the Grand Admiral
   account confined to the OL row) and `V332` drops `org_chart_position`. The data export, the
   person search, the erasure coverage and the account merge lose their org-chart entries with it.

## Consequences

- No chart route, endpoint, template, script or registry entry remains; officer reach, the Leitung
  page and the appointments are unchanged, because none of them read the chart.
- Stored favourites, hidden pages and start pages that name `orgchart.view` are pruned on the
  member's next page load, as for any unknown entry key.
- A rollback to an application version with the org chart finds the table and the free-text column
  gone; the forward fix re-creates them empty (migration README, the V331 / V332 exception).
- No dormant personal data is left behind, so nothing has to stay registered for the export, the
  person search or the account merge.

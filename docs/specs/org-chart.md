> **Doc type:** Retired spec — the org chart was removed by
> [ADR-0329](../adr/0329-the-org-chart-is-removed-and-the-leitung-view-becomes-the-leadership-module.md)
> (#2987). Last reviewed: 2026-11-02.
> **Owner area:** ORG

# Organisation org chart (retired)

The org chart (`/org-chart`, „Organigramm") described who held which functional rank. It granted
nothing, and nothing else read it, so it was removed with its page, editor, endpoints, quick-access
types and the mirror of the leadership ranks. The text of the requirements below is in the Git
history of this file; read it with `git log -p -- docs/specs/org-chart.md`.

## Retired requirements

The ids stay reserved and are not reused.

| Id | Was | Where it stands now |
| --- | --- | --- |
| `REQ-ORG-010` | the chart is descriptive and ADMIN-edited | retired |
| `REQ-ORG-012` | removing a Kommando cascades, vacating its leader does not | retired |
| `REQ-ORG-013` | the chart is keyboard-operable and screen-reader-navigable | retired |
| `REQ-ORG-020` | a position is held by an account or a free-text name | retired |
| `REQ-ORG-021` | the single Grand Admiral of the Organisationsleitung | moved to [`org-unit-tenancy.md`](org-unit-tenancy.md#req-org-021--the-organisationsleitung-has-a-single-grand-admiral), account holders only |
| `REQ-ORG-025` | a Kommando carries a fillable and vacatable Kommandoleiter | retired |
| `REQ-ORG-026` | multi-Bereich chart with an Organisationsleitung level | retired; the Bereichsfarbe tints the Leitung tree (REQ-ROLE-004) |

## Stored data

`V331` dropped `org_unit.grand_admiral_display_name` and `V332` dropped `org_chart_position`, an
owner-approved single-phase exception to REQ-DATA-001 recorded in the migration README.

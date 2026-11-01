# ADR-0322 — A Kommandoleiter needs no Kommandogruppe

- **Status:** Accepted
- **Date:** 2026-11-01
- **Deciders:** @greluc
- **Related:** REQ-ROLE-003 · REQ-ROLE-006 · amends point 3 of
  [ADR-0042](0042-unified-membership-rank-with-delegated-appointment.md) · spec
  [`role-model.md`](../specs/role-model.md)

## Context

ADR-0042 made the Kommandogruppe a required binding for a Kommandoleiter and a stellv.
Kommandoleiter, and optional only for an Ensign. In practice a Staffel appoints its leaders before
it has decided how to split into groups, or never splits at all. The Leitung page offered
„— allgemein —" for all three ranks, but the backend and the V185 CHECK rejected the two upper ones
without a group. A group grants no rights, so the binding only blocked the appointment.

## Decision

1. **The group is optional for all three in-Kommando ranks.** A Kommandoleiter, a stellv.
   Kommandoleiter and an Ensign may carry a `kommando_group_id` of their Staffel or none; a
   Staffelleiter still carries none. V313 relaxes `chk_org_unit_membership_kommando_group_role`
   accordingly.
2. **Caps per Staffel, and per group where there is one.** At most four Kommandoleiter, four stellv.
   and four Ensigns per Staffel, counted with and without a group; within a group still at most one
   Kommandoleiter and one stellv. (the V188 indexes, which ignore a `NULL` group). The per-Staffel
   counts are checked in the service only, like the Ensign cap before.
3. **The org chart mirrors an ungrouped rank.** An ungrouped Kommandoleiter holds an own unnamed
   `COMMAND_LEAD` node without a group link; an ungrouped stellv. reports straight to the
   Staffelleiter, as the general Ensign does. When the rank goes, an unnamed ungrouped Kommando
   nothing reports into is deleted; any other Kommando is vacated as before.

## Consequences

- A Staffelleiter can appoint the full leadership first and form groups later; moving a member into
  a group is one more change of the group select.
- A Staffel can show more Kommando nodes in the chart than it has groups. The chart editor's
  „Kommandogruppe hinzufügen" counts every Kommando node, so it disappears earlier.
- An account-led Kommando without a group offers no rename or remove in the chart editor; it is
  managed on the Leitung page like every other account seat.

## Alternatives considered

- **Keep the group required and create one on the fly.** Hides the choice behind a placeholder
  group the Staffel never wanted and that counts against its four groups.
- **Leave ungrouped Kommandoleiter out of the chart.** Less code, but the chart would no longer
  mirror every account-linked rank (REQ-ROLE-006).
- **One ungrouped Kommandoleiter and stellv. per Staffel.** A tighter cap with no rule behind it;
  the four-per-Staffel limit already stands in the role model.

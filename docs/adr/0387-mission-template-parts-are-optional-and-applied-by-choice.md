# ADR-0387 - Mission template parts are optional and applied by choice

- **Status:** Accepted
- **Date:** 2026-11-14
- **Deciders:** @greluc
- **Related:** [REQ-MISSION-035](../specs/mission-templates.md), [REQ-MISSION-036](../specs/mission-templates.md), [REQ-MISSION-037](../specs/mission-templates.md), [ADR-0376](0376-mission-templates-have-one-owner-and-revision.md)

## Context

A mission template held goals, procedure steps and unit planning, and applying it replaced all
three lists of the create draft. Planners also want to reuse a description, the TeamSpeak meeting,
the planned start and end, the meeting point, the visibility and mission-wide frequencies. Most
templates only need some of these, and a planner who applies a template often wants only part of
it. A reusable plan cannot carry a calendar date, and the create form had no frequency fields.

## Decision

Every part of a template is optional. A `null` value or an empty list is an absent part, and an
absent part leaves the matching mission field to the planner. The template stores no flag per part:
whether a part is set follows from its value. The editor shows the parts a template sets and offers
the others in a part bar; a removed part is saved as absent.

The times are stored as times of day without a date or time zone. The planner's browser applies
them in its own time zone to the start date of the draft. The TeamSpeak meeting and the planned
end take that date, a day earlier or later when the time of day lies on the other side of the
start.

Mission-wide frequencies are ordered rows bound to exactly one of a catalogue frequency type or a
custom label, with the value rules of the mission's frequencies. A catalogue type appears once per
template. Templates and the create request share one normalization. The create form carries
editable frequency rows, so applied frequencies stay visible and editable before the mission is
created, as goals, steps and units are.

Applying a template opens a dialog that lists the parts the template sets. Only the parts the
planner ticks are copied, and the dialog marks the parts that would replace entries. The applied
revision is checked at create as before, whatever subset was applied.

## Consequences

A template stays small when it sets little, and one template can serve plans that differ in the
parts it leaves open. A template cannot force a part to be empty. The times of day follow the
planner's time zone, so planners in different zones see the same wall-clock times. A deactivated
frequency type stays in a template and is offered under a hidden-type label, like a hidden ship type.
The single template revision of ADR-0376 still covers every part.

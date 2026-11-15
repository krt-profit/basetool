> **Doc type:** Living spec
> **Owner area:** MISSION/UI
> **Related ADR:** [ADR-0376](../adr/0376-mission-templates-have-one-owner-and-revision.md), [ADR-0387](../adr/0387-mission-template-parts-are-optional-and-applied-by-choice.md)

# Mission templates

## Requirements

### REQ-MISSION-035 — Manage reusable planning templates within their owning unit

Officers and Mission Managers can create, edit and delete named mission templates for the active
org units they manage. The **Mission templates** tab sits beside **Missions** and **Operations**
under the navigation tab contract (REQ-UI-026, ADR-0297). It is available when the caller has
template-management reach in the active context. The selected org-unit context and the scoped
officer or Mission Manager checks govern owner choices, list and detail reads, and writes
(REQ-ORG-003, REQ-ORG-032, REQ-SEC-005). Each template has one explicitly selected owner, fixed
after creation.

A template sets only the parts its planner adds, and every part is optional (ADR-0387). A new
editor starts with the name, the owner and a part bar offering the description, the times, the
meeting point, the visibility, mission-wide frequencies, goals, procedure steps and unit planning.
Adding a part shows its card; removing it asks for confirmation when the card holds entries. A
removed or empty part is saved as absent, and an absent part leaves the matching mission field to
the planner. The times are the times of day of the TeamSpeak meeting, the planned start and the
planned end, each optional, without a date and truncated to minutes. The visibility part states
internal or public. A mission-wide frequency is either a catalogue frequency type, at most once per
template, or a custom label of at most 100 characters. Its value lies between 0.00 and 999.99 with
at most two decimals. Goals carry their classification, procedure steps optional time/place hints,
and each unit row a display name, optional catalogue ship type, optional note, HVU flag and optional
unit frequency. The fields use the mission's validation rules. A supplied unit frequency is rounded
to two decimal places and must lie between 100.00 and 999.99 inclusive. Procedure completion,
assigned hangar ships, responsible members and crew assignments remain on missions.

On wide screens, the editor shows the added description, times, meeting point and visibility above
the planning lists, goals and frequencies in the left column and procedure and unit planning in the
right column. A column without an added part leaves its width to the other. On narrow screens, the
parts stack in the part bar's order. Goal, procedure and frequency fields share a compact row with
their actions wherever space permits; on touch devices a frequency row in a medium-width card breaks
into two lines rather than shrink its fields.

Template names are trimmed and compared case-sensitively within the owning unit. The database
enforces uniqueness when writes race. Duplicate names produce an inline error on the name field.
The editor saves the whole template under one revision. A change to any part advances it; a save
that changes nothing keeps the revision and records no event.
Edit and delete check the caller's current permission and require the revision the editor read.
A stale revision conflicts and leaves the latest saved template intact. Planning rows use the shared
reorder controls and keyboard shortcuts (REQ-UI-031).

The list is paginated and updates in place after local writes and committed changes by peers
authorized for the same unit. List refreshes preserve any open editor's inputs and the revision from which those
inputs were derived (REQ-FE-001, REQ-FE-003, REQ-FE-015). Each create, update or delete that
changes stored state records an event in the Missionen activity audit in the same transaction.
Failed, stale and unauthorized writes record no lifecycle event (REQ-AUDIT-001, REQ-AUDIT-007).

**API.** `/api/v1/mission-templates` lists summaries for the explicit `owningOrgUnitId` query
parameter. It returns a bounded `PageResponse` ordered by name and ID (REQ-API-005). `GET /owning-org-units` under that base path lists the units the caller may manage.
`POST` to the base path creates the template with an explicit `owningOrgUnitId`.
An absent part is `null` (`description`, `meetingPoint`, `isInternal`, `meetingTime`,
`plannedStartTime`, `plannedEndTime`) or an empty list (`frequencies`, `objectives`, `steps`,
`units`); a write may omit `frequencies`. A frequency with both or neither of `frequencyTypeId` and
`name`, or a repeated type, returns 400, an unknown type 404.
`GET /{id}` reads the plan, `PUT /{id}` updates it with a body containing `version`, and
`DELETE /{id}` requires the `version` query parameter. A duplicate-name 409 uses
`DUPLICATE_ENTITY` and identifies `name` in `fieldErrors`. A stale revision returns 409
`OPTIMISTIC_LOCK`. Every ID-based operation authorizes against the saved owner and returns 404 for an
unknown or inaccessible template ID. An explicit owner outside the caller's management scope is
refused with 403 on list and create.

**Acceptance**

- [ ] An officer or Mission Manager creates a blank template, saves ordered planning data and
  deletes it. Multiple templates in a unit work, and two units may use the same name.
- [x] A template that sets only some parts stores only those, with times truncated to minutes, and
  an unchanged save keeps its revision and records no event (`MissionTemplateHttpTest`).
- [x] A template frequency needs exactly one of a type and a label, uses a type at most once, names
  a known type and has a value up to 999.99 with two decimals (`MissionTemplateHttpTest`).
- [ ] The editor shows exactly the parts a template sets, offers the others in the part bar, and
  presents the planning lists in two columns on wide screens and in one column on narrow screens.
  Goal classifications and row actions stay with the goal they belong to.
- [ ] Concurrent same-owner writes of the same name yield one successful write and a name
  conflict. Invalid mission fields, including out-of-range frequencies, are rejected.
- [ ] List, detail and mutation endpoints enforce the target scope, including direct requests
  with a foreign template ID. Failed, stale and unauthorized writes leave no audit event.
- [ ] A planning-row edit advances the revision. An authorized peer sees the list change while
  their open editor retains its unsaved inputs and its previous revision.


### REQ-MISSION-036 - Start a template from a readable mission

**Save as template** opens the common template editor from the details of a standalone mission or
a mission linked to an operation. The draft starts with the mission name and, as parts, its
description, meeting point and visibility, the times of day at which the viewer sees its TeamSpeak
meeting, planned start and planned end, its typed and custom frequencies, ordered classified goals,
ordered procedure titles and hints, and each visible unit's display name, catalogue ship type,
note, HVU flag and unit frequency. A field the mission leaves empty starts no part, and the planner
removes the parts the template should not set. It uses the standard mission detail read and its
field visibility. Assignments, progress and other mission state stay with the source mission.

The destination picker lists the active units the caller can currently manage as specified in
REQ-MISSION-035. The source owner and the source's edit permission do not constrain the
destination. The planner can edit the name and plan before saving through the same create path
as a blank template. Opening or cancelling the editor performs no write.

**Acceptance**

- [ ] The planner opens the prefilled editor from the mission detail, edits the name and plan,
  and saves in a permitted unit other than the readable mission's owner.
- [ ] A direct request for an inaccessible source or a write to a destination outside management
  scope is refused. Cancelling leaves the destination list unchanged.
- [ ] Goal classifications and order, step hints, HVU and unit frequency round-trip. The saved
  template contains reusable planning fields only.
- [x] The draft carries the mission's description, visibility and frequencies and its times as
  instants for the viewer's time zone; a blank meeting point starts no part
  (`MissionTemplatePageControllerMvcTest`).

### REQ-MISSION-037 - Apply editable template rows when creating a mission

Standalone and operation-linked missions share one optional template selector in the
[create draft](mission-detail-tabs.md#req-mission-015--create-time-zieleablauf-seeding-verwaltung-landing-and-floating-speichern).
Choices and previews use the mission's effective owning org unit under REQ-ORG-004, REQ-ORG-016
and REQ-ORG-032. The usual create permission and resolved owner govern template use. The linked
operation's owner has no effect on template scope. No choices are offered for an ownerless draft
or while an owner pick is required. The selector loads further pages in the same scope on demand.

The selector is the first section of the create form. Choosing a template opens a dialog that lists
the parts the template sets and the form can take, each ticked and with a short preview; a part
whose application would replace an entry is marked. Only the ticked parts are copied into the
editable draft, and unticked parts as well as parts the template does not set stay as they are.
Description, meeting point and visibility replace the draft's values. A time of day sets the time
of the matching schedule field. The TeamSpeak meeting and the planned end take the date of the
planned start: a meeting later in the day than the start falls on the day before, an end not after
the start on the day after. A meeting or end without a date follows the start date once it is
picked. Frequencies, goals, procedure steps and unit planning replace the matching draft rows. The
draft planning rows use the shared reorder controls (REQ-UI-031). Cancel leaves the draft and the
applied selection as they were. Changing owners preserves all draft data and detaches the
selected revision. The selector reloads in the new scope and discards pending responses from the
previous scope. Validation failures leave the form and its inputs open.

The preview reads all planning rows and the revision from one snapshot. Creation checks an
applied template's scope and revision under a shared row lock before writing the mission or its
audit event. Template writers acquire an exclusive lock on that row before changing the plan.
A stale, deleted or inaccessible selected revision results in a conflict. The user can retain the
rows by explicitly detaching the selection or apply the current plan if it is available.

The create transaction stores the final submitted goals, procedure steps, units and frequencies
with fresh server-assigned IDs. It assigns goal and step order from the draft and initializes steps
as open. The copied rows use normal mission validation, visibility, permissions and audit events
(REQ-MISSION-015, REQ-AUDIT-001). Frequencies are checked before the mission is written, so an
invalid one writes neither the mission nor an audit event. Later template changes or deletion have no effect on saved
missions. The create flow navigates to the new mission's management tab (REQ-FE-006).

**API.** Under `/api/v1/missions`, `GET /template-choices` returns a bounded `PageResponse` ordered
by name and ID (REQ-API-005). `GET /template-preview/{templateId}` returns the scoped planning data
and revision. Both accept the optional `owningOrgUnitId` query parameter and return
`Cache-Control: private, no-store` (REQ-SEC-031). Without a pick or honoured pin, a member of several
units has no effective owner yet (REQ-ORG-023), so `GET /template-choices` answers an empty page,
never an error. A preview outside the resolved scope or with an
unknown template ID returns 404. `POST /api/v1/missions` accepts optional `units` and
`plannedFrequencies` planning rows and `templateSelection: {id, version}`. The frequency rows follow
the template frequency rules of REQ-MISSION-035; like `units`, the planning name keeps them apart
from the server-managed mission collection the write-DTO rule guards. A stale, deleted or inaccessible selected revision returns
`409 MISSION_TEMPLATE_STALE`. Callers may omit the selection and submit the rows as manual data.

**Acceptance**

- [ ] A creator without template-management reach can apply plans from the effective mission
  owner. A direct preview or submit cannot use a foreign template. The linked operation may
  belong to a different unit.
- [ ] Confirmation, cancellation, validation failure and scope changes preserve the relevant
  draft data. A late preview cannot apply to the new scope.
- [ ] A child-only template edit or deletion conflicts when the old revision is submitted. The
  conflict writes neither a mission nor an audit event. Detachment and reapplication both allow
  a deliberate retry.
- [ ] Saved goals, steps and unit planning rows are independently editable and survive template
  deletion.
- [ ] The dialog offers only the parts the template sets, applies only the ticked ones, marks the
  parts that replace entries, and leaves the draft unchanged on cancel. Times of day land on the
  start date with the day-before and day-after rules.
- [x] Typed and custom frequency rows are created with the mission and audited; a repeated type is
  rejected before anything is written (`MissionTemplateHttpTest`,
  `MissionPageControllerMvcTest`).
- [x] A member of several units without a pick or pin gets an empty template-choices page, with the
  request committed rather than rolled back (`MissionTemplateHttpTest`).

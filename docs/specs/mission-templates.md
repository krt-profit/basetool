> **Doc type:** Living spec
> **Owner area:** MISSION/UI
> **Related ADR:** [ADR-0375](../adr/0375-mission-templates-have-one-owner-and-revision.md)

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

A new editor starts with a name field and empty planning lists. The template stores the name,
ordered goals with their classification, ordered procedure steps with optional time/place hints,
and unit rows. Each unit row carries a display name, optional catalogue ship type, optional note,
HVU flag and optional unit frequency. The fields use the mission's validation rules. A supplied
frequency is rounded to two decimal places and must lie between 100.00 and 999.99 inclusive.
Procedure completion, assigned hangar ships, responsible members and crew assignments remain on
missions.

Template names are trimmed and compared case-sensitively within the owning unit. The database
enforces uniqueness when writes race. Duplicate names produce an inline error on the name field.
The editor saves the whole template under one revision. Changes to any planning row advance it.
Edit and delete check the caller's current permission and require the revision the editor read.
A stale revision conflicts and leaves the latest saved template intact.

The list updates in place after local writes and committed changes by peers authorized for the
same unit. List refreshes preserve any open editor's inputs and the revision from which those
inputs were derived (REQ-FE-001, REQ-FE-003, REQ-FE-015). Each successful create, update or delete
records an event in the Missionen activity audit in the same transaction. Failed, stale and
unauthorized writes record no lifecycle event (REQ-AUDIT-001, REQ-AUDIT-007).

**API.** `/api/v1/mission-templates` lists summaries for the explicit `owningOrgUnitId` query
parameter. `GET /owning-org-units` under that base path lists the units the caller may manage.
`POST` to the base path creates the template with an explicit `owningOrgUnitId`.
`GET /{id}` reads the plan, `PUT /{id}` updates it with a body containing `version`, and
`DELETE /{id}` requires the `version` query parameter. A duplicate-name 409 uses
`DUPLICATE_ENTITY` and identifies `name` in `fieldErrors`. A stale revision returns 409
`OPTIMISTIC_LOCK`. Every ID-based operation authorizes against the saved owner and returns 404 for an
unknown or inaccessible template ID. An explicit owner outside the caller's management scope is
refused with 403 on list and create.

**Acceptance**

- [ ] An officer or Mission Manager creates a blank template, saves ordered planning data and
  deletes it. Multiple templates in a unit work, and two units may use the same name.
- [ ] Concurrent same-owner writes of the same name yield one successful write and a name
  conflict. Invalid mission fields, including out-of-range frequencies, are rejected.
- [ ] List, detail and mutation endpoints enforce the target scope, including direct requests
  with a foreign template ID. Failed, stale and unauthorized writes leave no audit event.
- [ ] A planning-row edit advances the revision. An authorized peer sees the list change while
  their open editor retains its unsaved inputs and its previous revision.


### REQ-MISSION-036 - Start a template from a readable mission

**Save as template** opens the common template editor from the details of a standalone mission or
a mission linked to an operation. The draft starts with the mission name, ordered classified goals,
ordered procedure titles and hints, and each visible unit's display name, catalogue ship type,
note, HVU flag and unit frequency. It uses the standard mission detail read and its field
visibility. Assignments, progress and other mission state stay with the source mission.

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

### REQ-MISSION-037 - Apply editable template rows when creating a mission

Standalone and operation-linked missions share one optional template selector in the
[create draft](mission-detail-tabs.md#req-mission-015--create-time-zieleablauf-seeding-verwaltung-landing-and-floating-speichern).
Choices and previews use the mission's effective owning org unit under REQ-ORG-004, REQ-ORG-016
and REQ-ORG-032. The usual create permission and resolved owner govern template use. The linked
operation's owner has no effect on template scope. No choices are offered for an ownerless draft
or while an owner pick is required.

Applying a template copies its goals, procedure steps and unit planning values into editable draft
rows. Applying or reapplying requires confirmation when it would replace an existing row.
Cancel leaves the draft as it was. Changing owners preserves all draft data and detaches the
selected revision. The selector reloads in the new scope and discards pending responses from the
previous scope. Validation failures leave the form and its inputs open.

The preview reads all planning rows and the revision from one snapshot. Creation checks an
applied template's scope and revision under a shared row lock before writing the mission or its
audit event. Template writers acquire an exclusive lock on that row before changing the plan.
A stale, deleted or inaccessible selected revision results in a conflict. The user can retain the
rows by explicitly detaching the selection or apply the current plan if it is available.

The create transaction stores the final submitted goals, procedure steps and units with fresh
server-assigned IDs. It assigns goal and step order from the draft and initializes steps as open.
The copied rows use normal mission validation, visibility, permissions and audit events
(REQ-MISSION-015, REQ-AUDIT-001). Later template changes or deletion have no effect on saved
missions. The create flow navigates to the new mission's management tab (REQ-FE-006).

**API.** Under `/api/v1/missions`, `GET /template-choices` returns the scoped summaries and
`GET /template-preview/{templateId}` returns the scoped planning data and revision. Both accept the
optional `owningOrgUnitId` query parameter. A preview outside the resolved scope or with an
unknown template ID returns 404. `POST /api/v1/missions` accepts optional `units` planning rows and
`templateSelection: {id, version}`. A stale, deleted or inaccessible selected revision returns
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

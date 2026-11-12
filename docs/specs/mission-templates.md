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

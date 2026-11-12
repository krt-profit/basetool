# ADR-0375 - Mission templates have one owner and revision

- **Status:** Accepted
- **Date:** 2026-11-12
- **Deciders:** @greluc
- **Related:** [REQ-MISSION-035](../specs/mission-templates.md), [ADR-0297](0297-the-navigation-is-one-task-grouped-default-with-desktop-modes-a-configurable-tab-bar-and-a-scoped-entity-search.md), [ADR-0374](0374-the-active-org-unit-context-has-one-pin-rule-and-a-stored-choice.md), [template management decision](https://github.com/krt-profit/basetool/issues/3391#issuecomment-6822730301)

## Context

An org unit's planners need to maintain a reusable plan independently of any mission that may use
it. One editor saves the whole plan. A name collision or concurrent edit needs a predictable
outcome, and a peer's change must reach the shared list while an editor is open.

## Decision

The mission module owns the template aggregate. Its owner is an explicit, immutable org-unit
reference. The active-context officer and Mission Manager gates apply to the target owner for
every management operation. A database constraint enforces uniqueness on owner and trimmed name.

Goals, procedure steps and unit planning rows are ordered values owned by the template. A single
optimistic version covers the aggregate because one form submits it atomically.
A detail read uses one transaction snapshot. Writes lock only the affected template row before
checking the version and changing the plan. Template values
represent reusable planning information. Mission-specific runtime state belongs to the mission.

Lifecycle events enter the Missionen activity audit in the same transaction. After commit, the
owner's live-sync room invalidates the list section. Each subscriber refreshes that section
through the scoped paginated read, leaving open editor state and its revision intact.

For mission creation, template access follows the mission create permission and effective owning
unit (REQ-MISSION-037). Both the paginated choice list and the preview are private, no-store
reads. The template preview reads the plan and version in one snapshot.
The create transaction takes a shared lock on the scoped template row before checking its revision
and saving the mission. The persisted mission owns its edited rows and carries no template
dependency.

## Consequences

A stale edit or delete receives a conflict. Independent templates have independent revisions.
An owner-scoped name conflict can be shown on the editor's name field. The template list and its
updates use the same authorization rule.

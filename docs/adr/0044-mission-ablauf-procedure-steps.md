# ADR-0044 — Mission Ablauf as a persisted ordered child with a section-version counter and shared per-step done state

- **Status:** Accepted (final Einsatz design, owner-approved 2026-06-27)
- **Date:** 2026-06-27
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-MISSION-009/-010 ([`docs/specs/mission-detail-tabs.md`](../specs/mission-detail-tabs.md)) ·
  REQ-AUDIT-001 ([`docs/specs/audit.md`](../specs/audit.md)) ·
  REQ-FE-001…010 ([`docs/specs/frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) ·
  [ADR-0012](0012-frontend-krtfetch-json-mutations-csrf-retry.md) /
  [ADR-0031](0031-live-mission-sync-over-presence-websocket.md) ·
  the `Mission` section-version precedent (`coreVersion`/`scheduleVersion`/`flagsVersion`/`partyLeadVersion`)

## Context

The final Einsatz design (`proposals/einsatz-seiten-final.html`, #818 follow-up) adds an **Ablauf** —
an ordered procedure timeline of steps (title + optional time/place hint) authored in the Verwaltung
tab and shown read-only as a checklist on the Übersicht. Edit-authorised users tick a step's "done"
check during a running mission to track the current phase. This needs a data model and raises four
decisions:

1. **Where does the Ablauf live** — a structured child collection, or free text inside the existing
   Markdown description?
2. **Is `done` persisted and shared**, or a transient per-viewer affordance?
3. **How is the "current phase" determined** — stored, or derived?
4. **How is concurrency guarded** so editing the Ablauf does not collide with concurrent edits of
   other mission sections, and how is the audited-area duty met without leaking free text?

The mission aggregate already carries the section-version pattern (manual `@OptimisticLock(excluded =
true) Long` counters layered on the row `@Version`) and the krtFetch fragment-swap + presence-socket
live-sync stack, so the Ablauf should reuse them rather than invent new machinery.

## Decision

1. **Persisted ordered child `MissionStep` (REQ-MISSION-009).** A new `mission_step` table (V192),
   `@OneToMany(cascade=ALL, orphanRemoval=true) @OrderBy("orderIndex ASC") @OptimisticLock(excluded =
   true) Set<MissionStep>` on `Mission`, mirroring `MissionFrequency`/`MissionUnit`. `title` is required
   (≤200), `meta` optional (≤200), `done` a boolean, `orderIndex` an explicit stored position (the
   timeline is reorderable, so it cannot rely on `@OrderBy name` like units).

2. **`done` is persisted and shared across all viewers.** It is what makes the checklist a live
   mission-progress tracker rather than a personal note, so it is a real `MissionStep.done` column that
   every viewer sees and that — being a state mutation in an audited area — is audited.

3. **The single "current phase" is derived, never stored.** `step--now` = the first not-done step,
   computed server-side (Thymeleaf SpEL `mission.steps.^[!done]`). No extra column, and a peer's swap
   always shows the correct phase because it re-renders from the shared `done` flags.

4. **A dedicated `stepsVersion` section counter, NOT the row `@Version`.** A manual `@OptimisticLock(
   excluded = true) Long stepsVersion` on `Mission` (the fifth member of the
   core/schedule/flags/party-lead family) guards every Ablauf mutation. Editing a step therefore never
   409s a concurrent core/schedule/flags edit (and vice versa); a stale `stepsVersion` is the only
   thing that yields HTTP 409. Because it is not a `@Version`, the `saveAndFlush` writeback caveat does
   not apply. Reorder reassigns `orderIndex` over the **managed** children by dirty-checking (no
   per-child `save()`, no clearing bulk query mid-loop — the bulk-update-in-loop landmine), serialised
   by the `stepsVersion` optimistic guard rather than a pessimistic lock, and records **one** event.

5. **Audit logs ids only, never free text (REQ-AUDIT-001).** Five `MISSION_STEP_*` events (`ADDED` /
   `UPDATED` / `REMOVED` / `REORDERED` / `DONE_CHANGED`) record the mission id + name snapshot and a
   details payload of step id / count / the done boolean — the step title and meta (user free text)
   are **never** written to the audit trail.

6. **Outsiders see the Ablauf read-only.** Steps, objective (Ziel) and meeting point (Treffpunkt) are
   non-PII planning data, forwarded to guests/outsiders for non-internal missions like the already-
   forwarded units (incl. their free-text notes) and frequencies. The long Markdown **description**
   stays the one free-text field hidden from outsiders (ADR-0034 unchanged). Internal missions remain
   gated at the mission level, so their planning data is never reachable by outsiders regardless.

7. **In-place, peer-synced UI (REQ-FE).** All five mutations use krtFetch slim AJAX proxies and
   re-render the Verwaltung editor + Übersicht checklist fragments via
   `krtRefreshMissionSection(['steps','overview'])` (no reload), broadcasting over the presence socket
   so peers re-pull through their own authorised fragment endpoints (ADR-0031).

## Consequences

- One new table + one column, one DTO mirror, five endpoints, five audit events, ~34 i18n keys.
- The Ablauf can never collide with an unrelated mission edit, matching the rest of the page's
  fine-grained locking.
- The "current phase" indicator is free (derived) and always correct after a peer swap.
- The legacy full-PUT `updateMission` path also accepts `objective`/`meetingPoint` for contract
  completeness, but the frontend always uses the section-scoped `/core` patch.

## Alternatives considered

- **Free text in the description.** Rejected: no per-step done tracking, no reorder, no derivable
  current phase, and the checklist UX would not be possible.
- **Reuse a single `@Version`/`coreVersion` for steps.** Rejected: would 409 unrelated concurrent
  edits, violating the page's fine-grained-locking invariant.
- **Store the current-phase step explicitly.** Rejected: redundant with `done`, and a second column to
  keep consistent on every toggle/reorder.
- **Per-viewer (client-only) done state.** Rejected: the owner wants a shared live progress tracker;
  client-only state would not survive a reload nor be visible to the rest of the squadron.

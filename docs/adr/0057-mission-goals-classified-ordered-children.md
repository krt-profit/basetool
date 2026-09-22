# ADR-0057 — Mission goals (Ziele) as classified, ordered children replacing the single objective

- **Status:** Accepted (owner-approved)
- **Date:** 2026-06-30
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-MISSION-004/-010/-019 ([`docs/specs/mission-detail-tabs.md`](../specs/mission-detail-tabs.md)) ·
  REQ-AUDIT-001 ([`docs/specs/audit.md`](../specs/audit.md)) ·
  REQ-FE-001…010 ([`docs/specs/frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) ·
  [ADR-0012](0012-frontend-krtfetch-json-mutations-csrf-retry.md) /
  [ADR-0031](0031-live-mission-sync-over-presence-websocket.md) ·
  [ADR-0044](0044-mission-ablauf-procedure-steps.md) (the Ablauf precedent this mirrors) ·
  the `Mission` section-version family (`coreVersion`/`scheduleVersion`/`flagsVersion`/`partyLeadVersion`/`stepsVersion`)

## Context

The Einsatz overview led with a single short free-text **objective** (`Ziel`, ≤250 chars, REQ-MISSION-010)
in the "Mission auf einen Blick" panel. The owner wants the operation's goal expressed as a **structured
list** — individual goal items, each **classified** as a main goal, a secondary goal, or an explicit
*non*-goal (something the operation deliberately does NOT pursue, stated to bound the scope) — authored
like the Ablauf steps and shown grouped on the overview. This supersedes the single objective and raises
the same four decisions ADR-0044 answered for the Ablauf, plus a fifth:

1. **Where do the goals live** — a structured child collection, or the single free-text field kept?
2. **Is there a per-goal `done`/achieved state**, like an Ablauf step's progress flag?
3. **How is concurrency guarded** so editing the goals does not collide with concurrent edits of other
   mission sections, and how is the audited-area duty met without leaking free text?
4. **What happens to the existing `objective` data** when the single field is replaced?
5. **How are the three classifications modelled** and ordered on the overview?

The mission aggregate already carries the section-version pattern and the krtFetch fragment-swap +
presence-socket live-sync stack (ADR-0044), so the goals reuse them rather than invent new machinery.

## Decision

1. **Persisted ordered child `MissionObjective` (REQ-MISSION-019), replacing the single objective.** A new
   `mission_objective` table (V199), `@OneToMany(cascade=ALL, orphanRemoval=true) @OrderBy("orderIndex
   ASC") @OptimisticLock(excluded = true) Set<MissionObjective>` on `Mission`, mirroring `MissionStep`.
   `title` is required (≤250, sized to never truncate a migrated objective), `kind` is the classification,
   `orderIndex` an explicit stored position (the list is reorderable). The legacy `mission.objective`
   column is **dropped** in the same migration.

2. **No per-goal `done` flag.** A goal is a *scope statement*, not a progress item, so — unlike an Ablauf
   step — it carries no shared `done` boolean and no derived "current" indicator. It also carries no
   free-text `meta`; a goal is a single classified bullet. (If a future need arises to mark a goal
   "achieved", it can be added as an Ablauf-style shared flag then.)

3. **A dedicated `objectivesVersion` section counter, NOT the row `@Version`.** A manual `@OptimisticLock(
   excluded = true) Long objectivesVersion` on `Mission` (the sixth member of the
   core/schedule/flags/party-lead/steps family) guards every goal mutation. Editing a goal therefore never
   409s a concurrent core/schedule/flags/Ablauf edit (and vice versa); a stale `objectivesVersion` is the
   only thing that yields HTTP 409. Reorder reassigns `orderIndex` over the **managed** children by
   dirty-checking (no per-child `save()`, no clearing bulk query mid-loop — the bulk-update-in-loop
   landmine), serialised by the optimistic guard rather than a pessimistic lock, and records **one** event.

4. **Existing objective data is migrated, not discarded.** V199 inserts, for every mission with a non-empty
   `objective`, exactly one `PRIMARY` goal carrying that text (owner decision: "Ersetzen + migrieren"), then
   drops the column. No planning data is lost.

5. **Three classifications via an English enum, ordered on the overview.** `MissionObjectiveKind {
   PRIMARY, SECONDARY, NON_GOAL }` (code in English; the German labels Hauptziel / Nebenziel / Nicht-Ziel
   come from the i18n bundle). The read-only overview groups goals by kind — Hauptziele first, then
   Nebenziele, then Nicht-Ziele — within each group preserving the authored `orderIndex` order. The
   Verwaltung editor is a single flat sortable list with a per-row kind selector; changing a row's kind is
   an UPDATE (the overview regroups on the next render).

6. **Audit logs ids and the kind enum only, never the title (REQ-AUDIT-001).** Four `MISSION_OBJECTIVE_*`
   events (`ADDED` / `UPDATED` / `REMOVED` / `REORDERED`) record the mission id + name snapshot and a details
   payload of goal id / count / the `kind` enum name. The kind is a non-personal classification (like the
   membership rank enums) and may be logged; the goal **title** (user free text) is **never** written to the
   audit trail.

7. **Outsiders see the Ziele read-only.** Goals are non-PII planning data, forwarded to guests/outsiders for
   non-internal missions like the already-forwarded Ablauf steps, units and frequencies (ADR-0044 point 6).
   The long Markdown description stays the one free-text field hidden from outsiders.

8. **In-place, peer-synced UI (REQ-FE).** All four mutations use krtFetch slim AJAX proxies and re-render the
   Verwaltung editor + Übersicht Ziele fragments via `krtRefreshMissionSection(['objectives','overview'])`
   (no reload), broadcasting over the presence socket so peers re-pull through their own authorised fragment
   endpoints (ADR-0031). The two Übersicht columns were also swapped (Ziele/Ablauf/Teilnehmer/Kalender left;
   Mission auf einen Blick/Weitere Leads/Funk right) per the owner's layout decision.

## Consequences

- One new table + one column, one DTO mirror + one enum, four endpoints, four audit events, ~25 i18n keys,
  and a data migration that converts every existing objective into a Hauptziel.
- The Ziele can never collide with an unrelated mission edit, matching the rest of the page's fine-grained
  locking.
- The single `objective` field and its DTO/form/request slots are gone; the legacy full-PUT/`/core` paths no
  longer carry it (contract simplification, reflected in the regenerated `openapi.json`).

## Alternatives considered

- **Keep the single objective, add goals alongside.** Rejected by the owner ("Ersetzen + migrieren"): two
  overlapping "goal" fields would confuse authors and leave the single objective orphaned.
- **Free text / Markdown bullet list for goals.** Rejected: no per-goal classification, no reorder, no
  grouped display, and the structured editor UX would not be possible.
- **Reuse `coreVersion` for goals.** Rejected: would 409 unrelated concurrent edits, violating the page's
  fine-grained-locking invariant.
- **A shared `done`/achieved flag per goal (like Ablauf).** Rejected for now: a goal is a scope statement,
  not a checklist item; the owner modelled goals purely by classification. Deferred, not foreclosed.

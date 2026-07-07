# ADR-0080 — Mission section-lock counters DB-enforced; row `@Version` decoupled via `@DynamicUpdate`

- **Status:** Accepted
- **Date:** 2026-07-07
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`mission-detail-tabs.md`](../specs/mission-detail-tabs.md) `REQ-MISSION-017` (hardens the enforcement of `REQ-MISSION-009` / `-012` / `-016`) · [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) `REQ-ORG-018` · ADR-0044 (Ablauf `stepsVersion`) · ADR-0050 (`owningOrgUnitVersion`) · ADR-0057 (`objectivesVersion`) · epic #1109, issues #1112 / #1114 / #1147

## Context

A `Mission` is edited section-by-section (core / schedule / flags / party-lead / owning-org-unit /
Ablauf steps / goals). Each section carries its own plain `Long` counter (`coreVersion`,
`scheduleVersion`, …) — not the row's JPA `@Version` — so an edit to one section should not 409 a
concurrent edit to another (ADR-0044 / -0050 / -0057, `REQ-ORG-018`). The counters were checked and
bumped **in memory** (`MissionSectionVersions.assertSectionVersion` + `bumpSectionVersion`).

The round-2 concurrency audit (epic #1109) found this enforcement had three real defects:

- **P0-3 (#1112) — silent lost update.** `setPartyLead` / `updateOwningOrgUnit` mutate only
  `@OptimisticLock(excluded = true)` fields, so their `save` issues a non-versioned UPDATE. The
  in-memory check has a TOCTOU window: two overlapping reassignments both pass and the later commit
  silently overwrites the other — no 409.
- **P1-1 (#1114) — cross-section 409.** The mission's business scalars (`name`, `description`,
  `meetingPoint`, `calendarLink`, `status`, the schedule timestamps, `isInternal`) were **not**
  `@OptimisticLock(excluded = true)`, so any core/schedule/flags edit bumped the row `@Version` and
  409-ed an *unrelated* concurrent section edit — the coarse, screen-wide lock CLAUDE.md flags as a
  defect. The per-section counters could not decouple the sections at the row level.
- **P2-16 (#1147) — duplicate `orderIndex`.** Two concurrent `addStep` / `addObjective` both compute
  `orderIndex = max + 1` on the same snapshot and both commit; `@OrderBy("orderIndex ASC")` then
  renders a nondeterministic tie, and concurrent reorders/deletes interleave into gaps.

The naive per-defect fixes conflict: excluding the header scalars (needed for #1114) removes the last
non-excluded column, so a non-dynamic full-row UPDATE would write **every** column from the writer's
stale snapshot — trading a spurious 409 for a *silent* lost update across sections.

## Decision

Move the section-counter guard from in-memory to a **DB-enforced atomic conditional bump**, exclude
every mutable mission column from the row lock, and make the entity `@DynamicUpdate` so the two are
compatible.

- **Atomic conditional bump.** Each section's check-and-bump is a single
  `UPDATE Mission m SET m.xVersion = m.xVersion + 1 WHERE m.id = :id AND m.xVersion = :expected`
  (`MissionRepository.bump*VersionIfMatches`, dispatched by the private `MissionSection` enum through
  `MissionSectionVersions.enforceSectionVersion`). **0 rows affected → `ObjectOptimisticLockingFailureException` (409).** The statement row-locks the mission, so two racing same-section writers
  genuinely serialise: the loser blocks, re-reads the bumped counter and 409s. On success the managed
  entity's in-memory counter is advanced to `expected + 1` so the response echoes the fresh version.
- **Full column exclusion + `@DynamicUpdate`.** Every mutable `Mission` scalar and association is
  `@OptimisticLock(excluded = true)`, and the entity is annotated `@DynamicUpdate`. A section edit
  therefore dirties only its own columns, so (a) it never bumps the row `@Version` — no cross-section
  409 — and (b) the column-narrowed flush never clobbers a concurrent other-section change with a
  stale full-row snapshot.
- **Legacy full-replace keeps the row `@Version`.** With every scalar excluded, the legacy
  whole-mission `updateMission` (`PUT /missions/{id}`) would no longer bump `@Version` on its own, so
  two concurrent overwrites would become last-writer-wins. It loads through `findByIdForFullReplace`
  under JPA `LockModeType.OPTIMISTIC_FORCE_INCREMENT`, which forces the version increment + check at
  flush — restoring its "exactly one of two concurrent overwrites wins" guarantee. The row `@Version`
  now guards exactly this one path.
- **Steps/goals DB backstop.** A **deferrable** unique `(mission_id, order_index)` constraint on
  `mission_step` and `mission_objective` (migration V208, `DEFERRABLE INITIALLY DEFERRED`) rejects a
  duplicate ordinal at commit. Deferral is required: a reorder swaps two rows' ordinals and
  transiently collides mid-flush, which an immediately-checked index would reject even
  single-threaded; deferring validates the final `0..n-1` state once at commit.
- **In-memory `bumpSectionVersion` survives only for the two unconditional cross-section pokes** with
  no client echo: the legacy full-replace (which force-increments the row `@Version`) and the
  activation auto-stamp of `actualStartTime` inside a core patch.

## Consequences

- Same-section concurrency is now correct at the database, not merely best-effort in memory: the
  silent lost update on party-lead / owning-org-unit and the duplicate step/goal ordinal can no
  longer occur; the loser gets a truthful 409.
- Disjoint-section edits never 409 each other. They are briefly **serialised** by the row lock the
  conditional bump takes (one blocks the other for the duration of a single statement) — acceptable
  latency, not a conflict; the acceptance tests assert both commit.
- `@DynamicUpdate` makes Hibernate build the flush SQL per-dirty-column rather than caching one static
  UPDATE — a small, well-understood cost, paid only on mission-row writes, in exchange for the
  cross-section decoupling.
- The row `@Version` is now effectively vestigial for section edits and load-bearing only for the
  legacy full-replace path; that is intentional and documented.
- The DB-enforced bump writes the counter column twice on a section edit (once by the conditional
  UPDATE, once by the dirty-checking flush of the in-memory advance) — the same value, idempotent, one
  extra column in an UPDATE that already runs. We accept it to keep the response version fresh without
  clearing the persistence context.
- The change is behaviour-preserving at the API boundary (same endpoints, DTOs, 409 status, audit
  events) — it hardens *how* the existing section locks are enforced. It amends the enforcement
  described in ADR-0044 / -0050 / -0057; those remain accepted, their per-section-counter design
  unchanged.

## Alternatives considered

- **Keep the in-memory check, add pessimistic `SELECT … FOR UPDATE` per section.** Rejected: a second
  round-trip and a held lock for the whole request; the conditional UPDATE is a single statement that
  both checks and locks.
- **Promote each section counter to a real JPA `@Version` on a companion per-section entity.**
  Rejected: seven companion tables/entities for what a conditional bump on one row expresses; a large
  schema and mapping change for no additional guarantee.
- **Exclude the header scalars without `@DynamicUpdate`.** Rejected — unsafe: a non-dynamic full-row
  UPDATE writes every column from the stale snapshot, turning the #1114 spurious-409 fix into a silent
  cross-section lost update.
- **Non-deferrable unique `(mission_id, order_index)` index.** Rejected: a reorder's transient
  mid-flush collision would fail even single-threaded; `DEFERRABLE INITIALLY DEFERRED` validates the
  final state at commit.
- **Drop the row `@Version` entirely and give the legacy full-replace its own conditional bump.**
  Rejected: `OPTIMISTIC_FORCE_INCREMENT` is the JPA-native tool for "force a version check even when
  only excluded fields changed"; a manual `@Modifying` bump on the `@Version` column fights
  Hibernate's own version management on the managed entity's flush.


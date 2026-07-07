-- Stage-2 backstop for the Ablauf-step / goal ordering invariant (REQ-MISSION-015, issue #1147,
-- epic #1109). Every mission_step / mission_objective mutator guarded its order_index only via the
-- in-memory section-counter check and then bumped a counter that is @OptimisticLock(excluded=true).
-- When the only dirtied mission-row column is that excluded counter, Hibernate emitted a
-- non-versioned UPDATE, so two concurrent same-section writers touching disjoint child rows both
-- passed the check and both committed: addStep computes order_index = max+1 in memory, so two
-- concurrent appends produced two steps sharing the same order_index; @OrderBy("orderIndex ASC")
-- then rendered them in a nondeterministic tie for different viewers, and delete/repack interleaved
-- with an add produced gaps/duplicates. V192/V199 created only plain, non-unique indexes.
--
-- The primary fix is code-side: since #1147 the steps/objectives section counters are DB-enforced
-- via an atomic conditional bump (MissionRepository.bump{Steps,Objectives}VersionIfMatches) that
-- row-locks the mission, serialising same-section writers so the max+1 computation and the reorder
-- id-set check are race-free. This migration adds the database backstop: a UNIQUE constraint on
-- (mission_id, order_index) after a de-duplicating renumber (V96 precedent).
--
-- The constraint is DEFERRABLE INITIALLY DEFERRED on purpose. A reorder (swap two ordinals) and a
-- delete-then-repack transiently place two rows on the same order_index mid-flush; Hibernate has no
-- ordering guarantee that avoids that. An immediately-checked unique index would reject the
-- intermediate state even single-threaded. Deferring the check to COMMIT lets the final 0..n-1 state
-- be validated once, while still catching a genuinely duplicate ordinal (e.g. a raced append that
-- escaped the counter guard) at the committing transaction.

-- 1) Renumber any pre-existing duplicate/gapped order_index to a contiguous 0..n-1 per mission,
--    preserving the current visual order (order_index first, then created_at, then id as a stable
--    tiebreaker). Only rows whose ordinal actually changes are written.
WITH renumbered AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY mission_id
               ORDER BY order_index, created_at NULLS LAST, id
           ) - 1 AS new_index
    FROM mission_step
)
UPDATE mission_step s
SET order_index = r.new_index
FROM renumbered r
WHERE s.id = r.id
  AND s.order_index <> r.new_index;

WITH renumbered AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY mission_id
               ORDER BY order_index, created_at NULLS LAST, id
           ) - 1 AS new_index
    FROM mission_objective
)
UPDATE mission_objective o
SET order_index = r.new_index
FROM renumbered r
WHERE o.id = r.id
  AND o.order_index <> r.new_index;

-- 2) Replace the plain V192/V199 indexes with deferrable UNIQUE constraints on the same columns.
--    The unique constraint's backing index serves the (mission_id, order_index) lookups the plain
--    index used to, so the old indexes become redundant and are dropped.
DROP INDEX IF EXISTS idx_mission_step_mission_order;
ALTER TABLE mission_step
    ADD CONSTRAINT uq_mission_step_mission_order
    UNIQUE (mission_id, order_index) DEFERRABLE INITIALLY DEFERRED;

DROP INDEX IF EXISTS idx_mission_objective_mission_order;
ALTER TABLE mission_objective
    ADD CONSTRAINT uq_mission_objective_mission_order
    UNIQUE (mission_id, order_index) DEFERRABLE INITIALLY DEFERRED;

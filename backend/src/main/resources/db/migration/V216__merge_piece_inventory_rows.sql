-- One-time backfill for the write-time stock merge (REQ-INV-026, ADR-0097; amends the append-only
-- ADR-0003 / REQ-INV-001 for PIECE materials). Folds pre-existing PIECE inventory rows that share
-- the stock identity into a single row, so the deployed dataset matches what the new create /
-- update / transfer / rebook paths produce from now on. SCU rows are deliberately left untouched:
-- they stay append-only unless a user opts a single action in.
--
-- Merge identity = user_id, material_id, location_id, quality, job_order_id, mission_id, personal,
-- owning_org_unit_id, with NULLs in the three nullable dimensions compared as equal (a fixed
-- sentinel UUID stands in for NULL in the PARTITION BY). `delivered` is NOT part of the identity;
-- the surviving row is reset to not-delivered (matching the runtime merge). The notes of the folded
-- rows are concatenated distinct, in first-seen (created_at, then id) order and whitespace-trimmed,
-- then newline-joined and truncated to the 1000-char column -- matching the runtime merge's
-- LinkedHashSet + StringNormalization.trimToNull, so a backfilled survivor carries the same note
-- text (and, past 1000 chars, the same retained lines) as a runtime-merged one. The survivor is the
-- oldest row of each group (created_at, then id).
--
-- Materialbörse safety: rows referenced by a material_exchange_offer are excluded from the merge
-- entirely (NOT EXISTS). Merging would otherwise change the offer's live-read material/amount or,
-- when a folded row is deleted, destroy the offer through the ON DELETE CASCADE FK (V210). The
-- offer's offered quantity must never change as a side effect of a merge.
--
-- Idempotent by construction: after this runs, each group holds exactly one row, so a re-run finds
-- no group with COUNT(*) > 1 and changes nothing.

WITH mergeable AS (
    SELECT ii.id,
           ii.amount,
           ii.note,
           ii.created_at,
           first_value(ii.id) OVER (
               PARTITION BY ii.user_id,
                            ii.material_id,
                            ii.location_id,
                            ii.quality,
                            COALESCE(ii.job_order_id, '00000000-0000-0000-0000-000000000000'::uuid),
                            COALESCE(ii.mission_id, '00000000-0000-0000-0000-000000000000'::uuid),
                            ii.personal,
                            COALESCE(ii.owning_org_unit_id,
                                     '00000000-0000-0000-0000-000000000000'::uuid)
               ORDER BY ii.created_at ASC, ii.id ASC
           ) AS survivor_id
    FROM inventory_item ii
    JOIN material m ON m.id = ii.material_id AND m.quantity_type = 'PIECE'
    WHERE NOT EXISTS (
        SELECT 1 FROM material_exchange_offer o WHERE o.inventory_item_id = ii.id
    )
),
-- One row per (group, distinct trimmed note), keeping the FIRST occurrence (created_at, then id).
-- regexp_replace strips leading/trailing whitespace (\s) to approximate the runtime's
-- String.strip(); stored notes are already trimmed + NFC-normalized by the global binder, so this
-- rarely changes anything.
note_first_seen AS (
    SELECT DISTINCT ON (survivor_id, regexp_replace(note, '^\s+|\s+$', '', 'g'))
           survivor_id,
           regexp_replace(note, '^\s+|\s+$', '', 'g') AS note_trimmed,
           created_at,
           id
    FROM mergeable
    WHERE note IS NOT NULL AND regexp_replace(note, '^\s+|\s+$', '', 'g') <> ''
    ORDER BY survivor_id, regexp_replace(note, '^\s+|\s+$', '', 'g'), created_at ASC, id ASC
),
merged_notes AS (
    SELECT survivor_id,
           LEFT(STRING_AGG(note_trimmed, E'\n' ORDER BY created_at ASC, id ASC), 1000) AS merged_note
    FROM note_first_seen
    GROUP BY survivor_id
),
grp AS (
    SELECT mg.survivor_id,
           SUM(mg.amount) AS total_amount,
           mn.merged_note AS merged_note
    FROM mergeable mg
    LEFT JOIN merged_notes mn ON mn.survivor_id = mg.survivor_id
    GROUP BY mg.survivor_id, mn.merged_note
    HAVING COUNT(*) > 1
),
folded AS (
    UPDATE inventory_item ii
    SET amount = ROUND(g.total_amount::numeric, 3),
        note = LEFT(g.merged_note, 1000),
        delivered = FALSE,
        updated_at = now()
    FROM grp g
    WHERE ii.id = g.survivor_id
    RETURNING ii.id
)
DELETE FROM inventory_item ii
USING mergeable mg, grp g
WHERE mg.survivor_id = g.survivor_id
  AND mg.id <> mg.survivor_id
  AND ii.id = mg.id;

-- V209 (#1109 / #1122): restore the status-covering composite index on the mission list/search
-- hot path.
--
-- History: V93 created idx_mission_squadron_internal_status(owning_squadron_id, is_internal, status)
-- as "the (owning_squadron_id, is_internal, status) covering index for the mission search hot path".
-- V103 dropped it together with the legacy owning_squadron_id column, and V99 recreated only the
-- 2-column shape idx_mission_owning_org_unit_internal(owning_org_unit_id, is_internal) on the new
-- owning_org_unit_id column -- never re-adding status. searchMissions (both overloads) and
-- findAllActiveReference filter on status, so without status in the index every list/search request
-- does a wider index scan plus a heap filter on status, and latency climbs under load.
--
-- Restore the full 3-column covering index. A composite index on
-- (owning_org_unit_id, is_internal, status) already serves every (owning_org_unit_id) and
-- (owning_org_unit_id, is_internal) prefix lookup, so the narrower V99 index becomes redundant and
-- is dropped to save its write + storage overhead.
CREATE INDEX IF NOT EXISTS idx_mission_owning_org_unit_internal_status
    ON mission (owning_org_unit_id, is_internal, status);

DROP INDEX IF EXISTS idx_mission_owning_org_unit_internal;

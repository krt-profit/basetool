DROP TABLE IF EXISTS member_evaluation;
DROP TABLE IF EXISTS rank_requirement;
DROP TABLE IF EXISTS promotion_level_content;
DROP TABLE IF EXISTS promotion_category;
DROP TABLE IF EXISTS promotion_topic;

DROP FUNCTION IF EXISTS guard_promotion_topic_owner_kind();
DROP FUNCTION IF EXISTS guard_rank_requirement_owner_kind();

ALTER TABLE org_unit DROP CONSTRAINT IF EXISTS chk_org_unit_promotion_only_squadron;
ALTER TABLE org_unit DROP COLUMN IF EXISTS is_promotion_enabled;

-- audit_event.domain and event_type map onto Java enums that no longer carry these values.
DELETE FROM audit_event WHERE domain = 'PROMOTION';

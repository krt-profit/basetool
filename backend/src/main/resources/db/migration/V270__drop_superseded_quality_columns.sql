ALTER TABLE job_order_material DROP CONSTRAINT IF EXISTS ck_job_order_material_min_quality_range;
ALTER TABLE job_order_material DROP COLUMN IF EXISTS min_quality;

ALTER TABLE job_order_item_material DROP CONSTRAINT IF EXISTS chk_job_order_item_material_quality;
ALTER TABLE job_order_item_material DROP COLUMN IF EXISTS quality_requirement;

DROP INDEX IF EXISTS uq_material_claim_bucket_org_unit;
DROP INDEX IF EXISTS idx_material_claim_bucket;
ALTER TABLE material_claim DROP COLUMN IF EXISTS quality_requirement;

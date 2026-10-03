ALTER TABLE job_order_item_material ADD COLUMN quality_tier_id UUID;

UPDATE job_order_item_material m
   SET quality_tier_id = t.id
  FROM quality_tier t
 WHERE m.quality_tier_id IS NULL
   AND t.code = m.quality_requirement;

ALTER TABLE job_order_item_material ALTER COLUMN quality_tier_id SET NOT NULL;

ALTER TABLE job_order_item_material
    ADD CONSTRAINT fk_job_order_item_material_quality_tier
        FOREIGN KEY (quality_tier_id) REFERENCES quality_tier (id);

CREATE INDEX idx_job_order_item_material_quality_tier ON job_order_item_material (quality_tier_id);

ALTER TABLE job_order_item_material DROP CONSTRAINT IF EXISTS chk_job_order_item_material_quality;

ALTER TABLE job_order_item_material ALTER COLUMN quality_requirement DROP NOT NULL;

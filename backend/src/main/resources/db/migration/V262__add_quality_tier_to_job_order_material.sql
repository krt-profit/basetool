ALTER TABLE job_order_material ADD COLUMN quality_tier_id UUID;

UPDATE job_order_material m
   SET quality_tier_id = t.id
  FROM quality_tier t
 WHERE m.quality_tier_id IS NULL
   AND t.min_quality = COALESCE(m.min_quality, 0);

ALTER TABLE job_order_material ALTER COLUMN quality_tier_id SET NOT NULL;

ALTER TABLE job_order_material
    ADD CONSTRAINT fk_job_order_material_quality_tier
        FOREIGN KEY (quality_tier_id) REFERENCES quality_tier (id);

CREATE INDEX idx_job_order_material_quality_tier ON job_order_material (quality_tier_id);

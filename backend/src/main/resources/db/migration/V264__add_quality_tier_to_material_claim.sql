ALTER TABLE material_claim ADD COLUMN quality_tier_id UUID;

UPDATE material_claim c
   SET quality_tier_id = t.id
  FROM quality_tier t
 WHERE c.quality_tier_id IS NULL
   AND t.code = c.quality_requirement;

ALTER TABLE material_claim ALTER COLUMN quality_tier_id SET NOT NULL;

ALTER TABLE material_claim
    ADD CONSTRAINT fk_material_claim_quality_tier
        FOREIGN KEY (quality_tier_id) REFERENCES quality_tier (id);

DROP INDEX IF EXISTS uq_material_claim_bucket_org_unit;

DROP INDEX IF EXISTS idx_material_claim_bucket;

CREATE UNIQUE INDEX uq_material_claim_tier_bucket_org_unit
    ON material_claim (job_order_id, material_id, quality_tier_id, claiming_org_unit_id);

CREATE INDEX idx_material_claim_tier_bucket
    ON material_claim (job_order_id, material_id, quality_tier_id);

CREATE INDEX idx_material_claim_quality_tier ON material_claim (quality_tier_id);

ALTER TABLE material_claim ALTER COLUMN quality_requirement DROP NOT NULL;

CREATE UNIQUE INDEX uq_org_unit_profit_designation
    ON org_unit (department) WHERE department = 'PROFIT';

ALTER TABLE job_order ADD COLUMN original_profit_org_unit_id UUID;
COMMENT ON COLUMN job_order.original_profit_org_unit_id IS
    'Verified Profit root at creation. NULL preserves unverifiable legacy origin.';

ALTER TABLE material_claim ADD COLUMN ended_at TIMESTAMP WITH TIME ZONE;
COMMENT ON COLUMN material_claim.ended_at IS
    'End of active promise; amount and attribution remain historical facts.';

DROP INDEX uq_material_claim_tier_bucket_org_unit;
CREATE UNIQUE INDEX uq_material_claim_active_bucket_org_unit
    ON material_claim (job_order_id, material_id, quality_tier_id, claiming_org_unit_id)
    WHERE ended_at IS NULL;

INSERT INTO notification_rule_selector (id, rule_id, kind)
SELECT gen_random_uuid(), r.id, 'EVENT_RECIPIENTS'
FROM notification_rule r
WHERE r.id = '62200000-0000-0000-0000-000000000023'
  AND NOT EXISTS (SELECT 1 FROM notification_rule_selector s
                  WHERE s.rule_id = r.id AND s.kind = 'EVENT_RECIPIENTS');

ALTER TABLE material_provision_credit ADD COLUMN quantity_amount double precision;
UPDATE material_provision_credit c SET quantity_amount = COALESCE((
    SELECT sum(f.to_quanta - f.from_quanta)::numeric / 1000
    FROM material_provision_fragment f WHERE f.credit_id = c.id), 0);
ALTER TABLE material_provision_credit ALTER COLUMN quantity_amount SET NOT NULL;
ALTER TABLE material_provision_credit ADD CONSTRAINT chk_material_provision_quantity_nonnegative CHECK (quantity_amount >= 0);
ALTER TABLE material_provision_credit ADD COLUMN supplied_quality integer;
UPDATE material_provision_credit c SET supplied_quality = q.min_quality FROM quality_tier q WHERE q.id = c.quality_tier_id;
ALTER TABLE material_provision_credit ALTER COLUMN supplied_quality SET NOT NULL;
ALTER TABLE material_provision_credit ADD CONSTRAINT chk_material_provision_supplied_quality CHECK (supplied_quality BETWEEN 0 AND 1000);

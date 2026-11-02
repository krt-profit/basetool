CREATE TABLE inventory_stock_level (
    id              UUID                     PRIMARY KEY,
    version         BIGINT                   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    org_unit_id     UUID                     NOT NULL,
    material_id     UUID                     NOT NULL,
    quality_tier_id UUID,
    minimum_amount  DOUBLE PRECISION         NOT NULL,
    target_amount   DOUBLE PRECISION         NOT NULL,
    CONSTRAINT fk_inventory_stock_level_org_unit FOREIGN KEY (org_unit_id) REFERENCES org_unit (id),
    CONSTRAINT fk_inventory_stock_level_material FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT fk_inventory_stock_level_quality_tier
        FOREIGN KEY (quality_tier_id) REFERENCES quality_tier (id),
    CONSTRAINT chk_inventory_stock_level_minimum CHECK (minimum_amount > 0),
    CONSTRAINT chk_inventory_stock_level_target CHECK (target_amount >= minimum_amount)
);

CREATE UNIQUE INDEX uq_inventory_stock_level_material
    ON inventory_stock_level (org_unit_id, material_id, quality_tier_id) NULLS NOT DISTINCT;
CREATE INDEX idx_inventory_stock_level_material ON inventory_stock_level (material_id);
CREATE INDEX idx_inventory_stock_level_quality_tier ON inventory_stock_level (quality_tier_id);

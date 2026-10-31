ALTER TABLE inventory_stock_level ALTER COLUMN material_id DROP NOT NULL;
ALTER TABLE inventory_stock_level ADD COLUMN game_item_id UUID;

ALTER TABLE inventory_stock_level
    ADD CONSTRAINT fk_inventory_stock_level_game_item
        FOREIGN KEY (game_item_id) REFERENCES game_item (id);
ALTER TABLE inventory_stock_level
    ADD CONSTRAINT chk_inventory_stock_level_subject
        CHECK ((material_id IS NOT NULL) <> (game_item_id IS NOT NULL));
ALTER TABLE inventory_stock_level
    ADD CONSTRAINT chk_inventory_stock_level_item_untiered
        CHECK (game_item_id IS NULL OR quality_tier_id IS NULL);

DROP INDEX uq_inventory_stock_level_material;
CREATE UNIQUE INDEX uq_inventory_stock_level_material
    ON inventory_stock_level (org_unit_id, material_id, quality_tier_id) NULLS NOT DISTINCT
    WHERE material_id IS NOT NULL;
CREATE UNIQUE INDEX uq_inventory_stock_level_game_item
    ON inventory_stock_level (org_unit_id, game_item_id)
    WHERE game_item_id IS NOT NULL;
CREATE INDEX idx_inventory_stock_level_game_item ON inventory_stock_level (game_item_id);

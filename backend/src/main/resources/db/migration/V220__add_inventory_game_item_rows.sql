-- V220: catalog-discriminated inventory rows (REQ-INV-029, ADR-0101).
-- The Lager gains game-item stock rows next to the existing material rows: a row now references
-- EITHER a material (with quality) OR a game item (no quality, whole units). All existing rows are
-- material rows and already satisfy both CHECK constraints, so no backfill is needed. The partial
-- index backs the item-side stack grouping/drilldown (REQ-INV-002/005 item variants) the same way
-- idx_inventory_item_stack_key backs the material side.

ALTER TABLE inventory_item ALTER COLUMN material_id DROP NOT NULL;
ALTER TABLE inventory_item ALTER COLUMN quality DROP NOT NULL;

ALTER TABLE inventory_item ADD COLUMN game_item_id uuid REFERENCES game_item (id);

ALTER TABLE inventory_item ADD CONSTRAINT chk_inventory_item_catalog_xor
  CHECK ((material_id IS NULL) <> (game_item_id IS NULL));

ALTER TABLE inventory_item ADD CONSTRAINT chk_inventory_item_quality_by_kind
  CHECK ((material_id IS NOT NULL AND quality IS NOT NULL)
      OR (game_item_id IS NOT NULL AND quality IS NULL));

CREATE INDEX idx_inventory_item_item_stack_key
  ON inventory_item (game_item_id, user_id, location_id, personal, owning_org_unit_id)
  WHERE game_item_id IS NOT NULL;

COMMENT ON INDEX idx_inventory_item_item_stack_key IS 'Partial composite index on the item stack identity (game item, user, location, personal, owning org unit) over game-item rows only (REQ-INV-029, ADR-0101). Backs the item-side group-on-read per-stack GROUP BY and the lazy per-stack entries lookup, mirroring idx_inventory_item_stack_key on the material side.';

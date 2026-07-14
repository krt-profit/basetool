-- Drop the scalar inventory-item association + delivered columns (Variante C, REQ-INV-027, ADR-0098).
-- The to-many quantity splits (inventory_item_job_order_allocation / inventory_item_mission_allocation,
-- V217) are now the single source of truth: every read (stacking, filters, fulfilment, the mapper's
-- chips + the first-allocation soak-compat fields) and every write (create, refinery deposit, book-out,
-- transfer / personal-rebook, handover, and the physical-identity stock merge that unions allocations)
-- goes through the allocation tables. V217 already backfilled each scalar assignment — including the
-- per-entry delivered flag — into an allocation, so the columns hold no data the allocations lack.
--
-- Postgres drops the column-dependent foreign keys and indexes with the columns (the job_order_id /
-- mission_id FK indexes from V92 and the composite stack-key index from V143, which listed both).
ALTER TABLE inventory_item DROP COLUMN job_order_id;
ALTER TABLE inventory_item DROP COLUMN mission_id;
ALTER TABLE inventory_item DROP COLUMN delivered;

-- Rebuild the stack-identity composite index without the dropped earmark columns: since Variante C
-- the stack key is the row's PHYSICAL identity only (material · user · location · quality · personal
-- · owning org unit), so rebuild it on the narrowed tuple to keep the group-on-read per-stack GROUP
-- BY and the lazy per-stack entries lookup index-driven (V143, ADR-0003 / REQ-INV-002).
CREATE INDEX IF NOT EXISTS idx_inventory_item_stack_key
    ON inventory_item (material_id, user_id, location_id, quality, personal, owning_org_unit_id);

COMMENT ON INDEX idx_inventory_item_stack_key IS 'Composite index on the inventory physical stack identity (material, user, location, quality, personal, owning org unit); Variante C (REQ-INV-027) dropped the job-order / mission earmarks from the stack key. Backs the group-on-read per-stack GROUP BY and the lazy per-stack entries lookup (ADR-0003 / REQ-INV-002).';

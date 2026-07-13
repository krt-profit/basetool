-- Inventory associations as to-many quantity splits (Variante C, REQ-INV-027, ADR-0098).
-- Additive step: an inventory_item may now earmark parts of its quantity to SEVERAL job orders and
-- SEVERAL missions at once, each slice carrying its own amount, with the two dimensions split
-- independently ("Modell G"). This migration creates the two allocation tables and backfills each
-- existing single scalar assignment as ONE allocation carrying the full entry amount (R6). The scalar
-- inventory_item.job_order_id / mission_id columns stay in place here and are dropped once all reads
-- and writes go through the allocations (V217).

CREATE TABLE inventory_item_job_order_allocation (
    id                UUID PRIMARY KEY,
    -- The split entry; cascade-delete so an entry's allocations vanish with it.
    inventory_item_id UUID NOT NULL REFERENCES inventory_item(id) ON DELETE CASCADE,
    -- The earmarked job order; cascade-delete replaces the former unlinkJobOrder null-out — the entry
    -- survives as (partially) unassigned stock when the order is deleted.
    job_order_id      UUID NOT NULL REFERENCES job_order(id) ON DELETE CASCADE,
    amount            DOUBLE PRECISION NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    -- At most one slice per (entry, order): adding the same order twice edits the existing slice.
    CONSTRAINT uq_inv_job_order_alloc UNIQUE (inventory_item_id, job_order_id)
);

CREATE INDEX idx_inv_job_order_alloc_order ON inventory_item_job_order_allocation (job_order_id);
CREATE INDEX idx_inv_job_order_alloc_item ON inventory_item_job_order_allocation (inventory_item_id);

CREATE TABLE inventory_item_mission_allocation (
    id                UUID PRIMARY KEY,
    inventory_item_id UUID NOT NULL REFERENCES inventory_item(id) ON DELETE CASCADE,
    -- Cascade-delete replaces the former unlinkMissions null-out.
    mission_id        UUID NOT NULL REFERENCES mission(id) ON DELETE CASCADE,
    amount            DOUBLE PRECISION NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_inv_mission_alloc UNIQUE (inventory_item_id, mission_id)
);

CREATE INDEX idx_inv_mission_alloc_mission ON inventory_item_mission_allocation (mission_id);
CREATE INDEX idx_inv_mission_alloc_item ON inventory_item_mission_allocation (inventory_item_id);

-- Backfill: each existing scalar assignment becomes one allocation carrying the full entry amount (R6).
INSERT INTO inventory_item_job_order_allocation
    (id, inventory_item_id, job_order_id, amount, version, created_at, updated_at)
SELECT gen_random_uuid(), i.id, i.job_order_id, COALESCE(i.amount, 0.0), 0, now(), now()
FROM inventory_item i
WHERE i.job_order_id IS NOT NULL;

INSERT INTO inventory_item_mission_allocation
    (id, inventory_item_id, mission_id, amount, version, created_at, updated_at)
SELECT gen_random_uuid(), i.id, i.mission_id, COALESCE(i.amount, 0.0), 0, now(), now()
FROM inventory_item i
WHERE i.mission_id IS NOT NULL;

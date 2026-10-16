ALTER TABLE exchange_change ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE exchange_change
    ADD COLUMN org_unit_id UUID REFERENCES org_unit (id) ON DELETE CASCADE;

ALTER TABLE exchange_change DROP CONSTRAINT ck_exchange_change_resource;

ALTER TABLE exchange_change
    ADD CONSTRAINT ck_exchange_change_resource
        CHECK (resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK'));

ALTER TABLE exchange_change
    ADD CONSTRAINT ck_exchange_change_keyed_by
        CHECK ((resource = 'ORG_STOCK' AND org_unit_id IS NOT NULL AND user_id IS NULL)
            OR (resource <> 'ORG_STOCK' AND user_id IS NOT NULL AND org_unit_id IS NULL));

CREATE INDEX idx_exchange_change_org_unit
    ON exchange_change (org_unit_id, tx, seq)
    WHERE org_unit_id IS NOT NULL;

COMMENT ON COLUMN exchange_change.org_unit_id IS
    'The unit whose pool changed, for the org-stock feed keyed by unit instead of by member; set exactly when resource is ORG_STOCK (REQ-XCH-042, ADR-0257).';

CREATE OR REPLACE FUNCTION exchange_record_org_change(p_unit UUID, p_key VARCHAR)
RETURNS VOID AS $$
DECLARE
    v_source  TEXT := COALESCE(NULLIF(current_setting('basetool.change_source', true), ''), 'system');
    v_channel TEXT := split_part(v_source, '|', 1);
BEGIN
    IF p_unit IS NULL OR p_key IS NULL
       OR NOT EXISTS (SELECT 1 FROM org_unit WHERE id = p_unit) THEN
        RETURN;
    END IF;
    IF v_channel NOT IN ('web', 'app', 'client', 'system') THEN
        v_channel := 'system';
    END IF;
    INSERT INTO exchange_change
        (org_unit_id, resource, entity_key, source_channel, source_client, source_key)
    VALUES (p_unit, 'ORG_STOCK', p_key, v_channel,
            CASE WHEN v_channel = 'client'
                 THEN NULLIF(left(split_part(v_source, '|', 2), 64), '') END,
            CASE WHEN v_channel = 'client'
                 THEN NULLIF(left(split_part(v_source, '|', 3), 64), '') END);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_org_stock_lot_key(
    p_unit UUID, p_material UUID, p_item UUID, p_location UUID, p_quality INTEGER,
    p_stolen BOOLEAN)
RETURNS VARCHAR AS $$
    SELECT 'u:' || p_unit || '|'
           || exchange_stock_lot_key(p_material, p_item, p_location, p_quality, p_stolen);
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION exchange_record_org_lot_of_item(p_item UUID)
RETURNS VOID AS $$
BEGIN
    PERFORM exchange_record_org_change(
                i.owning_org_unit_id,
                exchange_org_stock_lot_key(i.owning_org_unit_id, i.material_id, i.game_item_id,
                                           i.location_id, i.quality, i.stolen))
       FROM inventory_item i
      WHERE i.id = p_item AND NOT i.personal AND i.owning_org_unit_id IS NOT NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_inventory_item_changed()
RETURNS TRIGGER AS $$
DECLARE
    v_moved BOOLEAN := TG_OP = 'UPDATE'
        AND (NEW.user_id IS DISTINCT FROM OLD.user_id
             OR NEW.material_id IS DISTINCT FROM OLD.material_id
             OR NEW.game_item_id IS DISTINCT FROM OLD.game_item_id
             OR NEW.location_id IS DISTINCT FROM OLD.location_id
             OR NEW.quality IS DISTINCT FROM OLD.quality
             OR NEW.stolen IS DISTINCT FROM OLD.stolen);
    v_pool_moved BOOLEAN := TG_OP = 'UPDATE'
        AND (NEW.personal IS DISTINCT FROM OLD.personal
             OR NEW.owning_org_unit_id IS DISTINCT FROM OLD.owning_org_unit_id
             OR NEW.material_id IS DISTINCT FROM OLD.material_id
             OR NEW.game_item_id IS DISTINCT FROM OLD.game_item_id
             OR NEW.location_id IS DISTINCT FROM OLD.location_id
             OR NEW.quality IS DISTINCT FROM OLD.quality
             OR NEW.stolen IS DISTINCT FROM OLD.stolen);
BEGIN
    IF TG_OP = 'DELETE'
       OR (TG_OP = 'UPDATE' AND (v_moved OR NEW.amount IS DISTINCT FROM OLD.amount)) THEN
        PERFORM exchange_record_change(OLD.user_id, 'STOCK',
            exchange_stock_lot_key(OLD.material_id, OLD.game_item_id, OLD.location_id,
                                   OLD.quality, OLD.stolen));
    END IF;
    IF TG_OP = 'INSERT' OR v_moved THEN
        PERFORM exchange_record_change(NEW.user_id, 'STOCK',
            exchange_stock_lot_key(NEW.material_id, NEW.game_item_id, NEW.location_id,
                                   NEW.quality, NEW.stolen));
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE')
       AND NOT OLD.personal AND OLD.owning_org_unit_id IS NOT NULL
       AND (TG_OP = 'DELETE'
            OR (TG_OP = 'UPDATE'
                AND (v_pool_moved
                     OR NEW.amount IS DISTINCT FROM OLD.amount
                     OR NEW.user_id IS DISTINCT FROM OLD.user_id
                     OR NEW.note IS DISTINCT FROM OLD.note))) THEN
        PERFORM exchange_record_org_change(OLD.owning_org_unit_id,
            exchange_org_stock_lot_key(OLD.owning_org_unit_id, OLD.material_id, OLD.game_item_id,
                                       OLD.location_id, OLD.quality, OLD.stolen));
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE')
       AND NOT NEW.personal AND NEW.owning_org_unit_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR v_pool_moved) THEN
        PERFORM exchange_record_org_change(NEW.owning_org_unit_id,
            exchange_org_stock_lot_key(NEW.owning_org_unit_id, NEW.material_id, NEW.game_item_id,
                                       NEW.location_id, NEW.quality, NEW.stolen));
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_inventory_allocation_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM exchange_record_org_lot_of_item(OLD.inventory_item_id);
    END IF;
    IF TG_OP = 'INSERT'
       OR (TG_OP = 'UPDATE' AND NEW.inventory_item_id IS DISTINCT FROM OLD.inventory_item_id) THEN
        PERFORM exchange_record_org_lot_of_item(NEW.inventory_item_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_inventory_item_job_order_allocation_exchange_change
AFTER INSERT OR UPDATE OR DELETE ON inventory_item_job_order_allocation
FOR EACH ROW EXECUTE FUNCTION exchange_inventory_allocation_changed();

CREATE TRIGGER trg_inventory_item_mission_allocation_exchange_change
AFTER INSERT OR UPDATE OR DELETE ON inventory_item_mission_allocation
FOR EACH ROW EXECUTE FUNCTION exchange_inventory_allocation_changed();

CREATE OR REPLACE FUNCTION exchange_record_org_pool(p_unit UUID)
RETURNS VOID AS $$
BEGIN
    PERFORM exchange_record_org_change(p_unit, lots.lot_key)
       FROM (SELECT DISTINCT
                    exchange_org_stock_lot_key(i.owning_org_unit_id, i.material_id,
                                               i.game_item_id, i.location_id, i.quality,
                                               i.stolen) AS lot_key
               FROM inventory_item i
              WHERE i.owning_org_unit_id = p_unit AND NOT i.personal) lots;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_org_unit_membership_joined()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.org_unit_id IS DISTINCT FROM OLD.org_unit_id THEN
        PERFORM exchange_record_org_pool(NEW.org_unit_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_unit_membership_exchange_change
AFTER INSERT OR UPDATE OF org_unit_id ON org_unit_membership
FOR EACH ROW EXECUTE FUNCTION exchange_org_unit_membership_joined();

CREATE OR REPLACE FUNCTION exchange_org_unit_changed()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM exchange_record_change(s.owner_id, 'SHIP', s.id::text)
       FROM ship s
      WHERE s.owning_org_unit_id = NEW.id;
    IF NEW.name IS DISTINCT FROM OLD.name THEN
        PERFORM exchange_record_org_pool(NEW.id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TRIGGER trg_org_unit_exchange_change ON org_unit IS
    'Re-announces every ship of a unit in the exchange ship feed when the unit''s name or shorthand changes, and every lot of its pool in the org-stock feed when its name changes, so a client never keeps a stale unit (REQ-XCH-017, REQ-XCH-042, ADR-0261).';

COMMENT ON TRIGGER trg_org_unit_membership_exchange_change ON org_unit_membership IS
    'Announces every lot of a unit''s pool in the org-stock feed when a member joins it or a membership moves to it, so a member whose cursor predates the join receives the pool (REQ-XCH-042).';

INSERT INTO exchange_change (org_unit_id, resource, entity_key, source_channel)
SELECT lots.unit_id, 'ORG_STOCK', lots.lot_key, 'system'
FROM (SELECT DISTINCT i.owning_org_unit_id AS unit_id,
                      exchange_org_stock_lot_key(i.owning_org_unit_id, i.material_id,
                                                 i.game_item_id, i.location_id, i.quality,
                                                 i.stolen) AS lot_key
      FROM inventory_item i
      JOIN org_unit u ON u.id = i.owning_org_unit_id
      WHERE NOT i.personal) lots
ORDER BY lots.unit_id, lots.lot_key;

ALTER TABLE exchange_change
    ADD COLUMN org_entry_id UUID,
    ADD CONSTRAINT ck_exchange_change_org_entry
        CHECK (org_entry_id IS NULL OR resource = 'ORG_STOCK');

COMMENT ON COLUMN exchange_change.org_entry_id IS
    'The shared Lager row whose own write, or the write of its earmarks, made this org-stock entry; NULL for a lot-level re-announce. Only the row id, purged with the feed (REQ-PATCH-057, REQ-XCH-042).';

CREATE INDEX idx_exchange_change_org_entry
    ON exchange_change (org_unit_id, org_entry_id, tx, seq)
    WHERE org_entry_id IS NOT NULL;

DROP FUNCTION exchange_record_org_change(UUID, VARCHAR);

CREATE FUNCTION exchange_record_org_change(p_unit UUID, p_key VARCHAR, p_entry UUID DEFAULT NULL)
RETURNS VOID AS $$
BEGIN
    IF p_unit IS NULL OR p_key IS NULL
       OR NOT EXISTS (SELECT 1 FROM org_unit WHERE id = p_unit) THEN
        RETURN;
    END IF;
    INSERT INTO exchange_change
        (org_unit_id, resource, entity_key, source_channel, source_client, source_key,
         source_patch_change, org_entry_id)
    SELECT p_unit, 'ORG_STOCK', p_key, src.channel, src.client, src.installation_key,
           src.patch_change, p_entry
      FROM exchange_change_source() src;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_record_org_change(UUID, VARCHAR, UUID) IS
    'Sequences a change of one org-stock lot in its unit''s feed with the writing transaction''s source; p_entry names the shared row whose write it is, and a lot-level re-announce leaves it NULL (REQ-XCH-042, REQ-PATCH-057).';

CREATE OR REPLACE FUNCTION exchange_record_org_lot_of_item(p_item UUID)
RETURNS VOID AS $$
BEGIN
    PERFORM exchange_record_org_change(
                i.owning_org_unit_id,
                exchange_org_stock_lot_key(i.owning_org_unit_id, i.material_id, i.game_item_id,
                                           i.location_id, i.quality, i.stolen),
                i.id)
       FROM inventory_item i
      WHERE i.id = p_item AND NOT i.personal AND i.owning_org_unit_id IS NOT NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_record_org_lot_of_item(UUID) IS
    'Sequences the write of a shared row''s earmarks under the row''s lot and names the row (REQ-XCH-043, REQ-PATCH-057).';

CREATE OR REPLACE FUNCTION exchange_mission_renamed()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.name IS DISTINCT FROM OLD.name THEN
        PERFORM exchange_record_org_change(
                    i.owning_org_unit_id,
                    exchange_org_stock_lot_key(i.owning_org_unit_id, i.material_id,
                                               i.game_item_id, i.location_id, i.quality,
                                               i.stolen))
           FROM inventory_item_mission_allocation a
           JOIN inventory_item i ON i.id = a.inventory_item_id
          WHERE a.mission_id = NEW.id AND NOT i.personal AND i.owning_org_unit_id IS NOT NULL;
    END IF;
    RETURN NULL;
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
    v_split BOOLEAN := TG_OP = 'UPDATE'
        AND (NEW.personal IS DISTINCT FROM OLD.personal
             OR (NEW.owning_org_unit_id IS DISTINCT FROM OLD.owning_org_unit_id
                 AND NOT (OLD.personal AND NEW.personal)));
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
       OR (TG_OP = 'UPDATE'
           AND (v_moved OR v_split OR NEW.amount IS DISTINCT FROM OLD.amount)) THEN
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
                                       OLD.location_id, OLD.quality, OLD.stolen),
            OLD.id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE')
       AND NOT NEW.personal AND NEW.owning_org_unit_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR v_pool_moved) THEN
        PERFORM exchange_record_org_change(NEW.owning_org_unit_id,
            exchange_org_stock_lot_key(NEW.owning_org_unit_id, NEW.material_id, NEW.game_item_id,
                                       NEW.location_id, NEW.quality, NEW.stolen),
            NEW.id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_inventory_item_changed() IS
    'Sequences a Lager row''s change in its holder''s stock feed and, for a shared row of a unit, in that unit''s org-stock feed under the row''s id; a rebooking between personal and shared or between pools changes the lot''s split and so reaches the stock feed under the unchanged lot key (REQ-XCH-016, REQ-XCH-042, REQ-PATCH-057).';

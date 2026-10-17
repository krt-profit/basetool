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

COMMENT ON FUNCTION exchange_inventory_item_changed() IS
    'Sequences a Lager row''s change in its holder''s stock feed and, for a shared row of a unit, in that unit''s org-stock feed; a rebooking between personal and shared or between pools changes the lot''s split and so reaches the stock feed under the unchanged lot key (REQ-XCH-016, REQ-XCH-042).';

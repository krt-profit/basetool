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
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

INSERT INTO exchange_change (user_id, resource, entity_key, source_channel)
SELECT lots.user_id, 'STOCK', lots.lot_key, 'system'
FROM (SELECT DISTINCT i.user_id,
                      exchange_stock_lot_key(i.material_id, i.game_item_id, i.location_id,
                                             i.quality, i.stolen) AS lot_key
      FROM inventory_item i
      JOIN app_user u ON u.id = i.user_id
      WHERE NOT i.personal) lots
ORDER BY lots.user_id, lots.lot_key;

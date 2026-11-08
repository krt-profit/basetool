ALTER TABLE exchange_client_capability DROP CONSTRAINT ck_exchange_client_capability;

ALTER TABLE exchange_client_capability ADD CONSTRAINT ck_exchange_client_capability CHECK (capability IN (
    'exchange.connect',
    'exchange.blueprints.read',
    'exchange.blueprints.write',
    'exchange.stock.read',
    'exchange.stock.write',
    'exchange.hangar.read',
    'exchange.hangar.write',
    'exchange.demand.read',
    'exchange.demand.details',
    'exchange.orgunits.read',
    'exchange.orgstock.read',
    'exchange.orgstock.details',
    'exchange.orgstock.write',
    'exchange.refinery.read',
    'exchange.refinery.write',
    'exchange.refinery.store',
    'exchange.drafts.blueprints',
    'exchange.drafts.refinery'));

ALTER TABLE exchange_change DROP CONSTRAINT ck_exchange_change_resource;

ALTER TABLE exchange_change
    ADD CONSTRAINT ck_exchange_change_resource
        CHECK (resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK', 'REFINERY_ORDER'));

CREATE OR REPLACE FUNCTION exchange_record_refinery_order(p_user UUID, p_order UUID)
RETURNS VOID AS $$
BEGIN
    IF p_user IS NULL OR p_order IS NULL
       OR EXISTS (SELECT 1
                    FROM exchange_change rc
                   WHERE rc.user_id = p_user
                     AND rc.resource = 'REFINERY_ORDER'
                     AND rc.entity_key = p_order::text
                     AND rc.tx = (pg_current_xact_id()::text)::bigint) THEN
        RETURN;
    END IF;
    PERFORM exchange_record_change(p_user, 'REFINERY_ORDER', p_order::text);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_record_refinery_order(UUID, UUID) IS
    'Announces a refinery order in its owner''s feed, at most once per writing transaction, so an order saved with its goods is one change (REQ-XCH-051, ADR-0224).';

CREATE OR REPLACE FUNCTION exchange_refinery_order_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM exchange_record_refinery_order(OLD.owner_id, OLD.id);
    END IF;
    IF TG_OP = 'INSERT'
       OR (TG_OP = 'UPDATE' AND NEW.owner_id IS DISTINCT FROM OLD.owner_id) THEN
        PERFORM exchange_record_refinery_order(NEW.owner_id, NEW.id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refinery_order_exchange_change
AFTER INSERT OR UPDATE OR DELETE ON refinery_order
FOR EACH ROW EXECUTE FUNCTION exchange_refinery_order_changed();

COMMENT ON TRIGGER trg_refinery_order_exchange_change ON refinery_order IS
    'Announces a refinery order to its owner on every write; a move to another owner announces it to both, which leaves the old owner a tombstone (REQ-XCH-051).';

-- A good whose order is already gone (its cascade on a hard delete) finds no owner and records
-- nothing; the order's own row announces the removal.
CREATE OR REPLACE FUNCTION exchange_refinery_good_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM exchange_record_refinery_order(ro.owner_id, ro.id)
           FROM refinery_order ro
          WHERE ro.id = OLD.refinery_order_id;
    END IF;
    IF TG_OP = 'INSERT'
       OR (TG_OP = 'UPDATE' AND NEW.refinery_order_id IS DISTINCT FROM OLD.refinery_order_id) THEN
        PERFORM exchange_record_refinery_order(ro.owner_id, ro.id)
           FROM refinery_order ro
          WHERE ro.id = NEW.refinery_order_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refinery_good_exchange_change
AFTER INSERT OR UPDATE OR DELETE ON refinery_good
FOR EACH ROW EXECUTE FUNCTION exchange_refinery_good_changed();

COMMENT ON TRIGGER trg_refinery_good_exchange_change ON refinery_good IS
    'Announces the order of a written good, and both orders when a good moves between them (REQ-XCH-051).';

CREATE OR REPLACE FUNCTION exchange_record_location_entries(p_locations UUID[])
RETURNS VOID AS $$
DECLARE
    v_tx BIGINT := (pg_current_xact_id()::text)::bigint;
BEGIN
    IF cardinality(p_locations) = 0 THEN
        RETURN;
    END IF;
    INSERT INTO exchange_change
        (user_id, resource, entity_key, source_channel, source_client, source_key)
    SELECT held.user_id, held.resource, held.entity_key,
           src.channel, src.client, src.installation_key
      FROM (SELECT es.owner_id AS user_id, 'SHIP' AS resource, es.id::text AS entity_key
              FROM ship es
             WHERE es.location_id = ANY (p_locations)
            UNION
            SELECT ei.user_id, 'STOCK',
                   exchange_stock_lot_key(ei.material_id, ei.game_item_id, ei.location_id,
                                          ei.quality, ei.stolen)
              FROM inventory_item ei
             WHERE ei.location_id = ANY (p_locations)
            UNION
            SELECT er.owner_id, 'REFINERY_ORDER', er.id::text
              FROM refinery_order er
             WHERE er.location_id = ANY (p_locations)) held
      JOIN app_user eu ON eu.id = held.user_id
     CROSS JOIN exchange_change_source() src
     WHERE NOT EXISTS (SELECT 1
                         FROM exchange_change ec
                        WHERE ec.user_id = held.user_id
                          AND ec.resource = held.resource
                          AND ec.entity_key = held.entity_key
                          AND ec.tx = v_tx)
     ORDER BY held.user_id, held.resource, held.entity_key;
    INSERT INTO exchange_change
        (org_unit_id, resource, entity_key, source_channel, source_client, source_key)
    SELECT pooled.unit_id, 'ORG_STOCK', pooled.entity_key,
           src.channel, src.client, src.installation_key
      FROM (SELECT DISTINCT oi.owning_org_unit_id AS unit_id,
                   exchange_org_stock_lot_key(oi.owning_org_unit_id, oi.material_id,
                                              oi.game_item_id, oi.location_id, oi.quality,
                                              oi.stolen) AS entity_key
              FROM inventory_item oi
             WHERE oi.location_id = ANY (p_locations)
               AND NOT oi.personal AND oi.owning_org_unit_id IS NOT NULL) pooled
      JOIN org_unit eo ON eo.id = pooled.unit_id
     CROSS JOIN exchange_change_source() src
     WHERE NOT EXISTS (SELECT 1
                         FROM exchange_change oc
                        WHERE oc.org_unit_id = pooled.unit_id
                          AND oc.tx = v_tx
                          AND oc.resource = 'ORG_STOCK'
                          AND oc.entity_key = pooled.entity_key)
     ORDER BY pooled.unit_id, pooled.entity_key;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_record_location_entries(UUID[]) IS
    'Re-announces every ship, stock lot, refinery order and org-stock lot at the given places, at most once per entry and writing transaction (REQ-XCH-018, REQ-XCH-051).';

ALTER TABLE exchange_change DROP CONSTRAINT ck_exchange_change_channel;

ALTER TABLE exchange_change
    ADD COLUMN source_patch_change UUID,
    ADD CONSTRAINT ck_exchange_change_channel
        CHECK (source_channel IN ('web', 'app', 'client', 'system', 'patch')),
    ADD CONSTRAINT ck_exchange_change_patch_source
        CHECK ((source_channel = 'patch') = (source_patch_change IS NOT NULL));

COMMENT ON COLUMN exchange_change.source_patch_change IS
    'The patch change whose trigger made the change, set exactly for the patch channel (REQ-PATCH-007).';

DROP FUNCTION exchange_change_source();

-- A patch source reads 'patch|<patch change id>'; a malformed id falls back to the system channel.
CREATE FUNCTION exchange_change_source(
    OUT channel VARCHAR, OUT client VARCHAR, OUT installation_key VARCHAR, OUT patch_change UUID)
AS $$
DECLARE
    v_source TEXT := COALESCE(NULLIF(current_setting('basetool.change_source', true), ''), 'system');
    v_patch  TEXT;
BEGIN
    channel := split_part(v_source, '|', 1);
    IF channel NOT IN ('web', 'app', 'client', 'system', 'patch') THEN
        channel := 'system';
    END IF;
    IF channel = 'client' THEN
        client := NULLIF(left(split_part(v_source, '|', 2), 64), '');
        installation_key := NULLIF(left(split_part(v_source, '|', 3), 64), '');
    END IF;
    IF channel = 'patch' THEN
        v_patch := split_part(v_source, '|', 2);
        IF v_patch ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
            patch_change := v_patch::uuid;
        ELSE
            channel := 'system';
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION exchange_change_source() IS
    'The channel, client, installation key and patch change of the writing transaction, read from basetool.change_source; an unknown channel is system, only a client write names a client and key, and only a patch write names its patch change (REQ-XCH-013, REQ-PATCH-007, ADR-0224).';

CREATE OR REPLACE FUNCTION exchange_record_change(p_user UUID, p_resource VARCHAR, p_key VARCHAR)
RETURNS VOID AS $$
BEGIN
    IF p_user IS NULL OR p_key IS NULL
       OR NOT EXISTS (SELECT 1 FROM app_user WHERE id = p_user) THEN
        RETURN;
    END IF;
    INSERT INTO exchange_change
        (user_id, resource, entity_key, source_channel, source_client, source_key,
         source_patch_change)
    SELECT p_user, p_resource, p_key, src.channel, src.client, src.installation_key,
           src.patch_change
      FROM exchange_change_source() src;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_record_org_change(p_unit UUID, p_key VARCHAR)
RETURNS VOID AS $$
BEGIN
    IF p_unit IS NULL OR p_key IS NULL
       OR NOT EXISTS (SELECT 1 FROM org_unit WHERE id = p_unit) THEN
        RETURN;
    END IF;
    INSERT INTO exchange_change
        (org_unit_id, resource, entity_key, source_channel, source_client, source_key,
         source_patch_change)
    SELECT p_unit, 'ORG_STOCK', p_key, src.channel, src.client, src.installation_key,
           src.patch_change
      FROM exchange_change_source() src;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_record_location_entries(p_locations UUID[])
RETURNS VOID AS $$
DECLARE
    v_tx BIGINT := (pg_current_xact_id()::text)::bigint;
BEGIN
    IF cardinality(p_locations) = 0 THEN
        RETURN;
    END IF;
    INSERT INTO exchange_change
        (user_id, resource, entity_key, source_channel, source_client, source_key,
         source_patch_change)
    SELECT held.user_id, held.resource, held.entity_key,
           src.channel, src.client, src.installation_key, src.patch_change
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
        (org_unit_id, resource, entity_key, source_channel, source_client, source_key,
         source_patch_change)
    SELECT pooled.unit_id, 'ORG_STOCK', pooled.entity_key,
           src.channel, src.client, src.installation_key, src.patch_change
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

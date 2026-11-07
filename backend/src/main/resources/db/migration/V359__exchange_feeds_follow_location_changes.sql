CREATE OR REPLACE FUNCTION exchange_change_source(
    OUT channel VARCHAR, OUT client VARCHAR, OUT installation_key VARCHAR)
AS $$
DECLARE
    v_source TEXT := COALESCE(NULLIF(current_setting('basetool.change_source', true), ''), 'system');
BEGIN
    channel := split_part(v_source, '|', 1);
    IF channel NOT IN ('web', 'app', 'client', 'system') THEN
        channel := 'system';
    END IF;
    IF channel = 'client' THEN
        client := NULLIF(left(split_part(v_source, '|', 2), 64), '');
        installation_key := NULLIF(left(split_part(v_source, '|', 3), 64), '');
    END IF;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION exchange_change_source() IS
    'The channel, client and installation key of the writing transaction, read from basetool.change_source; an unknown channel is system, and only a client write names a client and key (REQ-XCH-013, ADR-0224).';

CREATE OR REPLACE FUNCTION exchange_record_change(p_user UUID, p_resource VARCHAR, p_key VARCHAR)
RETURNS VOID AS $$
BEGIN
    IF p_user IS NULL OR p_key IS NULL
       OR NOT EXISTS (SELECT 1 FROM app_user WHERE id = p_user) THEN
        RETURN;
    END IF;
    INSERT INTO exchange_change
        (user_id, resource, entity_key, source_channel, source_client, source_key)
    SELECT p_user, p_resource, p_key, src.channel, src.client, src.installation_key
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
        (org_unit_id, resource, entity_key, source_channel, source_client, source_key)
    SELECT p_unit, 'ORG_STOCK', p_key, src.channel, src.client, src.installation_key
      FROM exchange_change_source() src;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_starmap_guid(
    p_uuid UUID, p_deleted_at TIMESTAMP WITH TIME ZONE, p_hidden_at TIMESTAMP WITH TIME ZONE)
RETURNS UUID AS $$
    SELECT CASE WHEN p_deleted_at IS NULL AND p_hidden_at IS NULL THEN p_uuid END;
$$ LANGUAGE sql IMMUTABLE;

COMMENT ON FUNCTION exchange_starmap_guid(UUID, TIMESTAMP WITH TIME ZONE, TIMESTAMP WITH TIME ZONE) IS
    'The GUID a starmap object gives a place in the exchange: none once the sweep stamped it or an admin hid it (REQ-P4K-037, REQ-XCH-018).';

CREATE OR REPLACE FUNCTION exchange_starmap_object_guid(p_object UUID)
RETURNS UUID AS $$
    SELECT exchange_starmap_guid(so.p4k_uuid, so.p4k_deleted_at, so.p4k_hidden_at)
      FROM starmap_object so
     WHERE so.id = p_object;
$$ LANGUAGE sql STABLE;

-- Mirrors ExchangeLocationDto: a city link wins over a space-station link, an id below 1 is no
-- link, and the GUID is the place's own object's, else its city's, else its space station's.
CREATE OR REPLACE FUNCTION exchange_location_ref(
    p_name TEXT, p_uex_city INTEGER, p_uex_station INTEGER,
    p_object_guid UUID, p_city_guid UUID, p_station_guid UUID)
RETURNS JSONB AS $$
    SELECT jsonb_build_object(
        'name', p_name,
        'uex', CASE WHEN p_uex_city > 0
                    THEN jsonb_build_object('kind', 'CITY', 'id', p_uex_city)
                    WHEN p_uex_station > 0
                    THEN jsonb_build_object('kind', 'SPACE_STATION', 'id', p_uex_station) END,
        'guid', COALESCE(p_object_guid, p_city_guid, p_station_guid));
$$ LANGUAGE sql IMMUTABLE;

COMMENT ON FUNCTION exchange_location_ref(TEXT, INTEGER, INTEGER, UUID, UUID, UUID) IS
    'The location reference {name, uex, guid} the stock, org-stock and ship feeds serialise, built from its parts (REQ-XCH-018).';

CREATE OR REPLACE FUNCTION exchange_location_ref_of(
    p_name TEXT, p_city UUID, p_station UUID, p_object UUID)
RETURNS JSONB AS $$
    SELECT exchange_location_ref(p_name, rc.id_city, rs.id_space_station,
                                 exchange_starmap_object_guid(p_object),
                                 exchange_starmap_object_guid(rc.starmap_object_id),
                                 exchange_starmap_object_guid(rs.starmap_object_id))
      FROM (SELECT 1) one
      LEFT JOIN city rc ON rc.id = p_city
      LEFT JOIN space_station rs ON rs.id = p_station;
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION exchange_location_ref_of(TEXT, UUID, UUID, UUID) IS
    'The location reference a place with these columns serialises to, read against the current city, space station and starmap rows (REQ-XCH-018).';

-- One entry per ship, stock lot and org-stock lot at the places, each skipped when the writing
-- transaction already announced it, so a transaction that changes many places announces an entry once.
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
             WHERE ei.location_id = ANY (p_locations)) held
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
    'Re-announces every ship, stock lot and org-stock lot at the given places, at most once per entry and writing transaction (REQ-XCH-018).';

CREATE OR REPLACE FUNCTION exchange_location_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF exchange_location_ref_of(OLD.name, OLD.city_id, OLD.space_station_id, OLD.starmap_object_id)
       IS DISTINCT FROM
       exchange_location_ref_of(NEW.name, NEW.city_id, NEW.space_station_id, NEW.starmap_object_id)
    THEN
        PERFORM exchange_record_location_entries(ARRAY[NEW.id]);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_location_exchange_change
AFTER UPDATE OF name, city_id, space_station_id, starmap_object_id ON location
FOR EACH ROW
WHEN (NEW.name IS DISTINCT FROM OLD.name
      OR NEW.city_id IS DISTINCT FROM OLD.city_id
      OR NEW.space_station_id IS DISTINCT FROM OLD.space_station_id
      OR NEW.starmap_object_id IS DISTINCT FROM OLD.starmap_object_id)
EXECUTE FUNCTION exchange_location_changed();

COMMENT ON TRIGGER trg_location_exchange_change ON location IS
    'Re-announces the ships, stock lots and org-stock lots at a place when its name, UEX link or starmap link changes what its location reference serialises to (REQ-XCH-018).';

CREATE OR REPLACE FUNCTION exchange_city_changed()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM exchange_record_location_entries(ARRAY(
        SELECT cl.id
          FROM location cl
          LEFT JOIN space_station cs ON cs.id = cl.space_station_id
         WHERE cl.city_id = NEW.id
           AND exchange_location_ref(cl.name, OLD.id_city, cs.id_space_station,
                                     exchange_starmap_object_guid(cl.starmap_object_id),
                                     exchange_starmap_object_guid(OLD.starmap_object_id),
                                     exchange_starmap_object_guid(cs.starmap_object_id))
               IS DISTINCT FROM
               exchange_location_ref_of(cl.name, cl.city_id, cl.space_station_id,
                                        cl.starmap_object_id)));
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_city_exchange_change
AFTER UPDATE OF id_city, starmap_object_id ON city
FOR EACH ROW
WHEN (NEW.id_city IS DISTINCT FROM OLD.id_city
      OR NEW.starmap_object_id IS DISTINCT FROM OLD.starmap_object_id)
EXECUTE FUNCTION exchange_city_changed();

COMMENT ON TRIGGER trg_city_exchange_change ON city IS
    'Re-announces the entries at the places on a city when its UEX id or starmap link changes what their location reference serialises to (REQ-XCH-018).';

CREATE OR REPLACE FUNCTION exchange_space_station_changed()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM exchange_record_location_entries(ARRAY(
        SELECT sl.id
          FROM location sl
          LEFT JOIN city sc ON sc.id = sl.city_id
         WHERE sl.space_station_id = NEW.id
           AND exchange_location_ref(sl.name, sc.id_city, OLD.id_space_station,
                                     exchange_starmap_object_guid(sl.starmap_object_id),
                                     exchange_starmap_object_guid(sc.starmap_object_id),
                                     exchange_starmap_object_guid(OLD.starmap_object_id))
               IS DISTINCT FROM
               exchange_location_ref_of(sl.name, sl.city_id, sl.space_station_id,
                                        sl.starmap_object_id)));
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_space_station_exchange_change
AFTER UPDATE OF id_space_station, starmap_object_id ON space_station
FOR EACH ROW
WHEN (NEW.id_space_station IS DISTINCT FROM OLD.id_space_station
      OR NEW.starmap_object_id IS DISTINCT FROM OLD.starmap_object_id)
EXECUTE FUNCTION exchange_space_station_changed();

COMMENT ON TRIGGER trg_space_station_exchange_change ON space_station IS
    'Re-announces the entries at the places on a space station when its UEX id or starmap link changes what their location reference serialises to (REQ-XCH-018).';

-- The OLD GUID stands in for this object in each slot it fills; the other slots read their
-- current object, so the comparison isolates this row's change.
CREATE OR REPLACE FUNCTION exchange_starmap_object_changed()
RETURNS TRIGGER AS $$
DECLARE
    v_old_guid UUID := exchange_starmap_guid(OLD.p4k_uuid, OLD.p4k_deleted_at, OLD.p4k_hidden_at);
BEGIN
    PERFORM exchange_record_location_entries(ARRAY(
        SELECT ml.id
          FROM location ml
          LEFT JOIN city mc ON mc.id = ml.city_id
          LEFT JOIN space_station ms ON ms.id = ml.space_station_id
         WHERE (ml.starmap_object_id = NEW.id
                OR mc.starmap_object_id = NEW.id
                OR ms.starmap_object_id = NEW.id)
           AND exchange_location_ref(
                   ml.name, mc.id_city, ms.id_space_station,
                   CASE WHEN ml.starmap_object_id = NEW.id THEN v_old_guid
                        ELSE exchange_starmap_object_guid(ml.starmap_object_id) END,
                   CASE WHEN mc.starmap_object_id = NEW.id THEN v_old_guid
                        ELSE exchange_starmap_object_guid(mc.starmap_object_id) END,
                   CASE WHEN ms.starmap_object_id = NEW.id THEN v_old_guid
                        ELSE exchange_starmap_object_guid(ms.starmap_object_id) END)
               IS DISTINCT FROM
               exchange_location_ref_of(ml.name, ml.city_id, ml.space_station_id,
                                        ml.starmap_object_id)));
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_starmap_object_exchange_change
AFTER UPDATE OF p4k_uuid, p4k_deleted_at, p4k_hidden_at ON starmap_object
FOR EACH ROW
WHEN (exchange_starmap_guid(NEW.p4k_uuid, NEW.p4k_deleted_at, NEW.p4k_hidden_at)
      IS DISTINCT FROM exchange_starmap_guid(OLD.p4k_uuid, OLD.p4k_deleted_at, OLD.p4k_hidden_at))
EXECUTE FUNCTION exchange_starmap_object_changed();

COMMENT ON TRIGGER trg_starmap_object_exchange_change ON starmap_object IS
    'Re-announces the entries at the places a starmap object gives their GUID when the GUID it gives changes, by a new GUID, the sweep''s stamp or an admin''s hiding (REQ-XCH-018).';

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
    'exchange.orgunits.read',
    'exchange.orgstock.read',
    'exchange.orgstock.details',
    'exchange.drafts.blueprints',
    'exchange.drafts.refinery'));

ALTER TABLE exchange_client
    ADD COLUMN holder_ref_salt BYTEA NOT NULL
        DEFAULT sha256(uuid_send(gen_random_uuid()) || uuid_send(gen_random_uuid()));

ALTER TABLE exchange_client
    ADD CONSTRAINT ck_exchange_client_holder_ref_salt CHECK (octet_length(holder_ref_salt) = 32);

COMMENT ON COLUMN exchange_client.holder_ref_salt IS
    'The 32-byte random salt of the client''s holder pseudonyms: an org-stock entry''s holder ref is HMAC-SHA256(salt, user id). Created with the row, never served, never mirrored (REQ-XCH-043, ADR-0257).';

CREATE OR REPLACE FUNCTION exchange_record_org_lots_of_holder(p_user UUID)
RETURNS VOID AS $$
BEGIN
    PERFORM exchange_record_org_change(lots.unit_id, lots.lot_key)
       FROM (SELECT DISTINCT i.owning_org_unit_id AS unit_id,
                    exchange_org_stock_lot_key(i.owning_org_unit_id, i.material_id,
                                               i.game_item_id, i.location_id, i.quality,
                                               i.stolen) AS lot_key
               FROM inventory_item i
              WHERE i.user_id = p_user AND NOT i.personal
                AND i.owning_org_unit_id IS NOT NULL) lots;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_app_user_renamed()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.username IS DISTINCT FROM OLD.username
       OR NEW.display_name IS DISTINCT FROM OLD.display_name THEN
        PERFORM exchange_record_org_lots_of_holder(NEW.id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_app_user_exchange_change
AFTER UPDATE OF username, display_name ON app_user
FOR EACH ROW EXECUTE FUNCTION exchange_app_user_renamed();

COMMENT ON TRIGGER trg_app_user_exchange_change ON app_user IS
    'Announces every org-stock lot a member holds a shared entry in when the name the web shows for them changes, so a Logistician''s client never keeps a stale holder name (REQ-XCH-042, REQ-XCH-043).';

CREATE OR REPLACE FUNCTION exchange_name_withholding_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.withhold_name THEN
            PERFORM exchange_record_org_lots_of_holder(OLD.user_id);
        END IF;
    ELSIF TG_OP = 'INSERT' THEN
        IF NEW.withhold_name THEN
            PERFORM exchange_record_org_lots_of_holder(NEW.user_id);
        END IF;
    ELSIF NEW.withhold_name IS DISTINCT FROM OLD.withhold_name THEN
        PERFORM exchange_record_org_lots_of_holder(NEW.user_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_exchange_name_withholding_exchange_change
AFTER INSERT OR UPDATE OR DELETE ON exchange_name_withholding
FOR EACH ROW EXECUTE FUNCTION exchange_name_withholding_changed();

COMMENT ON TRIGGER trg_exchange_name_withholding_exchange_change ON exchange_name_withholding IS
    'Announces every org-stock lot a member holds a shared entry in when they start or stop withholding their name (REQ-XCH-044).';

CREATE OR REPLACE FUNCTION exchange_org_unit_membership_joined()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.org_unit_id IS DISTINCT FROM OLD.org_unit_id THEN
        PERFORM exchange_record_org_pool(NEW.org_unit_id);
    ELSIF (NEW.is_logistician OR NEW.role <> 'MEMBER')
          IS DISTINCT FROM (OLD.is_logistician OR OLD.role <> 'MEMBER') THEN
        PERFORM exchange_record_org_pool(NEW.org_unit_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trg_org_unit_membership_exchange_change ON org_unit_membership;

CREATE TRIGGER trg_org_unit_membership_exchange_change
AFTER INSERT OR UPDATE OF org_unit_id, is_logistician, role ON org_unit_membership
FOR EACH ROW EXECUTE FUNCTION exchange_org_unit_membership_joined();

COMMENT ON TRIGGER trg_org_unit_membership_exchange_change ON org_unit_membership IS
    'Announces every lot of a unit''s pool in the org-stock feed when a member joins it, a membership moves to it, or a membership starts or stops making its member a Logistician of the unit, so the pool''s entries appear or vanish in that member''s feed (REQ-XCH-042, REQ-XCH-043).';

CREATE OR REPLACE FUNCTION exchange_mission_renamed()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.name IS DISTINCT FROM OLD.name THEN
        PERFORM exchange_record_org_lot_of_item(a.inventory_item_id)
           FROM inventory_item_mission_allocation a
          WHERE a.mission_id = NEW.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_mission_exchange_change
AFTER UPDATE OF name ON mission
FOR EACH ROW EXECUTE FUNCTION exchange_mission_renamed();

COMMENT ON TRIGGER trg_mission_exchange_change ON mission IS
    'Announces every org-stock lot with an entry earmarked to a mission when the mission is renamed, for the entries'' mission earmarks (REQ-XCH-043).';

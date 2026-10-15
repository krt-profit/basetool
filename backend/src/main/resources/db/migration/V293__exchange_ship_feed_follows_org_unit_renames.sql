CREATE OR REPLACE FUNCTION exchange_org_unit_changed()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM exchange_record_change(s.owner_id, 'SHIP', s.id::text)
       FROM ship s
      WHERE s.owning_org_unit_id = NEW.id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_unit_exchange_change
AFTER UPDATE OF name, shorthand ON org_unit
FOR EACH ROW
WHEN (NEW.name IS DISTINCT FROM OLD.name OR NEW.shorthand IS DISTINCT FROM OLD.shorthand)
EXECUTE FUNCTION exchange_org_unit_changed();

COMMENT ON TRIGGER trg_org_unit_exchange_change ON org_unit IS
    'Re-announces every ship of a unit in the exchange ship feed when the unit''s name or shorthand changes, so a client never keeps a stale unit (REQ-XCH-017, ADR-0261).';

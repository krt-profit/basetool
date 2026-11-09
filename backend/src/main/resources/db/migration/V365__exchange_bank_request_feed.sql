ALTER TABLE exchange_change DROP CONSTRAINT ck_exchange_change_resource;

ALTER TABLE exchange_change
    ADD CONSTRAINT ck_exchange_change_resource
        CHECK (resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK', 'BANK_REQUEST'));

CREATE OR REPLACE FUNCTION exchange_bank_booking_request_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR (TG_OP = 'UPDATE' AND NEW.requested_by IS DISTINCT FROM OLD.requested_by) THEN
        PERFORM exchange_record_change(OLD.requested_by, 'BANK_REQUEST', OLD.id::text);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM exchange_record_change(NEW.requested_by, 'BANK_REQUEST', NEW.id::text);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bank_booking_request_exchange_change
AFTER INSERT OR UPDATE OR DELETE ON bank_booking_request
FOR EACH ROW EXECUTE FUNCTION exchange_bank_booking_request_changed();

COMMENT ON TRIGGER trg_bank_booking_request_exchange_change ON bank_booking_request IS
    'Sequences every write of a booking request in the requester''s BANK_REQUEST feed, keyed by the request id, whichever channel wrote it (REQ-BXCH-003, REQ-XCH-013).';

CREATE OR REPLACE FUNCTION exchange_record_bank_requests_of_accounts(p_accounts UUID[])
RETURNS VOID AS $$
BEGIN
    PERFORM exchange_record_change(r.requested_by, 'BANK_REQUEST', r.id::text)
       FROM bank_booking_request r
      WHERE r.account_id = ANY (p_accounts) OR r.target_account_id = ANY (p_accounts);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_record_bank_requests_of_accounts(UUID[]) IS
    'Re-announces every booking request raised against or transferring to the given accounts in its requester''s BANK_REQUEST feed (REQ-BXCH-003).';

CREATE OR REPLACE FUNCTION exchange_bank_account_changed()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM exchange_record_bank_requests_of_accounts(ARRAY[NEW.id]);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bank_account_exchange_change
AFTER UPDATE OF account_no, name, org_unit_id ON bank_account
FOR EACH ROW
WHEN (NEW.account_no IS DISTINCT FROM OLD.account_no
      OR NEW.name IS DISTINCT FROM OLD.name
      OR NEW.org_unit_id IS DISTINCT FROM OLD.org_unit_id)
EXECUTE FUNCTION exchange_bank_account_changed();

COMMENT ON TRIGGER trg_bank_account_exchange_change ON bank_account IS
    'Re-announces the booking requests on and to an account when its number, name or owning unit changes what a request serialises, so a client never keeps a stale account (REQ-BXCH-003).';

CREATE OR REPLACE FUNCTION exchange_org_unit_changed()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM exchange_record_change(s.owner_id, 'SHIP', s.id::text)
       FROM ship s
      WHERE s.owning_org_unit_id = NEW.id;
    IF NEW.name IS DISTINCT FROM OLD.name THEN
        PERFORM exchange_record_org_pool(NEW.id);
    END IF;
    IF NEW.shorthand IS DISTINCT FROM OLD.shorthand THEN
        PERFORM exchange_record_bank_requests_of_accounts(
            ARRAY(SELECT a.id FROM bank_account a WHERE a.org_unit_id = NEW.id));
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TRIGGER trg_org_unit_exchange_change ON org_unit IS
    'Re-announces every ship of a unit in the exchange ship feed when the unit''s name or shorthand changes, every lot of its pool in the org-stock feed when its name changes, and every booking request on its bank account in the BANK_REQUEST feed when its shorthand changes, so a client never keeps a stale unit (REQ-XCH-017, REQ-XCH-042, REQ-BXCH-003, ADR-0261).';

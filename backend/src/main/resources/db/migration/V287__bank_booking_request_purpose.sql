ALTER TABLE bank_booking_request
    ADD COLUMN purpose_kind VARCHAR(32),
    ADD COLUMN purpose_ref  UUID,
    ADD CONSTRAINT chk_bank_booking_request_purpose
        CHECK ((purpose_kind IS NULL) = (purpose_ref IS NULL));

CREATE INDEX idx_bank_booking_request_purpose ON bank_booking_request (purpose_kind, purpose_ref);

COMMENT ON COLUMN bank_booking_request.purpose_kind IS
    'What a request set through the bank command API is for (REQ-BANK-061); opaque to the bank, both purpose columns or neither.';
COMMENT ON COLUMN bank_booking_request.purpose_ref IS
    'The owner''s id for the thing the request pays for; never read by the bank, only handed back to the observer of purpose_kind.';

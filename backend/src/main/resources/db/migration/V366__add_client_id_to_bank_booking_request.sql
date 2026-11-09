-- The connected application that raised a booking request through the exchange (REQ-BXCH-004,
-- REQ-BANK-067): the registry client id the exchange gate admitted. NULL for a request raised in
-- the Basetool itself.
-- VARCHAR(60) matches audit_event.client_id and bank_audit_event.client_id.
--
-- Rollback: ALTER TABLE bank_booking_request DROP COLUMN client_id.

ALTER TABLE bank_booking_request
    ADD COLUMN client_id VARCHAR(60);

COMMENT ON COLUMN bank_booking_request.client_id IS
    'Exchange client that raised the request (registry client id); NULL for the Basetool itself';

-- The connected application that raised a booking request through the exchange (REQ-BXCH-004,
-- REQ-BANK-067): the registry client id the exchange gate admitted. NULL for a request raised in
-- the Basetool itself.
-- VARCHAR(63) matches exchange_client.client_id, the longest id the registry admits; the bank's
-- own trail, bank_audit_event.client_id, records the same id and is widened to it.
--
-- Rollback: ALTER TABLE bank_booking_request DROP COLUMN client_id;
--           ALTER TABLE bank_audit_event ALTER COLUMN client_id TYPE VARCHAR(60).

ALTER TABLE bank_booking_request
    ADD COLUMN client_id VARCHAR(63);

COMMENT ON COLUMN bank_booking_request.client_id IS
    'Exchange client that raised the request (registry client id); NULL for the Basetool itself';

ALTER TABLE bank_audit_event
    ALTER COLUMN client_id TYPE VARCHAR(63);

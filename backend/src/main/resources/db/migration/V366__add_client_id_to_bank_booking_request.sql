-- The connected application that raised a booking request through the exchange (REQ-BXCH-004,
-- REQ-BANK-067): the registry client id the exchange gate admitted. NULL for a request raised in
-- the Basetool itself.
-- VARCHAR(63) matches exchange_client.client_id, the longest id the registry admits; the bank's
-- own trail, bank_audit_event.client_id, records the same id and is widened to it.
--
-- Rollback: DROP INDEX idx_bank_booking_request_requested_by_id;
--           ALTER TABLE bank_booking_request DROP COLUMN idempotency_key;
--           ALTER TABLE bank_booking_request DROP COLUMN client_id;
--           ALTER TABLE bank_audit_event ALTER COLUMN client_id TYPE VARCHAR(60).

ALTER TABLE bank_booking_request
    ADD COLUMN client_id VARCHAR(63);

COMMENT ON COLUMN bank_booking_request.client_id IS
    'Exchange client that raised the request (registry client id); NULL for the Basetool itself';

-- The client's Idempotency-Key of the raise (REQ-BXCH-004, REQ-XCH-020), 8 to 128 characters as
-- the gateway admits it. Unique per client and requester, so a retry after a lost answer finds the
-- request instead of raising it again; NULL never collides, so web requests are unconstrained.
ALTER TABLE bank_booking_request
    ADD COLUMN idempotency_key VARCHAR(128);

ALTER TABLE bank_booking_request
    ADD CONSTRAINT uq_bank_booking_request_client_idempotency
        UNIQUE (client_id, requested_by, idempotency_key);

COMMENT ON COLUMN bank_booking_request.idempotency_key IS
    'Idempotency-Key the exchange client raised the request with; NULL without one';

ALTER TABLE bank_audit_event
    ALTER COLUMN client_id TYPE VARCHAR(63);

-- The keyset of the exchange snapshot of a member's own requests (REQ-BXCH-003) pages by
-- requested_by and id; this index serves it in id order without a sort.
CREATE INDEX idx_bank_booking_request_requested_by_id
    ON bank_booking_request (requested_by, id);

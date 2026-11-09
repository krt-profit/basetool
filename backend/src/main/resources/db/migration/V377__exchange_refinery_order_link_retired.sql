ALTER TABLE exchange_refinery_order_link ADD COLUMN retired_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE exchange_refinery_order_link DROP CONSTRAINT uk_exchange_refinery_order_link_key;

CREATE UNIQUE INDEX uk_exchange_refinery_order_link_live_key
    ON exchange_refinery_order_link (user_id, client_id, client_key)
    WHERE retired_at IS NULL;

COMMENT ON COLUMN exchange_refinery_order_link.retired_at IS
    'When a later create of the client took the key over; a retired link still names the key for its order''s tombstone (REQ-XCH-051).';

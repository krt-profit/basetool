ALTER TABLE refinery_order
    ADD COLUMN created_source     VARCHAR(8) NOT NULL DEFAULT 'WEB',
    ADD COLUMN created_client     VARCHAR(64),
    ADD COLUMN last_change_source VARCHAR(8) NOT NULL DEFAULT 'WEB',
    ADD COLUMN last_change_client VARCHAR(64),
    ADD COLUMN last_changed_at    TIMESTAMP WITH TIME ZONE,
    ADD COLUMN picked_up_at       TIMESTAMP WITH TIME ZONE,
    ADD COLUMN picked_up_client   VARCHAR(64),
    ADD CONSTRAINT ck_refinery_order_created_source CHECK (
        (created_source = 'WEB' AND created_client IS NULL)
            OR (created_source = 'CLIENT' AND created_client IS NOT NULL)),
    ADD CONSTRAINT ck_refinery_order_last_change_source CHECK (
        (last_change_source = 'WEB' AND last_change_client IS NULL)
            OR (last_change_source = 'CLIENT' AND last_change_client IS NOT NULL)),
    ADD CONSTRAINT ck_refinery_order_pickup CHECK ((picked_up_at IS NULL) = (picked_up_client IS NULL));

-- The backfill is no change an exchange client should see: V367's feed trigger stays off for it.
ALTER TABLE refinery_order DISABLE TRIGGER trg_refinery_order_exchange_change;

UPDATE refinery_order
SET last_changed_at = COALESCE(updated_at, created_at, now());

ALTER TABLE refinery_order ENABLE TRIGGER trg_refinery_order_exchange_change;

ALTER TABLE refinery_order
    ALTER COLUMN last_changed_at SET NOT NULL;

COMMENT ON COLUMN refinery_order.created_client IS
    'Exchange client id that created the order; NULL for an order created in the web (REQ-REFINERY-027).';
COMMENT ON COLUMN refinery_order.last_change_client IS
    'Exchange client id of the last change; NULL when the web made it (REQ-REFINERY-027).';
COMMENT ON COLUMN refinery_order.picked_up_at IS
    'When an exchange client reported the pickup; NULL until then (REQ-REFINERY-028).';

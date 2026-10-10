ALTER TABLE refinery_order
    ADD COLUMN ready_notified_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN refinery_order.ready_notified_at IS
    'When the order-ready notice was raised; NULL until then, reset when the start or the duration changes (REQ-REFINERY-023).';

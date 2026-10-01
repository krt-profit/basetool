ALTER TABLE refinery_order ADD COLUMN stored_at TIMESTAMP WITH TIME ZONE;

UPDATE refinery_order
   SET stored_at = COALESCE(updated_at, created_at, started_at, now())
 WHERE status = 'COMPLETED';

COMMENT ON COLUMN refinery_order.stored_at IS
    'When the order output was booked into inventory; set only by the store operation. A non-null value forbids storing again and changing the status by update (REQ-REFINERY-022).';

ALTER TABLE job_order ADD COLUMN closed_at TIMESTAMP WITH TIME ZONE;

UPDATE job_order
   SET closed_at = updated_at
 WHERE status IN ('COMPLETED', 'CANCELLED', 'REJECTED');

ALTER TABLE job_order
    ADD CONSTRAINT chk_job_order_closed_at
        CHECK ((status IN ('COMPLETED', 'CANCELLED', 'REJECTED')) = (closed_at IS NOT NULL));

COMMENT ON COLUMN job_order.closed_at IS
    'When the order entered a terminal status; kept while it changes between terminal statuses, NULL while it is OPEN or IN_PROGRESS (REQ-ORDERS-054).';

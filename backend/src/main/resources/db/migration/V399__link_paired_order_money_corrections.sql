ALTER TABLE job_order_money_operation
    DROP CONSTRAINT chk_job_order_money_operation_purpose,
    ADD CONSTRAINT chk_job_order_money_operation_purpose CHECK (
        (kind IN ('PURPOSE_INFLOW', 'PURPOSE_OUTFLOW')
            AND peer_order_id IS NOT NULL AND peer_order_id <> order_id)
        OR (kind = 'CORRECTION' AND (peer_order_id IS NULL OR peer_order_id <> order_id))
        OR (kind NOT IN ('PURPOSE_INFLOW', 'PURPOSE_OUTFLOW', 'CORRECTION') AND peer_order_id IS NULL)
    );

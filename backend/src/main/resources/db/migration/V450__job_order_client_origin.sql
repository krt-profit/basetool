ALTER TABLE job_order
    ADD COLUMN comment_client_id VARCHAR(63);

ALTER TABLE job_order_money_target_change
    ADD COLUMN client_id VARCHAR(63);

COMMENT ON COLUMN job_order.comment_client_id IS
    'The registry client the comment was last written through; NULL while its last write came from the Basetool. Moves with comment_version (REQ-ORDERS-078).';

COMMENT ON COLUMN job_order_money_target_change.client_id IS
    'The registry client the target change was made through; NULL for a change made in the Basetool (REQ-ORDERS-078).';

ALTER TABLE job_order
    ADD COLUMN comment_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_job_order_comment_version
        CHECK (comment_version >= 0);

COMMENT ON COLUMN job_order.comment_version IS
    'The revision of the comment, raised by every write that changes it and by no other write; independent of the contact''s money_information_version and of the row version (REQ-ORDERS-066).';

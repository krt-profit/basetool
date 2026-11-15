ALTER TABLE bank_transaction
    ADD COLUMN effective_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT chk_bank_transaction_effective_at
        CHECK (effective_at IS NULL OR type = 'WIPE_RESET');

COMMENT ON COLUMN bank_transaction.effective_at IS
    'The live time of the patch change whose currency wipe booked this reset; NULL for every booking that takes effect when it is recorded (REQ-PATCH-041).';

ALTER TABLE job_order_money_operation
    ADD COLUMN effective_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT chk_job_order_money_operation_effective_at
        CHECK (effective_at IS NULL OR patch_change_id IS NOT NULL);

COMMENT ON COLUMN job_order_money_operation.effective_at IS
    'The live time of the patch change whose currency wipe booked this shortage; NULL for every other operation (REQ-PATCH-044).';

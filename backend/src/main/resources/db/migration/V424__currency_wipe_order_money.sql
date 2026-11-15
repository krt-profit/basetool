ALTER TABLE job_order_money_operation
    ADD COLUMN patch_change_id UUID,
    ADD CONSTRAINT chk_job_order_money_operation_patch_change
        CHECK (patch_change_id IS NULL OR kind = 'SHORTAGE');

COMMENT ON COLUMN job_order_money_operation.patch_change_id IS
    'The patch change whose currency wipe booked this real shortage; NULL for every other operation (REQ-PATCH-044).';

ALTER TABLE job_order_linked_money_attempt
    ADD COLUMN cancelled_by_patch_change UUID,
    ADD CONSTRAINT chk_order_attempt_cancelled_by_patch_change
        CHECK (cancelled_by_patch_change IS NULL OR state = 'CANCELLED');

COMMENT ON COLUMN job_order_linked_money_attempt.cancelled_by_patch_change IS
    'The patch change whose currency wipe cancelled the waiting attempt as its reason; NULL otherwise (REQ-PATCH-043).';

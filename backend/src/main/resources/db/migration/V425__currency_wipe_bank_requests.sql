ALTER TABLE bank_booking_request
    ADD COLUMN cancelled_by_patch_change UUID,
    ADD CONSTRAINT chk_bank_booking_request_cancelled_by_patch_change
        CHECK (cancelled_by_patch_change IS NULL OR status = 'CANCELLED');

COMMENT ON COLUMN bank_booking_request.cancelled_by_patch_change IS
    'The patch change whose currency wipe cancelled the pending request as its reason; NULL otherwise (REQ-PATCH-042).';

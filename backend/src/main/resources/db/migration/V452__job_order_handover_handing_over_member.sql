-- REQ-ORDERS-079: the member of the processing unit who handed a material or item handover over.
-- The user id is kept with the act (ON DELETE SET NULL); the handle snapshot outlives the account,
-- so a named user always carries a handle, while a handle may remain after its account is gone.

ALTER TABLE job_order_handover
    ADD COLUMN handing_over_user_id UUID,
    ADD COLUMN handing_over_handle  VARCHAR(255),
    ADD CONSTRAINT fk_job_order_handover_handing_over_user
        FOREIGN KEY (handing_over_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    ADD CONSTRAINT chk_job_order_handover_handing_over
        CHECK (handing_over_user_id IS NULL OR handing_over_handle IS NOT NULL);

CREATE INDEX idx_job_order_handover_handing_over_user_id
    ON job_order_handover (handing_over_user_id);

ALTER TABLE job_order_item_handover
    ADD COLUMN handing_over_user_id UUID,
    ADD COLUMN handing_over_handle  VARCHAR(255),
    ADD CONSTRAINT fk_job_order_item_handover_handing_over_user
        FOREIGN KEY (handing_over_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    ADD CONSTRAINT chk_job_order_item_handover_handing_over
        CHECK (handing_over_user_id IS NULL OR handing_over_handle IS NOT NULL);

CREATE INDEX idx_job_order_item_handover_handing_over_user_id
    ON job_order_item_handover (handing_over_user_id);

COMMENT ON COLUMN job_order_handover.handing_over_user_id IS
    'The member of the processing unit who handed the materials over (REQ-ORDERS-079); NULL when none was recorded or the account is deleted.';
COMMENT ON COLUMN job_order_handover.handing_over_handle IS
    'Handle snapshot of handing_over_user_id at recording time, shown on the order and in the protocol PDF; replaced by the erasure sentinel on anonymisation and name retention.';
COMMENT ON COLUMN job_order_item_handover.handing_over_user_id IS
    'The member of the processing unit who handed the items over (REQ-ORDERS-079); NULL when none was recorded or the account is deleted.';
COMMENT ON COLUMN job_order_item_handover.handing_over_handle IS
    'Handle snapshot of handing_over_user_id at recording time, shown on the order and in the delivery note PDF; replaced by the erasure sentinel on anonymisation and name retention.';

ALTER TABLE job_order_money_recipient
    ADD COLUMN corrected_into_id UUID,
    ADD COLUMN corrects_id UUID,
    ADD COLUMN correction_actor_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN corrected_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT fk_job_order_money_recipient_corrected_into
        FOREIGN KEY (order_id, corrected_into_id) REFERENCES job_order_money_recipient (order_id, id),
    ADD CONSTRAINT fk_job_order_money_recipient_corrects
        FOREIGN KEY (order_id, corrects_id) REFERENCES job_order_money_recipient (order_id, id),
    ADD CONSTRAINT chk_job_order_money_recipient_correction
        CHECK (
            (corrects_id IS NULL) = (corrected_at IS NULL)
            AND (corrected_into_id IS NULL OR NOT active)
            AND corrected_into_id IS DISTINCT FROM id
            AND corrects_id IS DISTINCT FROM id
        );

CREATE INDEX idx_job_order_money_recipient_corrected_into
    ON job_order_money_recipient (order_id, corrected_into_id) WHERE corrected_into_id IS NOT NULL;
CREATE UNIQUE INDEX idx_job_order_money_recipient_corrects
    ON job_order_money_recipient (order_id, corrects_id) WHERE corrects_id IS NOT NULL;
CREATE INDEX idx_job_order_money_recipient_correction_actor
    ON job_order_money_recipient (correction_actor_id) WHERE correction_actor_id IS NOT NULL;

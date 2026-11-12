DROP INDEX idx_job_order_money_holder_change_target;
CREATE INDEX idx_job_order_money_holder_change_target
    ON job_order_money_holder_change (order_id, target_holder_id);

DROP INDEX idx_job_order_money_recipient_corrected_into;
CREATE INDEX idx_job_order_money_recipient_corrected_into
    ON job_order_money_recipient (order_id, corrected_into_id);

DROP INDEX idx_job_order_money_recipient_corrects;
CREATE UNIQUE INDEX idx_job_order_money_recipient_corrects
    ON job_order_money_recipient (order_id, corrects_id);

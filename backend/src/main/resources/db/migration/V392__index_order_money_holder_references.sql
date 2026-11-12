CREATE INDEX idx_job_order_money_holder_order_merge
    ON job_order_money_holder (order_id, merged_into_id);

CREATE INDEX idx_job_order_money_operation_holder_order
    ON job_order_money_operation (holder_id, order_id);

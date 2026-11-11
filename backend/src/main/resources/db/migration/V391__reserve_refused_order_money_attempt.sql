CREATE TABLE job_order_money_refused_attempt (
    order_id UUID NOT NULL REFERENCES job_order (id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    PRIMARY KEY (order_id, operation_id)
);

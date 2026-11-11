CREATE TABLE job_order_money_recipient (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES job_order (id) ON DELETE CASCADE,
    user_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    holder_id UUID,
    accountless_name VARCHAR(200),
    active BOOLEAN NOT NULL,
    UNIQUE (order_id, id),
    FOREIGN KEY (order_id, holder_id) REFERENCES job_order_money_holder (order_id, id),
    CHECK (num_nonnulls(user_id, holder_id, accountless_name) <= 1)
);

CREATE INDEX idx_job_order_money_recipient_user ON job_order_money_recipient (user_id);
CREATE INDEX idx_job_order_money_recipient_holder ON job_order_money_recipient (order_id, holder_id);

ALTER TABLE job_order_money_operation
    ADD COLUMN kind VARCHAR(24) NOT NULL DEFAULT 'PROVISION',
    ADD COLUMN destination_holder_id UUID,
    ADD COLUMN recipient_id UUID,
    ADD COLUMN new_provision NUMERIC,
    ADD COLUMN fee_rate NUMERIC,
    ADD COLUMN fee NUMERIC,
    ADD FOREIGN KEY (order_id, destination_holder_id) REFERENCES job_order_money_holder (order_id, id),
    ADD FOREIGN KEY (order_id, recipient_id) REFERENCES job_order_money_recipient (order_id, id),
    ADD CONSTRAINT chk_job_order_money_operation_movement CHECK (
        (kind = 'PROVISION' AND destination_holder_id IS NULL AND recipient_id IS NULL
             AND new_provision IS NULL AND fee_rate IS NULL AND fee IS NULL)
        OR
        (new_provision IS NOT NULL AND new_provision >= 0 AND new_provision < 'Infinity'::numeric
             AND new_provision = trunc(new_provision)
             AND fee_rate IS NOT NULL AND fee_rate >= 0 AND fee_rate < 1
             AND fee IS NOT NULL AND fee >= 0 AND fee < 'Infinity'::numeric AND fee = trunc(fee)
             AND ((kind = 'COLLECTION' AND destination_holder_id IS NOT NULL AND recipient_id IS NULL)
                  OR (kind = 'HANDOVER' AND destination_holder_id IS NULL AND recipient_id IS NOT NULL)
                  OR (kind = 'PURPOSE_HANDOVER' AND destination_holder_id IS NULL AND recipient_id IS NOT NULL
                      AND new_provision = 0 AND fee_rate = 0 AND fee = 0)))
    );

CREATE INDEX idx_job_order_money_operation_destination ON job_order_money_operation (order_id, destination_holder_id);
CREATE INDEX idx_job_order_money_operation_recipient ON job_order_money_operation (order_id, recipient_id);

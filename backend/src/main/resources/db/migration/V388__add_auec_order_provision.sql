ALTER TABLE job_order
    DROP CONSTRAINT chk_job_order_type,
    ADD CONSTRAINT chk_job_order_type CHECK (type IN ('MATERIAL', 'ITEM', 'AUEC'));

CREATE TABLE job_order_money_target (
    order_id UUID PRIMARY KEY REFERENCES job_order (id) ON DELETE CASCADE,
    amount NUMERIC NOT NULL CHECK (amount > 0 AND amount < 'Infinity'::numeric AND amount = trunc(amount)),
    version BIGINT NOT NULL
);

CREATE TABLE job_order_money_holder (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES job_order (id) ON DELETE CASCADE,
    user_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    accountless_name VARCHAR(200),
    revision BIGINT NOT NULL CHECK (revision > 0),
    UNIQUE (order_id, user_id),
    UNIQUE (order_id, id)
);

CREATE INDEX idx_job_order_money_holder_user ON job_order_money_holder (user_id);

CREATE TABLE job_order_money_operation (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES job_order (id),
    operation_id UUID NOT NULL,
    holder_id UUID NOT NULL,
    actor_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    selection_kind VARCHAR(16) NOT NULL CHECK (selection_kind IN ('SELF', 'EXISTING', 'LINKED', 'ACCOUNTLESS')),
    selected_user_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    selected_accountless_name VARCHAR(200),
    bank_handling BOOLEAN NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount > 0 AND amount < 'Infinity'::numeric AND amount = trunc(amount)),
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (order_id, operation_id),
    FOREIGN KEY (order_id, holder_id) REFERENCES job_order_money_holder (order_id, id)
);

CREATE INDEX idx_job_order_money_operation_actor ON job_order_money_operation (actor_id);
CREATE INDEX idx_job_order_money_operation_selected_user ON job_order_money_operation (selected_user_id);

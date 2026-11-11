ALTER TABLE job_order
    ADD COLUMN money_information_version BIGINT NOT NULL DEFAULT 0
        CHECK (money_information_version >= 0);

CREATE TABLE job_order_money_target_change (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES job_order (id) ON DELETE CASCADE,
    actor_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    previous_target NUMERIC NOT NULL CHECK (previous_target > 0
        AND previous_target < 'Infinity'::numeric AND previous_target = trunc(previous_target)),
    target NUMERIC NOT NULL CHECK (target > 0
        AND target < 'Infinity'::numeric AND target = trunc(target)),
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (target <> previous_target)
);

CREATE INDEX idx_job_order_money_target_change_order
    ON job_order_money_target_change (order_id, recorded_at, id);
CREATE INDEX idx_job_order_money_target_change_actor
    ON job_order_money_target_change (actor_id);

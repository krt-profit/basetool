ALTER TABLE job_order_money_holder
    ADD COLUMN merged_into_id UUID,
    ADD CONSTRAINT fk_job_order_money_holder_merge
        FOREIGN KEY (order_id, merged_into_id) REFERENCES job_order_money_holder (order_id, id),
    ADD CONSTRAINT chk_job_order_money_holder_alias
        CHECK (merged_into_id IS NULL OR
               (merged_into_id <> id AND user_id IS NULL AND accountless_name IS NULL));

CREATE INDEX idx_job_order_money_holder_merged_into
    ON job_order_money_holder (merged_into_id) WHERE merged_into_id IS NOT NULL;

CREATE TABLE job_order_money_account_holding (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES job_order (id) ON DELETE CASCADE,
    bank_account_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    UNIQUE (order_id, bank_account_id),
    UNIQUE (order_id, id),
    UNIQUE (order_id, id, bank_account_id)
);

DO $$
DECLARE
    target_custodian_check name;
BEGIN
    SELECT conname INTO STRICT target_custodian_check
    FROM pg_constraint
    WHERE conrelid = 'job_order_linked_money_attempt'::regclass
        AND contype = 'c'
        AND pg_get_constraintdef(oid) LIKE '%bank_target_custodian_id IS NOT NULL%';
    EXECUTE format(
        'ALTER TABLE job_order_linked_money_attempt DROP CONSTRAINT %I', target_custodian_check);
END;
$$;

ALTER TABLE job_order_linked_money_attempt
    ALTER COLUMN holder_id DROP NOT NULL,
    ALTER COLUMN selection_kind DROP NOT NULL,
    ALTER COLUMN bank_custodian_id DROP NOT NULL,
    ADD COLUMN account_holding_id UUID,
    ADD CONSTRAINT fk_order_attempt_account_holding
        FOREIGN KEY (order_id, account_holding_id, bank_account_id)
        REFERENCES job_order_money_account_holding (order_id, id, bank_account_id),
    ADD CONSTRAINT chk_order_attempt_holding
        CHECK (
            num_nonnulls(holder_id, account_holding_id) = 1
            AND (holder_id IS NOT NULL) = (selection_kind IS NOT NULL)
            AND (holder_id IS NOT NULL) = (bank_custodian_id IS NOT NULL)
            AND (holder_id IS NOT NULL AND bank_type IN ('TRANSFER', 'HOLDER_TRANSFER')) =
                (bank_target_custodian_id IS NOT NULL)
            AND (account_holding_id IS NULL OR (
                destination_holder_id IS NULL
                AND num_nonnulls(selected_ref, selected_user_id, selected_accountless_name) = 0
                AND (
                    (kind = 'PROVISION' AND bank_type IN ('DEPOSIT', 'AUTHORISATION'))
                    OR (kind = 'PURPOSE_HANDOVER' AND bank_type = 'TRANSFER' AND NOT actual_payment)
                )
            ))
        );

CREATE INDEX idx_order_attempt_account_holding
    ON job_order_linked_money_attempt (order_id, account_holding_id, bank_account_id);

ALTER TABLE job_order_money_operation
    ALTER COLUMN holder_id DROP NOT NULL,
    ALTER COLUMN selection_kind DROP NOT NULL,
    ADD COLUMN account_holding_id UUID,
    ADD CONSTRAINT fk_order_operation_account_holding
        FOREIGN KEY (order_id, account_holding_id)
        REFERENCES job_order_money_account_holding (order_id, id),
    ADD CONSTRAINT chk_order_operation_holding
        CHECK (
            num_nonnulls(holder_id, account_holding_id) = 1
            AND (holder_id IS NOT NULL) = (selection_kind IS NOT NULL)
            AND (account_holding_id IS NULL OR (
                kind IN ('PROVISION', 'PURPOSE_HANDOVER', 'CORRECTION')
                AND destination_holder_id IS NULL
                AND num_nonnulls(selected_user_id, selected_accountless_name) = 0
            ))
        );

CREATE INDEX idx_order_operation_account_holding
    ON job_order_money_operation (order_id, account_holding_id);

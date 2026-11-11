ALTER TABLE job_order_linked_money_attempt
    ADD COLUMN holding_bank_account_id UUID GENERATED ALWAYS AS (
        CASE WHEN kind = 'REQUESTER_RETURN' THEN bank_target_account_id ELSE bank_account_id END
    ) STORED,
    DROP CONSTRAINT fk_order_attempt_account_holding,
    DROP CONSTRAINT chk_order_attempt_holding,
    ADD CONSTRAINT fk_order_attempt_account_holding
        FOREIGN KEY (order_id, account_holding_id, holding_bank_account_id)
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
                    OR (kind IN ('PURPOSE_HANDOVER', 'REQUESTER_RETURN') AND bank_type = 'TRANSFER'
                        AND NOT actual_payment AND recipient_id IS NOT NULL AND commitment_id IS NULL
                        AND provision = 0 AND actual_fee = 0 AND fee_funding = 'NONE')
                )
            ))
        );

DROP INDEX idx_order_attempt_account_holding;
CREATE INDEX idx_order_attempt_account_holding
    ON job_order_linked_money_attempt (order_id, account_holding_id, holding_bank_account_id);

ALTER TABLE job_order_money_operation
    DROP CONSTRAINT chk_order_operation_holding,
    DROP CONSTRAINT chk_job_order_money_operation_movement,
    ADD CONSTRAINT chk_order_operation_holding
        CHECK (
            num_nonnulls(holder_id, account_holding_id) = 1
            AND (holder_id IS NOT NULL) = (selection_kind IS NOT NULL)
            AND (account_holding_id IS NULL OR (
                kind IN ('PROVISION', 'PURPOSE_HANDOVER', 'REQUESTER_RETURN', 'RELEASE', 'SHORTAGE', 'CORRECTION')
                AND bank_handling = (kind IN ('PROVISION', 'PURPOSE_HANDOVER', 'REQUESTER_RETURN'))
                AND destination_holder_id IS NULL
                AND num_nonnulls(selected_user_id, selected_accountless_name) = 0
            ))
        ),
    ADD CONSTRAINT chk_job_order_money_operation_movement CHECK (
        (kind IN ('PROVISION', 'PURPOSE_INFLOW', 'PURPOSE_OUTFLOW', 'RELEASE', 'SHORTAGE', 'CORRECTION')
            AND destination_holder_id IS NULL AND recipient_id IS NULL
            AND new_provision IS NULL AND fee_rate IS NULL AND fee IS NULL)
        OR
        (new_provision IS NOT NULL AND new_provision >= 0 AND new_provision < 'Infinity'::numeric
            AND new_provision = trunc(new_provision)
            AND fee_rate IS NOT NULL AND fee_rate >= 0 AND fee_rate < 1
            AND fee IS NOT NULL AND fee >= 0 AND fee < 'Infinity'::numeric AND fee = trunc(fee)
            AND ((kind = 'COLLECTION' AND destination_holder_id IS NOT NULL AND recipient_id IS NULL)
                OR (kind = 'HANDOVER' AND destination_holder_id IS NULL AND recipient_id IS NOT NULL)
                OR (kind = 'REQUESTER_RETURN' AND recipient_id IS NOT NULL AND (
                    (account_holding_id IS NULL AND destination_holder_id IS NOT NULL)
                    OR (account_holding_id IS NOT NULL AND destination_holder_id IS NULL
                        AND new_provision = 0 AND fee_rate = 0 AND fee = 0)))
                OR (kind = 'SETTLEMENT' AND destination_holder_id IS NULL AND recipient_id IS NULL)
                OR (kind = 'PURPOSE_HANDOVER' AND destination_holder_id IS NULL AND recipient_id IS NOT NULL
                    AND new_provision = 0 AND fee_rate = 0 AND fee = 0)))
    );

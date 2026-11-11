ALTER TABLE job_order_linked_money_attempt
    DROP CONSTRAINT job_order_linked_money_attempt_fee_funding_check,
    ADD CONSTRAINT chk_order_linked_money_fee_funding CHECK (
        fee_funding IN ('NONE', 'ORDER', 'KRT', 'DEPOSITOR', 'BANK_SOURCE')
        AND (fee_funding = 'BANK_SOURCE') =
            (kind = 'PROVISION' AND bank_type = 'WITHDRAWAL' AND actual_payment)
    );

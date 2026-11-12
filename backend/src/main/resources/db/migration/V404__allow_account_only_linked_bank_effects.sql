ALTER TABLE bank_authorisation
    ALTER COLUMN holder_id DROP NOT NULL;

ALTER TABLE bank_booking_request
    DROP CONSTRAINT chk_bank_booking_request_linked_terms,
    DROP CONSTRAINT chk_bank_booking_request_confirmed_refs,
    ADD CONSTRAINT chk_bank_booking_request_linked_terms CHECK (
        (linked_attempt_id IS NOT NULL) = (purpose_kind IS NOT DISTINCT FROM 'AUEC_ORDER')
        AND (linked_attempt_id IS NOT NULL OR requested_holder_id IS NULL)
        AND (linked_attempt_id IS NULL OR type NOT IN ('WITHDRAWAL', 'HOLDER_TRANSFER')
             OR requested_holder_id IS NOT NULL)
        AND (type NOT IN ('HOLDER_TRANSFER', 'AUTHORISATION') OR linked_attempt_id IS NOT NULL)
        AND (type = 'HOLDER_TRANSFER') = (account_id IS NULL)
        AND (requested_target_holder_id IS NOT NULL) =
            (linked_attempt_id IS NOT NULL AND requested_holder_id IS NOT NULL
             AND type IN ('TRANSFER', 'HOLDER_TRANSFER'))
        AND (linked_attempt_id IS NULL OR type NOT IN ('DEPOSIT', 'WITHDRAWAL')
             OR counterparty_handle IS NOT NULL)
        AND (linked_attempt_id IS NULL OR client_id IS NULL)
        AND (type <> 'HOLDER_TRANSFER' OR NOT requires_owner_approval)
    ),
    ADD CONSTRAINT chk_bank_booking_request_confirmed_refs CHECK (
        (status = 'CONFIRMED'
         AND (holder_id IS NOT NULL) =
             (linked_attempt_id IS NULL OR requested_holder_id IS NOT NULL OR type = 'DEPOSIT')
         AND (
             (type = 'AUTHORISATION' AND authorisation_id IS NOT NULL
              AND authorisation_id = linked_attempt_id AND resulting_transaction_id IS NULL)
             OR (type <> 'AUTHORISATION' AND resulting_transaction_id IS NOT NULL
                 AND authorisation_id IS NULL)
         ))
        OR (status <> 'CONFIRMED' AND holder_id IS NULL AND resulting_transaction_id IS NULL
            AND authorisation_id IS NULL)
    );

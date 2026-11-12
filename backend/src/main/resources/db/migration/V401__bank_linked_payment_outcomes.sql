CREATE TABLE bank_linked_attempt (
    id UUID PRIMARY KEY,
    purpose_kind VARCHAR(32) NOT NULL,
    purpose_ref UUID NOT NULL,
    fee_rate NUMERIC NOT NULL CHECK (fee_rate >= 0 AND fee_rate < 1),
    fee NUMERIC(19, 4) NOT NULL CHECK (fee >= 0 AND fee = trunc(fee)),
    same_player BOOLEAN NOT NULL DEFAULT FALSE,
    transaction_id UUID UNIQUE REFERENCES bank_transaction (id),
    CONSTRAINT chk_bank_linked_attempt_purpose CHECK (purpose_kind = 'AUEC_ORDER')
);

CREATE TABLE bank_authorisation (
    id UUID PRIMARY KEY REFERENCES bank_linked_attempt (id),
    account_id UUID NOT NULL REFERENCES bank_account (id),
    holder_id UUID NOT NULL REFERENCES bank_holder (id),
    amount NUMERIC(19, 4) NOT NULL CHECK (amount > 0 AND amount = trunc(amount)),
    authorised_by UUID REFERENCES app_user (id) ON DELETE SET NULL,
    authorised_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_bank_authorisation_account ON bank_authorisation (account_id);
CREATE INDEX idx_bank_authorisation_holder ON bank_authorisation (holder_id);
CREATE INDEX idx_bank_authorisation_actor ON bank_authorisation (authorised_by);

ALTER TABLE bank_booking_request
    ALTER COLUMN account_id DROP NOT NULL,
    ADD COLUMN linked_attempt_id UUID UNIQUE REFERENCES bank_linked_attempt (id),
    ADD COLUMN requested_holder_id UUID REFERENCES bank_holder (id),
    ADD COLUMN requested_target_holder_id UUID REFERENCES bank_holder (id),
    ADD COLUMN authorisation_id UUID UNIQUE REFERENCES bank_authorisation (id),
    DROP CONSTRAINT chk_bank_booking_request_type,
    DROP CONSTRAINT chk_bank_booking_request_confirmed_refs,
    DROP CONSTRAINT chk_bank_booking_request_counterparty,
    ADD CONSTRAINT chk_bank_booking_request_type
        CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'HOLDER_TRANSFER', 'AUTHORISATION')),
    ADD CONSTRAINT chk_bank_booking_request_linked_terms
        CHECK (
            (linked_attempt_id IS NOT NULL) = (purpose_kind IS NOT DISTINCT FROM 'AUEC_ORDER')
            AND (linked_attempt_id IS NOT NULL) = (requested_holder_id IS NOT NULL)
            AND (type NOT IN ('HOLDER_TRANSFER', 'AUTHORISATION') OR linked_attempt_id IS NOT NULL)
            AND (type = 'HOLDER_TRANSFER') = (account_id IS NULL)
            AND (requested_target_holder_id IS NOT NULL) =
                (linked_attempt_id IS NOT NULL AND type IN ('TRANSFER', 'HOLDER_TRANSFER'))
            AND (linked_attempt_id IS NULL OR type NOT IN ('DEPOSIT', 'WITHDRAWAL') OR
                 counterparty_handle IS NOT NULL)
            AND (linked_attempt_id IS NULL OR client_id IS NULL)
            AND (type <> 'HOLDER_TRANSFER' OR NOT requires_owner_approval)
        ),
    ADD CONSTRAINT chk_bank_booking_request_confirmed_refs
        CHECK (
            (status = 'CONFIRMED' AND holder_id IS NOT NULL AND (
                (type = 'AUTHORISATION' AND authorisation_id IS NOT NULL AND
                 authorisation_id = linked_attempt_id AND resulting_transaction_id IS NULL)
                OR (type <> 'AUTHORISATION' AND resulting_transaction_id IS NOT NULL AND
                    authorisation_id IS NULL)
            ))
            OR (status <> 'CONFIRMED' AND holder_id IS NULL AND resulting_transaction_id IS NULL AND
                authorisation_id IS NULL)
        ),
    ADD CONSTRAINT chk_bank_booking_request_counterparty
        CHECK (
            (counterparty_user_id IS NULL OR counterparty_handle IS NOT NULL)
            AND (counterparty_org_unit_id IS NULL OR counterparty_org_unit_name IS NOT NULL)
            AND (counterparty_org_unit_name IS NULL OR counterparty_handle IS NOT NULL)
        );

CREATE INDEX idx_bank_booking_request_requested_holder
    ON bank_booking_request (requested_holder_id);
CREATE INDEX idx_bank_booking_request_requested_target_holder
    ON bank_booking_request (requested_target_holder_id);

CREATE OR REPLACE FUNCTION exchange_bank_booking_request_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE')
       AND OLD.purpose_kind IS DISTINCT FROM 'AUEC_ORDER'
       AND (TG_OP = 'DELETE'
            OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
            OR NEW.purpose_kind = 'AUEC_ORDER') THEN
        PERFORM exchange_record_change(OLD.requested_by, 'BANK_REQUEST', OLD.id::text);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE')
       AND NEW.purpose_kind IS DISTINCT FROM 'AUEC_ORDER' THEN
        PERFORM exchange_record_change(NEW.requested_by, 'BANK_REQUEST', NEW.id::text);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TRIGGER trg_bank_booking_request_exchange_change ON bank_booking_request IS
    'Sequences writes of requests visible to connected applications in the requester''s BANK_REQUEST feed (REQ-BXCH-003, REQ-ORDERS-076).';

CREATE OR REPLACE FUNCTION exchange_record_bank_requests_of_accounts(p_accounts UUID[])
RETURNS VOID AS $$
BEGIN
    PERFORM exchange_record_change(r.requested_by, 'BANK_REQUEST', r.id::text)
       FROM bank_booking_request r
      WHERE r.purpose_kind IS DISTINCT FROM 'AUEC_ORDER'
        AND (r.account_id = ANY (p_accounts) OR r.target_account_id = ANY (p_accounts));
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_record_bank_requests_of_accounts(UUID[]) IS
    'Re-announces connected-application-visible requests on or to the given accounts in their requester''s BANK_REQUEST feed (REQ-BXCH-003, REQ-ORDERS-076).';

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000063', 'BANK_FUNDS_AUTHORISED', 'BANK_FUNDS_AUTHORISED',
        'Default: tell the responsible holders that Bank staff authorised funds without a booking.',
        TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000063', 'ACCOUNT_RESPONSIBLE');

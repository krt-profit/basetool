CREATE TABLE job_order_linked_money_attempt (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES job_order (id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    purpose_ref UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN (
        'PROVISION', 'COLLECTION', 'HANDOVER', 'PURPOSE_HANDOVER', 'REQUESTER_RETURN', 'SETTLEMENT'
    )),
    actor_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    decision_actor_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    holder_id UUID NOT NULL,
    destination_holder_id UUID,
    recipient_id UUID,
    commitment_id UUID,
    selection_kind VARCHAR(16) NOT NULL CHECK (selection_kind IN ('SELF', 'EXISTING', 'LINKED', 'ACCOUNTLESS')),
    selected_ref UUID,
    selected_user_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    selected_accountless_name VARCHAR(200),
    amount NUMERIC NOT NULL CHECK (amount > 0 AND amount < 'Infinity'::numeric AND amount = trunc(amount)),
    provision NUMERIC NOT NULL CHECK (provision >= 0 AND provision < 'Infinity'::numeric AND provision = trunc(provision)),
    rate NUMERIC NOT NULL CHECK (rate >= 0 AND rate < 1),
    actual_fee NUMERIC NOT NULL CHECK (actual_fee >= 0 AND actual_fee < 'Infinity'::numeric AND actual_fee = trunc(actual_fee)),
    fee_funding VARCHAR(16) NOT NULL CHECK (fee_funding IN ('NONE', 'ORDER', 'KRT', 'DEPOSITOR')),
    source_binding NUMERIC NOT NULL CHECK (source_binding >= 0 AND source_binding < 'Infinity'::numeric AND source_binding = trunc(source_binding)),
    actual_payment BOOLEAN NOT NULL,
    bank_type VARCHAR(16) NOT NULL CHECK (bank_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'HOLDER_TRANSFER', 'AUTHORISATION')),
    bank_account_id UUID,
    bank_custodian_id UUID NOT NULL,
    bank_target_account_id UUID,
    bank_target_custodian_id UUID,
    counterparty_user_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    counterparty_name VARCHAR(200),
    state VARCHAR(16) NOT NULL CHECK (state IN (
        'SUBMITTING', 'WAITING', 'POSTED', 'AUTHORISED', 'REJECTED', 'CANCELLED', 'REVERSED', 'CLARIFICATION'
    )),
    request_id UUID UNIQUE,
    transaction_id UUID UNIQUE,
    authorisation_id UUID UNIQUE,
    reversal_id UUID UNIQUE,
    submitted_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (order_id, operation_id, revision),
    FOREIGN KEY (order_id, holder_id) REFERENCES job_order_money_holder (order_id, id),
    FOREIGN KEY (order_id, destination_holder_id) REFERENCES job_order_money_holder (order_id, id),
    FOREIGN KEY (order_id, recipient_id) REFERENCES job_order_money_recipient (order_id, id),
    FOREIGN KEY (order_id, commitment_id) REFERENCES job_order_money_commitment (order_id, id),
    CHECK (num_nonnulls(counterparty_user_id, counterparty_name) <= 1),
    CHECK ((revision = 1) = (id = purpose_ref)),
    CHECK (active OR state IN ('REJECTED', 'CANCELLED')),
    CHECK ((bank_type = 'HOLDER_TRANSFER') = (bank_account_id IS NULL)),
    CHECK ((bank_type = 'TRANSFER') = (bank_target_account_id IS NOT NULL)),
    CHECK ((bank_type IN ('TRANSFER', 'HOLDER_TRANSFER')) = (bank_target_custodian_id IS NOT NULL)),
    CHECK ((state IN ('POSTED', 'REVERSED', 'CLARIFICATION')) = (transaction_id IS NOT NULL)),
    CHECK ((state = 'AUTHORISED') = (authorisation_id IS NOT NULL)),
    CHECK ((state IN ('REVERSED', 'CLARIFICATION')) = (reversal_id IS NOT NULL)),
    CHECK (state NOT IN ('WAITING', 'REJECTED', 'CANCELLED') OR request_id IS NOT NULL)
);

CREATE UNIQUE INDEX idx_order_linked_money_active ON job_order_linked_money_attempt (order_id, operation_id) WHERE active;
CREATE INDEX idx_order_linked_money_state_age ON job_order_linked_money_attempt (state, submitted_at) WHERE active;
CREATE INDEX idx_order_linked_money_holder ON job_order_linked_money_attempt (order_id, holder_id);
CREATE INDEX idx_order_linked_money_destination ON job_order_linked_money_attempt (order_id, destination_holder_id);
CREATE INDEX idx_order_linked_money_recipient ON job_order_linked_money_attempt (order_id, recipient_id);
CREATE INDEX idx_order_linked_money_commitment ON job_order_linked_money_attempt (order_id, commitment_id);
CREATE INDEX idx_order_linked_money_actor ON job_order_linked_money_attempt (actor_id);
CREATE INDEX idx_order_linked_money_decider ON job_order_linked_money_attempt (decision_actor_id);
CREATE INDEX idx_order_linked_money_selected ON job_order_linked_money_attempt (selected_user_id);

CREATE INDEX idx_order_linked_money_counterparty ON job_order_linked_money_attempt (counterparty_user_id);

ALTER TABLE job_order_money_recipient
    ADD COLUMN bank_account_id UUID,
    ADD CONSTRAINT chk_order_money_recipient_party CHECK (
        num_nonnulls(user_id, holder_id, accountless_name, bank_account_id) <= 1
    );

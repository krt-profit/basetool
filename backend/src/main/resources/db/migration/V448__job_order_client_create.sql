ALTER TABLE job_order
    ADD COLUMN created_by_client_id   VARCHAR(63),
    ADD COLUMN created_by_member_id   UUID REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN create_idempotency_key VARCHAR(128),
    ADD CONSTRAINT chk_job_order_client_create_key
        CHECK (create_idempotency_key IS NULL OR created_by_client_id IS NOT NULL);

-- Leads with the member, so it also covers the foreign key and the per-member counts of the caps.
ALTER TABLE job_order
    ADD CONSTRAINT uq_job_order_client_create
        UNIQUE (created_by_member_id, created_by_client_id, create_idempotency_key);

COMMENT ON COLUMN job_order.created_by_client_id IS
    'The registry client an order created through the exchange was created by; NULL for an order created in the Basetool. Never changes (REQ-OXCH-004).';

COMMENT ON COLUMN job_order.created_by_member_id IS
    'The member who created the order through a client; NULL for a web create and after the account is deleted (REQ-OXCH-004).';

COMMENT ON COLUMN job_order.create_idempotency_key IS
    'The client''s Idempotency-Key of the create; a create under a key the client already created an order of the member with answers that order (REQ-OXCH-004).';

ALTER TABLE exchange_client_capability DROP CONSTRAINT ck_exchange_client_capability;

ALTER TABLE exchange_client_capability ADD CONSTRAINT ck_exchange_client_capability CHECK (capability IN (
    'exchange.connect',
    'exchange.blueprints.read',
    'exchange.blueprints.write',
    'exchange.stock.read',
    'exchange.stock.write',
    'exchange.hangar.read',
    'exchange.hangar.write',
    'exchange.demand.read',
    'exchange.demand.details',
    'exchange.orgunits.read',
    'exchange.orgstock.read',
    'exchange.orgstock.details',
    'exchange.orgstock.write',
    'exchange.refinery.read',
    'exchange.refinery.write',
    'exchange.refinery.store',
    'exchange.drafts.blueprints',
    'exchange.drafts.refinery',
    'exchange.bank.read',
    'exchange.bank.request',
    'exchange.bank.queue',
    'exchange.patch.read',
    'exchange.patch.write',
    'exchange.joborders.read',
    'exchange.joborders.write'));

ALTER TABLE exchange_change DROP CONSTRAINT ck_exchange_change_resource;

ALTER TABLE exchange_change
    ADD CONSTRAINT ck_exchange_change_resource
        CHECK (resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK', 'BANK_REQUEST', 'REFINERY_ORDER',
                            'JOB_ORDER'));

-- VARCHAR(63) matches exchange_client.client_id, the longest id the registry admits.
ALTER TABLE audit_event
    ALTER COLUMN client_id TYPE VARCHAR(63);

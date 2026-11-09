CREATE TABLE exchange_refinery_order_link (
    id                UUID PRIMARY KEY,
    user_id           UUID                     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    client_id         VARCHAR(64)              NOT NULL,
    client_key        VARCHAR(128)             NOT NULL,
    refinery_order_id UUID                     NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uk_exchange_refinery_order_link_key UNIQUE (user_id, client_id, client_key),
    CONSTRAINT uk_exchange_refinery_order_link_order UNIQUE (refinery_order_id)
);

COMMENT ON TABLE exchange_refinery_order_link IS
    'The client key a creating exchange client gave a refinery order (REQ-XCH-052). The order id carries no foreign key, so a tombstone still names the key after the order is gone.';

ALTER TABLE exchange_journal DROP CONSTRAINT ck_exchange_journal_resource;

ALTER TABLE exchange_journal ADD CONSTRAINT ck_exchange_journal_resource
    CHECK (resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK', 'REFINERY_ORDER'));

ALTER TABLE exchange_journal DROP CONSTRAINT ck_exchange_journal_action;

ALTER TABLE exchange_journal ADD CONSTRAINT ck_exchange_journal_action
    CHECK (action IN ('BLUEPRINT_ADD', 'BLUEPRINT_REMOVE', 'STOCK_SET_QUANTITY', 'STOCK_SHARE',
        'STOCK_UNSHARE', 'SHIP_LINK', 'SHIP_UPSERT', 'SHIP_REMOVE', 'ORG_STOCK_BOOK_IN',
        'ORG_STOCK_BOOK_OUT', 'ORG_STOCK_SELL', 'ORG_STOCK_MOVE_LOCATION', 'ORG_STOCK_MOVE_HOLDER',
        'ORG_STOCK_MOVE_POOL', 'ORG_STOCK_NOTE', 'ORG_STOCK_STOLEN', 'ORG_STOCK_EARMARK_ADD',
        'ORG_STOCK_EARMARK_CHANGE', 'ORG_STOCK_EARMARK_REMOVE', 'REFINERY_CREATE',
        'REFINERY_UPDATE', 'REFINERY_GOOD_ADD', 'REFINERY_GOOD_UPDATE', 'REFINERY_GOOD_REMOVE'));

ALTER TABLE exchange_bulk_undo_run DROP CONSTRAINT ck_exchange_bulk_undo_run_resource;

ALTER TABLE exchange_bulk_undo_run ADD CONSTRAINT ck_exchange_bulk_undo_run_resource
    CHECK (resource IS NULL
        OR resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK', 'REFINERY_ORDER'));

ALTER TABLE exchange_bulk_undo_skip DROP CONSTRAINT ck_exchange_bulk_undo_skip_resource;

ALTER TABLE exchange_bulk_undo_skip ADD CONSTRAINT ck_exchange_bulk_undo_skip_resource
    CHECK (resource IS NULL
        OR resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK', 'REFINERY_ORDER'));

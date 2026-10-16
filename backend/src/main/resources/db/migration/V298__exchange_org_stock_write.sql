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
    'exchange.orgunits.read',
    'exchange.orgstock.read',
    'exchange.orgstock.details',
    'exchange.orgstock.write',
    'exchange.drafts.blueprints',
    'exchange.drafts.refinery'));

ALTER TABLE exchange_journal DROP CONSTRAINT ck_exchange_journal_resource;

ALTER TABLE exchange_journal ADD CONSTRAINT ck_exchange_journal_resource
    CHECK (resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK'));

ALTER TABLE exchange_journal DROP CONSTRAINT ck_exchange_journal_action;

ALTER TABLE exchange_journal ADD CONSTRAINT ck_exchange_journal_action
    CHECK (action IN ('BLUEPRINT_ADD', 'BLUEPRINT_REMOVE', 'STOCK_SET_QUANTITY', 'SHIP_LINK',
        'SHIP_UPSERT', 'SHIP_REMOVE', 'ORG_STOCK_BOOK_IN', 'ORG_STOCK_BOOK_OUT'));

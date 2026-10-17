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
    'exchange.drafts.blueprints',
    'exchange.drafts.refinery'));

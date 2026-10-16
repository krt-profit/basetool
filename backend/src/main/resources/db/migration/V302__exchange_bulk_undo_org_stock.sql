ALTER TABLE exchange_bulk_undo_run DROP CONSTRAINT ck_exchange_bulk_undo_run_resource;

ALTER TABLE exchange_bulk_undo_run ADD CONSTRAINT ck_exchange_bulk_undo_run_resource
    CHECK (resource IS NULL OR resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK'));

ALTER TABLE exchange_bulk_undo_skip DROP CONSTRAINT ck_exchange_bulk_undo_skip_resource;

ALTER TABLE exchange_bulk_undo_skip ADD CONSTRAINT ck_exchange_bulk_undo_skip_resource
    CHECK (resource IS NULL OR resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK'));

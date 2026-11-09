ALTER TABLE exchange_bulk_undo_skip DROP CONSTRAINT ck_exchange_bulk_undo_skip_reason;

ALTER TABLE exchange_bulk_undo_skip ADD CONSTRAINT ck_exchange_bulk_undo_skip_reason
    CHECK (reason IN ('CHANGED_AFTERWARDS', 'GONE', 'FAILED', 'STOCK_BOOKED'));

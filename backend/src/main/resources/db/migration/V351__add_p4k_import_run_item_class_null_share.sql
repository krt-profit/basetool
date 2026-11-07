-- The share of catalogue items whose itemClass is null, per APPLY run (REQ-OBS-022). NULL on a run
-- recorded before the column existed and on a run whose catalogue lists no item.
ALTER TABLE p4k_import_run ADD COLUMN item_class_null_share DOUBLE PRECISION;
ALTER TABLE p4k_import_run
    ADD CONSTRAINT chk_p4k_import_run_item_class_null_share
        CHECK (item_class_null_share BETWEEN 0 AND 1);

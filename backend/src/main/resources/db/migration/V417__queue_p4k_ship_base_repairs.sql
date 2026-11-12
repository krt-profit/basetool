ALTER TABLE p4k_review_case
    ADD COLUMN ship_repair_row_id UUID,
    ADD COLUMN ship_repair_variant_guid UUID,
    ADD COLUMN ship_repair_variant_class VARCHAR(255),
    ADD COLUMN ship_repair_base_class VARCHAR(255),
    ADD COLUMN ship_repair_by UUID;

ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_ship_repair CHECK (
        num_nonnulls(ship_repair_row_id, ship_repair_variant_guid, ship_repair_variant_class,
            ship_repair_base_class, ship_repair_by) = 0
        OR (kind = 'SHIP' AND reason = 'GUID_CONFLICT' AND status = 'OPEN'
            AND ship_repair_row_id IS NOT NULL AND ship_repair_variant_guid IS NOT NULL
            AND ship_repair_variant_class IS NOT NULL AND ship_repair_base_class IS NOT NULL
            AND ship_repair_by IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_p4k_review_case_ship_repair_row
    ON p4k_review_case (ship_repair_row_id)
    WHERE ship_repair_row_id IS NOT NULL;

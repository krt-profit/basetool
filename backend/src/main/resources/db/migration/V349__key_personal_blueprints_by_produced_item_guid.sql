-- REQ-P4K-029, REQ-INV-070, ADR-0343: the P4K import stores the DataForge GUID of the item a
-- blueprint produces; a product keyed by that GUID holds its lower-case text in product_key.

ALTER TABLE blueprint ADD COLUMN produced_item_guid UUID;

CREATE INDEX idx_blueprint_produced_item_guid ON blueprint (produced_item_guid);

-- A personal-blueprint name key that resolves to no produced-item GUID or to several is a review
-- case; the case's guid is a name-based UUID of that name key.
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_reason;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_reason
        CHECK (reason IN ('UNCLEAR_PLAYABILITY', 'SEEDED_NOT_CREATABLE', 'GUID_CONFLICT',
                          'NAME_CONFLICT', 'PRODUCT_KEY_UNRESOLVED', 'PRODUCT_KEY_AMBIGUOUS'));

-- RESOLVED: a later run resolved the case itself, so no admin resolved it.
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_resolution;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_resolution CHECK (resolution IN ('KEEP', 'HIDE', 'RESOLVED'));
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_resolved;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_resolved
        CHECK ((status = 'OPEN' AND resolution IS NULL AND resolved_by IS NULL AND resolved_at IS NULL)
            OR (status = 'REVIEWED' AND resolution IN ('KEEP', 'HIDE') AND resolved_by IS NOT NULL
                AND resolved_at IS NOT NULL)
            OR (status = 'REVIEWED' AND resolution = 'RESOLVED' AND resolved_by IS NULL
                AND resolved_at IS NOT NULL));

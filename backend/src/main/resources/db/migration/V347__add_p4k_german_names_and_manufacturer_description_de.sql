-- German names written by the P4K import only (REQ-P4K-023) and the manufacturer's German
-- description, a game text (REQ-P4K-020, ADR-0338).
ALTER TABLE manufacturer ADD COLUMN name_de VARCHAR(255);
ALTER TABLE game_item    ADD COLUMN name_de VARCHAR(255);
ALTER TABLE ship_type    ADD COLUMN name_de VARCHAR(255);
ALTER TABLE material     ADD COLUMN name_de VARCHAR(255);

ALTER TABLE manufacturer ADD COLUMN description_de TEXT;

-- NAME_CONFLICT: the P4K name of a manufacturer is held by another row (REQ-P4K-020).
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_reason;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_reason
        CHECK (reason IN ('UNCLEAR_PLAYABILITY', 'SEEDED_NOT_CREATABLE', 'GUID_CONFLICT',
                          'NAME_CONFLICT'));

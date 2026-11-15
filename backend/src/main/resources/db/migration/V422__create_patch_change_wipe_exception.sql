CREATE TABLE patch_change_wipe_exception (
    patch_change_id UUID        NOT NULL REFERENCES patch_change (id) ON DELETE CASCADE,
    target          VARCHAR(16) NOT NULL,
    catalogue_id    UUID        NOT NULL,
    mode            VARCHAR(8)  NOT NULL,
    PRIMARY KEY (patch_change_id, target, catalogue_id),
    CONSTRAINT ck_patch_change_wipe_exception_target CHECK (target IN ('GAME_ITEM', 'MATERIAL')),
    CONSTRAINT ck_patch_change_wipe_exception_mode CHECK (mode IN ('KEEP', 'WIPE'))
);

COMMENT ON TABLE patch_change_wipe_exception IS
    'Single game items or materials a patch change kept or wiped beside its wipe groups (REQ-PATCH-011).';
COMMENT ON COLUMN patch_change_wipe_exception.catalogue_id IS
    'Id of the game item or material the exception names; no foreign key, the record outlives a catalogue entry.';

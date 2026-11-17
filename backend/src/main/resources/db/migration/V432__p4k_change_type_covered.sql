ALTER TABLE p4k_import_change DROP CONSTRAINT chk_p4k_import_change_type;
ALTER TABLE p4k_import_change
    ADD CONSTRAINT chk_p4k_import_change_type
        CHECK (change_type IN ('ADDED', 'UNMATCHED', 'COVERED', 'REVIEW', 'IDENTITY_MISMATCH',
                               'UNKNOWN_VALUE', 'CHANGED', 'VANISHED', 'REAPPEARED', 'SWEEP_SKIPPED',
                               'LOADOUT_ISSUES'));

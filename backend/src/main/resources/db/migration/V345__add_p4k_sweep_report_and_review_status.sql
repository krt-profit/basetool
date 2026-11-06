-- The P4K orphan sweep, its mass guard, the full change report and the lasting review status
-- (REQ-P4K-011…016, ADR-0336).

-- p4k_hidden_at: an admin hid the row through a review case; no later match clears it.
ALTER TABLE game_item    ADD COLUMN p4k_hidden_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE ship_type    ADD COLUMN p4k_hidden_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE manufacturer ADD COLUMN p4k_hidden_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE material     ADD COLUMN p4k_hidden_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE blueprint    ADD COLUMN p4k_hidden_at TIMESTAMP WITH TIME ZONE;

-- position keeps the order the run found its rows in; still_live_elsewhere flags a VANISHED row
-- UEX or the SC Wiki still list as live.
ALTER TABLE p4k_import_change DROP CONSTRAINT chk_p4k_import_change_type;
ALTER TABLE p4k_import_change
    ADD CONSTRAINT chk_p4k_import_change_type
        CHECK (change_type IN ('ADDED', 'UNMATCHED', 'REVIEW', 'IDENTITY_MISMATCH', 'UNKNOWN_VALUE',
                               'CHANGED', 'VANISHED', 'REAPPEARED', 'SWEEP_SKIPPED'));
ALTER TABLE p4k_import_change ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
ALTER TABLE p4k_import_change ADD COLUMN still_live_elsewhere BOOLEAN NOT NULL DEFAULT FALSE;

DROP INDEX idx_p4k_import_change_run;
CREATE INDEX idx_p4k_import_change_run ON p4k_import_change (run_id, kind, change_type, position);

-- NULL on the runs recorded before the sweep existed.
ALTER TABLE p4k_import_run_kind ADD COLUMN sweep VARCHAR(24);
ALTER TABLE p4k_import_run_kind
    ADD CONSTRAINT chk_p4k_import_run_kind_sweep
        CHECK (sweep IN ('SWEPT', 'SKIPPED_PARTIAL', 'SKIPPED_EMPTY', 'SKIPPED_DUPLICATE_GUID',
                         'SKIPPED_MASS_GUARD'));

-- A case opened outside a P4K run (a GUID conflict the SC Wiki / UEX reconciliation meets) has no
-- run; other_row_id names the second row of a GUID conflict.
ALTER TABLE p4k_review_case ALTER COLUMN first_run_id DROP NOT NULL;
ALTER TABLE p4k_review_case ALTER COLUMN last_run_id DROP NOT NULL;
ALTER TABLE p4k_review_case ADD COLUMN other_row_id UUID;
ALTER TABLE p4k_review_case ADD COLUMN resolution VARCHAR(8);
ALTER TABLE p4k_review_case ADD COLUMN resolved_by UUID;
ALTER TABLE p4k_review_case ADD COLUMN resolved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_resolution CHECK (resolution IN ('KEEP', 'HIDE'));
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_resolved
        CHECK ((status = 'OPEN' AND resolution IS NULL AND resolved_by IS NULL AND resolved_at IS NULL)
            OR (status = 'REVIEWED' AND resolution IS NOT NULL AND resolved_by IS NOT NULL
                AND resolved_at IS NOT NULL));

CREATE INDEX idx_p4k_review_case_status ON p4k_review_case (status, created_at);

-- The APPLY's "apply marking anyway" box (REQ-P4K-012).
ALTER TABLE p4k_import_job ADD COLUMN override_mass_guard BOOLEAN NOT NULL DEFAULT FALSE;

-- A preview's change report, gzipped JSON; it expires with its job (REQ-P4K-015).
ALTER TABLE p4k_import_job_payload ADD COLUMN preview_report BYTEA;

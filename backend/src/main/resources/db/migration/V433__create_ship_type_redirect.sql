CREATE TABLE ship_type_redirect (
    id               UUID PRIMARY KEY,
    canonical_id     UUID                     NOT NULL REFERENCES ship_type (id),
    retired_snapshot JSONB                    NOT NULL,
    version          BIGINT                   NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_ship_type_redirect_distinct CHECK (id <> canonical_id)
);

CREATE INDEX idx_ship_type_redirect_canonical ON ship_type_redirect (canonical_id);

ALTER TABLE p4k_import_change DROP CONSTRAINT chk_p4k_import_change_type;
ALTER TABLE p4k_import_change
    ADD CONSTRAINT chk_p4k_import_change_type
        CHECK (change_type IN ('ADDED', 'UNMATCHED', 'COVERED', 'MERGED', 'REVIEW',
                               'IDENTITY_MISMATCH', 'UNKNOWN_VALUE', 'CHANGED', 'VANISHED',
                               'REAPPEARED', 'SWEEP_SKIPPED', 'LOADOUT_ISSUES'));

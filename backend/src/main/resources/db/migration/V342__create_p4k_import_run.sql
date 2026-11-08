-- P4K import runs (REQ-P4K-004, REQ-P4K-005, ADR-0335): one row per successful APPLY, the
-- counts and completeness per kind, the change report and the review cases (REQ-P4K-007…010).
-- Runs, report rows and review cases are never pruned.

CREATE TABLE p4k_import_run (
    id                   UUID                     PRIMARY KEY,
    job_id               UUID                     REFERENCES p4k_import_job (id) ON DELETE SET NULL,
    channel              VARCHAR(32)              NOT NULL,
    build_branch         VARCHAR(128)             NOT NULL,
    build_version        VARCHAR(64)              NOT NULL,
    change_number        BIGINT                   NOT NULL,
    build_id             VARCHAR(64)              NOT NULL,
    patch_version        VARCHAR(32),
    patch_version_origin VARCHAR(24),
    tool_version         VARCHAR(64),
    exported_at          TIMESTAMP WITH TIME ZONE,
    created_by           UUID                     NOT NULL,
    applied_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT                   NOT NULL DEFAULT 0,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_p4k_import_run_patch_version_origin
        CHECK (patch_version_origin IN ('PUBLIC_PATCH', 'BRANCH_LOWER_BOUND')),
    CONSTRAINT chk_p4k_import_run_patch_version_pair
        CHECK ((patch_version IS NULL) = (patch_version_origin IS NULL))
);

-- The build-order check reads the highest applied change number.
CREATE INDEX idx_p4k_import_run_change_number ON p4k_import_run (change_number DESC);
CREATE INDEX idx_p4k_import_run_job ON p4k_import_run (job_id);

CREATE TABLE p4k_import_run_kind (
    run_id              UUID        NOT NULL REFERENCES p4k_import_run (id) ON DELETE CASCADE,
    kind                VARCHAR(32) NOT NULL,
    completeness        VARCHAR(16) NOT NULL,
    completeness_filter TEXT,
    matched             INTEGER     NOT NULL DEFAULT 0,
    unmatched           INTEGER     NOT NULL DEFAULT 0,
    added               INTEGER     NOT NULL DEFAULT 0,
    vanished            INTEGER     NOT NULL DEFAULT 0,
    reappeared          INTEGER     NOT NULL DEFAULT 0,
    changed             INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id, kind),
    CONSTRAINT chk_p4k_import_run_kind_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT')),
    CONSTRAINT chk_p4k_import_run_kind_completeness
        CHECK (completeness IN ('COMPLETE', 'PARTIAL'))
);

-- One row per change of a run (REQ-P4K-014). class_name, name_en and record_path identify an
-- UNMATCHED record. No column holds a game text.
CREATE TABLE p4k_import_change (
    id          UUID                     PRIMARY KEY,
    run_id      UUID                     NOT NULL REFERENCES p4k_import_run (id) ON DELETE CASCADE,
    kind        VARCHAR(32)              NOT NULL,
    guid        UUID,
    change_type VARCHAR(32)              NOT NULL,
    field_name  VARCHAR(64),
    old_value   TEXT,
    new_value   TEXT,
    class_name  VARCHAR(255),
    name_en     VARCHAR(255),
    record_path TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_p4k_import_change_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT')),
    CONSTRAINT chk_p4k_import_change_type
        CHECK (change_type IN ('ADDED', 'UNMATCHED', 'REVIEW', 'IDENTITY_MISMATCH', 'UNKNOWN_VALUE'))
);

CREATE INDEX idx_p4k_import_change_run ON p4k_import_change (run_id, kind, change_type);

-- A review case is one lasting entry per kind, GUID and reason (REQ-P4K-016), and row_id names the
-- Basetool row when there is one.
CREATE TABLE p4k_review_case (
    id           UUID                     PRIMARY KEY,
    kind         VARCHAR(32)              NOT NULL,
    guid         UUID                     NOT NULL,
    reason       VARCHAR(32)              NOT NULL,
    status       VARCHAR(16)              NOT NULL DEFAULT 'OPEN',
    row_id       UUID,
    first_run_id UUID                     NOT NULL REFERENCES p4k_import_run (id),
    last_run_id  UUID                     NOT NULL REFERENCES p4k_import_run (id),
    version      BIGINT                   NOT NULL DEFAULT 0,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_p4k_review_case UNIQUE (kind, guid, reason),
    CONSTRAINT chk_p4k_review_case_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT')),
    CONSTRAINT chk_p4k_review_case_reason
        CHECK (reason IN ('UNCLEAR_PLAYABILITY', 'SEEDED_NOT_CREATABLE', 'GUID_CONFLICT')),
    CONSTRAINT chk_p4k_review_case_status
        CHECK (status IN ('OPEN', 'REVIEWED'))
);

CREATE INDEX idx_p4k_review_case_first_run ON p4k_review_case (first_run_id);
CREATE INDEX idx_p4k_review_case_last_run ON p4k_review_case (last_run_id);

ALTER TABLE game_item    ADD COLUMN p4k_seen_run_id UUID REFERENCES p4k_import_run (id);
ALTER TABLE ship_type    ADD COLUMN p4k_seen_run_id UUID REFERENCES p4k_import_run (id);
ALTER TABLE manufacturer ADD COLUMN p4k_seen_run_id UUID REFERENCES p4k_import_run (id);
ALTER TABLE material     ADD COLUMN p4k_seen_run_id UUID REFERENCES p4k_import_run (id);
ALTER TABLE blueprint    ADD COLUMN p4k_seen_run_id UUID REFERENCES p4k_import_run (id);

CREATE INDEX idx_game_item_p4k_seen_run ON game_item (p4k_seen_run_id);
CREATE INDEX idx_ship_type_p4k_seen_run ON ship_type (p4k_seen_run_id);
CREATE INDEX idx_manufacturer_p4k_seen_run ON manufacturer (p4k_seen_run_id);
CREATE INDEX idx_material_p4k_seen_run ON material (p4k_seen_run_id);
CREATE INDEX idx_blueprint_p4k_seen_run ON blueprint (p4k_seen_run_id);

-- A job carries the per-run "create new items and ships" box (REQ-P4K-009) and no seeding flag.
ALTER TABLE p4k_import_job DROP COLUMN seed_new;
ALTER TABLE p4k_import_job ADD COLUMN create_items_and_ships BOOLEAN NOT NULL DEFAULT FALSE;

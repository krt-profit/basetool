CREATE TABLE external_sync_run (
    id UUID PRIMARY KEY,
    version BIGINT,
    source_system VARCHAR(16) NOT NULL,
    aggregate VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    game_version VARCHAR(128),
    fetched BIGINT NOT NULL DEFAULT 0,
    valid BIGINT NOT NULL DEFAULT 0,
    applied BIGINT NOT NULL DEFAULT 0,
    skipped BIGINT NOT NULL DEFAULT 0,
    failed BIGINT NOT NULL DEFAULT 0,
    census_complete BOOLEAN NOT NULL DEFAULT FALSE,
    sweep_outcome VARCHAR(16) NOT NULL,
    CONSTRAINT ck_external_sync_run_source CHECK (source_system IN ('UEX', 'SCWIKI', 'P4K')),
    CONSTRAINT ck_external_sync_run_status
        CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'UNCHANGED')),
    CONSTRAINT ck_external_sync_run_sweep
        CHECK (sweep_outcome IN ('NOT_REQUIRED', 'PERFORMED', 'SKIPPED')),
    CONSTRAINT ck_external_sync_run_counts
        CHECK (fetched >= 0 AND valid >= 0 AND applied >= 0 AND skipped >= 0 AND failed >= 0),
    CONSTRAINT ck_external_sync_run_finish CHECK (finished_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX idx_external_sync_run_source_started
    ON external_sync_run (source_system, started_at DESC);
CREATE INDEX idx_external_sync_run_source_status_finished
    ON external_sync_run (source_system, status, finished_at DESC);

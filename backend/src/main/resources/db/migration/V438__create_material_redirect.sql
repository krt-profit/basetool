CREATE TABLE material_redirect (
    id               UUID PRIMARY KEY,
    canonical_id     UUID                     NOT NULL REFERENCES material (id),
    retired_snapshot JSONB                    NOT NULL,
    uex_commodity_id INTEGER,
    version          BIGINT                   NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_material_redirect_distinct CHECK (id <> canonical_id)
);

CREATE INDEX idx_material_redirect_canonical ON material_redirect (canonical_id);
CREATE INDEX idx_material_redirect_uex_commodity ON material_redirect (uex_commodity_id);

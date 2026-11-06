CREATE TABLE game_item_redirect (
    id UUID PRIMARY KEY,
    canonical_id UUID NOT NULL REFERENCES game_item(id),
    retired_snapshot JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_game_item_redirect_distinct CHECK (id <> canonical_id)
);

CREATE INDEX idx_game_item_redirect_canonical ON game_item_redirect(canonical_id);

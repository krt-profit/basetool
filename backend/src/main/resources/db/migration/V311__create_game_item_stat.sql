CREATE TABLE game_item_stat (
    id           UUID PRIMARY KEY,
    version      BIGINT                   NOT NULL DEFAULT 0,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    game_item_id UUID                     NOT NULL REFERENCES game_item(id) ON DELETE CASCADE,
    property     VARCHAR(64)              NOT NULL,
    base_value   DOUBLE PRECISION         NOT NULL,
    CONSTRAINT uk_game_item_stat_item_property UNIQUE (game_item_id, property)
);

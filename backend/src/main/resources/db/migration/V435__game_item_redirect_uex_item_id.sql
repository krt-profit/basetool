ALTER TABLE game_item_redirect ADD COLUMN uex_item_id INTEGER;

CREATE INDEX idx_game_item_redirect_uex_item ON game_item_redirect (uex_item_id);

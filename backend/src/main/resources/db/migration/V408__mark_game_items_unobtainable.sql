-- An admin's hide of a game item as not obtainable in the game (REQ-P4K-048, ADR-0373); no import,
-- sync, sweep or merge writes it.
ALTER TABLE game_item ADD COLUMN unobtainable_since TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_game_item_unobtainable_since ON game_item (unobtainable_since)
  WHERE unobtainable_since IS NOT NULL;

ALTER TABLE game_item ADD COLUMN scwiki_name VARCHAR(255), ADD COLUMN uex_name VARCHAR(255);
ALTER TABLE ship_type ALTER COLUMN health TYPE DOUBLE PRECISION,
    ALTER COLUMN shield_hp TYPE DOUBLE PRECISION;
ALTER TABLE material ADD COLUMN wiki_mining_data JSONB;
ALTER TABLE game_item ADD COLUMN wiki_metadata JSONB;
ALTER TABLE ship_type ADD COLUMN scwiki_game_version_seen VARCHAR(100);

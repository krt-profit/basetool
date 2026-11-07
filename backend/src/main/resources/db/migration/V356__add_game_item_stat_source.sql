-- REQ-P4K-030, ADR-0342: every stored blueprint stat base value names its source; the P4K value
-- wins and the SC Wiki fills gaps. The rows this migration finds are SC Wiki values.
ALTER TABLE game_item_stat ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'SCWIKI';
ALTER TABLE game_item_stat ALTER COLUMN source DROP DEFAULT;
ALTER TABLE game_item_stat
    ADD CONSTRAINT chk_game_item_stat_source CHECK (source IN ('P4K', 'SCWIKI'));

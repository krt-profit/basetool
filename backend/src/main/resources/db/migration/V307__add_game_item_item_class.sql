ALTER TABLE game_item ADD COLUMN item_class VARCHAR(16);

ALTER TABLE game_item ADD CONSTRAINT ck_game_item_item_class
    CHECK (item_class IS NULL OR item_class IN ('MILITARY', 'CIVILIAN', 'INDUSTRIAL', 'STEALTH',
        'COMPETITION'));

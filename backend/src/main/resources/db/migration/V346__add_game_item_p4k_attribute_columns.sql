-- The P4K's own value of each game_item attribute it leads (REQ-P4K-017…019, ADR-0337). The
-- displayed column holds the P4K value where it is non-null; the SC Wiki and UEX write a displayed
-- column only where its p4k_* column is null.

ALTER TABLE game_item ADD COLUMN p4k_name            VARCHAR(255);
ALTER TABLE game_item ADD COLUMN p4k_manufacturer_id UUID REFERENCES manufacturer (id);
ALTER TABLE game_item ADD COLUMN p4k_type            VARCHAR(128);
ALTER TABLE game_item ADD COLUMN p4k_sub_type        VARCHAR(128);
ALTER TABLE game_item ADD COLUMN p4k_size            INTEGER;
-- The raw DataForge grade of every matched item, whatever its type; a fact value with no UI.
ALTER TABLE game_item ADD COLUMN p4k_grade_raw       INTEGER;
ALTER TABLE game_item ADD COLUMN p4k_item_class      VARCHAR(16);
ALTER TABLE game_item ADD COLUMN p4k_mass            DOUBLE PRECISION;

ALTER TABLE game_item ADD CONSTRAINT ck_game_item_p4k_item_class
    CHECK (p4k_item_class IN ('MILITARY', 'CIVILIAN', 'INDUSTRIAL', 'STEALTH', 'COMPETITION'));

CREATE INDEX idx_game_item_p4k_manufacturer ON game_item (p4k_manufacturer_id);

-- A row the P4K seeded holds the P4K's name, manufacturer and mass in its displayed columns.
UPDATE game_item
SET p4k_name            = name,
    p4k_manufacturer_id = manufacturer_id,
    p4k_mass            = mass
WHERE source_systems = 'P4K';

-- A row an earlier P4K import matched (p4k_synced_at) holds the name that import led, so p4k_name
-- keeps the SC Wiki and UEX name guards on it until an APPLY mirrors the column.
UPDATE game_item
SET p4k_name = name
WHERE p4k_synced_at IS NOT NULL
  AND p4k_name IS NULL;

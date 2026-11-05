-- REQ-P4K-039, ADR-0345 decisions 8 and 10 (step A): the personal inventory references its place
-- through location.id instead of a UEX id plus a type discriminator.

ALTER TABLE location ADD COLUMN reference_only BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN location.reference_only IS
    'TRUE for a place kept only so personal-inventory rows resolve. No storage-place picker, list or exchange read offers it. The UEX sync clears it once the city or station is live.';

-- A referenced city or station without a location row gets one. A name already taken falls back
-- to the name with its UEX id, and a fallback that collides too leaves the row unresolved.
INSERT INTO location (id, version, created_at, updated_at, name, hidden, is_home_location,
                      city_id, reference_only)
SELECT gen_random_uuid(), 0, now(), now(), c.name, FALSE, FALSE, c.id, TRUE
FROM city c
WHERE c.name IS NOT NULL
  AND EXISTS (SELECT 1 FROM personal_inventory_item p
              WHERE p.location_type = 'CITY' AND p.location_uex_id = c.id_city)
  AND NOT EXISTS (SELECT 1 FROM location l WHERE l.city_id = c.id)
ON CONFLICT (name) DO NOTHING;

INSERT INTO location (id, version, created_at, updated_at, name, hidden, is_home_location,
                      city_id, reference_only)
SELECT gen_random_uuid(), 0, now(), now(), c.name || ' (UEX city ' || c.id_city || ')', FALSE,
       FALSE, c.id, TRUE
FROM city c
WHERE c.name IS NOT NULL
  AND EXISTS (SELECT 1 FROM personal_inventory_item p
              WHERE p.location_type = 'CITY' AND p.location_uex_id = c.id_city)
  AND NOT EXISTS (SELECT 1 FROM location l WHERE l.city_id = c.id)
ON CONFLICT (name) DO NOTHING;

INSERT INTO location (id, version, created_at, updated_at, name, hidden, is_home_location,
                      space_station_id, reference_only)
SELECT gen_random_uuid(), 0, now(), now(), s.name, FALSE, FALSE, s.id, TRUE
FROM space_station s
WHERE s.name IS NOT NULL
  AND EXISTS (SELECT 1 FROM personal_inventory_item p
              WHERE p.location_type = 'SPACE_STATION' AND p.location_uex_id = s.id_space_station)
  AND NOT EXISTS (SELECT 1 FROM location l WHERE l.space_station_id = s.id)
ON CONFLICT (name) DO NOTHING;

INSERT INTO location (id, version, created_at, updated_at, name, hidden, is_home_location,
                      space_station_id, reference_only)
SELECT gen_random_uuid(), 0, now(), now(),
       s.name || ' (UEX space station ' || s.id_space_station || ')', FALSE, FALSE, s.id, TRUE
FROM space_station s
WHERE s.name IS NOT NULL
  AND EXISTS (SELECT 1 FROM personal_inventory_item p
              WHERE p.location_type = 'SPACE_STATION' AND p.location_uex_id = s.id_space_station)
  AND NOT EXISTS (SELECT 1 FROM location l WHERE l.space_station_id = s.id)
ON CONFLICT (name) DO NOTHING;

ALTER TABLE personal_inventory_item ADD COLUMN location_id UUID;

-- Several rows on one place: an offered one wins over a reference-only or hidden one.
UPDATE personal_inventory_item p
SET location_id = (SELECT l.id FROM location l JOIN city c ON c.id = l.city_id
                   WHERE c.id_city = p.location_uex_id
                   ORDER BY l.reference_only, l.hidden, l.name LIMIT 1)
WHERE p.location_type = 'CITY';

UPDATE personal_inventory_item p
SET location_id = (SELECT l.id FROM location l JOIN space_station s ON s.id = l.space_station_id
                   WHERE s.id_space_station = p.location_uex_id
                   ORDER BY l.reference_only, l.hidden, l.name LIMIT 1)
WHERE p.location_type = 'SPACE_STATION';

ALTER TABLE personal_inventory_item
    ADD CONSTRAINT fk_personal_inventory_item_location
        FOREIGN KEY (location_id) REFERENCES location (id) ON DELETE SET NULL;

CREATE INDEX idx_personal_inventory_item_location ON personal_inventory_item (location_id);

ALTER TABLE personal_inventory_item DROP CONSTRAINT ck_personal_inventory_item_location_type;

ALTER TABLE personal_inventory_item DROP COLUMN location_uex_id;

ALTER TABLE personal_inventory_item DROP COLUMN location_type;

COMMENT ON COLUMN personal_inventory_item.location_id IS
    'The storage place (location.id). NULL when the place no longer resolves, location_name_snapshot still names it.';

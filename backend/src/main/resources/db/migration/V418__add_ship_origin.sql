ALTER TABLE ship ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE ship ADD COLUMN origin_locked BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ship ADD CONSTRAINT ck_ship_origin
    CHECK (origin IN ('PLEDGE', 'IN_GAME', 'UNKNOWN'));

-- Every row is written once, so the exchange ship feed re-announces each ship with its origin.
UPDATE ship
   SET origin = CASE WHEN pledge_id IS NOT NULL OR production_status IS NOT NULL
                     THEN 'PLEDGE' ELSE 'UNKNOWN' END,
       origin_locked = (pledge_id IS NOT NULL OR production_status IS NOT NULL);

COMMENT ON COLUMN ship.origin IS
    'Whether the ship was pledged, bought in-game or is of unknown origin, as the owner or an admin chose it; part of the row version (REQ-HANGAR-017).';
COMMENT ON COLUMN ship.origin_locked IS
    'Whether a file import or a pledge id fixed the origin to PLEDGE; written only by the import, outside the row version (REQ-HANGAR-017, ADR-0295).';

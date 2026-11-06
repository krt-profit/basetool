-- REQ-P4K-007: p4k_uuid is unique per kind. A GUID that several rows hold is cleared on all of
-- them first, and the next APPLY matches those rows again and sends an ambiguous match to review.

UPDATE game_item SET p4k_uuid = NULL
WHERE p4k_uuid IN (SELECT p4k_uuid FROM game_item WHERE p4k_uuid IS NOT NULL
                   GROUP BY p4k_uuid HAVING count(*) > 1);
CREATE UNIQUE INDEX uq_game_item_p4k_uuid ON game_item (p4k_uuid);

UPDATE ship_type SET p4k_uuid = NULL
WHERE p4k_uuid IN (SELECT p4k_uuid FROM ship_type WHERE p4k_uuid IS NOT NULL
                   GROUP BY p4k_uuid HAVING count(*) > 1);
CREATE UNIQUE INDEX uq_ship_type_p4k_uuid ON ship_type (p4k_uuid);

UPDATE manufacturer SET p4k_uuid = NULL
WHERE p4k_uuid IN (SELECT p4k_uuid FROM manufacturer WHERE p4k_uuid IS NOT NULL
                   GROUP BY p4k_uuid HAVING count(*) > 1);
CREATE UNIQUE INDEX uq_manufacturer_p4k_uuid ON manufacturer (p4k_uuid);

UPDATE material SET p4k_uuid = NULL
WHERE p4k_uuid IN (SELECT p4k_uuid FROM material WHERE p4k_uuid IS NOT NULL
                   GROUP BY p4k_uuid HAVING count(*) > 1);
CREATE UNIQUE INDEX uq_material_p4k_uuid ON material (p4k_uuid);

UPDATE blueprint SET p4k_uuid = NULL
WHERE p4k_uuid IN (SELECT p4k_uuid FROM blueprint WHERE p4k_uuid IS NOT NULL
                   GROUP BY p4k_uuid HAVING count(*) > 1);
CREATE UNIQUE INDEX uq_blueprint_p4k_uuid ON blueprint (p4k_uuid);

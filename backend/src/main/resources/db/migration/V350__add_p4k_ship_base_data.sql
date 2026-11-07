-- REQ-P4K-031: the ship base data the P4K import leads. crewSize, mass, hullHealth and cargoScu
-- land in the existing crew_max, mass, health and scu columns.
ALTER TABLE ship_type
    ADD COLUMN role                              VARCHAR(255),
    ADD COLUMN career                            VARCHAR(255),
    ADD COLUMN size_class                        INTEGER,
    ADD COLUMN vehicle_kind                      VARCHAR(16),
    ADD COLUMN insurance_restore_on_claim        BOOLEAN,
    ADD COLUMN insurance_ship_class_name         VARCHAR(255),
    ADD COLUMN insurance_claim_minutes           DOUBLE PRECISION,
    ADD COLUMN insurance_mandatory_claim_minutes DOUBLE PRECISION,
    ADD COLUMN insurance_expedite_fee            BIGINT,
    ADD CONSTRAINT ck_ship_type_vehicle_kind
        CHECK (vehicle_kind IN ('SPACESHIP', 'GROUND_VEHICLE')),
    ADD CONSTRAINT ck_ship_type_size_class CHECK (size_class >= 0);

-- REQ-P4K-032: the variant groups of the catalogue's ships, so a variant's class name without a
-- ship type of its own resolves to the ship type of its group base.
CREATE TABLE ship_type_p4k_variant
(
    variant_uuid UUID         NOT NULL PRIMARY KEY,
    class_name   VARCHAR(255) NOT NULL,
    base_uuid    UUID         NOT NULL
);
CREATE UNIQUE INDEX uq_ship_type_p4k_variant_class_name ON ship_type_p4k_variant (lower(class_name));

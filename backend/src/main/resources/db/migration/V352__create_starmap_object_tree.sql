-- REQ-P4K-034…038, ADR-0345 step B: the P4K starmap tree, the links of the nine UEX location tables
-- to it, and the storage place on a starmap node.

-- One row per P4K starmap object, keyed by its GUID. position_* are metres in the frame of the star
-- system container, all three set or all three null. name and name_de are game texts.
CREATE TABLE starmap_object (
    id                  UUID                     PRIMARY KEY,
    p4k_uuid            UUID                     NOT NULL,
    class_name          VARCHAR(255),
    record_path         TEXT,
    type                VARCHAR(64),
    parent_id           UUID                     REFERENCES starmap_object (id) ON DELETE SET NULL,
    star_system         VARCHAR(64),
    position_x          DOUBLE PRECISION,
    position_y          DOUBLE PRECISION,
    position_z          DOUBLE PRECISION,
    hide_in_starmap     BOOLEAN,
    name                TEXT,
    name_de             TEXT,
    visibility_override VARCHAR(8),
    p4k_synced_at       TIMESTAMP WITH TIME ZONE,
    p4k_seen_run_id     UUID                     REFERENCES p4k_import_run (id),
    p4k_deleted_at      TIMESTAMP WITH TIME ZONE,
    p4k_hidden_at       TIMESTAMP WITH TIME ZONE,
    version             BIGINT                   NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_starmap_object_p4k_uuid UNIQUE (p4k_uuid),
    CONSTRAINT ck_starmap_object_position
        CHECK ((position_x IS NULL AND position_y IS NULL AND position_z IS NULL)
            OR (position_x IS NOT NULL AND position_y IS NOT NULL AND position_z IS NOT NULL)),
    CONSTRAINT ck_starmap_object_visibility_override
        CHECK (visibility_override IN ('VISIBLE', 'HIDDEN'))
);

CREATE INDEX idx_starmap_object_parent ON starmap_object (parent_id);
CREATE INDEX idx_starmap_object_p4k_seen_run ON starmap_object (p4k_seen_run_id);

-- The UEX location tables link to the tree. starmap_link_manual marks an admin's link or clear,
-- which no APPLY overwrites.
ALTER TABLE star_system
    ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id) ON DELETE SET NULL,
    ADD COLUMN starmap_link_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orbit
    ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id) ON DELETE SET NULL,
    ADD COLUMN starmap_link_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE planet
    ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id) ON DELETE SET NULL,
    ADD COLUMN starmap_link_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE moon
    ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id) ON DELETE SET NULL,
    ADD COLUMN starmap_link_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE city
    ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id) ON DELETE SET NULL,
    ADD COLUMN starmap_link_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE outpost
    ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id) ON DELETE SET NULL,
    ADD COLUMN starmap_link_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE space_station
    ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id) ON DELETE SET NULL,
    ADD COLUMN starmap_link_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE poi
    ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id) ON DELETE SET NULL,
    ADD COLUMN starmap_link_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE terminal
    ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id) ON DELETE SET NULL,
    ADD COLUMN starmap_link_manual BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_star_system_starmap_object ON star_system (starmap_object_id);
CREATE INDEX idx_orbit_starmap_object ON orbit (starmap_object_id);
CREATE INDEX idx_planet_starmap_object ON planet (starmap_object_id);
CREATE INDEX idx_moon_starmap_object ON moon (starmap_object_id);
CREATE INDEX idx_city_starmap_object ON city (starmap_object_id);
CREATE INDEX idx_outpost_starmap_object ON outpost (starmap_object_id);
CREATE INDEX idx_space_station_starmap_object ON space_station (starmap_object_id);
CREATE INDEX idx_poi_starmap_object ON poi (starmap_object_id);
CREATE INDEX idx_terminal_starmap_object ON terminal (starmap_object_id);

-- A storage place on a starmap node an admin picked; one per node.
ALTER TABLE location ADD COLUMN starmap_object_id UUID REFERENCES starmap_object (id);
CREATE UNIQUE INDEX uq_location_starmap_object ON location (starmap_object_id);

-- A location names a city, a space station or a starmap node.
ALTER TABLE location
    ADD CONSTRAINT ck_location_place
        CHECK (city_id IS NOT NULL OR space_station_id IS NOT NULL OR starmap_object_id IS NOT NULL);

-- The starmap kind joins the run, the change report and the review list.
ALTER TABLE p4k_import_run_kind DROP CONSTRAINT chk_p4k_import_run_kind_kind;
ALTER TABLE p4k_import_run_kind
    ADD CONSTRAINT chk_p4k_import_run_kind_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'STARMAP_OBJECT'));
ALTER TABLE p4k_import_change DROP CONSTRAINT chk_p4k_import_change_kind;
ALTER TABLE p4k_import_change
    ADD CONSTRAINT chk_p4k_import_change_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'STARMAP_OBJECT'));
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_kind;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'STARMAP_OBJECT'));

-- A UEX location row the APPLY links to several starmap objects or to none; the case's guid is a
-- name-based UUID of the row.
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_reason;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_reason
        CHECK (reason IN ('UNCLEAR_PLAYABILITY', 'SEEDED_NOT_CREATABLE', 'GUID_CONFLICT',
                          'NAME_CONFLICT', 'PRODUCT_KEY_UNRESOLVED', 'PRODUCT_KEY_AMBIGUOUS',
                          'STARMAP_LINK_AMBIGUOUS', 'STARMAP_LINK_NOT_FOUND'));

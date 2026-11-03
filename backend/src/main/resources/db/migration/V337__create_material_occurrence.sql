-- Where a material can be found, as served by UEX /commodities (REQ-DATA-031).
-- Each row names exactly one location of one of the five UEX-keyed location tables.
CREATE TABLE material_occurrence (
    id             UUID PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMP WITH TIME ZONE,
    updated_at     TIMESTAMP WITH TIME ZONE,
    material_id    UUID NOT NULL REFERENCES material (id) ON DELETE CASCADE,
    star_system_id UUID REFERENCES star_system (id) ON DELETE CASCADE,
    planet_id      UUID REFERENCES planet (id) ON DELETE CASCADE,
    moon_id        UUID REFERENCES moon (id) ON DELETE CASCADE,
    orbit_id       UUID REFERENCES orbit (id) ON DELETE CASCADE,
    poi_id         UUID REFERENCES poi (id) ON DELETE CASCADE,
    CONSTRAINT ck_material_occurrence_one_location
        CHECK (num_nonnulls(star_system_id, planet_id, moon_id, orbit_id, poi_id) = 1)
);

CREATE INDEX idx_material_occurrence_material ON material_occurrence (material_id);

CREATE UNIQUE INDEX uq_material_occurrence_star_system
    ON material_occurrence (star_system_id, material_id) WHERE star_system_id IS NOT NULL;
CREATE UNIQUE INDEX uq_material_occurrence_planet
    ON material_occurrence (planet_id, material_id) WHERE planet_id IS NOT NULL;
CREATE UNIQUE INDEX uq_material_occurrence_moon
    ON material_occurrence (moon_id, material_id) WHERE moon_id IS NOT NULL;
CREATE UNIQUE INDEX uq_material_occurrence_orbit
    ON material_occurrence (orbit_id, material_id) WHERE orbit_id IS NOT NULL;
CREATE UNIQUE INDEX uq_material_occurrence_poi
    ON material_occurrence (poi_id, material_id) WHERE poi_id IS NOT NULL;

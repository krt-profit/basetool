CREATE TABLE manufacturer_p4k_alias (
    p4k_uuid UUID PRIMARY KEY,
    manufacturer_id UUID NOT NULL REFERENCES manufacturer (id) ON DELETE CASCADE
);

CREATE INDEX idx_manufacturer_p4k_alias_manufacturer ON manufacturer_p4k_alias (manufacturer_id);

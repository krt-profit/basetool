CREATE TABLE mission_template (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    owning_org_unit_id UUID NOT NULL REFERENCES org_unit (id),
    name VARCHAR(255) NOT NULL,
    CONSTRAINT uk_mission_template_owner_name UNIQUE (owning_org_unit_id, name)
);

CREATE TABLE mission_template_objective (
    template_id UUID NOT NULL REFERENCES mission_template (id) ON DELETE CASCADE,
    order_index INTEGER NOT NULL,
    title VARCHAR(500) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    PRIMARY KEY (template_id, order_index)
);

CREATE TABLE mission_template_step (
    template_id UUID NOT NULL REFERENCES mission_template (id) ON DELETE CASCADE,
    order_index INTEGER NOT NULL,
    title VARCHAR(500) NOT NULL,
    meta VARCHAR(200),
    PRIMARY KEY (template_id, order_index)
);

CREATE TABLE mission_template_unit (
    template_id UUID NOT NULL REFERENCES mission_template (id) ON DELETE CASCADE,
    order_index INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    ship_type_id UUID REFERENCES ship_type (id),
    note VARCHAR(500),
    high_value_unit BOOLEAN NOT NULL,
    frequency DOUBLE PRECISION CHECK (frequency BETWEEN 100.00 AND 999.99),
    PRIMARY KEY (template_id, order_index)
);

CREATE INDEX idx_mission_template_unit_ship_type ON mission_template_unit (ship_type_id);

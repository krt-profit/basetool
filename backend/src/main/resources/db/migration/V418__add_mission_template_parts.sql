ALTER TABLE mission_template
    ADD COLUMN description TEXT,
    ADD COLUMN meeting_point VARCHAR(200),
    ADD COLUMN is_internal BOOLEAN,
    ADD COLUMN meeting_time TIME,
    ADD COLUMN planned_start_time TIME,
    ADD COLUMN planned_end_time TIME;

CREATE TABLE mission_template_frequency (
    template_id UUID NOT NULL REFERENCES mission_template (id) ON DELETE CASCADE,
    order_index INTEGER NOT NULL,
    frequency_type_id UUID REFERENCES frequency_type (id),
    name VARCHAR(100),
    frequency_value NUMERIC(5, 2) NOT NULL CHECK (frequency_value BETWEEN 0 AND 999.99),
    PRIMARY KEY (template_id, order_index),
    CONSTRAINT ck_mission_template_frequency_typed_xor_named CHECK (
        (frequency_type_id IS NOT NULL AND name IS NULL)
        OR (frequency_type_id IS NULL AND name IS NOT NULL)
    )
);

CREATE INDEX idx_mission_template_frequency_type ON mission_template_frequency (frequency_type_id);

ALTER TABLE material_provision_credit
    ADD COLUMN supplied_quality_known BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN fact_history JSONB NOT NULL DEFAULT '[]'::JSONB;

UPDATE material_provision_credit
SET supplied_quality_known = (evidence = 'OBSERVED');

ALTER TABLE material_provision_credit
    ADD CONSTRAINT material_provision_fact_history_array
        CHECK (jsonb_typeof(fact_history) = 'array');

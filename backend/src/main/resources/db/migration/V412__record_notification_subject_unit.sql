ALTER TABLE notification
    ADD COLUMN org_unit_id UUID,
    ADD COLUMN org_unit_kind VARCHAR(32),
    ADD COLUMN org_unit_name VARCHAR(255);

UPDATE mission
SET is_internal = false, flags_version = flags_version + 1
WHERE owning_org_unit_id IS NULL AND is_internal = true;

ALTER TABLE mission
    ADD CONSTRAINT ck_mission_internal_has_owning_org_unit
    CHECK (owning_org_unit_id IS NOT NULL OR is_internal = false);

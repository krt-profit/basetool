-- REQ-P4K-027: a modifier belongs to one requirement group or, as a recipe-wide modifier of a
-- choose-n root selection, to the blueprint itself; exactly one of the two owners is set.
ALTER TABLE blueprint_requirement_modifier
    ADD COLUMN IF NOT EXISTS blueprint_id UUID REFERENCES blueprint (id);
ALTER TABLE blueprint_requirement_modifier ALTER COLUMN requirement_group_id DROP NOT NULL;
ALTER TABLE blueprint_requirement_modifier
    DROP CONSTRAINT IF EXISTS chk_blueprint_requirement_modifier_owner;
ALTER TABLE blueprint_requirement_modifier
    ADD CONSTRAINT chk_blueprint_requirement_modifier_owner
        CHECK (num_nonnulls(requirement_group_id, blueprint_id) = 1);

CREATE INDEX IF NOT EXISTS idx_blueprint_requirement_modifier_blueprint
    ON blueprint_requirement_modifier (blueprint_id);

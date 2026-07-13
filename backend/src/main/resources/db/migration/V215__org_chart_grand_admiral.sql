-- Grand Admiral (REQ-ORG-021): the single OL member designated as the org-wide "Grand Admiral"
-- post, rendered at the very top of the Organisationsleitung in the org chart. Rights are
-- UNCHANGED — an account holder keeps the OL_MEMBER rank (identical officer-equivalent reach), so no
-- authority column changes here. This migration only records WHO carries the title.
--
-- Like every other person-position on the chart (REQ-ORG-020) the Grand Admiral may be held by
-- EITHER a Basetool account (grand_admiral_user_id, appointed under Leitung with OL-member rights)
-- OR a free-text name for a member without an account yet (grand_admiral_display_name, set in the
-- chart editor, grants nothing) — never both. The designation lives on the OL org-unit row (the
-- source of truth); the descriptive chart merely mirrors it (REQ-ROLE-006, ADR-0042). Because the OL
-- is a singleton tier, a single pair of nullable columns is itself the org-wide "at most one Grand
-- Admiral" guarantee.
ALTER TABLE org_unit
    ADD COLUMN grand_admiral_user_id UUID;

ALTER TABLE org_unit
    ADD COLUMN grand_admiral_display_name TEXT;

-- Only the Organisationsleitung row may name a Grand Admiral; every other kind keeps both NULL.
ALTER TABLE org_unit
    ADD CONSTRAINT chk_org_unit_grand_admiral_only_ol
        CHECK (
            (grand_admiral_user_id IS NULL AND grand_admiral_display_name IS NULL)
                OR kind = 'ORGANISATIONSLEITUNG');

-- Account XOR free-text: a Grand Admiral is either an account or a typed name, never both — mirrors
-- the chk_org_chart_holder rule on org_chart_position (REQ-ORG-020).
ALTER TABLE org_unit
    ADD CONSTRAINT chk_org_unit_grand_admiral_holder
        CHECK (grand_admiral_user_id IS NULL OR grand_admiral_display_name IS NULL);

-- Referential integrity for the account holder: deleting the account clears the designation instead
-- of leaving a dangling id (the holder simply stops being the Grand Admiral).
ALTER TABLE org_unit
    ADD CONSTRAINT fk_org_unit_grand_admiral_user
        FOREIGN KEY (grand_admiral_user_id) REFERENCES app_user (id) ON DELETE SET NULL;

-- Grand Admiral (REQ-ORG-021): the single OL member designated as the org-wide "Grand Admiral"
-- post, rendered at the very top of the Organisationsleitung in the org chart. Rights are
-- UNCHANGED — the holder keeps the OL_MEMBER rank (identical officer-equivalent reach), so no
-- authority column changes here. This migration only records WHICH OL member carries the title.
--
-- The designation lives on the OL org-unit row (the source of truth); the descriptive chart merely
-- mirrors it (REQ-ROLE-006, ADR-0042). Because the OL is effectively a singleton tier, a single
-- nullable column is itself the org-wide "at most one Grand Admiral" guarantee.
ALTER TABLE org_unit
    ADD COLUMN grand_admiral_user_id UUID;

-- Only the Organisationsleitung row may name a Grand Admiral; every other kind keeps it NULL.
ALTER TABLE org_unit
    ADD CONSTRAINT chk_org_unit_grand_admiral_only_ol
        CHECK (grand_admiral_user_id IS NULL OR kind = 'ORGANISATIONSLEITUNG');

-- Referential integrity to the local account table: deleting the account clears the designation
-- instead of leaving a dangling id (the holder simply stops being the Grand Admiral).
ALTER TABLE org_unit
    ADD CONSTRAINT fk_org_unit_grand_admiral_user
        FOREIGN KEY (grand_admiral_user_id) REFERENCES app_user (id) ON DELETE SET NULL;

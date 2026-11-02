ALTER TABLE org_unit DROP CONSTRAINT IF EXISTS chk_org_unit_grand_admiral_holder;

ALTER TABLE org_unit DROP CONSTRAINT IF EXISTS chk_org_unit_grand_admiral_only_ol;

ALTER TABLE org_unit
    ADD CONSTRAINT chk_org_unit_grand_admiral_only_ol
        CHECK (grand_admiral_user_id IS NULL OR kind = 'ORGANISATIONSLEITUNG');

ALTER TABLE org_unit DROP COLUMN IF EXISTS grand_admiral_display_name;
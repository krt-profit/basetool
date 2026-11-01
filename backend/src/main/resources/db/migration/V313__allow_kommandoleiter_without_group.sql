-- REQ-ROLE-003: Kommandoleiter, stellv. Kommandoleiter and Ensign may carry a Kommandogruppe;
-- none of them needs one. Every other rank keeps a NULL group.
ALTER TABLE org_unit_membership DROP CONSTRAINT IF EXISTS chk_org_unit_membership_kommando_group_role;
ALTER TABLE org_unit_membership ADD CONSTRAINT chk_org_unit_membership_kommando_group_role
    CHECK (
        kommando_group_id IS NULL
        OR role IN ('KOMMANDOLEITER', 'STELLV_KOMMANDOLEITER', 'ENSIGN')
    );

ALTER TABLE supply_donation
    ADD COLUMN withdrawn_by_patch_change UUID,
    ADD CONSTRAINT ck_supply_donation_withdrawn_by_patch_change CHECK (
        withdrawn_by_patch_change IS NULL OR status = 'WITHDRAWN');

COMMENT ON COLUMN supply_donation.withdrawn_by_patch_change IS
    'The patch change whose wipe withdrew the pledge as its reason; NULL for every other donation (REQ-PATCH-013).';

-- REQ-P4K-024/025: an admin's resource group or refined material on a material is an override that
-- no P4K APPLY writes over; TRUE until the admin hands the field back to the P4K.
ALTER TABLE material
    ADD COLUMN IF NOT EXISTS resource_group_manual BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE material
    ADD COLUMN IF NOT EXISTS refined_material_manual BOOLEAN NOT NULL DEFAULT FALSE;

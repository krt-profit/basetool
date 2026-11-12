CREATE UNIQUE INDEX uq_org_unit_membership_one_bereichsleiter
    ON org_unit_membership (org_unit_id)
    WHERE role = 'BEREICHSLEITER';

CREATE OR REPLACE FUNCTION exchange_record_org_pool_and_below(p_unit UUID)
RETURNS VOID AS $$
BEGIN
    PERFORM exchange_record_org_pool(u.id)
       FROM org_unit u
       JOIN org_unit g ON g.id = p_unit
      WHERE u.id = g.id
         OR u.parent_org_unit_id = g.id
         OR g.kind = 'ORGANISATIONSLEITUNG';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION exchange_org_unit_membership_joined()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.org_unit_id IS DISTINCT FROM OLD.org_unit_id THEN
        IF NEW.is_logistician OR NEW.role <> 'MEMBER' THEN
            PERFORM exchange_record_org_pool_and_below(NEW.org_unit_id);
        ELSE
            PERFORM exchange_record_org_pool(NEW.org_unit_id);
        END IF;
    ELSIF (NEW.is_logistician OR NEW.role <> 'MEMBER')
          IS DISTINCT FROM (OLD.is_logistician OR OLD.role <> 'MEMBER') THEN
        PERFORM exchange_record_org_pool_and_below(NEW.org_unit_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TRIGGER trg_org_unit_membership_exchange_change ON org_unit_membership IS
    'Announces every lot of a unit''s pool in the org-stock feed when a member joins it, a membership moves to it, or a membership starts or stops making its member a Logistician of the unit, and of the pools below a Bereich or the Organisationsleitung when that membership cascades, so the entries appear or vanish in that member''s feed (REQ-XCH-042, REQ-XCH-043, REQ-SEC-005).';

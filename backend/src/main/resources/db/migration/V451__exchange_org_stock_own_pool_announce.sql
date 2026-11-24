CREATE OR REPLACE FUNCTION exchange_org_unit_membership_changed() RETURNS TRIGGER AS $$
DECLARE
    v_moved BOOLEAN := TG_OP = 'UPDATE'
        AND (NEW.org_unit_id IS DISTINCT FROM OLD.org_unit_id
             OR NEW.user_id IS DISTINCT FROM OLD.user_id);
BEGIN
    IF TG_OP = 'INSERT'
       OR (TG_OP = 'UPDATE'
           AND (NEW.org_unit_id IS DISTINCT FROM OLD.org_unit_id
                OR (NEW.is_logistician OR NEW.role <> 'MEMBER')
                   IS DISTINCT FROM (OLD.is_logistician OR OLD.role <> 'MEMBER'))) THEN
        PERFORM exchange_record_org_pool(NEW.org_unit_id);
    END IF;
    IF TG_OP = 'DELETE' OR v_moved THEN
        PERFORM exchange_announce_job_orders_of_unit_to_member(OLD.user_id, OLD.org_unit_id, TRUE);
    END IF;
    IF TG_OP = 'INSERT' OR v_moved THEN
        PERFORM exchange_announce_job_orders_of_unit_to_member(NEW.user_id, NEW.org_unit_id, FALSE);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION exchange_record_org_pool_and_below(UUID);

COMMENT ON TRIGGER trg_org_unit_membership_exchange_change ON org_unit_membership IS
    'Announces every lot of a unit''s pool in the org-stock feed when a member joins it, a membership moves to it, or a membership starts or stops making its member a Logistician of the unit (REQ-XCH-042, REQ-XCH-043); and announces to the member the job orders they start or stop reading through the unit when a membership starts, ends or moves (REQ-OXCH-003).';

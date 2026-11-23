ALTER TABLE exchange_job_order_fingerprint ADD COLUMN left_set_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN exchange_job_order_fingerprint.left_set_at IS
    'When the order was announced as outside the client set: by a fingerprint written while it lay outside, or by the nightly window run once its 7 days ended; NULL while its last fingerprint was written inside the set (REQ-OXCH-003).';

-- A fingerprint written outside the set announced the order where it was last announced, or, as a
-- first fingerprint, reached no client; either way the window run has nothing left to announce.
UPDATE exchange_job_order_fingerprint f
   SET left_set_at = f.changed_at
  FROM job_order o
 WHERE o.id = f.job_order_id
   AND NOT exchange_job_order_in_window(o.status, o.closed_at, o.type, f.settlement_open)
   AND f.changed_at >= o.closed_at + INTERVAL '7 days';

CREATE FUNCTION exchange_job_order_fingerprint_window() RETURNS TRIGGER AS $$
BEGIN
    NEW.left_set_at := CASE
        WHEN EXISTS (SELECT 1
                       FROM job_order o
                      WHERE o.id = NEW.job_order_id
                        AND exchange_job_order_in_window(o.status, o.closed_at, o.type,
                                                         NEW.settlement_open))
        THEN NULL
        ELSE now()
    END;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_exchange_job_order_fingerprint_window
BEFORE INSERT OR UPDATE OF fingerprint ON exchange_job_order_fingerprint
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_fingerprint_window();

COMMENT ON TRIGGER trg_exchange_job_order_fingerprint_window ON exchange_job_order_fingerprint IS
    'Records with each fingerprint whether it was written outside the client set, read in the worker''s snapshot, so the window run announces only orders whose last fingerprint found them inside (REQ-OXCH-003, ADR-0406).';

CREATE OR REPLACE FUNCTION exchange_job_order_fingerprint_changed() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM exchange_record_job_order(OLD.job_order_id, OLD.announced_unit_ids,
                                          OLD.announced_member_ids);
    ELSIF TG_OP = 'INSERT' THEN
        IF exchange_job_order_in_client_set(NEW.job_order_id) THEN
            PERFORM exchange_record_job_order(NEW.job_order_id, NEW.announced_unit_ids,
                                              NEW.announced_member_ids);
        END IF;
    ELSIF NEW.fingerprint IS DISTINCT FROM OLD.fingerprint THEN
        PERFORM exchange_record_job_order(
                    NEW.job_order_id,
                    OLD.announced_unit_ids || NEW.announced_unit_ids,
                    OLD.announced_member_ids || NEW.announced_member_ids);
    ELSIF OLD.left_set_at IS NULL AND NEW.left_set_at IS NOT NULL THEN
        PERFORM exchange_record_job_order(NEW.job_order_id, NEW.announced_unit_ids,
                                          NEW.announced_member_ids);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trg_exchange_job_order_fingerprint_exchange_change ON exchange_job_order_fingerprint;

CREATE TRIGGER trg_exchange_job_order_fingerprint_exchange_change
AFTER INSERT OR DELETE OR UPDATE OF fingerprint, left_set_at ON exchange_job_order_fingerprint
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_fingerprint_changed();

COMMENT ON TRIGGER trg_exchange_job_order_fingerprint_exchange_change ON exchange_job_order_fingerprint IS
    'Announces a job order whose fingerprint changed, or whose order was deleted, in the logs of its units and assignees, old and new, and an order the window run found outside the client set where it was last announced (REQ-OXCH-003, ADR-0406).';

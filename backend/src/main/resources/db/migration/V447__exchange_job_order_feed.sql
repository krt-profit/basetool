ALTER TABLE exchange_change DROP CONSTRAINT ck_exchange_change_resource;

ALTER TABLE exchange_change
    ADD CONSTRAINT ck_exchange_change_resource
        CHECK (resource IN ('BLUEPRINT', 'STOCK', 'SHIP', 'ORG_STOCK', 'BANK_REQUEST',
                            'REFINERY_ORDER', 'JOB_ORDER'));

ALTER TABLE exchange_change DROP CONSTRAINT ck_exchange_change_keyed_by;

ALTER TABLE exchange_change
    ADD CONSTRAINT ck_exchange_change_keyed_by
        CHECK ((resource = 'ORG_STOCK' AND org_unit_id IS NOT NULL AND user_id IS NULL)
            OR (resource = 'JOB_ORDER' AND (org_unit_id IS NULL) <> (user_id IS NULL))
            OR (resource NOT IN ('ORG_STOCK', 'JOB_ORDER')
                AND user_id IS NOT NULL AND org_unit_id IS NULL));

COMMENT ON COLUMN exchange_change.org_unit_id IS
    'The unit whose log holds the entry: the pool of an org-stock lot (REQ-XCH-042) or a unit a job order is announced to (REQ-OXCH-003); a job-order entry names a unit or a member, never both.';

ALTER TABLE exchange_job_order_fingerprint
    ADD COLUMN announced_unit_ids UUID[] NOT NULL DEFAULT '{}',
    ADD COLUMN announced_member_ids UUID[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN exchange_job_order_fingerprint.announced_unit_ids IS
    'The requesting, the responsible and the actively supporting units as of the fingerprint: the unit logs the order was last announced to, which a change or a deletion announces it to again (REQ-OXCH-003).';

COMMENT ON COLUMN exchange_job_order_fingerprint.announced_member_ids IS
    'The assignees as of the fingerprint: the member logs the order was last announced to (REQ-OXCH-003).';


CREATE FUNCTION exchange_job_order_in_client_set(p_order UUID) RETURNS BOOLEAN AS $$
    SELECT EXISTS (SELECT 1
                     FROM job_order o
                     LEFT JOIN exchange_job_order_fingerprint f ON f.job_order_id = o.id
                    WHERE o.id = p_order
                      AND exchange_job_order_in_window(o.status, o.closed_at, o.type,
                                                       f.settlement_open));
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION exchange_job_order_in_client_set(UUID) IS
    'Whether a job order lies in the window of the client set (REQ-OXCH-003).';


CREATE FUNCTION exchange_job_order_units(p_order UUID) RETURNS UUID[] AS $$
    SELECT COALESCE(array_agg(DISTINCT u.unit_id ORDER BY u.unit_id), '{}')
      FROM (SELECT o.requesting_org_unit_id AS unit_id FROM job_order o WHERE o.id = p_order
            UNION ALL
            SELECT o.responsible_org_unit_id FROM job_order o WHERE o.id = p_order
            UNION ALL
            SELECT p.claiming_org_unit_id
              FROM job_order_participation p
             WHERE p.job_order_id = p_order AND p.active) u;
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION exchange_job_order_units(UUID) IS
    'The requesting, the responsible and every actively supporting unit of a job order, sorted (REQ-OXCH-003).';

CREATE FUNCTION exchange_job_order_assignee_ids(p_order UUID) RETURNS UUID[] AS $$
    SELECT COALESCE(array_agg(DISTINCT a.user_id ORDER BY a.user_id), '{}')
      FROM job_order_assignees a
     WHERE a.job_order_id = p_order;
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION exchange_job_order_assignee_ids(UUID) IS
    'The members holding an assignee entry on a job order, sorted (REQ-OXCH-003).';

CREATE FUNCTION exchange_record_job_order_unit(p_unit UUID, p_order UUID) RETURNS VOID AS $$
BEGIN
    IF p_unit IS NULL OR p_order IS NULL
       OR NOT EXISTS (SELECT 1 FROM org_unit WHERE id = p_unit)
       OR EXISTS (SELECT 1
                    FROM exchange_change uc
                   WHERE uc.org_unit_id = p_unit
                     AND uc.tx = (pg_current_xact_id()::text)::bigint
                     AND uc.resource = 'JOB_ORDER'
                     AND uc.entity_key = p_order::text) THEN
        RETURN;
    END IF;
    INSERT INTO exchange_change
        (org_unit_id, resource, entity_key, source_channel, source_client, source_key,
         source_patch_change)
    SELECT p_unit, 'JOB_ORDER', p_order::text, src.channel, src.client, src.installation_key,
           src.patch_change
      FROM exchange_change_source() src;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_record_job_order_unit(UUID, UUID) IS
    'Announces a job order in one unit''s log, at most once per writing transaction (REQ-OXCH-003, ADR-0406).';

CREATE FUNCTION exchange_record_job_order_member(p_user UUID, p_order UUID) RETURNS VOID AS $$
BEGIN
    IF p_user IS NULL OR p_order IS NULL
       OR EXISTS (SELECT 1
                    FROM exchange_change mc
                   WHERE mc.user_id = p_user
                     AND mc.resource = 'JOB_ORDER'
                     AND mc.entity_key = p_order::text
                     AND mc.tx = (pg_current_xact_id()::text)::bigint) THEN
        RETURN;
    END IF;
    PERFORM exchange_record_change(p_user, 'JOB_ORDER', p_order::text);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_record_job_order_member(UUID, UUID) IS
    'Announces a job order in one member''s own log, at most once per writing transaction (REQ-OXCH-003, ADR-0406).';

CREATE FUNCTION exchange_record_job_order(p_order UUID, p_units UUID[], p_members UUID[])
RETURNS VOID AS $$
DECLARE
    v_unit   UUID;
    v_member UUID;
BEGIN
    FOREACH v_unit IN ARRAY p_units LOOP
        PERFORM exchange_record_job_order_unit(v_unit, p_order);
    END LOOP;
    FOREACH v_member IN ARRAY p_members LOOP
        PERFORM exchange_record_job_order_member(v_member, p_order);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_record_job_order(UUID, UUID[], UUID[]) IS
    'Announces a job order in the logs of the given units and members (REQ-OXCH-003).';

CREATE FUNCTION exchange_announce_job_order(p_order UUID) RETURNS VOID AS $$
BEGIN
    PERFORM exchange_record_job_order(f.job_order_id, f.announced_unit_ids, f.announced_member_ids)
       FROM exchange_job_order_fingerprint f
      WHERE f.job_order_id = p_order;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_announce_job_order(UUID) IS
    'Announces a job order to every unit and member it was last announced to; an order without a fingerprint has reached no client and is not announced (REQ-OXCH-003).';

CREATE FUNCTION exchange_job_order_fingerprint_audience() RETURNS TRIGGER AS $$
BEGIN
    NEW.announced_unit_ids := exchange_job_order_units(NEW.job_order_id);
    NEW.announced_member_ids := exchange_job_order_assignee_ids(NEW.job_order_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_exchange_job_order_fingerprint_audience
BEFORE INSERT OR UPDATE OF fingerprint ON exchange_job_order_fingerprint
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_fingerprint_audience();

COMMENT ON TRIGGER trg_exchange_job_order_fingerprint_audience ON exchange_job_order_fingerprint IS
    'Stores with each fingerprint the units and assignees it is announced to, read in the worker''s snapshot (REQ-OXCH-003, ADR-0406).';

-- A first fingerprint outside the client set reaches no client, since the snapshot needs one; a
-- change or a deletion announces old and new alike, so an order leaving the set is announced too.
CREATE FUNCTION exchange_job_order_fingerprint_changed() RETURNS TRIGGER AS $$
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
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_exchange_job_order_fingerprint_exchange_change
AFTER INSERT OR DELETE OR UPDATE OF fingerprint ON exchange_job_order_fingerprint
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_fingerprint_changed();

COMMENT ON TRIGGER trg_exchange_job_order_fingerprint_exchange_change ON exchange_job_order_fingerprint IS
    'Announces a job order whose fingerprint changed, or whose order was deleted, in the logs of its units and assignees, old and new (REQ-OXCH-003, ADR-0406).';

UPDATE exchange_job_order_fingerprint
   SET announced_unit_ids = exchange_job_order_units(job_order_id),
       announced_member_ids = exchange_job_order_assignee_ids(job_order_id);

-- A member who stops reading through a unit may hold, besides the unit's orders of the client set,
-- every order last announced to the unit and every order its log still names, including one that
-- left the unit before the member's client synced.
CREATE FUNCTION exchange_job_orders_of_unit(p_unit UUID, p_leaving BOOLEAN)
RETURNS SETOF UUID AS $$
    SELECT o.id
      FROM job_order o
      LEFT JOIN exchange_job_order_fingerprint f ON f.job_order_id = o.id
     WHERE exchange_job_order_in_window(o.status, o.closed_at, o.type, f.settlement_open)
       AND (o.requesting_org_unit_id = p_unit
            OR o.responsible_org_unit_id = p_unit
            OR EXISTS (SELECT 1
                         FROM job_order_participation p
                        WHERE p.job_order_id = o.id AND p.active
                          AND p.claiming_org_unit_id = p_unit)
            OR (p_leaving AND p_unit = ANY (f.announced_unit_ids)))
    UNION
    SELECT CAST(c.entity_key AS UUID)
      FROM exchange_change c
     WHERE p_leaving AND c.org_unit_id = p_unit AND c.resource = 'JOB_ORDER';
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION exchange_job_orders_of_unit(UUID, BOOLEAN) IS
    'The job orders a member reads through a unit: those of the client set the unit requests, is responsible for or actively supports, and for a member who stops reading through it also those last announced to it and every order its log names (REQ-OXCH-003).';

CREATE FUNCTION exchange_announce_job_orders_of_unit_to_member(
    p_user UUID, p_unit UUID, p_leaving BOOLEAN)
RETURNS VOID AS $$
BEGIN
    PERFORM exchange_record_job_order_member(p_user, uo.job_order_id)
       FROM exchange_job_orders_of_unit(p_unit, p_leaving) AS uo(job_order_id);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_announce_job_orders_of_unit_to_member(UUID, UUID, BOOLEAN) IS
    'Announces to one member, in their own log, the job orders they start or stop reading through a unit (REQ-OXCH-003).';

CREATE FUNCTION exchange_announce_job_orders_of_assignee(p_user UUID) RETURNS VOID AS $$
BEGIN
    PERFORM exchange_announce_job_order(a.job_order_id)
       FROM job_order_assignees a
      WHERE a.user_id = p_user AND exchange_job_order_in_client_set(a.job_order_id);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_announce_job_orders_of_assignee(UUID) IS
    'Announces the job orders of the client set a member holds an assignee entry on, when the name they carry changes (REQ-OXCH-003, REQ-XCH-044).';

DROP TRIGGER trg_org_unit_membership_exchange_change ON org_unit_membership;

DROP FUNCTION exchange_org_unit_membership_joined();

CREATE FUNCTION exchange_org_unit_membership_changed() RETURNS TRIGGER AS $$
DECLARE
    v_moved BOOLEAN := TG_OP = 'UPDATE'
        AND (NEW.org_unit_id IS DISTINCT FROM OLD.org_unit_id
             OR NEW.user_id IS DISTINCT FROM OLD.user_id);
BEGIN
    IF TG_OP = 'INSERT'
       OR (TG_OP = 'UPDATE' AND NEW.org_unit_id IS DISTINCT FROM OLD.org_unit_id) THEN
        IF NEW.is_logistician OR NEW.role <> 'MEMBER' THEN
            PERFORM exchange_record_org_pool_and_below(NEW.org_unit_id);
        ELSE
            PERFORM exchange_record_org_pool(NEW.org_unit_id);
        END IF;
    ELSIF TG_OP = 'UPDATE'
          AND (NEW.is_logistician OR NEW.role <> 'MEMBER')
              IS DISTINCT FROM (OLD.is_logistician OR OLD.role <> 'MEMBER') THEN
        PERFORM exchange_record_org_pool_and_below(NEW.org_unit_id);
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

CREATE TRIGGER trg_org_unit_membership_exchange_change
AFTER INSERT OR DELETE OR UPDATE OF org_unit_id, user_id, is_logistician, role
ON org_unit_membership
FOR EACH ROW EXECUTE FUNCTION exchange_org_unit_membership_changed();

COMMENT ON TRIGGER trg_org_unit_membership_exchange_change ON org_unit_membership IS
    'Announces every lot of a unit''s pool in the org-stock feed when a member joins it, a membership moves to it, or a membership starts or stops making its member a Logistician of the unit, and of the pools below a Bereich or the Organisationsleitung when that membership cascades (REQ-XCH-042, REQ-XCH-043, REQ-SEC-005); and announces to the member the job orders they start or stop reading through the unit when a membership starts, ends or moves (REQ-OXCH-003).';

CREATE OR REPLACE FUNCTION exchange_org_unit_changed()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.name IS DISTINCT FROM OLD.name OR NEW.shorthand IS DISTINCT FROM OLD.shorthand THEN
        PERFORM exchange_record_change(s.owner_id, 'SHIP', s.id::text)
           FROM ship s
          WHERE s.owning_org_unit_id = NEW.id;
        PERFORM exchange_announce_job_order(uo.job_order_id)
           FROM exchange_job_orders_of_unit(NEW.id, FALSE) AS uo(job_order_id);
    END IF;
    IF NEW.name IS DISTINCT FROM OLD.name THEN
        PERFORM exchange_record_org_pool(NEW.id);
    END IF;
    IF NEW.shorthand IS DISTINCT FROM OLD.shorthand THEN
        PERFORM exchange_record_bank_requests_of_accounts(
            ARRAY(SELECT a.id FROM bank_account a WHERE a.org_unit_id = NEW.id));
    END IF;
    IF NEW.active IS DISTINCT FROM OLD.active THEN
        PERFORM exchange_announce_job_orders_of_unit_to_member(m.user_id, NEW.id, NOT NEW.active)
           FROM org_unit_membership m
          WHERE m.org_unit_id = NEW.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trg_org_unit_exchange_change ON org_unit;

CREATE TRIGGER trg_org_unit_exchange_change
AFTER UPDATE OF name, shorthand, active ON org_unit
FOR EACH ROW
WHEN (NEW.name IS DISTINCT FROM OLD.name
      OR NEW.shorthand IS DISTINCT FROM OLD.shorthand
      OR NEW.active IS DISTINCT FROM OLD.active)
EXECUTE FUNCTION exchange_org_unit_changed();

COMMENT ON TRIGGER trg_org_unit_exchange_change ON org_unit IS
    'Re-announces every ship of a unit in the exchange ship feed and its job orders of the client set when the unit''s name or shorthand changes, every lot of its pool in the org-stock feed when its name changes, and every booking request on its bank account in the BANK_REQUEST feed when its shorthand changes, so a client never keeps a stale unit; announces to every member the job orders they start or stop reading through the unit when it is activated or deactivated (REQ-XCH-017, REQ-XCH-042, REQ-BXCH-003, REQ-OXCH-003, ADR-0261).';

CREATE OR REPLACE FUNCTION exchange_app_user_renamed()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.username IS DISTINCT FROM OLD.username
       OR NEW.display_name IS DISTINCT FROM OLD.display_name THEN
        PERFORM exchange_record_org_lots_of_holder(NEW.id);
        PERFORM exchange_announce_job_orders_of_assignee(NEW.id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TRIGGER trg_app_user_exchange_change ON app_user IS
    'Announces every org-stock lot a member holds a shared entry in, and every job order of the client set they are an assignee of, when the name the web shows for them changes, so a client never keeps a stale name (REQ-XCH-042, REQ-XCH-043, REQ-OXCH-003).';

CREATE OR REPLACE FUNCTION exchange_name_withholding_changed()
RETURNS TRIGGER AS $$
DECLARE
    v_user UUID := CASE WHEN TG_OP = 'DELETE' THEN OLD.user_id ELSE NEW.user_id END;
    v_switched BOOLEAN := CASE TG_OP
        WHEN 'DELETE' THEN OLD.withhold_name
        WHEN 'INSERT' THEN NEW.withhold_name
        ELSE NEW.withhold_name IS DISTINCT FROM OLD.withhold_name
    END;
BEGIN
    IF v_switched THEN
        PERFORM exchange_record_org_lots_of_holder(v_user);
        PERFORM exchange_announce_job_orders_of_assignee(v_user);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TRIGGER trg_exchange_name_withholding_exchange_change ON exchange_name_withholding IS
    'Announces every org-stock lot a member holds a shared entry in, and every job order of the client set they are an assignee of, when they start or stop withholding their name (REQ-XCH-044, REQ-OXCH-003).';

CREATE FUNCTION exchange_job_order_assignee_note_changed() RETURNS TRIGGER AS $$
BEGIN
    IF exchange_job_order_in_client_set(NEW.job_order_id) THEN
        PERFORM exchange_record_job_order_member(NEW.user_id, NEW.job_order_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_job_order_assignees_exchange_change
AFTER UPDATE OF note ON job_order_assignees
FOR EACH ROW
WHEN (NEW.note IS DISTINCT FROM OLD.note)
EXECUTE FUNCTION exchange_job_order_assignee_note_changed();

COMMENT ON TRIGGER trg_job_order_assignees_exchange_change ON job_order_assignees IS
    'Announces a job order of the client set to its assignee alone when their own note changes, the one part of the read no other member carries (REQ-OXCH-003).';

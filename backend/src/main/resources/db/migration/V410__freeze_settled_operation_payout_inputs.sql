ALTER TABLE operation_payout_status
    ADD COLUMN settlement_fee_rate NUMERIC,
    ADD COLUMN settlement_calculated_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_operation_payout_basis CHECK (
        (settlement_fee_rate IS NULL AND settlement_calculated_at IS NULL)
        OR (settlement_fee_rate IS NOT NULL AND settlement_fee_rate >= 0 AND settlement_fee_rate < 1
            AND settlement_calculated_at IS NOT NULL)
    );

WITH settings AS (
    SELECT CASE WHEN pg_input_is_valid(btrim(setting_value), 'numeric')
                THEN btrim(setting_value)::numeric END AS rate
    FROM system_setting
    WHERE setting_key = 'operation.transfer_fee_rate'
), basis AS (
    SELECT coalesce(max(rate) FILTER (WHERE rate >= 0 AND rate < 1), 0.005) AS rate
    FROM settings
)
UPDATE operation_payout_status payout
SET settlement_fee_rate = basis.rate, settlement_calculated_at = transaction_timestamp()
FROM basis
WHERE payout.paid_out;

CREATE INDEX idx_operation_payout_status_settled
    ON operation_payout_status (operation_id) WHERE paid_out;

CREATE FUNCTION operation_payout_lock_key(target_id UUID) RETURNS BIGINT
    LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE
    RETURN hashtextextended('operation-payout:' || target_id::text, 0);

CREATE FUNCTION require_mutable_operation_payout(target_ids UUID[]) RETURNS VOID
    LANGUAGE plpgsql AS $$
DECLARE
    target_id UUID;
BEGIN
    FOREACH target_id IN ARRAY coalesce(target_ids, ARRAY[]::UUID[])
    LOOP
        IF target_id IS NULL THEN
            CONTINUE;
        END IF;
        IF NOT pg_try_advisory_xact_lock(operation_payout_lock_key(target_id)) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'operation_payout_calculation_in_progress',
                MESSAGE = 'Operation payout calculation is in progress';
        END IF;
        IF EXISTS (
            SELECT 1 FROM operation_payout_status
            WHERE operation_id = target_id AND paid_out
        ) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'operation_payout_inputs_frozen',
                MESSAGE = 'Operation payout inputs are frozen';
        END IF;
    END LOOP;
END
$$;

CREATE FUNCTION protect_operation_mission_payout() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM require_mutable_operation_payout(ARRAY[OLD.operation_id]);
        RETURN OLD;
    ELSIF TG_OP = 'INSERT' THEN
        PERFORM require_mutable_operation_payout(ARRAY[NEW.operation_id]);
    ELSIF ROW(OLD.operation_id, OLD.actual_start_time, OLD.actual_end_time)
        IS DISTINCT FROM (NEW.operation_id, NEW.actual_start_time, NEW.actual_end_time) THEN
        PERFORM require_mutable_operation_payout(ARRAY[OLD.operation_id, NEW.operation_id]);
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mission_operation_payout
    BEFORE INSERT OR DELETE OR UPDATE OF operation_id, actual_start_time, actual_end_time ON mission
    FOR EACH ROW EXECUTE FUNCTION protect_operation_mission_payout();

CREATE FUNCTION require_mutable_linked_mission_payout(mission_ids UUID[]) RETURNS VOID
    LANGUAGE plpgsql AS $$
DECLARE
    operation_ids UUID[];
BEGIN
    SELECT array_agg(link.operation_id)
    INTO operation_ids
    FROM (
        SELECT operation_id FROM mission WHERE id = ANY(mission_ids) ORDER BY id FOR SHARE
    ) link;
    PERFORM require_mutable_operation_payout(operation_ids);
END
$$;

CREATE FUNCTION protect_participant_operation_payout() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[OLD.mission_id]);
        RETURN OLD;
    ELSIF TG_OP = 'INSERT' THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[NEW.mission_id]);
    ELSIF ROW(OLD.mission_id, OLD.start_time, OLD.end_time, OLD.payout_preference, OLD.guest_name)
        IS DISTINCT FROM (NEW.mission_id, NEW.start_time, NEW.end_time, NEW.payout_preference, NEW.guest_name) THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[OLD.mission_id, NEW.mission_id]);
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_participant_operation_payout
    BEFORE INSERT OR DELETE OR UPDATE OF mission_id, start_time, end_time, payout_preference, guest_name
    ON mission_participant FOR EACH ROW EXECUTE FUNCTION protect_participant_operation_payout();

CREATE FUNCTION protect_mission_finance_operation_payout() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[OLD.mission_id]);
        RETURN OLD;
    ELSIF TG_OP = 'INSERT' THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[NEW.mission_id]);
    ELSIF ROW(OLD.mission_id, OLD.mission_participant_id, OLD.type, OLD.amount)
        IS DISTINCT FROM (NEW.mission_id, NEW.mission_participant_id, NEW.type, NEW.amount) THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[OLD.mission_id, NEW.mission_id]);
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mission_finance_operation_payout
    BEFORE INSERT OR DELETE OR UPDATE OF mission_id, mission_participant_id, type, amount
    ON mission_finance_entry FOR EACH ROW EXECUTE FUNCTION protect_mission_finance_operation_payout();

CREATE FUNCTION protect_refinery_operation_payout() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[OLD.mission_id]);
        RETURN OLD;
    ELSIF TG_OP = 'INSERT' THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[NEW.mission_id]);
    ELSIF ROW(OLD.mission_id, OLD.expenses, OLD.other_expenses, OLD.ore_sales)
        IS DISTINCT FROM (NEW.mission_id, NEW.expenses, NEW.other_expenses, NEW.ore_sales) THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[OLD.mission_id, NEW.mission_id]);
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_refinery_operation_payout
    BEFORE INSERT OR DELETE OR UPDATE OF mission_id, expenses, other_expenses, ore_sales
    ON refinery_order FOR EACH ROW EXECUTE FUNCTION protect_refinery_operation_payout();

CREATE FUNCTION protect_operation_payout_on_delete() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    PERFORM require_mutable_operation_payout(ARRAY[OLD.id]);
    RETURN OLD;
END
$$;

CREATE TRIGGER trg_operation_payout_on_delete
    BEFORE DELETE ON operation FOR EACH ROW EXECUTE FUNCTION protect_operation_payout_on_delete();

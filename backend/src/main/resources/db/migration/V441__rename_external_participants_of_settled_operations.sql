-- An external participant's name may change, or be removed, while the operation is settled: the
-- payout key guest_<name> moves with it, so the split stays the same (REQ-MISSION-034, ADR-0375).
CREATE FUNCTION rename_external_payout_participant(
    participant_id UUID, target_mission UUID, old_name TEXT, new_name TEXT) RETURNS VOID
    LANGUAGE plpgsql AS $$
DECLARE
    target_operation UUID;
    old_key TEXT := 'guest_' || old_name;
    -- The keys OperationPayoutCalculator.participantKey gives the renamed row.
    new_key TEXT := CASE WHEN new_name IS NULL THEN 'deleted_' || participant_id::text
                         ELSE 'guest_' || new_name END;
    settled BOOLEAN;
BEGIN
    SELECT operation_id INTO target_operation FROM mission WHERE id = target_mission FOR SHARE;
    IF target_operation IS NULL THEN
        RETURN;
    END IF;
    IF NOT pg_try_advisory_xact_lock(operation_payout_lock_key(target_operation)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'operation_payout_calculation_in_progress',
            MESSAGE = 'Operation payout calculation is in progress';
    END IF;
    settled := EXISTS (
        SELECT 1 FROM operation_payout_status
        WHERE operation_id = target_operation AND paid_out
    );
    -- A name another external participant of the operation already carries merges two payout rows.
    IF new_name IS NOT NULL AND EXISTS (
        SELECT 1 FROM mission_participant other JOIN mission ON mission.id = other.mission_id
        WHERE mission.operation_id = target_operation AND other.id <> participant_id
          AND other.user_id IS NULL AND other.guest_name = new_name
    ) THEN
        IF settled THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'operation_payout_inputs_frozen',
                MESSAGE = 'Operation payout inputs are frozen';
        END IF;
        RETURN;
    END IF;
    -- Other rows still carry the old name: the mark is copied so both keys stay paid.
    IF EXISTS (
        SELECT 1 FROM mission_participant other JOIN mission ON mission.id = other.mission_id
        WHERE mission.operation_id = target_operation AND other.id <> participant_id
          AND other.user_id IS NULL AND other.guest_name = old_name
    ) THEN
        INSERT INTO operation_payout_status (
            id, version, created_at, updated_at, operation_id, participant_key, paid_out,
            paid_out_at, paid_out_by_user_id, settlement_fee_rate, settlement_calculated_at)
        SELECT gen_random_uuid(), 0, now(), now(), operation_id, new_key, paid_out,
               paid_out_at, paid_out_by_user_id, settlement_fee_rate, settlement_calculated_at
        FROM operation_payout_status
        WHERE operation_id = target_operation AND participant_key = old_key
        ON CONFLICT (operation_id, participant_key) DO NOTHING;
    ELSE
        UPDATE operation_payout_status
        SET participant_key = new_key, version = version + 1, updated_at = now()
        WHERE operation_id = target_operation AND participant_key = old_key
          AND NOT EXISTS (
              SELECT 1 FROM operation_payout_status taken
              WHERE taken.operation_id = target_operation AND taken.participant_key = new_key);
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION protect_participant_operation_payout() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[OLD.mission_id]);
        RETURN OLD;
    ELSIF TG_OP = 'INSERT' THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[NEW.mission_id]);
    ELSIF ROW(OLD.mission_id, OLD.start_time, OLD.end_time, OLD.payout_preference)
            IS NOT DISTINCT FROM (NEW.mission_id, NEW.start_time, NEW.end_time, NEW.payout_preference)
        AND OLD.guest_name IS DISTINCT FROM NEW.guest_name
        AND OLD.guest_name IS NOT NULL AND OLD.user_id IS NULL AND NEW.user_id IS NULL THEN
        PERFORM rename_external_payout_participant(OLD.id, OLD.mission_id, OLD.guest_name, NEW.guest_name);
    ELSIF ROW(OLD.mission_id, OLD.start_time, OLD.end_time, OLD.payout_preference, OLD.guest_name)
        IS DISTINCT FROM (NEW.mission_id, NEW.start_time, NEW.end_time, NEW.payout_preference, NEW.guest_name) THEN
        PERFORM require_mutable_linked_mission_payout(ARRAY[OLD.mission_id, NEW.mission_id]);
    END IF;
    RETURN NEW;
END
$$;

ALTER TABLE mission
    ADD COLUMN reminder_24h_sent_at    TIMESTAMP WITH TIME ZONE,
    ADD COLUMN reminder_1h_sent_at     TIMESTAMP WITH TIME ZONE,
    ADD COLUMN never_ended_notified_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN mission.reminder_24h_sent_at IS
    'When the 24-hour reminder was raised; NULL until then, reset when the meeting time or planned start changes (REQ-MISSION-022).';
COMMENT ON COLUMN mission.reminder_1h_sent_at IS
    'When the one-hour reminder was raised; NULL until then, reset when the meeting time or planned start changes (REQ-MISSION-022).';
COMMENT ON COLUMN mission.never_ended_notified_at IS
    'When the never-ended notice was raised; NULL until then, reset when the end time is recorded or the planned end changes (REQ-MISSION-025).';

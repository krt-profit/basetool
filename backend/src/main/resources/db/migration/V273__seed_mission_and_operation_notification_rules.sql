INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000013', 'MISSION_RESCHEDULED', 'MISSION_RESCHEDULED', 'Default: notify the participants of a mission whose meeting time or planned start moved (REQ-MISSION-021).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000014', 'MISSION_CANCELLED', 'MISSION_CANCELLED', 'Default: notify the participants of a cancelled mission (REQ-MISSION-021).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000015', 'MISSION_DELETED', 'MISSION_DELETED', 'Default: notify the participants of a deleted mission, listed by the event because the mission is gone (REQ-MISSION-021).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000016', 'MISSION_REMINDER_DUE', 'MISSION_REMINDER', 'Default: remind each participant 24 hours and one hour before the mission (REQ-MISSION-022).', TRUE, FALSE),
       ('62200000-0000-0000-0000-000000000017', 'MISSION_STARTED', 'MISSION_CHECKIN_OPEN', 'Default: tell the participants who have not checked in that the mission started (REQ-MISSION-023).', TRUE, FALSE),
       ('62200000-0000-0000-0000-000000000018', 'MISSION_PARTICIPANT_ADDED', 'MISSION_PARTICIPANT_ADDED_BY_OTHER', 'Default: tell a member that somebody else added them to a mission (REQ-MISSION-024).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000019', 'MISSION_PARTICIPANT_REMOVED', 'MISSION_PARTICIPANT_REMOVED_BY_OTHER', 'Default: tell a member that somebody else removed them from a mission (REQ-MISSION-024).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000001a', 'MISSION_PARTICIPANT_LEFT', 'MISSION_PARTICIPANT_LEFT', 'Default: tell the mission leadership that a participant with a slot or a role dropped out (REQ-MISSION-027).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000001b', 'MISSION_NEVER_ENDED', 'MISSION_NEVER_ENDED', 'Default: tell the mission leadership that a mission ended without an end time (REQ-MISSION-025).', TRUE, FALSE),
       ('62200000-0000-0000-0000-00000000001c', 'MISSION_RESPONSIBILITY_ASSIGNED', 'MISSION_RESPONSIBILITY_ASSIGNED', 'Default: tell a member that they became responsible for part of a mission (REQ-MISSION-026).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000001d', 'OPERATION_PAYOUT_MARKED', 'OPERATION_PAYOUT_PAID_OUT', 'Default: tell a participant that their operation payout was paid out (REQ-MISSION-028).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000001e', 'OPERATION_COMPLETED', 'OPERATION_COMPLETED', 'Default: tell the Einsatzmanager and officers of the owning unit that an operation is complete and payouts are due (REQ-MISSION-029).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000001f', 'OPERATION_COMPLETED_UNOWNED', 'OPERATION_COMPLETED', 'Default: tell every officer that an operation without an owning unit is complete and payouts are due (REQ-MISSION-029).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000013', 'MISSION_PARTICIPANTS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000014', 'MISSION_PARTICIPANTS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000015', 'EVENT_RECIPIENTS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000016', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000017', 'MISSION_PARTICIPANTS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000018', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000019', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000001a', 'MISSION_LEADERSHIP'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000001b', 'MISSION_LEADERSHIP'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000001c', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000001d', 'EVENT_RECIPIENT');

INSERT INTO notification_rule_selector
    (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000001e', 'ORG_RELATIVE_ROLE', 'MISSION_MANAGER', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000001e', 'ORG_RELATIVE_ROLE', 'OFFICER', 'RESPONSIBLE');

INSERT INTO notification_rule_selector
    (id, rule_id, kind, role_code)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000001f', 'ROLE', 'OFFICER');

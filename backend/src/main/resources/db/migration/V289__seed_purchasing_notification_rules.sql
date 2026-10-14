INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000040',
        'PURCHASING_NEED_RELEASED',
        'PURCHASING_NEED_RELEASED',
        'Default: tell every active Handelsbeauftragter that a need was released for purchase (REQ-PUR-024).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000000041',
        'PURCHASING_DELIVERY_RECORDED',
        'PURCHASING_DELIVERY_RECORDED',
        'Default: tell the lead and the Logisticians of the raising unit that a delivery awaits acceptance (REQ-PUR-024).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000000042',
        'PURCHASING_DELIVERY_DECIDED',
        'PURCHASING_DELIVERY_DECIDED',
        'Default: tell the deal''s Handelsbeauftragter that a delivery was accepted or rejected (REQ-PUR-024).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000000043',
        'PURCHASING_SETTLEMENT_OVERDUE',
        'PURCHASING_SETTLEMENT_OVERDUE',
        'Default: tell the deal''s Handelsbeauftragter, the governing leadership and the bank management that a settlement is overdue (REQ-PUR-018).',
        TRUE,
        FALSE),
       ('62200000-0000-0000-0000-000000000044',
        'PURCHASING_PAUSED',
        'PURCHASING_PAUSED',
        'Default: tell the Handelsbeauftragte and, for a unit pause, the unit''s leadership that purchasing was paused (REQ-PUR-024).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000000045',
        'PURCHASING_RESUMED',
        'PURCHASING_RESUMED',
        'Default: tell the Handelsbeauftragte and, for a unit, the unit''s leadership that purchasing was resumed (REQ-PUR-024).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000000046',
        'PURCHASING_DELEGATE_APPOINTED',
        'PURCHASING_DELEGATE_APPOINTED',
        'Default: tell a member that they were appointed Handelsbeauftragter (REQ-PUR-024).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000000047',
        'PURCHASING_DELEGATE_REVOKED',
        'PURCHASING_DELEGATE_REVOKED',
        'Default: tell a member that their appointment as Handelsbeauftragter was revoked (REQ-PUR-024).',
        TRUE,
        TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000041', 'ORG_RELATIVE_ROLE', 'UNIT_LEADERSHIP', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000041', 'ORG_RELATIVE_ROLE', 'LOGISTICIAN', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000043', 'ORG_RELATIVE_ROLE', 'UNIT_LEADERSHIP', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000044', 'ORG_RELATIVE_ROLE', 'UNIT_LEADERSHIP', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000045', 'ORG_RELATIVE_ROLE', 'UNIT_LEADERSHIP', 'RESPONSIBLE');

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000040', 'EVENT_RECIPIENTS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000042', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000043', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000044', 'EVENT_RECIPIENTS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000045', 'EVENT_RECIPIENTS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000046', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000047', 'EVENT_RECIPIENT');

INSERT INTO notification_rule_selector
    (id, rule_id, kind, role_code)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000043', 'ROLE', 'BANK_MANAGEMENT');

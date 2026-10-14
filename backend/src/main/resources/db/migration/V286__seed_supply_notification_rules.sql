INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-00000000003d',
        'SUPPLY_DONATION_PLEDGED',
        'SUPPLY_DONATION_PLEDGED',
        'Default: tell the lead and the Logisticians of the receiving unit that a member pledged a material donation (REQ-SUP-016).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-00000000003e',
        'SUPPLY_DONATION_RECEIVED',
        'SUPPLY_DONATION_RECEIVED',
        'Default: tell the donor that the receiving unit confirmed the donation (REQ-SUP-016).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-00000000003f',
        'SUPPLY_DONATION_DECLINED',
        'SUPPLY_DONATION_DECLINED',
        'Default: tell the donor that the receiving unit declined the pledge (REQ-SUP-016).',
        TRUE,
        TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000003d', 'ORG_RELATIVE_ROLE', 'UNIT_LEADERSHIP', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000003d', 'ORG_RELATIVE_ROLE', 'LOGISTICIAN', 'RESPONSIBLE');

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000003e', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000003f', 'EVENT_RECIPIENT');

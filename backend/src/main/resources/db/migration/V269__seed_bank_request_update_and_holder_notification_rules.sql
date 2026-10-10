INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000011',
        'BANK_BOOKING_REQUEST_UPDATED_BY_REQUESTER',
        'BANK_BOOKING_REQUEST_UPDATED',
        'Default: when a requester corrects their pending booking request, tell the bank management, '
            'the employees granted on the account and its responsible holder again, so nobody acts '
            'on the old values (REQ-BANK-056).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000000012',
        'BANK_ACCOUNT_RESPONSIBLE_ASSIGNED',
        'BANK_ACCOUNT_RESPONSIBLE_ASSIGNED',
        'Default: tell a member who became a responsible holder of a bank account, with the number '
            'of requests awaiting their approval (REQ-BANK-034).',
        TRUE,
        TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, role_code)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000011', 'ROLE', 'BANK_MANAGEMENT');

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000011', 'ACCOUNT_GRANT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000011', 'ACCOUNT_RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000012', 'EVENT_RECIPIENT');

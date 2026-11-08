INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000315301',
        'BANK_BOOKING_REQUEST_CREATED',
        'BANK_BOOKING_REQUEST_APPROVAL_NEEDED',
        'Default: tell the required approver of an over-limit booking request that their approval is needed (REQ-NOTIF-033).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000315302',
        'BANK_BOOKING_REQUEST_UPDATED_BY_REQUESTER',
        'BANK_BOOKING_REQUEST_APPROVAL_NEEDED',
        'Default: when a requester corrects their booking request, tell its required approver again that their approval is needed (REQ-NOTIF-033).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000315303',
        'BANK_BOOKING_REQUEST_APPROVAL_REVOKED',
        'BANK_BOOKING_REQUEST_APPROVAL_NEEDED',
        'Default: when the approval of a booking request is revoked, tell its required approvers again that their approval is needed (REQ-NOTIF-033).',
        TRUE,
        FALSE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000315301', 'REQUIRED_APPROVER'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000315302', 'REQUIRED_APPROVER'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000315303', 'REQUIRED_APPROVER');

-- The generic new-request notices reach no responsible holder of the KRT account (REQ-NOTIF-033).
UPDATE notification_rule_selector
SET kind = 'REQUEST_RESPONSIBLE'
WHERE kind = 'ACCOUNT_RESPONSIBLE'
  AND rule_id IN ('62200000-0000-0000-0000-000000000002',
                  '62200000-0000-0000-0000-000000000011');

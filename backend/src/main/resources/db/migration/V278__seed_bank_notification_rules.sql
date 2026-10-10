INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000029', 'BANK_BOOKING_REQUEST_APPROVED', 'BANK_BOOKING_REQUEST_APPROVED', 'Default: tell the bank staff on the account, the bank management and the requester that a booking request is approved and ready to confirm (REQ-BANK-057).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000002a', 'BANK_GRANT_CHANGED', 'BANK_GRANT_CHANGED', 'Default: tell a bank employee that their access to an account was granted or changed (REQ-BANK-058).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000002b', 'BANK_GRANT_REVOKED', 'BANK_GRANT_REVOKED', 'Default: tell a bank employee that their access to an account was withdrawn (REQ-BANK-058).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000002c', 'BANK_PAYOUT_BOOKED', 'BANK_PAYOUT_RECEIVED', 'Default: tell a member that bank staff paid an amount out to them (REQ-BANK-059).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000002d', 'BANK_HOLDER_TRANSFER_BOOKED', 'BANK_HOLDER_TRANSFER_RECEIVED', 'Default: tell the receiving holder''s member that aUEC was moved to them and must be taken over (REQ-BANK-059).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000002e', 'BANK_ACCOUNT_DEBITED', 'BANK_ACCOUNT_DEBITED', 'Default: tell the responsible holders of an account that bank staff debited it or reversed a booking on it (REQ-BANK-059).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000002f', 'BANK_HOLDER_DEACTIVATED_WITH_BALANCE', 'BANK_HOLDER_DEACTIVATED_WITH_BALANCE', 'Default: tell the bank management that a deactivated holder still holds aUEC (REQ-BANK-060).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, role_code)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000029', 'ROLE', 'BANK_MANAGEMENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000002f', 'ROLE', 'BANK_MANAGEMENT');

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000029', 'ACCOUNT_GRANT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000029', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000002a', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000002b', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000002c', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000002d', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000002e', 'ACCOUNT_RESPONSIBLE');

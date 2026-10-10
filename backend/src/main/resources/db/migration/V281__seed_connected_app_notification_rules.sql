INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000038', 'EXCHANGE_CLIENT_SUSPENDED', 'EXCHANGE_CLIENT_SUSPENDED', 'Default: tell the holders of a connected application''s installations that it was suspended (REQ-XCH-040).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000039', 'EXCHANGE_CLIENT_ACTIVATED', 'EXCHANGE_CLIENT_ACTIVATED', 'Default: tell the holders of a connected application''s installations that it is active again (REQ-XCH-040).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000003a', 'EXCHANGE_CLIENT_UPDATE_REQUIRED', 'EXCHANGE_CLIENT_UPDATE_REQUIRED', 'Default: tell the holders of a connected application''s installations that it needs an update (REQ-XCH-040).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000003b', 'EXCHANGE_CLIENT_CAPABILITY_REMOVED', 'EXCHANGE_CLIENT_CAPABILITY_REMOVED', 'Default: tell the holders of a connected application''s installations that it lost capabilities (REQ-XCH-040).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000003c', 'EXCHANGE_SWITCHED_OFF', 'EXCHANGE_SWITCHED_OFF', 'Default: tell the holders of any installation that the exchange was switched off (REQ-XCH-041).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000038', 'EXCHANGE_CLIENT_HOLDERS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000039', 'EXCHANGE_CLIENT_HOLDERS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000003a', 'EXCHANGE_CLIENT_HOLDERS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000003b', 'EXCHANGE_CLIENT_HOLDERS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000003c', 'EXCHANGE_CLIENT_HOLDERS');

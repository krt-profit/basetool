INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000024', 'REFINERY_ORDER_READY', 'REFINERY_ORDER_READY', 'Default: tell the owner that a refinery order is ready to collect (REQ-REFINERY-023).', TRUE, FALSE),
       ('62200000-0000-0000-0000-000000000025', 'REFINERY_ORDER_CHANGED_BY_OTHER', 'REFINERY_ORDER_CHANGED_BY_OTHER', 'Default: tell a member that somebody else changed, cancelled or stored their refinery order, or booked its yield onto them (REQ-REFINERY-024).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000024', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000025', 'EVENT_RECIPIENT');

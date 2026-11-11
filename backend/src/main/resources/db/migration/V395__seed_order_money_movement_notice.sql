INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62392000-0000-0000-0000-000000000001',
        'JOB_ORDER_MONEY_MOVED',
        'JOB_ORDER_MONEY_MOVED',
        'Default: tell affected linked participants when another member records an aUEC movement involving them.',
        TRUE, TRUE);

INSERT INTO notification_rule_selector (id, rule_id, kind)
VALUES (gen_random_uuid(), '62392000-0000-0000-0000-000000000001', 'EVENT_RECIPIENT');

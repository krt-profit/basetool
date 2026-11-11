INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62387000-0000-0000-0000-000000000001',
        'JOB_ORDER_MONEY_PROVISIONED',
        'JOB_ORDER_MONEY_PROVISIONED',
        'Default: tell the linked holder when another member reports a provision for them.',
        TRUE, TRUE);

INSERT INTO notification_rule_selector (id, rule_id, kind)
VALUES (gen_random_uuid(), '62387000-0000-0000-0000-000000000001', 'EVENT_RECIPIENT');

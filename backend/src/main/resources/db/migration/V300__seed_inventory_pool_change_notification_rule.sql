INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000049', 'INVENTORY_POOL_CHANGED_BY_OTHER', 'INVENTORY_POOL_CHANGED_BY_OTHER', 'Default: tell a member that somebody else moved their shared stock into another pool (REQ-INV-059).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000049', 'EVENT_RECIPIENT');

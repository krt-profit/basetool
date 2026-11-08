INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-00000000004c', 'USER_ROLE_CHANGED', 'USER_ROLE_CHANGED', 'Default: tell a member that one of their roles was assigned or withdrawn (REQ-SEC-088).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000004c', 'EVENT_RECIPIENT');

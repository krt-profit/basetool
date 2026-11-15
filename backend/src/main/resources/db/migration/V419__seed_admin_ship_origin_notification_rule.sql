INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000064', 'HANGAR_SHIPS_ORIGIN_CHANGED_BY_ADMIN', 'HANGAR_SHIPS_ORIGIN_CHANGED_BY_ADMIN', 'Default: tell a member that an admin changed the origin of several of their ships (REQ-HANGAR-018).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000064', 'EVENT_RECIPIENT');

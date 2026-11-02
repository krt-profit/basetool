ALTER TABLE inventory_stock_level
    ADD COLUMN low_notified_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN inventory_stock_level.low_notified_at IS
    'When the unit was told that the level turned low; NULL while armed, cleared once the allocated stock reaches the target again (REQ-NOTIF-030).';

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-00000000004b',
        'INVENTORY_STOCK_LEVEL_LOW',
        'INVENTORY_STOCK_LEVEL_LOW',
        'Default: tell the direct Logisticians and seat holders of a unit that one of its minimum stock levels turned low (REQ-NOTIF-030).',
        TRUE,
        FALSE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000004b', 'ORG_RELATIVE_ROLE', 'UNIT_LEADERSHIP', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000004b', 'ORG_RELATIVE_ROLE', 'LOGISTICIAN', 'RESPONSIBLE');

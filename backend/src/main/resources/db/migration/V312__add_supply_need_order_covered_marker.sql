ALTER TABLE supply_need
    ADD COLUMN order_covered_notified_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN supply_need.order_covered_notified_at IS
    'When the unit was told that the linked job order covers the need''s bucket; NULL until then, reset when the link changes (REQ-SUP-024).';

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-00000000004a',
        'SUPPLY_NEED_ORDER_COVERED',
        'SUPPLY_NEED_ORDER_COVERED',
        'Default: tell the lead and the Logisticians of the raising unit that the job order linked to an open need is covered by its stock (REQ-SUP-024).',
        TRUE,
        TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000004a', 'ORG_RELATIVE_ROLE', 'UNIT_LEADERSHIP', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000004a', 'ORG_RELATIVE_ROLE', 'LOGISTICIAN', 'RESPONSIBLE');

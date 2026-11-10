CREATE TABLE notification_cause_receipt (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62381000-0000-0000-0000-000000000001',
        'JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED',
        'JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED',
        'Default: group material contribution changes for affected Staffel writers and responsible coordination.',
        TRUE, TRUE);

INSERT INTO notification_rule_selector (id, rule_id, kind)
VALUES (gen_random_uuid(), '62381000-0000-0000-0000-000000000001', 'EVENT_RECIPIENTS');

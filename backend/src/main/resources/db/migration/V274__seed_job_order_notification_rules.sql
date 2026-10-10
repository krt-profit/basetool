INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000020', 'JOB_ORDER_REASSIGNED', 'JOB_ORDER_REASSIGNED', 'Default: tell the officers, leads and logisticians of the new responsible unit that an order moved to them (REQ-ORDERS-041).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000021', 'JOB_ORDER_FINISHED', 'JOB_ORDER_FINISHED', 'Default: tell the officers, leads and logisticians of the requesting unit that their order was completed, rejected or deleted (REQ-ORDERS-042).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000022', 'JOB_ORDER_ASSIGNEE_ADDED', 'JOB_ORDER_ASSIGNED', 'Default: tell a member that somebody assigned them to a job order (REQ-ORDERS-043).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000023', 'JOB_ORDER_CLAIM_WITHDRAWN', 'JOB_ORDER_CLAIM_WITHDRAWN', 'Default: tell a member that their material claim was withdrawn by an order edit or a de-escalation (REQ-ORDERS-044).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000020', 'ORG_RELATIVE_ROLE', 'OFFICER', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000020', 'ORG_RELATIVE_ROLE', 'LEAD', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000020', 'ORG_RELATIVE_ROLE', 'LOGISTICIAN', 'RESPONSIBLE'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000021', 'ORG_RELATIVE_ROLE', 'OFFICER', 'REQUESTING'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000021', 'ORG_RELATIVE_ROLE', 'LEAD', 'REQUESTING'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000021', 'ORG_RELATIVE_ROLE', 'LOGISTICIAN', 'REQUESTING');

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000022', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000023', 'EVENT_RECIPIENT');

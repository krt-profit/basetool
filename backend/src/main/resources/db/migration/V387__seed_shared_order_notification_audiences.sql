WITH defaults(rule_id, type, stock_selectors, old_description, description, contexts, assignees) AS (
    VALUES (
        '62200000-0000-0000-0000-000000000001'::uuid,
        'JOB_ORDER_CREATED',
        ARRAY[
            'ORG_RELATIVE_ROLE:LEAD:RESPONSIBLE',
            'ORG_RELATIVE_ROLE:LOGISTICIAN:RESPONSIBLE',
            'ORG_RELATIVE_ROLE:OFFICER:RESPONSIBLE',
            'ROLE:ADMIN'
        ],
        'Default: notify officers / leads and logisticians of the responsible org unit plus admins when a new job order is created.',
        'Default: notify current requesting and responsible unit writers when a job order is created.',
        ARRAY['RESPONSIBLE', 'REQUESTING'],
        FALSE
    ), (
        '62200000-0000-0000-0000-000000000009'::uuid,
        'JOB_ORDER_UPDATED_BY_REQUESTER',
        ARRAY['ORG_RELATIVE_ROLE:LEAD:RESPONSIBLE', 'ORG_RELATIVE_ROLE:OFFICER:RESPONSIBLE'],
        'Default: notify the processing unit''s officers and leads when the requesting owner edits their order.',
        'Default: notify current requesting and responsible unit writers when the requester edits the order.',
        ARRAY['RESPONSIBLE', 'REQUESTING'],
        FALSE
    ), (
        '62200000-0000-0000-0000-000000000020'::uuid,
        'JOB_ORDER_REASSIGNED',
        ARRAY[
            'ORG_RELATIVE_ROLE:LEAD:RESPONSIBLE',
            'ORG_RELATIVE_ROLE:LOGISTICIAN:RESPONSIBLE',
            'ORG_RELATIVE_ROLE:OFFICER:RESPONSIBLE'
        ],
        'Default: tell the officers, leads and logisticians of the new responsible unit that an order moved to them (REQ-ORDERS-041).',
        'Default: tell current writers of the requesting unit and both responsible units about the transfer.',
        ARRAY['RESPONSIBLE', 'REQUESTING', 'PREVIOUS_RESPONSIBLE'],
        FALSE
    ), (
        '62200000-0000-0000-0000-000000000021'::uuid,
        'JOB_ORDER_FINISHED',
        ARRAY[
            'ORG_RELATIVE_ROLE:LEAD:REQUESTING',
            'ORG_RELATIVE_ROLE:LOGISTICIAN:REQUESTING',
            'ORG_RELATIVE_ROLE:OFFICER:REQUESTING'
        ],
        'Default: tell the officers, leads and logisticians of the requesting unit that their order was completed, rejected or deleted (REQ-ORDERS-042).',
        'Default: tell current unit writers and assignees when their order ends.',
        ARRAY['RESPONSIBLE', 'REQUESTING'],
        TRUE
    )
), stock_rules AS (
    SELECT d.*
    FROM defaults d
    JOIN notification_rule r ON r.id = d.rule_id
    WHERE r.event_type = d.type AND r.notification_type = d.type
      AND ARRAY(
          SELECT concat_ws(':', s.kind, s.role_code, s.org_relative_role, s.context_role)
          FROM notification_rule_selector s
          WHERE s.rule_id = r.id
          ORDER BY 1
      ) = d.stock_selectors
), removed AS (
    DELETE FROM notification_rule_selector s
    USING stock_rules r
    WHERE s.rule_id = r.rule_id
    RETURNING s.rule_id
), updated AS (
    UPDATE notification_rule r
    SET description = CASE
            WHEN r.description = d.old_description THEN d.description
            ELSE r.description
        END,
        version = r.version + 1,
        updated_at = CURRENT_TIMESTAMP
    FROM stock_rules d
    WHERE r.id = d.rule_id AND r.id IN (SELECT rule_id FROM removed)
    RETURNING r.id, d.contexts, d.assignees
)
INSERT INTO notification_rule_selector (id, rule_id, kind, org_relative_role, context_role)
SELECT gen_random_uuid(), updated.id, 'ORG_RELATIVE_ROLE', 'UNIT_WRITER', context.role
FROM updated CROSS JOIN LATERAL unnest(updated.contexts) context(role)
UNION ALL
SELECT gen_random_uuid(), updated.id, 'EVENT_RECIPIENTS', NULL, NULL
FROM updated WHERE updated.assignees;

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62384000-0000-0000-0000-000000000001',
        'JOB_ORDER_CLARIFICATION_REQUIRED',
        'JOB_ORDER_CLARIFICATION_REQUIRED',
        'Default: tell current unit writers and assignees when an order needs an eligible responsible unit.',
        TRUE, TRUE);

INSERT INTO notification_rule_selector (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62384000-0000-0000-0000-000000000001',
        'ORG_RELATIVE_ROLE', 'UNIT_WRITER', 'RESPONSIBLE'),
       (gen_random_uuid(), '62384000-0000-0000-0000-000000000001',
        'ORG_RELATIVE_ROLE', 'UNIT_WRITER', 'REQUESTING'),
       (gen_random_uuid(), '62384000-0000-0000-0000-000000000001',
        'EVENT_RECIPIENTS', NULL, NULL);

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62384000-0000-0000-0000-000000000002',
        'JOB_ORDER_PARTICIPATION_CHANGED',
        'JOB_ORDER_PARTICIPATION_CHANGED',
        'Default: tell current Staffel and responsible unit writers when support starts or ends.',
        TRUE, TRUE);

INSERT INTO notification_rule_selector (id, rule_id, kind)
VALUES (gen_random_uuid(), '62384000-0000-0000-0000-000000000002', 'EVENT_RECIPIENTS');

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62384000-0000-0000-0000-000000000003',
        'JOB_ORDER_REOPENED',
        'JOB_ORDER_REOPENED',
        'Default: tell current unit writers and assignees when their order reopens.',
        TRUE, TRUE);

INSERT INTO notification_rule_selector (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62384000-0000-0000-0000-000000000003',
        'ORG_RELATIVE_ROLE', 'UNIT_WRITER', 'RESPONSIBLE'),
       (gen_random_uuid(), '62384000-0000-0000-0000-000000000003',
        'ORG_RELATIVE_ROLE', 'UNIT_WRITER', 'REQUESTING'),
       (gen_random_uuid(), '62384000-0000-0000-0000-000000000003',
        'EVENT_RECIPIENTS', NULL, NULL);

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62384000-0000-0000-0000-000000000004',
        'JOB_ORDER_UPDATED_BY_RESPONSIBLE',
        'JOB_ORDER_UPDATED_BY_RESPONSIBLE',
        'Default: notify current requesting and responsible unit writers when the responsible side edits the order.',
        TRUE, TRUE);

INSERT INTO notification_rule_selector (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62384000-0000-0000-0000-000000000004',
        'ORG_RELATIVE_ROLE', 'UNIT_WRITER', 'RESPONSIBLE'),
       (gen_random_uuid(), '62384000-0000-0000-0000-000000000004',
        'ORG_RELATIVE_ROLE', 'UNIT_WRITER', 'REQUESTING');

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000030', 'ORG_LEADERSHIP_ROLE_MISMATCH', 'ORG_LEADERSHIP_ROLE_MISMATCH', 'Default: tell the admins that a leadership change left a member''s OFFICER role out of step (REQ-ORG-029).', TRUE, FALSE),
       ('62200000-0000-0000-0000-000000000031', 'ORG_MEMBER_DEPARTED', 'ORG_MEMBER_DEPARTED', 'Default: tell the leadership of a unit that one of its members left the organisation (REQ-ORG-030).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, role_code)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000030', 'ROLE', 'ADMIN');

INSERT INTO notification_rule_selector
    (id, rule_id, kind, org_relative_role, context_role)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000031', 'ORG_RELATIVE_ROLE', 'UNIT_LEADERSHIP', 'RESPONSIBLE');

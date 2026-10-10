INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000032', 'HANGAR_SHIP_ASSIGNED_TO_UNIT', 'HANGAR_SHIP_ASSIGNED', 'Default: tell the owner that their ship was assigned to a mission unit (REQ-HANGAR-005).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000033', 'HANGAR_SHIP_DELETED_FROM_MISSION', 'HANGAR_SHIP_REMOVED_FROM_UNIT', 'Default: tell the mission leadership and the unit''s responsible member that a deleted ship drops out of a planned mission (REQ-HANGAR-006).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000034', 'HANGAR_FITTED_RESET_FOR_OWNER', 'HANGAR_FITTED_RESET', 'Default: tell a member that the fitted marks of their ships were reset (REQ-HANGAR-007).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000035', 'HANGAR_CHANGED_BY_ADMIN', 'HANGAR_CHANGED_BY_ADMIN', 'Default: tell a member that an admin changed their hangar (REQ-HANGAR-008).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000036', 'BLUEPRINT_CHANGED_BY_ADMIN', 'BLUEPRINT_CHANGED_BY_ADMIN', 'Default: tell a member that an admin changed their blueprints (REQ-HANGAR-008).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000037', 'BLUEPRINT_PURGED_BY_ADMIN', 'BLUEPRINT_PURGED_BY_ADMIN', 'Default: tell a member that an admin cleared every removable blueprint, theirs included (REQ-HANGAR-008).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000032', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000033', 'MISSION_LEADERSHIP'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000033', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000034', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000035', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000036', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000037', 'EVENT_RECIPIENT');

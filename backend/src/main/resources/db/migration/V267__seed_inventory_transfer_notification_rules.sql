INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-00000000000f',
        'INVENTORY_TRANSFERRED_TO_USER',
        'INVENTORY_TRANSFERRED_TO_USER',
        'Default: notify the member a Lager transfer booked stock onto, so they can trace the '
            'change in their stock (REQ-INV-055).',
        TRUE,
        TRUE),
       ('62200000-0000-0000-0000-000000000010',
        'INVENTORY_TRANSFERRED_FROM_USER',
        'INVENTORY_TRANSFERRED_FROM_USER',
        'Default: notify the member whose stock someone else transferred to another member, so '
            'they can trace why their stock went down (REQ-INV-055).',
        TRUE,
        TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000000f', 'EVENT_RECIPIENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000010', 'EVENT_RECIPIENT');

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000026', 'MATERIAL_EXCHANGE_OFFER_UNAVAILABLE', 'MATERIAL_EXCHANGE_OFFER_UNAVAILABLE', 'Default: tell the members who registered interest that an offer is gone (REQ-MARKET-021).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000027', 'MATERIAL_REQUEST_UNAVAILABLE', 'MATERIAL_REQUEST_UNAVAILABLE', 'Default: tell the members who signalled they can supply a request that it was withdrawn (REQ-MARKET-022).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000028', 'INVENTORY_BOOKED_OUT_BY_OTHER', 'INVENTORY_BOOKED_OUT_BY_OTHER', 'Default: tell a member that somebody else discarded or sold part of their stock (REQ-INV-056).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000026', 'EVENT_RECIPIENTS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000027', 'EVENT_RECIPIENTS'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000028', 'EVENT_RECIPIENT');

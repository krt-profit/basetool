ALTER TABLE exchange_settings
    ADD COLUMN bank_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN bank_version    BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN bank_updated_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN exchange_settings.bank_enabled IS
    'The bank switch: whether the bank route family of the exchange serves requests while the exchange is on; off until an admin switches it on (REQ-BXCH-006).';

COMMENT ON COLUMN exchange_settings.bank_version IS
    'The bank switch''s own optimistic-lock counter, independent of the row version that guards the global switch (REQ-BXCH-006).';

COMMENT ON COLUMN exchange_settings.bank_updated_at IS
    'When the bank switch last changed, or NULL before its first change (REQ-BXCH-006).';

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-00000000004e', 'BANK_EXCHANGE_SWITCHED_ON', 'BANK_EXCHANGE_SWITCHED_ON', 'Default: tell the admins and the Bankleitung that the bank was switched on for connected applications (REQ-BXCH-006).', TRUE, TRUE),
       ('62200000-0000-0000-0000-00000000004f', 'BANK_EXCHANGE_SWITCHED_OFF', 'BANK_EXCHANGE_SWITCHED_OFF', 'Default: tell the admins and the Bankleitung that the bank was switched off for connected applications (REQ-BXCH-006).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, role_code)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000004e', 'ROLE', 'ADMIN'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000004e', 'ROLE', 'BANK_MANAGEMENT'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000004f', 'ROLE', 'ADMIN'),
       (gen_random_uuid(), '62200000-0000-0000-0000-00000000004f', 'ROLE', 'BANK_MANAGEMENT');

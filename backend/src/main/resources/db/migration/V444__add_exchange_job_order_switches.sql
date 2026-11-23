ALTER TABLE exchange_settings
    ADD COLUMN job_orders_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN job_orders_version          BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN job_orders_updated_at       TIMESTAMP WITH TIME ZONE,
    ADD COLUMN job_order_writes_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN job_order_writes_version    BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN job_order_writes_updated_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN exchange_settings.job_orders_enabled IS
    'The job-order family switch: whether the job-order route family of the exchange serves requests while the exchange is on; off until an admin switches it on (REQ-OXCH-009).';

COMMENT ON COLUMN exchange_settings.job_orders_version IS
    'The job-order family switch''s own optimistic-lock counter, independent of every other switch''s version (REQ-OXCH-009).';

COMMENT ON COLUMN exchange_settings.job_orders_updated_at IS
    'When the job-order family switch last changed, or NULL before its first change (REQ-OXCH-009).';

COMMENT ON COLUMN exchange_settings.job_order_writes_enabled IS
    'The job-order writes switch: whether the job-order writes of the exchange are served while the family is on; off until an admin switches it on (REQ-OXCH-009).';

COMMENT ON COLUMN exchange_settings.job_order_writes_version IS
    'The job-order writes switch''s own optimistic-lock counter, independent of every other switch''s version (REQ-OXCH-009).';

COMMENT ON COLUMN exchange_settings.job_order_writes_updated_at IS
    'When the job-order writes switch last changed, or NULL before its first change (REQ-OXCH-009).';

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-000000000065', 'JOB_ORDER_EXCHANGE_SWITCHED_ON', 'JOB_ORDER_EXCHANGE_SWITCHED_ON', 'Default: tell the admins that job orders or their writes were switched on for connected applications (REQ-OXCH-009).', TRUE, TRUE),
       ('62200000-0000-0000-0000-000000000066', 'JOB_ORDER_EXCHANGE_SWITCHED_OFF', 'JOB_ORDER_EXCHANGE_SWITCHED_OFF', 'Default: tell the admins that job orders or their writes were switched off for connected applications (REQ-OXCH-009).', TRUE, TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, role_code)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-000000000065', 'ROLE', 'ADMIN'),
       (gen_random_uuid(), '62200000-0000-0000-0000-000000000066', 'ROLE', 'ADMIN');

ALTER TABLE navigation_preferences
    ADD COLUMN IF NOT EXISTS order_member_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS order_admin_version  BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS navigation_order (
    preferences_id UUID        NOT NULL,
    order_index    INTEGER     NOT NULL,
    mode           VARCHAR(8)  NOT NULL,
    scope          VARCHAR(64) NOT NULL,
    entry_key      VARCHAR(64) NOT NULL,
    CONSTRAINT pk_navigation_order PRIMARY KEY (preferences_id, order_index),
    CONSTRAINT uq_navigation_order_key UNIQUE (preferences_id, mode, scope, entry_key)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_navigation_order_preferences
        FOREIGN KEY (preferences_id) REFERENCES navigation_preferences (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_navigation_order_index CHECK (order_index >= 0),
    CONSTRAINT ck_navigation_order_mode CHECK (mode IN ('member', 'admin'))
);

ALTER TABLE navigation_preferences
    ADD COLUMN IF NOT EXISTS tab_bar_member_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tab_bar_admin_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS navigation_tab_bar (
    preferences_id UUID        NOT NULL,
    mode           VARCHAR(16) NOT NULL,
    slot_index     INTEGER     NOT NULL,
    entry_key      VARCHAR(64) NOT NULL,
    CONSTRAINT pk_navigation_tab_bar PRIMARY KEY (preferences_id, mode, slot_index),
    CONSTRAINT uq_navigation_tab_bar_key UNIQUE (preferences_id, mode, entry_key)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_navigation_tab_bar_preferences
        FOREIGN KEY (preferences_id) REFERENCES navigation_preferences (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_navigation_tab_bar_mode CHECK (mode IN ('MEMBER', 'ADMIN')),
    CONSTRAINT ck_navigation_tab_bar_slot CHECK (slot_index BETWEEN 0 AND 2)
);

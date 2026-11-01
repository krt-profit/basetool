ALTER TABLE navigation_preferences
    ADD COLUMN IF NOT EXISTS hidden_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS navigation_hidden (
    preferences_id UUID        NOT NULL,
    entry_key      VARCHAR(64) NOT NULL,
    CONSTRAINT pk_navigation_hidden PRIMARY KEY (preferences_id, entry_key),
    CONSTRAINT fk_navigation_hidden_preferences
        FOREIGN KEY (preferences_id) REFERENCES navigation_preferences (user_id) ON DELETE CASCADE
);

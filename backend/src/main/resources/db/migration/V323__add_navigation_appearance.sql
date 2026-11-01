ALTER TABLE navigation_preferences
    ADD COLUMN IF NOT EXISTS appearance_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS navigation_appearance (
    preferences_id UUID        PRIMARY KEY,
    desktop_mode   VARCHAR(16) NOT NULL DEFAULT 'DRAWER',
    CONSTRAINT fk_navigation_appearance_preferences
        FOREIGN KEY (preferences_id) REFERENCES navigation_preferences (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_navigation_appearance_desktop_mode
        CHECK (desktop_mode IN ('DRAWER', 'RAIL', 'PINNED'))
);

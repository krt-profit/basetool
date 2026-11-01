ALTER TABLE navigation_appearance
    ADD COLUMN IF NOT EXISTS shortcuts_enabled BOOLEAN NOT NULL DEFAULT TRUE;

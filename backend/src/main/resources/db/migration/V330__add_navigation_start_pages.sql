ALTER TABLE navigation_appearance
    ADD COLUMN IF NOT EXISTS start_page_member VARCHAR(64),
    ADD COLUMN IF NOT EXISTS start_page_admin  VARCHAR(64);

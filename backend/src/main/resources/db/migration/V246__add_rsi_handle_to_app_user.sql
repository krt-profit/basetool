ALTER TABLE app_user
    ADD COLUMN rsi_handle VARCHAR(60),
    ADD CONSTRAINT ck_app_user_rsi_handle_format
        CHECK (rsi_handle IS NULL OR rsi_handle ~ '^[A-Za-z0-9_-]{3,60}$');

CREATE UNIQUE INDEX ux_app_user_rsi_handle_lower
    ON app_user (LOWER(rsi_handle))
    WHERE rsi_handle IS NOT NULL;

COMMENT ON COLUMN app_user.rsi_handle IS
    'Optional RSI (Star Citizen) account handle the member entered on their own profile; unique case-insensitively; visible to the member and ADMIN only; REQ-SEC-072.';

ALTER TABLE app_user
    ADD COLUMN roles_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN app_user.roles_version IS
    'Optimistic counter of the user''s role set (user_roles), independent of app_user.version (REQ-SEC-088, ADR-0355).';

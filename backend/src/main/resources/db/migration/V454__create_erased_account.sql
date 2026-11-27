CREATE TABLE erased_account (
    id        UUID                     PRIMARY KEY,
    erased_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_erased_account_erased_at ON erased_account (erased_at);

COMMENT ON TABLE erased_account IS
    'The erasure register (REQ-DATA-048): the former app_user.id of every deleted account, so the account is never re-created from a surviving token or Keycloak user and its deletion is re-applied after a restore. Owned by the identity module; kept 12 months, longer than the backups.';
COMMENT ON COLUMN erased_account.id IS
    'The deleted account''s former app_user.id, the Keycloak subject. No foreign key: the account row is gone.';
COMMENT ON COLUMN erased_account.erased_at IS
    'When the account was deleted; the register drops the entry 12 months later.';

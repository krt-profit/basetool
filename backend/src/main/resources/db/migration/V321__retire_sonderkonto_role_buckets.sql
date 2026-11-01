WITH removed AS (
    DELETE FROM bank_account_view_grant
     WHERE grantee_kind = 'GLOBAL_ROLE'
    RETURNING account_id, role_code
)
INSERT INTO bank_audit_event
    (id, occurred_at, actor_user_id, actor_handle, event_type, account_id, details, client_id)
SELECT gen_random_uuid(), now(), NULL, 'system', 'BALANCE_VISIBILITY_REVOKED', account_id,
       'GLOBAL_ROLE:' || upper(role_code), 'none'
  FROM removed;

WITH removed AS (
    DELETE FROM bank_account_approval_limit
     WHERE grantee_kind = 'GLOBAL_ROLE'
    RETURNING account_id, role_code
)
INSERT INTO bank_audit_event
    (id, occurred_at, actor_user_id, actor_handle, event_type, account_id, details, client_id)
SELECT gen_random_uuid(), now(), NULL, 'system', 'APPROVAL_LIMIT_CLEARED', account_id,
       'GLOBAL_ROLE:' || upper(role_code), 'none'
  FROM removed;

ALTER TABLE bank_account_view_grant DROP CONSTRAINT IF EXISTS chk_bank_view_grant_kind;
ALTER TABLE bank_account_view_grant ADD CONSTRAINT chk_bank_view_grant_kind
    CHECK (grantee_kind IN ('MEMBERSHIP_ROLE', 'USER', 'ALL_MEMBERS', 'AREA_MEMBERS'));

ALTER TABLE bank_account_view_grant DROP CONSTRAINT IF EXISTS chk_bank_view_grant_payload;
ALTER TABLE bank_account_view_grant ADD CONSTRAINT chk_bank_view_grant_payload
    CHECK (
        (grantee_kind = 'MEMBERSHIP_ROLE' AND role_code IS NOT NULL AND grantee_user_id IS NULL)
        OR (grantee_kind = 'USER' AND grantee_user_id IS NOT NULL AND role_code IS NULL)
        OR (grantee_kind IN ('ALL_MEMBERS', 'AREA_MEMBERS')
            AND role_code IS NULL AND grantee_user_id IS NULL)
    );

ALTER TABLE bank_account_approval_limit DROP CONSTRAINT IF EXISTS chk_bank_appr_limit_kind;
ALTER TABLE bank_account_approval_limit ADD CONSTRAINT chk_bank_appr_limit_kind
    CHECK (grantee_kind IN ('MEMBERSHIP_ROLE', 'USER', 'ALL_MEMBERS', 'AREA_MEMBERS'));

ALTER TABLE bank_account_approval_limit DROP CONSTRAINT IF EXISTS chk_bank_appr_limit_payload;
ALTER TABLE bank_account_approval_limit ADD CONSTRAINT chk_bank_appr_limit_payload
    CHECK (
        (grantee_kind = 'MEMBERSHIP_ROLE' AND role_code IS NOT NULL AND grantee_user_id IS NULL)
        OR (grantee_kind = 'USER' AND grantee_user_id IS NOT NULL AND role_code IS NULL)
        OR (grantee_kind IN ('ALL_MEMBERS', 'AREA_MEMBERS')
            AND role_code IS NULL AND grantee_user_id IS NULL)
    );

COMMENT ON COLUMN bank_account_view_grant.grantee_kind IS
    'MEMBERSHIP_ROLE (role_code on the owning unit) | USER (grantee_user_id) | ALL_MEMBERS (owning-unit members) | AREA_MEMBERS (whole Bereich cascade, AREA accounts only).';
COMMENT ON COLUMN bank_account_approval_limit.grantee_kind IS
    'MEMBERSHIP_ROLE (role_code on the owning unit) | USER (grantee_user_id) | ALL_MEMBERS (owning-unit members) | AREA_MEMBERS (whole Bereich cascade, AREA accounts only).';

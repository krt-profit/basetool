WITH removed AS (
    DELETE FROM bank_account_view_grant
     WHERE grantee_kind = 'GLOBAL_ROLE' AND upper(role_code) = 'OFFICER'
    RETURNING account_id
)
INSERT INTO bank_audit_event
    (id, occurred_at, actor_user_id, actor_handle, event_type, account_id, details, client_id)
SELECT gen_random_uuid(), now(), NULL, 'system', 'BALANCE_VISIBILITY_REVOKED', account_id,
       'GLOBAL_ROLE:OFFICER', 'none'
  FROM removed;

WITH removed AS (
    DELETE FROM bank_account_approval_limit
     WHERE grantee_kind = 'GLOBAL_ROLE' AND upper(role_code) = 'OFFICER'
    RETURNING account_id
)
INSERT INTO bank_audit_event
    (id, occurred_at, actor_user_id, actor_handle, event_type, account_id, details, client_id)
SELECT gen_random_uuid(), now(), NULL, 'system', 'APPROVAL_LIMIT_CLEARED', account_id,
       'GLOBAL_ROLE:OFFICER', 'none'
  FROM removed;

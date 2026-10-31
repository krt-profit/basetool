SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;
\pset footer off

WITH hit AS (
    SELECT m.user_id, m.org_unit_id, m.kind, m.role, u.in_keycloak, u.enabled_in_keycloak
      FROM org_unit_membership m
      JOIN app_user u ON u.id = m.user_id
     WHERE m.role <> 'MEMBER'
       AND NOT EXISTS (
           SELECT 1 FROM user_roles ur JOIN role r ON r.id = ur.role_id
            WHERE ur.user_id = m.user_id AND upper(r.code) = 'KRT_MEMBER')
)
SELECT line FROM (
    SELECT 0 AS part, 'seat_without_krt_member|count=' || count(*) AS line FROM hit
    UNION ALL
    SELECT 1, 'seat_without_krt_member|user=' || user_id || '|unit=' || org_unit_id || '|kind=' || kind
        || '|rank=' || role || '|in_keycloak=' || in_keycloak || '|enabled=' || enabled_in_keycloak
      FROM hit
) report ORDER BY part, line;

WITH hit AS (
    SELECT DISTINCT ur.user_id
      FROM user_roles ur JOIN role r ON r.id = ur.role_id
     WHERE upper(r.code) = 'OFFICER'
       AND NOT EXISTS (
           SELECT 1 FROM org_unit_membership m WHERE m.user_id = ur.user_id AND m.role <> 'MEMBER')
)
SELECT line FROM (
    SELECT 0 AS part, 'officer_without_seat|count=' || count(*) AS line FROM hit
    UNION ALL
    SELECT 1, 'officer_without_seat|user=' || user_id FROM hit
) report ORDER BY part, line;

WITH hit AS (
    SELECT g.id, g.account_id, a.type
      FROM bank_account_view_grant g JOIN bank_account a ON a.id = g.account_id
     WHERE g.grantee_kind = 'GLOBAL_ROLE' AND upper(g.role_code) = 'OFFICER'
)
SELECT line FROM (
    SELECT 0 AS part, 'bank_view_grant_officer|count=' || count(*) AS line FROM hit
    UNION ALL
    SELECT 1, 'bank_view_grant_officer|grant=' || id || '|account=' || account_id || '|type=' || type
      FROM hit
) report ORDER BY part, line;

WITH hit AS (
    SELECT l.id, l.account_id, a.type
      FROM bank_account_approval_limit l JOIN bank_account a ON a.id = l.account_id
     WHERE l.grantee_kind = 'GLOBAL_ROLE' AND upper(l.role_code) = 'OFFICER'
)
SELECT line FROM (
    SELECT 0 AS part, 'bank_approval_limit_officer|count=' || count(*) AS line FROM hit
    UNION ALL
    SELECT 1, 'bank_approval_limit_officer|limit=' || id || '|account=' || account_id || '|type=' || type
      FROM hit
) report ORDER BY part, line;

WITH hit AS (
    SELECT s.id, s.rule_id, n.event_type, n.enabled
      FROM notification_rule_selector s JOIN notification_rule n ON n.id = s.rule_id
     WHERE s.kind = 'ROLE' AND upper(s.role_code) = 'OFFICER'
)
SELECT line FROM (
    SELECT 0 AS part, 'notification_rule_role_officer|count=' || count(*) AS line FROM hit
    UNION ALL
    SELECT 1, 'notification_rule_role_officer|selector=' || id || '|rule=' || rule_id
        || '|event=' || event_type || '|enabled=' || enabled
      FROM hit
) report ORDER BY part, line;

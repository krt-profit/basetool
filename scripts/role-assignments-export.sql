-- One '<user id><TAB><role code>' line per assignment of the four application roles (ADR-0355),
-- the input of scripts/restore-keycloak-realm-roles.py. Ids and codes only, no names.
SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;

SELECT ur.user_id || E'\t' || r.code
  FROM user_roles ur
  JOIN role r ON r.id = ur.role_id
 WHERE r.code IN ('ADMIN', 'KRT_MEMBER', 'BANK_EMPLOYEE', 'BANK_MANAGEMENT')
 ORDER BY ur.user_id, r.code;

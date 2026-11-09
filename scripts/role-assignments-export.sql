-- One '<user id><TAB><role code>' line per assignment of the four application roles (ADR-0355),
-- and one '<user id><TAB>DEPARTED' line per approved account present in Keycloak that does not hold
-- KRT Member, the input of scripts/restore-keycloak-realm-roles.py. Ids and codes only, no names.
SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;

SELECT export.line
  FROM (SELECT ur.user_id AS user_id, r.code AS code, ur.user_id || E'\t' || r.code AS line
          FROM user_roles ur
          JOIN role r ON r.id = ur.role_id
         WHERE r.code IN ('ADMIN', 'KRT_MEMBER', 'BANK_EMPLOYEE', 'BANK_MANAGEMENT')
        UNION ALL
        SELECT u.id, 'DEPARTED', u.id || E'\tDEPARTED'
          FROM app_user u
         WHERE u.approval_status = 'ACTIVE'
           AND u.in_keycloak IS NOT FALSE
           AND NOT EXISTS (SELECT 1
                             FROM user_roles ur
                             JOIN role r ON r.id = ur.role_id
                            WHERE ur.user_id = u.id
                              AND r.code = 'KRT_MEMBER')) AS export
 ORDER BY export.user_id, export.code;

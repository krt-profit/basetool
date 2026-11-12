BEGIN READ ONLY;

SELECT count(*) AS unknown_role_permissions
FROM role_permissions
WHERE permission IS NULL OR permission NOT IN (
    'HANGAR_READ', 'HANGAR_WRITE', 'MISSION_READ', 'MISSION_WRITE', 'MISSION_MANAGE',
    'USER_MANAGE', 'ROLE_MANAGE'
);

COMMIT;

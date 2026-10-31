DO $$
DECLARE
    affected TEXT;
BEGIN
    SELECT string_agg(ur.user_id::TEXT, ', ' ORDER BY ur.user_id::TEXT)
      INTO affected
      FROM user_roles ur
      JOIN role r ON r.id = ur.role_id
     WHERE upper(r.code) = 'OFFICER';

    IF affected IS NOT NULL THEN
        RAISE WARNING 'Retiring the OFFICER role from user ids: %', affected;
    END IF;
END
$$;

DELETE FROM user_roles
 WHERE role_id IN (SELECT id FROM role WHERE upper(code) = 'OFFICER');

DELETE FROM role_permissions
 WHERE role_id IN (SELECT id FROM role WHERE upper(code) = 'OFFICER');

DELETE FROM role
 WHERE upper(code) = 'OFFICER';

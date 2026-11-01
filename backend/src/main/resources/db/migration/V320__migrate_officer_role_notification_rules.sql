DELETE FROM notification_rule_selector s
 WHERE s.kind = 'ROLE'
   AND upper(s.role_code) = 'OFFICER'
   AND EXISTS (
       SELECT 1 FROM notification_rule_selector o
        WHERE o.rule_id = s.rule_id AND o.kind = 'SEAT_HOLDERS');

UPDATE notification_rule_selector
   SET kind = 'SEAT_HOLDERS', role_code = NULL
 WHERE kind = 'ROLE' AND upper(role_code) = 'OFFICER';

UPDATE notification_rule
   SET description = 'Default: tell the admins that a seat holder lacks the KRT Member role (REQ-ORG-029).'
 WHERE id = '62200000-0000-0000-0000-000000000030'
   AND description LIKE '%OFFICER role%';

UPDATE notification_rule
   SET description = 'Default: tell the mission leadership and the unit''s responsible member that an assigned ship leaves a planned mission (REQ-HANGAR-006).'
 WHERE id = '62200000-0000-0000-0000-000000000033'
   AND description = 'Default: tell the mission leadership and the unit''s responsible member that a deleted ship drops out of a planned mission (REQ-HANGAR-006).';

UPDATE notification_rule
   SET event_type = 'HANGAR_SHIP_LEFT_MISSION',
       version    = version + 1,
       updated_at = now()
 WHERE event_type = 'HANGAR_SHIP_DELETED_FROM_MISSION';

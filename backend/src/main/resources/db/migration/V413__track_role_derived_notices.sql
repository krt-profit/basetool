ALTER TABLE notification ADD COLUMN role_audience VARCHAR(32) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE notification ADD CONSTRAINT ck_notification_role_audience
    CHECK (role_audience IN ('DIRECT', 'MEMBER', 'BANK_STAFF', 'MEMBER_OR_BANK_STAFF'));

UPDATE notification AS n
SET role_audience = 'MEMBER'
WHERE n.type IN (
    'JOB_ORDER_CREATED', 'JOB_ORDER_UPDATED_BY_REQUESTER', 'JOB_ORDER_UPDATED_BY_RESPONSIBLE',
    'JOB_ORDER_CLAIM_WITHDRAWN', 'JOB_ORDER_PARTICIPATION_CHANGED', 'INVENTORY_STOCK_LEVEL_LOW',
    'SUPPLY_DONATION_PLEDGED', 'SUPPLY_NEED_ORDER_COVERED', 'BANK_ACCOUNT_RESPONSIBLE_ASSIGNED',
    'BANK_ACCOUNT_DEBITED', 'BANK_FUNDS_AUTHORISED', 'ORG_MEMBER_DEPARTED', 'PURCHASING_DELIVERY_RECORDED'
)
AND NOT EXISTS (
    SELECT 1
    FROM user_roles AS ur
    JOIN role AS r ON r.id = ur.role_id
    WHERE ur.user_id = n.recipient_user_id AND r.code = 'ADMIN' AND n.type = 'JOB_ORDER_CREATED'
);

UPDATE notification AS n
SET role_audience = 'MEMBER'
FROM bank_booking_request AS request
WHERE n.entity_type = 'BANK_BOOKING_REQUEST' AND n.entity_id = request.id
  AND n.type = 'BANK_BOOKING_REQUEST_APPROVAL_NEEDED'
  AND request.required_approver IS DISTINCT FROM 'BANK_MANAGEMENT';

UPDATE notification AS n
SET role_audience = 'BANK_STAFF'
WHERE n.type = 'BANK_GRANT_CHANGED';

WITH booking_audiences AS (
    SELECT n.id,
           EXISTS (
               SELECT 1 FROM bank_account_grant AS g
               WHERE g.account_id = account.id AND g.user_id = n.recipient_user_id
           ) AS bank_staff,
           EXISTS (
               SELECT 1 FROM org_unit_membership AS m
               JOIN org_unit AS unit ON unit.id = m.org_unit_id
               WHERE m.user_id = n.recipient_user_id
                 AND (
                     (unit.id = account.org_unit_id AND account.type = 'ORG_UNIT'
                         AND m.role IN ('STAFFELLEITER', 'SK_LEAD'))
                     OR (unit.id = account.org_unit_id AND account.type = 'AREA'
                         AND m.role = 'BEREICHSLEITER')
                     OR (account.type = 'CARTEL_BANK' AND unit.department = 'PROFIT'
                         AND m.role = 'BEREICHSLEITER')
                 )
           ) AS member
    FROM notification AS n
    JOIN bank_booking_request AS request ON n.entity_id = request.id
    JOIN bank_account AS account ON request.account_id = account.id
    WHERE n.entity_type = 'BANK_BOOKING_REQUEST'
      AND n.type IN ('BANK_BOOKING_REQUEST_CREATED', 'BANK_BOOKING_REQUEST_UPDATED',
                     'BANK_BOOKING_REQUEST_APPROVED')
      AND n.recipient_user_id IS DISTINCT FROM request.requested_by
      AND NOT EXISTS (
          SELECT 1 FROM user_roles AS ur
          JOIN role AS r ON r.id = ur.role_id
          WHERE ur.user_id = n.recipient_user_id AND r.code = 'BANK_MANAGEMENT'
      )
)
UPDATE notification AS n
SET role_audience = CASE
    WHEN audience.member AND audience.bank_staff THEN 'MEMBER_OR_BANK_STAFF'
    WHEN audience.member THEN 'MEMBER'
    ELSE 'BANK_STAFF'
END
FROM booking_audiences AS audience
WHERE n.id = audience.id AND (audience.member OR audience.bank_staff);

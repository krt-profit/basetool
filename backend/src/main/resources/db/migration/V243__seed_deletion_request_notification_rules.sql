-- =====================================================================
-- V243 - notification rules for the Art. 17 erasure requests
--        (REQ-SEC-061, REQ-NOTIF-007)
-- =====================================================================
-- Two rules, and each exists because of a deadline rather than as a
-- courtesy:
--
--   1. ACCOUNT_DELETION_REQUESTED -> every admin. Art. 12(3) gives the
--      controller ONE MONTH to respond to a data-subject request. A
--      queue nobody is told about is exactly how that month passes, so
--      the notification is part of meeting the obligation, not a nicety
--      layered on top of it. A ROLE selector on 'ADMIN', the same shape
--      V174 uses for a pending registration.
--
--   2. ACCOUNT_DELETION_REQUEST_DECLINED -> the requesting member, via
--      the EVENT_RECIPIENT selector (which reads the recipient off the
--      event and needs no selector columns). Art. 12(4) obliges the
--      controller to TELL the requester when a request is refused,
--      together with their right to complain to a supervisory authority
--      and their right to a judicial remedy. Leaving that to an admin
--      remembering to write is how it gets missed.
--
-- WHY THERE IS NO RULE FOR A CARRIED-OUT REQUEST. The recipient would
-- be an account that no longer exists: executing the request deletes it,
-- and notification.recipient_user_id is ON DELETE CASCADE (V235), so the
-- row would be written and immediately removed. Telling the member their
-- account is gone is an out-of-band act by definition.
--
-- exclude_actor = FALSE on the first rule: the requesting member is the
-- actor and is not an admin recipient anyway, so the flag would decide
-- nothing. Stated rather than left to chance, because a future rule edit
-- that widened the recipients would then have to think about it.
-- =====================================================================

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES ('62200000-0000-0000-0000-00000000000b',
        'ACCOUNT_DELETION_REQUESTED',
        'ACCOUNT_DELETION_REQUESTED',
        'Default: notify all admins when a member requests erasure of their account (Art. 17). '
            'Art. 12(3) sets a one-month response deadline.',
        TRUE,
        FALSE),
       ('62200000-0000-0000-0000-00000000000c',
        'ACCOUNT_DELETION_REQUEST_DECLINED',
        'ACCOUNT_DELETION_REQUEST_DECLINED',
        'Default: notify the requesting member when their erasure request is refused. '
            'Art. 12(4) requires informing them.',
        TRUE,
        FALSE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, role_code)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000000b', 'ROLE', 'ADMIN');

-- EVENT_RECIPIENT reads no selector columns at all: the recipient comes
-- off the event (REQ-NOTIF-007), which is what lets the bank's booking
-- lifecycle and this share one engine.
INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES (gen_random_uuid(), '62200000-0000-0000-0000-00000000000c', 'EVENT_RECIPIENT');

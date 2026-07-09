-- =====================================================================
-- V211 - Materialboerse: notify the owner on an interest registration
-- =====================================================================
-- Why: an offer owner ("Anbieter") had to poll the board to learn whether
-- anyone registered interest in one of their listings (#1187, REQ-MARKET-011).
-- This reuses the data-driven notification rule engine (epic #622,
-- REQ-NOTIF-007, ADR-0015) exactly like the bank decision notifications
-- (V161): the MATERIAL_EXCHANGE_INTEREST_REGISTERED event is mapped to a
-- same-named notification whose sole recipient is the offer owner, resolved
-- by the EVENT_RECIPIENT selector kind, which reads the directed recipient
-- off the event (NotificationEvent#contextRecipientSub). The registering
-- member is the event actor and is excluded (exclude_actor = TRUE) -- moot
-- in practice because a member can never register interest in their own
-- offer, so actor and recipient are always distinct. Like the other seed
-- rules this one is admin-editable and -deletable at runtime; neither type
-- column carries a CHECK (the @Enumerated(STRING) mappings are the source of
-- truth), so no schema change to notification/notification_rule is needed.
--
-- Rollback: DELETE the rule (its selector cascades via the V156 FK).

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES
    ('62200000-0000-0000-0000-000000000008',
     'MATERIAL_EXCHANGE_INTEREST_REGISTERED',
     'MATERIAL_EXCHANGE_INTEREST_REGISTERED',
     'Default: notify the offering member when someone registers interest in their material-exchange listing.',
     TRUE,
     TRUE);

-- EVENT_RECIPIENT resolves the offer owner carried by the event; it populates
-- no selector columns of its own.
INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES
    (gen_random_uuid(), '62200000-0000-0000-0000-000000000008', 'EVENT_RECIPIENT');

-- =====================================================================
-- V225 - Materialboerse: notify the requester on a fulfilment signal
-- =====================================================================
-- Why: a request owner ("Suchende") had to poll the board to learn whether
-- anyone can supply one of their Gesuche (REQ-MARKET-020). This reuses the
-- data-driven notification rule engine (epic #622, REQ-NOTIF-007, ADR-0015)
-- exactly like the material-exchange interest notification (V211): the
-- MATERIAL_REQUEST_FULFILLMENT_SIGNALLED event is mapped to a same-named
-- notification whose sole recipient is the request owner, resolved by the
-- EVENT_RECIPIENT selector kind, which reads the directed recipient off the
-- event (NotificationEvent#contextRecipientSub). The signalling member is the
-- event actor and is excluded (exclude_actor = TRUE) -- moot in practice
-- because a member can never signal fulfilment on their own request, so actor
-- and recipient are always distinct. Like the other seed rules this one is
-- admin-editable and -deletable at runtime; neither type column carries a
-- CHECK (the @Enumerated(STRING) mappings are the source of truth), so no
-- schema change to notification/notification_rule is needed.
--
-- Rollback: DELETE the rule (its selector cascades via the V156 FK).

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES
    ('62200000-0000-0000-0000-00000000000a',
     'MATERIAL_REQUEST_FULFILLMENT_SIGNALLED',
     'MATERIAL_REQUEST_FULFILLMENT_SIGNALLED',
     'Default: notify the requesting member when someone signals they can fulfil their material-exchange request.',
     TRUE,
     TRUE);

-- EVENT_RECIPIENT resolves the request owner carried by the event; it populates
-- no selector columns of its own.
INSERT INTO notification_rule_selector
    (id, rule_id, kind)
VALUES
    (gen_random_uuid(), '62200000-0000-0000-0000-00000000000a', 'EVENT_RECIPIENT');

-- =====================================================================
-- V213 - Notify the processing unit when the requester edits an order
-- =====================================================================
-- Why: a requesting owner (Auftraggeber) can now view and edit their own
-- job orders within limits (#1186, REQ-ORDERS-023). When they change
-- quantities, add/remove not-yet-delivered items or materials, or edit the
-- comment, the processing (responsible) squadron/SK must learn about it.
-- This reuses the data-driven notification rule engine (epic #622,
-- REQ-NOTIF-007, ADR-0015) exactly like the job-order-created rule (V156,
-- UC1): the JOB_ORDER_UPDATED_BY_REQUESTER event is mapped to a same-named
-- notification whose recipients are the OFFICERs and LEADs of the event's
-- RESPONSIBLE org unit, resolved by the ORG_RELATIVE_ROLE selector kind. The
-- editing member is the event actor and is excluded (exclude_actor = TRUE) --
-- moot in practice because recipients resolve from the responsible unit while
-- the actor belongs to the requesting unit, but kept for the edge case of a
-- member holding a seat in both. Unlike UC1 this rule deliberately omits the
-- LOGISTICIAN and global-ADMIN recipients (the issue scopes it to officers and
-- leads); it stays admin-editable at runtime, so an org can widen it. Neither
-- type column carries a CHECK (the @Enumerated(STRING) mappings are the source
-- of truth, V154/V156 precedent), so no schema change is needed.
--
-- Rollback: DELETE the rule (its selectors cascade via the V156 FK).

INSERT INTO notification_rule
    (id, event_type, notification_type, description, enabled, exclude_actor)
VALUES
    ('62200000-0000-0000-0000-000000000009',
     'JOB_ORDER_UPDATED_BY_REQUESTER',
     'JOB_ORDER_UPDATED_BY_REQUESTER',
     'Default: notify the processing unit''s officers and leads when the requesting owner edits their order.',
     TRUE,
     TRUE);

INSERT INTO notification_rule_selector
    (id, rule_id, kind, org_relative_role, context_role)
VALUES
    (gen_random_uuid(), '62200000-0000-0000-0000-000000000009', 'ORG_RELATIVE_ROLE', 'OFFICER',
     'RESPONSIBLE'),
    (gen_random_uuid(), '62200000-0000-0000-0000-000000000009', 'ORG_RELATIVE_ROLE', 'LEAD',
     'RESPONSIBLE');

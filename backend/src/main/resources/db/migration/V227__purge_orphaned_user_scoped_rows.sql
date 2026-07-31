-- =====================================================================
-- V227 - Purge rows left behind by earlier user deletions (REQ-DATA-008)
-- =====================================================================
-- Why: five tables identify their owner by the Keycloak subject stored as
-- a plain column with NO foreign key to app_user. Nothing cascaded when an
-- account was hard-deleted and no retention job reaches them, so every user
-- deletion so far leaked rows that survive their owner forever. Since this
-- release UserDeletionService purges them inside the delete transaction;
-- this migration cleans up what the previous behaviour already left behind.
--
-- The owner columns hold app_user.id: for personal_blueprint,
-- personal_inventory_item and member_evaluation as VARCHAR text, for
-- notification and notification_rule_selector as a native UUID. The text
-- variants are compared via app_user.id::text so the index-friendly form
-- stays on the literal side and a malformed non-UUID value can never abort
-- the statement with 22P02 (an explicit ::uuid cast on the column would).
--
-- Deliberately NOT touched:
--   * audit_event / bank_audit_event - the audit trail must outlive the
--     account (REQ-AUDIT-001); their handle columns are NOT NULL snapshots,
--     so a dangling target_user_id still renders.
--   * mission_participant - historical participation is preserved and now
--     renders as the deleted-user placeholder.
--
-- Rollback: none possible (rows are gone). The deleted rows belong to
-- accounts that no longer exist, so nothing in the app can reference them.

DELETE FROM personal_blueprint b
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id::text = b.owner_sub);

DELETE FROM personal_inventory_item p
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id::text = p.owner_sub);

DELETE FROM member_evaluation m
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id::text = m.user_id);

DELETE FROM notification n
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = n.recipient_sub);

-- Only SPECIFIC_USER selectors carry a user_sub; the other kinds leave it
-- NULL and must not be touched. A rule left without selectors is kept on
-- purpose: it may still carry role- or org-relative ones, and an emptied
-- rule is an admin-visible configuration question rather than something a
-- cleanup migration should silently decide.
DELETE FROM notification_rule_selector s
WHERE s.user_sub IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = s.user_sub);

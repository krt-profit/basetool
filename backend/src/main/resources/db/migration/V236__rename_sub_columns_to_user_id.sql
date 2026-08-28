-- =====================================================================
-- V236 - One name for the user identifier: rename the four *_sub columns
--        (ADR-0142 points 1 + 2, issue #1640)
-- =====================================================================
-- 34 columns hold an app_user.id under a *_user_id name; four held the same
-- value under a *_sub one. The value never differed -- app_user.id IS written
-- with the token's subject -- so the two names were a synonym pair that every
-- reader had to learn, and one half of it names the identity provider in the
-- schema. ADR-0142 point 2 settles that: the Keycloak `sub` is an
-- authentication INPUT, not a name for anything downstream.
--
--   notification.recipient_sub            -> recipient_user_id
--   notification_rule_selector.user_sub   -> user_id
--   personal_blueprint.owner_sub          -> owner_user_id
--   personal_inventory_item.owner_sub     -> owner_user_id
--
-- member_evaluation.user_id already carried the right name.
--
-- RENAME COLUMN is a catalogue-only operation: no table rewrite, no index
-- rebuild, and the ACCESS EXCLUSIVE lock is held only for the catalogue
-- update. That is why this ships separately from V235's casts rather than
-- folded into them -- there was nothing to save by combining them, and a
-- rename reviews very differently from a data-integrity fix.
--
-- No index or constraint name embeds the old column name, so none is renamed.
-- V235's foreign keys were deliberately named for the referenced thing
-- (fk_notification_recipient, fk_personal_blueprint_owner, ...) so this
-- migration is a rename and nothing else.
--
-- Rollback: rename back. No data moves.

ALTER TABLE notification
    RENAME COLUMN recipient_sub TO recipient_user_id;

ALTER TABLE notification_rule_selector
    RENAME COLUMN user_sub TO user_id;

ALTER TABLE personal_blueprint
    RENAME COLUMN owner_sub TO owner_user_id;

ALTER TABLE personal_inventory_item
    RENAME COLUMN owner_sub TO owner_user_id;

-- The comments V235 rewrote name the columns; keep them accurate.
COMMENT ON COLUMN notification.recipient_user_id IS
    'app_user.id of the sole recipient. FK ON DELETE CASCADE since V235 (REQ-DATA-008); the inbox is isolated by this column, not org-unit scoped (REQ-NOTIF-004).';
COMMENT ON COLUMN notification_rule_selector.user_id IS
    'app_user.id targeted by a SPECIFIC_USER selector; NULL for every other kind. FK ON DELETE CASCADE since V235 (REQ-DATA-008).';
COMMENT ON COLUMN personal_blueprint.owner_user_id IS
    'app_user.id of the owning user. All non-admin queries MUST filter by this column. FK ON DELETE CASCADE since V235 (REQ-DATA-008).';
COMMENT ON COLUMN personal_inventory_item.owner_user_id IS
    'app_user.id of the owning user. All non-admin queries MUST filter by this column. FK ON DELETE CASCADE since V235 (REQ-DATA-008).';

-- Two table comments named the old column; a stale one is worse than none.
COMMENT ON TABLE notification IS
    'Generic per-user notification inbox (epic #622, REQ-NOTIF-001). One row per recipient per event; isolated by recipient_user_id, not org-unit scoped.';
COMMENT ON TABLE personal_blueprint IS
    'Per-user record of unlocked crafting blueprints (#327). Ownership is per product (output item), keyed by normalized product_key. The owner is an app_user.id.';

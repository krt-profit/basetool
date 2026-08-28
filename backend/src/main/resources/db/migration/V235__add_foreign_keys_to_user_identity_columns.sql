-- =====================================================================
-- V235 - Foreign keys for the five user-identity columns
--        (REQ-DATA-008, ADR-0142 point 3, issue #1638)
-- =====================================================================
-- Five columns hold an app_user.id without a foreign key to it, so nothing
-- cascades and no retention job reaches them. V227 purged what earlier
-- deletions had already leaked and UserDeletionService now purges them inside
-- the delete transaction -- but that is a promise kept in code. A delete path
-- that forgets one of the five leaks again, silently, and the leaked rows are
-- re-adopted if the same Keycloak subject ever returns. This migration moves
-- the guarantee into the database.
--
-- Three of the five hold the id as text and must be cast before a foreign key
-- can reference the UUID primary key. Note ADR-0142's inventory listed
-- member_evaluation.user_id as UUID; it is VARCHAR(64) (V72), which V227's own
-- comment already had right. The ADR is corrected alongside this migration.
--
--   notification.recipient_sub                UUID        -> FK only
--   notification_rule_selector.user_sub       UUID        -> FK only
--   personal_blueprint.owner_sub              VARCHAR(64) -> cast + FK
--   personal_inventory_item.owner_sub         VARCHAR(64) -> cast + FK
--   member_evaluation.user_id                 VARCHAR(64) -> cast + FK
--
-- ON DELETE CASCADE on all five, which is what UserDeletionService already
-- does by hand today -- this migration encodes the existing behaviour rather
-- than changing it. Per table:
--
--   * notification            - an inbox row for an account that no longer
--                               exists can never be read or dismissed.
--   * notification_rule_selector - the worst of the five: left in place it
--                               keeps MINTING new notifications for a
--                               recipient that no longer exists, on every
--                               subsequent matching event.
--   * personal_blueprint      - account-owned data, free-text notes included.
--   * personal_inventory_item - account-owned data, free-text notes included.
--   * member_evaluation       - the promotion assessment OF the departed
--                               member; it evaluates a person, not an event,
--                               so it has no meaning without them.
--
-- Deliberately NOT given a foreign key, and now said so in the schema rather
-- than left as an absence: audit_event.target_user_id (V179) and
-- bank_audit_event.target_user_id (V154). The audit trail must outlive the
-- account (REQ-AUDIT-001); both tables carry NOT NULL handle snapshots, so a
-- dangling target still renders.
--
-- Rollback: DROP the five constraints. The type casts are not reversible
-- without a rewrite back to VARCHAR, and the purge below is not reversible at
-- all -- but every row it removes belongs to an account that no longer exists,
-- so nothing in the application can reference them.

-- ---------------------------------------------------------------------
-- 1. Purge orphans one more time, or the constraints below cannot be added.
-- ---------------------------------------------------------------------
-- Since V227 the deletion path purges these rows in-transaction, so on a
-- healthy database these statements delete nothing. They are here because
-- ADD CONSTRAINT validates the whole table and aborts the deploy on the first
-- surviving orphan -- exactly the failure this migration exists to prevent
-- from recurring, and not one to discover during a release.
--
-- The text columns are compared via app_user.id::text, keeping the cast on
-- the literal side: a malformed non-UUID value can then never abort the
-- statement with 22P02 (an explicit ::uuid cast on the column would), and it
-- is still removed, because a malformed string equals no rendered id.
DELETE FROM personal_blueprint b
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id::text = b.owner_sub);

DELETE FROM personal_inventory_item p
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id::text = p.owner_sub);

DELETE FROM member_evaluation m
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id::text = m.user_id);

DELETE FROM notification n
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = n.recipient_sub);

-- Only SPECIFIC_USER selectors carry a user_sub; the other kinds leave it
-- NULL and must not be touched (V227). A rule left without selectors is kept
-- on purpose: it may still carry role- or org-relative ones, and an emptied
-- rule is an admin-visible configuration question rather than something a
-- cleanup migration should silently decide.
DELETE FROM notification_rule_selector s
WHERE s.user_sub IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = s.user_sub);

-- ---------------------------------------------------------------------
-- 2. Cast the three text columns to UUID.
-- ---------------------------------------------------------------------
-- Each rewrites its table and rebuilds the indexes and the unique constraint
-- over the column. All three tables are per-member data at squadron scale, so
-- the rewrite is short; the ACCESS EXCLUSIVE lock it takes is the reason this
-- ships in a release window rather than beside a hot migration.
ALTER TABLE personal_blueprint
    ALTER COLUMN owner_sub TYPE UUID USING owner_sub::uuid;

ALTER TABLE personal_inventory_item
    ALTER COLUMN owner_sub TYPE UUID USING owner_sub::uuid;

ALTER TABLE member_evaluation
    ALTER COLUMN user_id TYPE UUID USING user_id::uuid;

-- ---------------------------------------------------------------------
-- 3. The missing child index.
-- ---------------------------------------------------------------------
-- The other four columns already lead an index (V155 x2, V126, V65, V72), so
-- the cascade's lookup is served. user_sub had none: without it every
-- app_user delete sequentially scans notification_rule_selector. Partial,
-- because only SPECIFIC_USER selectors populate the column.
CREATE INDEX idx_notification_rule_selector_user
    ON notification_rule_selector (user_sub)
    WHERE user_sub IS NOT NULL;

-- ---------------------------------------------------------------------
-- 4. The foreign keys.
-- ---------------------------------------------------------------------
-- Constraint names deliberately carry the referenced thing rather than the
-- current column name, so the ADR-0142 rename of *_sub -> *_user_id (#1640)
-- is a column rename and nothing else.
ALTER TABLE notification
    ADD CONSTRAINT fk_notification_recipient
        FOREIGN KEY (recipient_sub) REFERENCES app_user (id) ON DELETE CASCADE;

ALTER TABLE notification_rule_selector
    ADD CONSTRAINT fk_notification_rule_selector_user
        FOREIGN KEY (user_sub) REFERENCES app_user (id) ON DELETE CASCADE;

ALTER TABLE personal_blueprint
    ADD CONSTRAINT fk_personal_blueprint_owner
        FOREIGN KEY (owner_sub) REFERENCES app_user (id) ON DELETE CASCADE;

ALTER TABLE personal_inventory_item
    ADD CONSTRAINT fk_personal_inventory_item_owner
        FOREIGN KEY (owner_sub) REFERENCES app_user (id) ON DELETE CASCADE;

ALTER TABLE member_evaluation
    ADD CONSTRAINT fk_member_evaluation_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE;

-- ---------------------------------------------------------------------
-- 5. Refresh the column documentation.
-- ---------------------------------------------------------------------
-- The old texts describe the state this migration ends: "Keycloak JWT sub"
-- for a column that is an app_user foreign key, and "loose reference, no FK"
-- for one that now has exactly that.
COMMENT ON COLUMN notification.recipient_sub IS
    'app_user.id of the sole recipient. FK ON DELETE CASCADE since V235 (REQ-DATA-008); the inbox is isolated by this column, not org-unit scoped (REQ-NOTIF-004).';
COMMENT ON COLUMN notification_rule_selector.user_sub IS
    'app_user.id targeted by a SPECIFIC_USER selector; NULL for every other kind. FK ON DELETE CASCADE since V235 (REQ-DATA-008).';
COMMENT ON COLUMN personal_blueprint.owner_sub IS
    'app_user.id of the owning user. All non-admin queries MUST filter by this column. FK ON DELETE CASCADE since V235 (REQ-DATA-008).';
COMMENT ON COLUMN personal_inventory_item.owner_sub IS
    'app_user.id of the owning user. All non-admin queries MUST filter by this column. FK ON DELETE CASCADE since V235 (REQ-DATA-008).';
COMMENT ON COLUMN member_evaluation.user_id IS
    'app_user.id of the evaluated member. FK ON DELETE CASCADE since V235 (REQ-DATA-008).';

-- The two deliberate exemptions, stated rather than absent (ADR-0142 point 3).
COMMENT ON COLUMN audit_event.target_user_id IS
    'app_user.id the event was about; NO foreign key BY DESIGN, so the audit trail outlives the account (REQ-AUDIT-001). target_user_handle is a NOT NULL snapshot, so a dangling id still renders.';
COMMENT ON COLUMN bank_audit_event.target_user_id IS
    'app_user.id the event was about; NO foreign key BY DESIGN, so the audit trail outlives the account (REQ-AUDIT-001, REQ-BANK-012). target_user_handle is a NOT NULL snapshot, so a dangling id still renders.';

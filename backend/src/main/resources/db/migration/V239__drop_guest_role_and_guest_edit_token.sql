-- =====================================================================
-- V239 - The GUEST role and the guest edit token both stop existing
--        (REQ-SEC-052, REQ-SEC-053, ADR-0159)
-- =====================================================================
-- Two removals, one migration, because they are one decision: the
-- Basetool has no anonymous and no guest surface any more. `GUEST` was
-- the role a token mapped to when its realm roles matched nothing the
-- application knows, and its authority set was empty -- so "no role"
-- quietly meant "the anonymous read surface", which the URL matrix let
-- through. With that surface gone the role would be a name nobody may
-- hold, which is a name somebody will eventually be given.
-- `guest_edit_token_hash` is the per-row capability token that let an
-- unauthenticated person edit the participant row they created
-- (REQ-SEC-018, now superseded). There is no anonymous self-sign-up to
-- mint one for.
--
-- WHAT HAPPENS TO AN ACCOUNT THAT HELD ONLY `GUEST`. It is left with
-- no roles at all and is refused with 403 NO_ROLE (REQ-SEC-053) until an
-- administrator assigns one. That is the intended outcome and not a
-- side effect: such an account was never a member, and silently
-- promoting it to one here would be this migration deciding a
-- membership question that belongs to an administrator.
--
-- THE AFFECTED IDS ARE LOGGED BEFORE THE DELETE, at WARNING, as
-- identifiers rather than identities (no name, no e-mail -- REQ-OBS-004).
-- Restoring an assignment by hand needs the id; a rollback after
-- promotion cannot recover it from anywhere else, because the rows are
-- gone and the role row with them. WARNING and not INFO because
-- PostgreSQL never writes INFO to the server log at all -- see the
-- block itself. At INFO the one artefact the rollback path depends on
-- would exist nowhere it could be read back from.
--
-- ROLLBACK IS FORWARD, NOT BACKWARD. A reverted image validates its
-- schema with `ddl-auto = validate` and would fail to boot against the
-- missing column. The forward fix re-adds `guest_edit_token_hash` as a
-- nullable column and restores the `Guest` seed in DataInitializer; the
-- assignments come from the WARNING line below, by hand.
--
-- THE EXACT TWO FILES, with their content, are in the Rollback section
-- of `docs/MEMBERS_ONLY_PLAN.md` (§8). Written out there rather than
-- prepared as a branch, because a branch would claim the next Flyway
-- number months before it is needed and break the day another PR takes
-- it first.
-- =====================================================================

DO $$
DECLARE
    affected TEXT;
BEGIN
    -- Both spellings, because the DELETEs below match both: a deployment that
    -- ran under a renamed role carries `name = 'Guest'` with some other code,
    -- and logging only the `code` match would leave exactly those assignments
    -- unrecorded -- defeating the rollback path this block exists for.
    SELECT string_agg(ur.user_id::TEXT, ', ' ORDER BY ur.user_id::TEXT)
      INTO affected
      FROM user_roles ur
      JOIN role r ON r.id = ur.role_id
     WHERE upper(r.code) = 'GUEST' OR upper(r.name) = 'GUEST';

    IF affected IS NOT NULL THEN
        -- WARNING, NOT INFO, AND THE LEVEL IS LOAD-BEARING. PostgreSQL
        -- sends INFO to the client and never to the server log; the
        -- default `log_min_messages = warning` applies here, since the
        -- compose command sets only `log_error_verbosity` and
        -- `log_line_prefix`. At INFO these ids would reach the migrating
        -- client's stdout and nothing else, while the rollback recipe
        -- (MEMBERS_ONLY_PLAN.md section 8) says to read them back out of
        -- the log. WARNING clears the threshold, lands in the
        -- `db-backend` container log and is shipped to Loki by Alloy.
        -- Do not lower it back for tidiness.
        RAISE WARNING 'V239: dropping GUEST assignments for user ids: %', affected;
    ELSE
        -- NOTICE: there is nothing to reconstruct, so this one need not
        -- outlive the migration run.
        RAISE NOTICE 'V239: no GUEST assignments to drop';
    END IF;
END $$;

-- Order matters: both child tables reference role(id).
DELETE FROM user_roles
 WHERE role_id IN (SELECT id FROM role WHERE code = 'GUEST');

DELETE FROM role_permissions
 WHERE role_id IN (SELECT id FROM role WHERE code = 'GUEST');

DELETE FROM role WHERE code = 'GUEST';

-- Matched on `code`, which V73 stamped and which survives a rename, but a
-- deployment that never ran under a renamed role still carries the name.
DELETE FROM user_roles
 WHERE role_id IN (SELECT id FROM role WHERE upper(name) = 'GUEST');

DELETE FROM role_permissions
 WHERE role_id IN (SELECT id FROM role WHERE upper(name) = 'GUEST');

DELETE FROM role WHERE upper(name) = 'GUEST';

-- MATCHED CASE-INSENSITIVELY, here and on `role.name` above. Until this
-- release `applySelectors` stored `trimToNull(request.roleCode())` -- whatever
-- casing the client sent -- and `NotificationRuleService` only started
-- canonicalising it in the same PR as this migration. A selector persisted as
-- `Guest` or `guest` would survive an exact-match DELETE and then hit the new
-- case-insensitive existence check on the next edit of its rule, which refuses
-- the save with a 400 for ever. `role.name` has the same gap against
-- `findByNameIgnoreCase`, which is how the local catalogue is read everywhere
-- else. Both use `upper(...)`.
--
-- Notification rules could address a role by code, and `role_code` is a bare
-- VARCHAR with no FK to `role` (V156) -- so deleting the role leaves any GUEST
-- ROLE selector behind as a row pointing at nothing. It would match no
-- recipient, which is harmless; what is not harmless is that the rule becomes
-- PERMANENTLY UNSAVEABLE. `NotificationRuleService.update` clears the selectors
-- and re-applies them, re-validating every one, and `findByCode('GUEST')` is now
-- empty -- so any edit to that rule, including one that never touches its
-- recipients, is refused with a 400. The UI cannot repair it either: the GUEST
-- option is gone from the picker, so the browser submits an empty role code and
-- the save fails on the other branch instead.
DO $$
DECLARE
    orphaned INT;
BEGIN
    SELECT count(*) INTO orphaned
      FROM notification_rule_selector
     WHERE kind = 'ROLE' AND upper(role_code) = 'GUEST';

    IF orphaned > 0 THEN
        -- WARNING for the same reason as above: this deletes rows an
        -- administrator configured, and "why did my rule change?" is
        -- answerable only if the line survives. The count is the whole
        -- of it -- a GUEST selector cannot be restored (the role and
        -- the picker entry are both gone), so the rule ids would name
        -- nothing anyone could act on.
        RAISE WARNING 'V239: dropping % orphaned GUEST notification-rule selector(s)', orphaned;
    END IF;
END $$;

DELETE FROM notification_rule_selector
 WHERE kind = 'ROLE' AND upper(role_code) = 'GUEST';

-- The capability token (V177). Dropped in the same unit of work as the
-- role, by owner decision D10 of the members-only plan -- deliberately
-- NOT one release later, so there is never a window in which the column
-- exists with no code able to write it.
ALTER TABLE mission_participant
    DROP COLUMN IF EXISTS guest_edit_token_hash;

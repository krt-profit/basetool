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
-- THE AFFECTED IDS ARE LOGGED BEFORE THE DELETE, at INFO, as
-- identifiers rather than identities (no name, no e-mail -- REQ-OBS-004).
-- Restoring an assignment by hand needs the id; a rollback after
-- promotion cannot recover it from anywhere else, because the rows are
-- gone and the role row with them.
--
-- ROLLBACK IS FORWARD, NOT BACKWARD. A reverted image validates its
-- schema with `ddl-auto = validate` and would fail to boot against the
-- missing column. The prepared fix re-adds `guest_edit_token_hash` as
-- nullable and lets `DataInitializer` re-seed the role; the assignments
-- come from the log line below.
-- =====================================================================

DO $$
DECLARE
    affected TEXT;
BEGIN
    SELECT string_agg(ur.user_id::TEXT, ', ' ORDER BY ur.user_id::TEXT)
      INTO affected
      FROM user_roles ur
      JOIN role r ON r.id = ur.role_id
     WHERE r.code = 'GUEST';

    IF affected IS NOT NULL THEN
        RAISE INFO 'V239: dropping GUEST assignments for user ids: %', affected;
    ELSE
        RAISE INFO 'V239: no GUEST assignments to drop';
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
 WHERE role_id IN (SELECT id FROM role WHERE name = 'Guest');

DELETE FROM role_permissions
 WHERE role_id IN (SELECT id FROM role WHERE name = 'Guest');

DELETE FROM role WHERE name = 'Guest';

-- The capability token (V177). Dropped in the same unit of work as the
-- role, by owner decision D10 of the members-only plan -- deliberately
-- NOT one release later, so there is never a window in which the column
-- exists with no code able to write it.
ALTER TABLE mission_participant
    DROP COLUMN IF EXISTS guest_edit_token_hash;

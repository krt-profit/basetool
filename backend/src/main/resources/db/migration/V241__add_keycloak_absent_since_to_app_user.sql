-- =====================================================================
-- V241 - app_user.keycloak_absent_since: WHEN an account stopped being
--        present in Keycloak, so a forgotten second deletion step can
--        be noticed (REQ-SEC-059)
-- =====================================================================
-- Deleting a member is two acts. An admin removes the account in the
-- Keycloak console; the nightly roster sync then flips
-- app_user.in_keycloak to false, and only THEN does the member list
-- render its delete button (members.html gates on !user.inKeycloak,
-- and UserDeletionService refuses an account the flag still claims is
-- present). If the second click never happens, the row keeps the
-- e-mail address, the handle, the Discord snowflake, the guild
-- nickname and the profile description indefinitely -- and nothing in
-- the system notices, because a half-deleted account is indistinguish-
-- able from one whose admin is simply not done yet.
--
-- WHY A COLUMN AND NOT AN EXISTING TIMESTAMP. The gauge that closes
-- that gap needs the AGE of the oldest waiting row, and no existing
-- column carries it:
--   * created_at is when the account was created, not when it was
--     orphaned -- for a member who joined in 2026-04 and left in
--     2026-09 it overstates the wait by five months.
--   * updated_at is @UpdateTimestamp, and the flag is flipped by
--     UserRepository#markMissingUsers, a bulk JPQL UPDATE. Hibernate
--     does not run the entity lifecycle for a bulk update, so
--     updated_at is not even written by the flip; and it moves on every
--     unrelated profile edit, so it could not be trusted if it were.
-- The alternative considered and rejected was to keep only a count
-- gauge and let Prometheus' `for:` clause supply the duration. That
-- reads "something has been waiting 24h" as "the count has been above
-- zero for 24h", which is a different statement: a stream of orphans
-- each cleared within an hour never lets the count reach zero and
-- would fire the alert although no single account ever waited.
-- Decided by @greluc on 2026-09-15 (ADR-0182).
--
-- NULLABLE, AND NULL IS AN ANSWER. NULL means "present in Keycloak as
-- far as we know" and is the state of every account in normal service.
-- The column is cleared again when the sync sees the account return
-- (UserReconciliationService), so a re-created account does not stay
-- flagged as waiting.
--
-- THE BACKFILL IS DELIBERATELY now() AND DELIBERATELY NOT ACCURATE.
-- Rows that are ALREADY in_keycloak = false disappeared from Keycloak
-- at some unknown point in the past; nothing recorded it, which is the
-- defect this column fixes, so there is no value to recover. Stamping
-- them with the migration time understates their true age and is the
-- only honest option that keeps the gauge meaningful: it means the
-- alert starts counting from this deploy rather than firing for every
-- historical row at once. Reading the resulting numbers: any
-- keycloak_absent_since exactly equal to the deploy time is "unknown,
-- at least this long" and not a measurement.
-- =====================================================================

ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS keycloak_absent_since TIMESTAMPTZ;

COMMENT ON COLUMN app_user.keycloak_absent_since IS
    'When the roster sync last observed this account MISSING from Keycloak (in_keycloak = false). '
    'NULL means present. Cleared when the account reappears. Rows stamped with the V241 deploy time '
    'are backfilled placeholders, not measurements: nothing recorded when they actually disappeared.';

-- Backfill the accounts that are already waiting. now() is the stamp; see
-- the header for why it is the only honest value and how to read it.
UPDATE app_user
SET keycloak_absent_since = now()
WHERE in_keycloak = FALSE
  AND keycloak_absent_since IS NULL;

-- Partial index: every read of this column is "the rows that are waiting"
-- (the count gauge and the MIN(...) age gauge, once a minute). The
-- partial predicate keeps the index the size of the orphan set rather
-- than the size of the user base, which is the ratio that matters here --
-- in normal service the orphan set is empty or near it.
CREATE INDEX IF NOT EXISTS idx_app_user_keycloak_absent_since
    ON app_user (keycloak_absent_since)
    WHERE keycloak_absent_since IS NOT NULL;

-- =====================================================================
-- V230 - Mirror the Keycloak `enabled` flag onto app_user (ADR-0129)
-- =====================================================================
-- Why: since the ingest gateway calls under its own identity and NAMES the
-- member it acts for, a caller can present a subject instead of a token --
-- and a name does not expire the way a token does. The liveness guard that
-- bounds this reads `in_keycloak`, which the roster sync maintains, so a
-- DELETED member is refused. A merely DISABLED one was not: the sync already
-- fetches `enabled` from the Admin API and dropped it on the floor, so a
-- deactivated account kept ACTIVE and every role here indefinitely.
--
-- Without a token that is harmless -- a disabled account cannot refresh, so
-- its last access token expires in minutes. With a named subject it is the
-- difference between revocation taking effect in minutes and taking effect
-- at the next roster sync, which is the whole point of the guard.
--
-- Defaults to TRUE so every existing row keeps working: the flag can only
-- ever REFUSE, and defaulting to FALSE would lock out the entire member base
-- until the next sync. NOT NULL because "unknown" has no useful meaning here
-- -- the sync writes it on every pass, and an account it did not see is
-- already handled by `in_keycloak`.
--
-- Deliberately NOT reusing `in_keycloak`: presence in the roster and being
-- enabled are different facts with different remedies (re-invite vs.
-- re-activate), and collapsing them would make the refusal log ambiguous
-- exactly when someone is trying to work out why a member cannot send.
-- =====================================================================

ALTER TABLE app_user
    ADD COLUMN enabled_in_keycloak BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN app_user.enabled_in_keycloak IS
    'Keycloak account `enabled` flag as of the last roster sync. Read by the acting-member liveness guard (ADR-0129); a disabled account cannot be acted for.';

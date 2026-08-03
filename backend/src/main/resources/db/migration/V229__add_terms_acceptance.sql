-- =====================================================================
-- V229 - Record each user's acceptance of the Terms of Use (REQ-SEC-028)
-- =====================================================================
-- Why: until now the terms took effect merely by accessing the platform
-- (terms section intro) and section 12 treated continued use as consent.
-- That leaves no evidence of who agreed to which wording, which is exactly
-- what is needed when a clause is enforced against someone -- for instance
-- the section 4 obligation to use only operator-approved client software
-- (REQ-SEC-027). This table is that evidence.
--
-- Append-only by construction: a row is written when a user accepts and is
-- never updated or deleted while the account lives. Re-consent after a terms
-- change adds a NEW row rather than overwriting the old one, so the history
-- reads as "accepted version A on date X, version B on date Y". The unique
-- constraint therefore serves two purposes at once - it makes the per-request
-- "has this user accepted the current version?" lookup a single index probe,
-- and it makes a double submit (double click, retried request) idempotent
-- instead of duplicating history.
--
-- terms_version holds a content digest derived at build time from the
-- terms.* entries of the German message bundle (root Gradle task
-- generateTermsVersion), so any wording change produces a new version and
-- re-prompts. VARCHAR(64) leaves room for a full SHA-256 hex digest should
-- the truncation ever be widened, and for an operator-pinned literal.
--
-- ON DELETE CASCADE: deleting an account removes its consent records with
-- it. The record exists to evidence a living user's agreement, not to
-- outlive the account -- and REQ-DATA-008 (V227) exists precisely because
-- owner-less leftovers had accumulated elsewhere.

CREATE TABLE terms_acceptance (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    terms_version VARCHAR(64) NOT NULL,
    accepted_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_terms_acceptance_user_version UNIQUE (user_id, terms_version)
);

-- Supports the "show me this user's consent history" read (newest first).
-- The unique constraint above already covers the hot per-request lookup.
CREATE INDEX idx_terms_acceptance_user_accepted_at
    ON terms_acceptance (user_id, accepted_at DESC);

COMMENT ON TABLE terms_acceptance IS
    'Append-only record of Terms-of-Use acceptances (REQ-SEC-028). One row per user and terms '
    'version; never updated or deleted while the account exists.';

COMMENT ON COLUMN terms_acceptance.terms_version IS
    'Content digest of the terms wording at the time of acceptance, derived by the root Gradle '
    'task generateTermsVersion from the terms.* entries of messages_de.properties.';

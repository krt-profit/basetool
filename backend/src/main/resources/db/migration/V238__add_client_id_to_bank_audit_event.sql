-- =====================================================================
-- V238 - Record which client a bank mutation came through
--        (REQ-AUDIT-005, REQ-BANK-012, GHSA-2vq5-8p8w-5r64)
-- =====================================================================
-- The sibling of V237, on the table V237 could not reach. The bank keeps
-- its own audit trail (ADR-0037), so adding the column to audit_event
-- left the tenth tab of the unified viewer -- and the area where a
-- misattributed action is most expensive -- unable to answer "which
-- client did this".
--
-- ONE DIFFERENCE FROM V237 IS WORTH STATING. V237's comment records that
-- its nulls are unambiguous, because while those rows were written only
-- one client could hold the authority they record. That reasoning does
-- NOT carry over here. `Bank Employee` and `Bank Management` have been
-- on the mobile client's Keycloak scope since it was provisioned
-- (REQ-SEC-035, whose role list named them from its first revision), so
-- a bank row has been reachable from two clients for as long as that
-- client has existed -- independent of whether a shipped app screen ever
-- exercised it, because the authority rides on the token and a replayed
-- token carries it.
--
-- The nulls here are therefore an honest "not recorded", not "recorded
-- by implication". They are still not backfilled: the claim was never
-- stored, so there is nothing to backfill FROM, and inventing a value
-- would be worse than an absent one -- an audit trail that guesses is no
-- longer evidence. A reader must treat a NULL client on a bank row as
-- unknown, and must not read it as "the web frontend".
--
-- The value itself follows V237 exactly: the token's `azp` mapped through
-- the same bounded allowlist basetool_api_client_requests_total
-- {client_id} uses (REQ-OBS-018), via the one `ClientAttribution` seam --
-- a known client verbatim, an unregistered one as 'other', a caller with
-- no token or no azp as 'none'. Bounded because this table is evidence
-- and must never take a value the caller chose.
--
-- VARCHAR(60) matches audit_event.client_id; BankAuditService clamps to
-- it so an over-long value can never throw and roll back the business
-- mutation it is recording.
--
-- Rollback: ALTER TABLE bank_audit_event DROP COLUMN client_id (drops the
-- index with it). No other object references it.

ALTER TABLE bank_audit_event
    ADD COLUMN client_id VARCHAR(60);

-- The viewer's client filter narrows the trail newest-first, the same
-- access shape as idx_bank_audit_event_occurred with the client pinned.
CREATE INDEX idx_bank_audit_event_client_occurred
    ON bank_audit_event (client_id, occurred_at DESC);

COMMENT ON COLUMN bank_audit_event.client_id IS
    'Bounded client attribution: the token''s azp mapped through the known-client allowlist (REQ-OBS-018), else ''other'' / ''none''. NULL on rows predating V238 means NOT RECORDED - unlike audit_event, a bank row was reachable from two clients before this column existed, so a NULL must not be read as ''the web frontend''.';

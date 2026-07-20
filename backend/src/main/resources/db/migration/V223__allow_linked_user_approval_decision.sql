-- =====================================================================
-- V223 - allow LINKED in the user_approval_event decision check (REQ-SEC-026)
-- =====================================================================
-- Why: V173 created chk_user_approval_event_decision as
-- CHECK (decision IN ('APPROVED', 'REJECTED')). The admin-mediated Discord
-- registration linking (PR #1376 / ADR-0111) added a third ApprovalDecision
-- value, LINKED, written as the audit row of a successful link. ADR-0111
-- assumed "no DB migration" was needed because LINKED is an @Enumerated(STRING)
-- value -- but the check constraint whitelists the allowed strings, so every
-- link attempt failed at flush with a 23514 check violation
-- (chk_user_approval_event_decision) and rolled the link back. This widens the
-- whitelist to include LINKED so the LINKED audit row can be inserted.
--
-- Safe: purely widening (no existing row can violate the new predicate), no data
-- change, keeps ddl-auto: validate happy against the @Enumerated(STRING) column.

ALTER TABLE user_approval_event
    DROP CONSTRAINT chk_user_approval_event_decision;

ALTER TABLE user_approval_event
    ADD CONSTRAINT chk_user_approval_event_decision
        CHECK (decision IN ('APPROVED', 'REJECTED', 'LINKED'));

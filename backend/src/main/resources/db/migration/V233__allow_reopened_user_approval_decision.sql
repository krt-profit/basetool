-- =====================================================================
-- V233 - allow REOPENED in the user_approval_event decision check (REQ-SEC-034)
-- =====================================================================
-- Why: an erroneously REJECTED registration had no way back. The approval
-- queue reads PENDING only and decide(...) refuses every non-PENDING row, so
-- reversing a mistaken rejection required a manual UPDATE against production --
-- bypassing the audit trail. The new admin action
-- POST /api/v1/admin/registrations/{id}/reopen moves REJECTED -> PENDING and
-- writes its own audit row with the fourth ApprovalDecision value, REOPENED.
--
-- The decision column is @Enumerated(STRING) but chk_user_approval_event_decision
-- whitelists the allowed strings (V173, widened for LINKED in V223), so without
-- this widening every reopen would fail at flush with a 23514 check violation and
-- roll the reversal back -- exactly the failure V223 had to repair for LINKED.
--
-- REOPENED is a distinct value rather than a reuse of APPROVED because a reopen
-- grants no access: the account lands PENDING, not ACTIVE. Recording it as an
-- approval would make the audit trail assert an access grant that never happened.
--
-- Safe: purely widening (no existing row can violate the new predicate), no data
-- change, keeps ddl-auto: validate happy against the @Enumerated(STRING) column.

ALTER TABLE user_approval_event
    DROP CONSTRAINT chk_user_approval_event_decision;

ALTER TABLE user_approval_event
    ADD CONSTRAINT chk_user_approval_event_decision
        CHECK (decision IN ('APPROVED', 'REJECTED', 'LINKED', 'REOPENED'));

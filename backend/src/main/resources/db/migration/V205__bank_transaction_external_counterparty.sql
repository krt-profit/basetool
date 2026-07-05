-- =====================================================================
-- V205 - Kartell bank: external (non-tool-user) counterparty (REQ-BANK-044, #994)
-- =====================================================================
-- Why: a deposit/withdrawal counterparty (Einzahler/Empfaenger) may now be a
-- person WITHOUT a basetool account, recorded as a free-text name in the
-- existing counterparty_handle snapshot with counterparty_user_id NULL, and
-- attributed to ANY active org unit (the membership check is skipped, since
-- there is no linked user). This supersedes the "tool user, no free-text"
-- clause of ADR-0054 for the counterparty dimension (owner-approved 2026-07-05).
--
-- The V197 check constraint tied the handle 1:1 to the user id
-- ((user_id IS NULL) = (handle IS NULL)) and required an org unit to accompany
-- a USER (org_unit_id IS NULL OR user_id IS NOT NULL) — both forbid an external
-- counterparty. Relax the constraint so the HANDLE is the presence marker (a
-- registered user's snapshotted handle OR the external free-text name) and the
-- user id is optional; an org unit now requires only that some counterparty
-- (handle) is present. No column change — the snapshot columns already fit.
--
-- Rollback: restore the V197 constraint (drop chk_bank_transaction_counterparty
-- and re-add the (user_id IS NULL) = (handle IS NULL) / org-requires-user form).
-- Any external-counterparty rows written meanwhile would then violate it.

ALTER TABLE bank_transaction
    DROP CONSTRAINT chk_bank_transaction_counterparty;

-- A counterparty is present iff its handle snapshot is set (registered user's
-- handle OR external free-text name). A user id, when present, always has a
-- handle. The org-unit id + name are paired, and an org unit only ever
-- accompanies a present counterparty (handle).
ALTER TABLE bank_transaction
    ADD CONSTRAINT chk_bank_transaction_counterparty
        CHECK (
            (counterparty_user_id IS NULL OR counterparty_handle IS NOT NULL)
            AND (counterparty_org_unit_id IS NULL) = (counterparty_org_unit_name IS NULL)
            AND (counterparty_org_unit_id IS NULL OR counterparty_handle IS NOT NULL)
        );

COMMENT ON COLUMN bank_transaction.counterparty_user_id IS
    'The registered member on the far side of a DEPOSIT (Einzahler) / WITHDRAWAL (Empfaenger), FK app_user ON DELETE SET NULL; NULL for transfers/holder-transfers/reversal/wipe, for bookings without a recorded counterparty, and for an EXTERNAL free-text counterparty (REQ-BANK-044, #994).';
COMMENT ON COLUMN bank_transaction.counterparty_handle IS
    'Name snapshot of the counterparty (mirrors bank_audit_event.actor_handle): a registered user''s handle, or the external free-text name when counterparty_user_id is NULL (#994). The presence marker: NULL exactly when no counterparty is recorded.';
COMMENT ON COLUMN bank_transaction.counterparty_org_unit_id IS
    'Optional org unit the counterparty belongs to; FK org_unit ON DELETE SET NULL. For a registered counterparty it is one of their memberships; for an external counterparty it may be any active org unit (REQ-BANK-044, #994).';

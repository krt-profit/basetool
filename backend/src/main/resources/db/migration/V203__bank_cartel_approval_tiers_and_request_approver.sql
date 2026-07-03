-- =====================================================================
-- V203 - Bank: KRT-account (CARTEL) 3-stage approval ladder and the
--        per-request required-approver snapshot (REQ-BANK-047)
-- =====================================================================
-- Why: money LEAVING the KRT account (a withdrawal / transfer on the CARTEL
-- account) is approved by an escalating class of approver chosen by amount:
--   amount <= T1                     -> bank employee (self-approve)
--   T1 < amount <= T2                -> Bereichsleiter Profit
--   amount > T2                      -> Organisationsleitung
-- The two thresholds T1/T2 are managed EXCLUSIVELY by the Bankleitung
-- (BANK_MANAGEMENT) in the Verwaltung tab. They live on bank_account (like
-- balance_target, V189): both the thresholds and rename/close are infrequent
-- management actions, so sharing the row's @Version is fine and a concurrent
-- edit surfaces a 409. They are only meaningful for the singleton CARTEL
-- account -- chk_bank_account_cartel_tiers pins them NULL for every other type
-- and enforces T2 >= T1 when both are set.
--
-- required_approver snapshots WHICH class must approve a flagged request
-- (RESPONSIBLE_HOLDER for every non-KRT account -- today's behaviour --, or the
-- KRT amount-band approver AREA_LEAD_PROFIT / ORGANISATIONSLEITUNG). The enum is
-- the source of truth (no DB CHECK), mirroring the V193 owner-approval columns;
-- the org-unit-blind confirm path never reads it, only the org-unit-aware seam.
--
-- The KRT account no longer uses the per-audience approval limits (V193): its
-- ladder replaces them (owner decision). Any existing CARTEL limit rows are
-- deleted so they cannot silently apply.
--
-- Rollback: ALTER TABLE bank_account DROP COLUMN employee_approval_ceiling,
--             area_lead_approval_ceiling (drop chk_bank_account_cartel_tiers);
--           ALTER TABLE bank_booking_request DROP COLUMN required_approver.

-- ---------------------------------------------------------------------
-- 1. KRT (CARTEL) approval thresholds on the account row
-- ---------------------------------------------------------------------
ALTER TABLE bank_account
    ADD COLUMN employee_approval_ceiling  NUMERIC(19, 4),
    ADD COLUMN area_lead_approval_ceiling NUMERIC(19, 4);

ALTER TABLE bank_account
    ADD CONSTRAINT chk_bank_account_cartel_tiers
        CHECK (
            -- Only the KRT account may carry thresholds.
            (type = 'CARTEL'
                OR (employee_approval_ceiling IS NULL AND area_lead_approval_ceiling IS NULL))
            -- Whole-aUEC ceilings are non-negative.
            AND (employee_approval_ceiling IS NULL OR employee_approval_ceiling >= 0)
            AND (area_lead_approval_ceiling IS NULL OR area_lead_approval_ceiling >= 0)
            -- The area-lead band sits at or above the employee band when both are set.
            AND (employee_approval_ceiling IS NULL
                OR area_lead_approval_ceiling IS NULL
                OR area_lead_approval_ceiling >= employee_approval_ceiling)
        );

COMMENT ON COLUMN bank_account.employee_approval_ceiling IS
    'KRT-account T1 (REQ-BANK-047): amount up to which a bank employee may self-approve a withdrawal/transfer leaving the CARTEL account; NULL only for non-CARTEL. NUMERIC(19,4) per ADR-0002.';
COMMENT ON COLUMN bank_account.area_lead_approval_ceiling IS
    'KRT-account T2 (REQ-BANK-047): amount up to which the Bereichsleiter Profit approves; above it the Organisationsleitung approves. NULL = no upper band. NUMERIC(19,4) per ADR-0002.';

-- ---------------------------------------------------------------------
-- 2. Per-request required-approver snapshot
-- ---------------------------------------------------------------------
ALTER TABLE bank_booking_request
    ADD COLUMN required_approver VARCHAR(32);

COMMENT ON COLUMN bank_booking_request.required_approver IS
    'Snapshot at creation (REQ-BANK-041/-046): which class must approve a flagged request -- RESPONSIBLE_HOLDER (non-KRT accounts) | AREA_LEAD_PROFIT | ORGANISATIONSLEITUNG (KRT amount bands); NULL unless requires_owner_approval. Enum is source of truth (no CHECK).';

-- ---------------------------------------------------------------------
-- 3. Retire per-audience approval limits on the KRT account (replaced by
--    the ladder). Idempotent; safe on a fresh DB with no CARTEL limits.
-- ---------------------------------------------------------------------
DELETE FROM bank_account_approval_limit
    WHERE account_id IN (SELECT id FROM bank_account WHERE type = 'CARTEL');

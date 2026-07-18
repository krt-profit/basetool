-- =====================================================================
-- V222 - Bank: KRT middle-band approver Bereichsleiter Profit -> Bankleitung
--        (REQ-BANK-047, ADR-0109 supersedes ADR-0066)
-- =====================================================================
-- Why: the KRT-account amount ladder (V203) routed its MIDDLE band
--   (T1 < amount <= T2) to the Bereichsleiter Profit (required_approver
--   = 'AREA_LEAD_PROFIT'). The owner corrected this: the middle band is
--   meant for the actual bank management (Bankleitung, the BANK_MANAGEMENT
--   role), NOT the Profit Bereichsleiter. The top band (> T2) stays with
--   the Organisationsleitung.
--
-- BankRequestApprover.AREA_LEAD_PROFIT was renamed to BANK_MANAGEMENT
-- (enum = source of truth, no DB CHECK). Rewrite the persisted snapshots
-- of any still-pending (or historical) requests so the org-unit-aware
-- seam keeps routing them to the correct approver after the rename.
-- Idempotent; safe on a fresh DB with no such rows.
--
-- Rollback: UPDATE bank_booking_request SET required_approver =
--             'AREA_LEAD_PROFIT' WHERE required_approver = 'BANK_MANAGEMENT'.

UPDATE bank_booking_request
    SET required_approver = 'BANK_MANAGEMENT'
    WHERE required_approver = 'AREA_LEAD_PROFIT';

COMMENT ON COLUMN bank_booking_request.required_approver IS
    'Snapshot at creation (REQ-BANK-041/-046): which class must approve a flagged request -- RESPONSIBLE_HOLDER (non-KRT accounts) | BANK_MANAGEMENT | ORGANISATIONSLEITUNG (KRT amount bands, REQ-BANK-047/ADR-0109); NULL unless requires_owner_approval. Enum is source of truth (no CHECK).';

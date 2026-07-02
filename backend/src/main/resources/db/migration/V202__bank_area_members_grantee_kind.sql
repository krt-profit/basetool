-- =====================================================================
-- V202 - Bank: "Mitglieder des Bereichs" audience/tier (AREA_MEMBERS)
--        for Bereichskonten (REQ-BANK-048)
-- =====================================================================
-- Why: a Bereichskonto (AREA account) may now open its balance/limits to the
-- WHOLE area cascade -- the Bereichsleitung PLUS every member of the Bereich's
-- child Staffeln and Spezialkommandos -- not just the direct Bereich members
-- (which the existing ALL_MEMBERS already covers). This is a new value of the
-- shared BankAccountViewGranteeKind enum, so it extends BOTH the visibility
-- grants (bank_account_view_grant, V189) and the approval limits
-- (bank_account_approval_limit, V193) one-for-one. The cascade membership is
-- resolved inside OrgUnitBankAccessService so the bank stays org-unit-blind
-- (REQ-BANK-008, ADR-0011). AREA_MEMBERS carries no role_code / grantee_user_id,
-- exactly like ALL_MEMBERS.
--
-- The kind + payload CHECKs are widened (DROP + re-ADD; the widened set is a
-- superset, so every existing row stays valid). A partial unique index per
-- table keeps the AREA_MEMBERS grant/limit at most one per account (idempotent
-- toggle), mirroring uq_bank_*_all_members.
--
-- Rollback: restore the original chk_bank_view_grant_kind / _payload and
--           chk_bank_appr_limit_kind / _payload CHECKs and drop the two
--           uq_bank_*_area_members indexes (after deleting any AREA_MEMBERS rows).

-- ---------------------------------------------------------------------
-- 1. bank_account_view_grant: admit AREA_MEMBERS
-- ---------------------------------------------------------------------
ALTER TABLE bank_account_view_grant
    DROP CONSTRAINT IF EXISTS chk_bank_view_grant_kind;
ALTER TABLE bank_account_view_grant
    ADD CONSTRAINT chk_bank_view_grant_kind
        CHECK (grantee_kind IN ('MEMBERSHIP_ROLE', 'GLOBAL_ROLE', 'USER', 'ALL_MEMBERS', 'AREA_MEMBERS'));

ALTER TABLE bank_account_view_grant
    DROP CONSTRAINT IF EXISTS chk_bank_view_grant_payload;
ALTER TABLE bank_account_view_grant
    ADD CONSTRAINT chk_bank_view_grant_payload
        CHECK (
            (grantee_kind IN ('MEMBERSHIP_ROLE', 'GLOBAL_ROLE')
                AND role_code IS NOT NULL AND grantee_user_id IS NULL)
            OR (grantee_kind = 'USER'
                AND grantee_user_id IS NOT NULL AND role_code IS NULL)
            OR (grantee_kind IN ('ALL_MEMBERS', 'AREA_MEMBERS')
                AND role_code IS NULL AND grantee_user_id IS NULL)
        );

-- At most one area-members grant per account.
CREATE UNIQUE INDEX uq_bank_view_grant_area_members
    ON bank_account_view_grant (account_id)
    WHERE grantee_kind = 'AREA_MEMBERS';

-- ---------------------------------------------------------------------
-- 2. bank_account_approval_limit: admit AREA_MEMBERS
-- ---------------------------------------------------------------------
ALTER TABLE bank_account_approval_limit
    DROP CONSTRAINT IF EXISTS chk_bank_appr_limit_kind;
ALTER TABLE bank_account_approval_limit
    ADD CONSTRAINT chk_bank_appr_limit_kind
        CHECK (grantee_kind IN ('MEMBERSHIP_ROLE', 'GLOBAL_ROLE', 'USER', 'ALL_MEMBERS', 'AREA_MEMBERS'));

ALTER TABLE bank_account_approval_limit
    DROP CONSTRAINT IF EXISTS chk_bank_appr_limit_payload;
ALTER TABLE bank_account_approval_limit
    ADD CONSTRAINT chk_bank_appr_limit_payload
        CHECK (
            (grantee_kind IN ('MEMBERSHIP_ROLE', 'GLOBAL_ROLE')
                AND role_code IS NOT NULL AND grantee_user_id IS NULL)
            OR (grantee_kind = 'USER'
                AND grantee_user_id IS NOT NULL AND role_code IS NULL)
            OR (grantee_kind IN ('ALL_MEMBERS', 'AREA_MEMBERS')
                AND role_code IS NULL AND grantee_user_id IS NULL)
        );

-- At most one area-members limit per account.
CREATE UNIQUE INDEX uq_bank_appr_limit_area_members
    ON bank_account_approval_limit (account_id)
    WHERE grantee_kind = 'AREA_MEMBERS';

COMMENT ON COLUMN bank_account_view_grant.grantee_kind IS
    'MEMBERSHIP_ROLE (role_code on the owning unit) | GLOBAL_ROLE (global role code, SPECIAL accounts) | USER (grantee_user_id) | ALL_MEMBERS (owning-unit members) | AREA_MEMBERS (whole Bereich cascade, AREA accounts only).';
COMMENT ON COLUMN bank_account_approval_limit.grantee_kind IS
    'MEMBERSHIP_ROLE (role_code on the owning unit) | GLOBAL_ROLE (global role code, SPECIAL accounts) | USER (grantee_user_id) | ALL_MEMBERS (owning-unit members) | AREA_MEMBERS (whole Bereich cascade, AREA accounts only).';

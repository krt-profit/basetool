-- =====================================================================
-- V232 - Kartell bank: booking-request counterparty (REQ-BANK-044/-055)
-- =====================================================================
-- Why: a WITHDRAWAL raised from the Org-Einheits-Bank page could not name
-- its Empfaenger. The bank employee's Kontobewegung modal has had that
-- field since V197, but the requester's modal had none, so the confirming
-- employee had no choice: BankBookingRequestService.confirm DERIVED the
-- counterparty from the requester (requested_by + their primary org unit).
-- That is right in the common case — you request your own payout — and
-- wrong whenever the payout goes to someone else, which the requester was
-- unable to express and the employee unable to correct.
--
-- These four columns let the requester record the Empfaenger on the
-- request itself, pre-filled with themselves so the common case is one
-- fewer decision. On confirmation the stored counterparty WINS over the
-- derived one; when it is NULL (every row predating this migration, every
-- DEPOSIT, every TRANSFER) the existing requester-derivation is unchanged,
-- so no historical request changes meaning.
--
-- Shape mirrors bank_transaction's V197 columns one-for-one, including the
-- deletion-proof handle / org-unit-name snapshots, so the value can be
-- copied onto the ledger row at confirmation without a re-resolve (the
-- named user or their org unit may be deleted between request and
-- confirmation). Unlike V197 this table is NOT append-only: a requester may
-- still edit a pending request (REQ-BANK-056), so these columns are
-- updatable and the CHECK must hold after an UPDATE too.
--
-- Deliberately NO counterparty_external_name twin: the free-text external
-- counterparty (#994) stays a Bank-Employee-only capability per
-- REQ-BANK-044. A requester picks a registered tool user or nobody.
--
-- Rollback: ALTER TABLE bank_booking_request
--   DROP CONSTRAINT chk_bank_booking_request_counterparty,
--   DROP COLUMN counterparty_user_id, DROP COLUMN counterparty_handle,
--   DROP COLUMN counterparty_org_unit_id, DROP COLUMN counterparty_org_unit_name.

ALTER TABLE bank_booking_request
    ADD COLUMN counterparty_user_id       UUID REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN counterparty_handle        VARCHAR(255),
    ADD COLUMN counterparty_org_unit_id   UUID REFERENCES org_unit (id) ON DELETE SET NULL,
    ADD COLUMN counterparty_org_unit_name VARCHAR(255);

-- Same invariant as chk_bank_transaction_counterparty: a handle snapshot
-- exists iff a counterparty user is named, and an org unit (id + name) only
-- ever accompanies a counterparty user. NOT VALID is deliberately NOT used
-- — every existing row has all four columns NULL and satisfies it.
ALTER TABLE bank_booking_request
    ADD CONSTRAINT chk_bank_booking_request_counterparty
        CHECK (
            (counterparty_user_id IS NULL) = (counterparty_handle IS NULL)
            AND (counterparty_org_unit_id IS NULL) = (counterparty_org_unit_name IS NULL)
            AND (counterparty_org_unit_id IS NULL OR counterparty_user_id IS NOT NULL)
        );

COMMENT ON COLUMN bank_booking_request.counterparty_user_id IS
    'The Empfaenger the requester named on a WITHDRAWAL request, FK app_user ON DELETE SET NULL; NULL for DEPOSIT/TRANSFER requests and for withdrawal requests that named none, in which case confirmation falls back to deriving the requester (REQ-BANK-055).';
COMMENT ON COLUMN bank_booking_request.counterparty_handle IS
    'Deletion-proof handle snapshot of counterparty_user_id, copied onto bank_transaction.counterparty_handle at confirmation; NULL exactly when counterparty_user_id is NULL.';
COMMENT ON COLUMN bank_booking_request.counterparty_org_unit_id IS
    'Org unit of the named Empfaenger, chosen from THEIR memberships at request time (validated server-side); FK org_unit ON DELETE SET NULL, only set together with a counterparty user.';
COMMENT ON COLUMN bank_booking_request.counterparty_org_unit_name IS
    'Deletion-proof name snapshot of counterparty_org_unit_id; NULL exactly when counterparty_org_unit_id is NULL.';

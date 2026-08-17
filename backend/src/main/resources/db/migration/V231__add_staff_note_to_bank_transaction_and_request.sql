-- REQ-BANK-054: the bank employee's own note ("Notiz Bankmitarbeiter").
--
-- A sibling of the existing `note` (the requester's / booking party's note) and `justification`
-- (the requester's Begruendung), but authored by the BANK EMPLOYEE at booking time: on a direct
-- deposit / withdrawal / transfer, and when confirming a booking request. It records internal
-- context for the movement ("paid out in two tranches", "after checking back with the SL").
--
-- Mirrors V198's justification rollout by living on BOTH tables: on `bank_transaction` it is the
-- booking's note (history, statements, management report); on `bank_booking_request` it snapshots
-- what the confirming employee recorded, so the request queue and the approval tab can show it
-- without joining the resulting transaction per row (the no-N+1 rule).
--
-- Unlike the request's `note` / `justification` this column is NOT `updatable = false` on the
-- request entity: it is written at CONFIRMATION, not at creation.
--
-- Visibility: bank staff, Bankleitung, OL and the account's responsible holder only. It is
-- redacted out of the org-unit member-facing history and the Halter-redacted statement PDF
-- (REQ-BANK-038) exactly like the Halter columns -- an employee note is internal.
--
-- Never written into the audit `details` payload (REQ-BANK-012: no user free text / no PII there).

ALTER TABLE bank_transaction
    ADD COLUMN staff_note VARCHAR(500);

ALTER TABLE bank_booking_request
    ADD COLUMN staff_note VARCHAR(500);

COMMENT ON COLUMN bank_transaction.staff_note IS
    'REQ-BANK-054: optional free-text note authored by the booking bank employee (Notiz Bankmitarbeiter) for any transaction kind incl. deposits; shown in the booking history, the account statement and the management report, but redacted from the org-unit member-facing views (REQ-BANK-038). NULL when the employee recorded none.';

COMMENT ON COLUMN bank_booking_request.staff_note IS
    'REQ-BANK-054: the confirming bank employee''s note, snapshotted onto the request so the staff queue and the approval tab render it without joining the resulting transaction. Written at CONFIRMATION (not at creation, unlike note/justification), and copied onto bank_transaction.staff_note of the booking it produces. NULL while PENDING and when the employee recorded none.';

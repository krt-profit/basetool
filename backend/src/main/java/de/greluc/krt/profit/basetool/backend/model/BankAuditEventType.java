/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.model;

/**
 * Classifies a {@link BankAuditEvent} row (REQ-BANK-012). Deliberately NOT mirrored by a database
 * CHECK constraint (V154, following the V113 {@code external_sync_report} precedent): the set grows
 * with later phases and this {@code @Enumerated(STRING)} mapping is the source of truth.
 */
public enum BankAuditEventType {

  /** A bank account was created (any {@link BankAccountType}). */
  ACCOUNT_CREATED,

  /** A bank account's display name was changed. */
  ACCOUNT_RENAMED,

  /** A bank account was closed (zero balance enforced, REQ-BANK-002). */
  ACCOUNT_CLOSED,

  /** A closed bank account was reopened for booking. */
  ACCOUNT_REOPENED,

  /** A holder row was registered in the bank-local registry (REQ-BANK-003). */
  HOLDER_REGISTERED,

  /** A holder was deactivated — new postings naming the holder are blocked. */
  HOLDER_DEACTIVATED,

  /** A previously deactivated holder was reactivated. */
  HOLDER_REACTIVATED,

  /** A per-account grant row was created (REQ-BANK-009). */
  GRANT_CREATED,

  /** A grant's capability flags changed; the details payload carries before/after. */
  GRANT_UPDATED,

  /** A grant row was revoked (deleted) — the grantee loses view access to the account. */
  GRANT_REVOKED,

  /** A {@code DEPOSIT} transaction was booked. */
  DEPOSIT_BOOKED,

  /**
   * A split {@code DEPOSIT} was booked (REQ-BANK-043): one transaction crediting the named account
   * the remainder and distributing a percentage of the gross evenly across all active squadron
   * accounts. One summarizing event (like {@code WIPE_RESET_EXECUTED}), not one per credited
   * account; the details payload carries the gross, holder handle, percentage and target count — no
   * free text or PII beyond the holder handle the plain deposit event already records.
   */
  DEPOSIT_SPLIT_BOOKED,

  /** A {@code WITHDRAWAL} transaction was booked. */
  WITHDRAWAL_BOOKED,

  /** An account-to-account {@code TRANSFER} transaction was booked (REQ-BANK-011). */
  TRANSFER_BOOKED,

  /**
   * A holder→holder Umbuchung ({@code HOLDER_TRANSFER}) moved custody between two holders without
   * touching any account (REQ-BANK-031, ADR-0039).
   */
  HOLDER_TRANSFER,

  /**
   * Legacy: an intra-account holder rebooking (the pre-ADR-0039 model, REQ-BANK-011 variant 2). No
   * longer produced — kept so historical audit rows still render.
   */
  HOLDER_REBOOKED,

  /** A transaction was corrected by a {@code REVERSAL} transaction. */
  TRANSACTION_REVERSED,

  /** The admin wipe reset zeroed all balances (REQ-BANK-013); one summarizing event. */
  WIPE_RESET_EXECUTED,

  /** An account statement PDF was exported (REQ-BANK-014); details carry the period. */
  STATEMENT_EXPORTED,

  /** The management three-month report PDF was exported (REQ-BANK-015). */
  MANAGEMENT_REPORT_EXPORTED,

  /**
   * The bank audit log itself was exported as a PDF or JSON for a period (REQ-AUDIT-001 viewer).
   */
  AUDIT_LOG_EXPORTED,

  /**
   * Bank audit rows older than an admin-chosen cutoff were purged for retention (REQ-AUDIT-004).
   * The purge itself is recorded (this event survives, being newer than the cutoff).
   */
  AUDIT_LOG_PURGED,

  /**
   * An org-unit officer/lead raised a confirm-before-post booking request (REQ-BANK-022). Audited
   * on creation while still off-ledger (PENDING), before any money moves.
   */
  BOOKING_REQUEST_CREATED,

  /**
   * A bank employee confirmed a booking request; the linked ledger transaction is booked in the
   * same step (REQ-BANK-023). The {@code DEPOSIT_BOOKED} / {@code WITHDRAWAL_BOOKED} event for the
   * actual ledger movement is recorded alongside it.
   */
  BOOKING_REQUEST_CONFIRMED,

  /**
   * A bank employee rejected a booking request; no ledger effect, the reason is in details
   * (REQ-BANK-023).
   */
  BOOKING_REQUEST_REJECTED,

  /**
   * The requesting officer/lead cancelled their own pending booking request; no ledger effect
   * (REQ-BANK-022).
   */
  BOOKING_REQUEST_CANCELLED,

  /**
   * An account's balance target ("Kontostandsziel") was set or changed (REQ-BANK-036); the details
   * payload carries the new target amount.
   */
  BALANCE_TARGET_SET,

  /** An account's balance target was cleared (REQ-BANK-036). */
  BALANCE_TARGET_CLEARED,

  /**
   * A balance-visibility grant was added to an account (REQ-BANK-035): a role bucket, an
   * all-members toggle, or an individual user (then {@code targetUserId} is set). The details
   * payload carries the grantee kind and role code — never any free text or PII.
   */
  BALANCE_VISIBILITY_GRANTED,

  /** A balance-visibility grant was removed from an account (REQ-BANK-035). */
  BALANCE_VISIBILITY_REVOKED,

  /**
   * An account's per-tier approval limit was set or changed (REQ-BANK-041); the details payload
   * carries the tier and the new limit amount — never any free text or PII.
   */
  APPROVAL_LIMIT_SET,

  /** An account's per-tier approval limit was cleared (REQ-BANK-041). */
  APPROVAL_LIMIT_CLEARED,

  /**
   * The account's responsible holder granted in-app approval for a booking request that exceeds the
   * requester's limit (REQ-BANK-041); recorded from the "Fremde Anträge" tab.
   */
  BOOKING_REQUEST_OWNER_APPROVAL_GRANTED,

  /** The responsible holder revoked a previously granted in-app approval (REQ-BANK-041). */
  BOOKING_REQUEST_OWNER_APPROVAL_REVOKED,

  /**
   * A bank employee confirmed, at booking-request confirmation, that the responsible holder's
   * approval had been obtained (the mandatory checkbox for an over-limit request, REQ-BANK-041).
   */
  BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED,

  /**
   * The KRT-account (CARTEL) 3-stage approval thresholds T1/T2 were set or changed by bank
   * management in the Verwaltung tab (REQ-BANK-047). The details payload carries the two amounts —
   * never any free text or PII.
   */
  CARTEL_APPROVAL_TIERS_SET,

  /** The KRT-account 3-stage approval thresholds were cleared (REQ-BANK-047). */
  CARTEL_APPROVAL_TIERS_CLEARED
}

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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * Append-only bank transaction header (epic #556, REQ-BANK-004, ADR-0010), persisted in the {@code
 * bank_transaction} table created by Flyway V153 together with its 1..n {@link BankPosting} legs.
 *
 * <p>Insert-only event-log entity in the {@link ExternalSyncReport} style: it deliberately does NOT
 * extend {@link AbstractEntity} — no {@code @Version} (rows are never updated, so the
 * optimistic-locking trap class documented in CLAUDE.md cannot occur on bookings) and {@link
 * #createdAt} is the single authoritative timestamp, stamped by {@code BankLedgerService} so the
 * header and all its legs share the exact same instant. Corrections are {@link
 * BankTransactionType#REVERSAL} rows referencing the original via {@link #reversedTransaction}; the
 * V153 unique constraint caps reversals at one per original.
 */
@Entity
@Table(name = "bank_transaction")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankTransaction {

  /** Surrogate primary key, generated client-side by Hibernate ({@code GenerationType.UUID}). */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The kind of value movement; determines the posting-shape invariant (REQ-BANK-004). */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private BankTransactionType type;

  /**
   * The basetool user id (JWT {@code sub}) of the bank staffer who booked the transaction; the
   * database FK is {@code ON DELETE SET NULL}. Kept as a plain UUID column — the booking surfaces
   * never display the initiator (the K1 booking table shows the <em>holder</em>); the audit trail
   * carries the actor with a deletion-proof handle snapshot.
   */
  @Nullable
  @Column(name = "initiated_by")
  private UUID initiatedBy;

  /** Optional free-text note shown in the booking history and on statements. */
  @Nullable
  @Column(length = 500)
  private String note;

  /**
   * Optional free-text justification (Begr&uuml;ndung) shown in the booking history and on
   * statements, captured only for a {@code WITHDRAWAL} / {@code TRANSFER} (never a deposit) and
   * required when the source account type {@linkplain BankAccountType#requiresDebitJustification()
   * mandates a reason}; {@code null} otherwise (REQ-BANK-045).
   */
  @Nullable
  @Column(length = 500)
  private String justification;

  /**
   * Optional free-text note authored by the <em>booking bank employee</em> ("Notiz
   * Bankmitarbeiter", REQ-BANK-054) — internal context for the movement, as opposed to {@link
   * #note} / {@link #justification}, which come from the requester / booking party.
   *
   * <p>Captured for <strong>every</strong> transaction kind including a {@code DEPOSIT} (unlike
   * {@link #justification}, which debit-only rules govern), on a direct booking and on the
   * confirmation of a booking request alike, and always optional. Shown in the booking history, the
   * account statement and the management report, but <strong>redacted</strong> out of the org-unit
   * member-facing history and statement (REQ-BANK-038) — an employee note is internal.
   */
  @Nullable
  @Column(length = 500)
  private String staffNote;

  /**
   * The member on the far side of a {@link BankTransactionType#DEPOSIT} (the Einzahler who handed
   * the money in) or {@link BankTransactionType#WITHDRAWAL} (the Empf&auml;nger who received the
   * payout), distinct from the {@code BankHolderPosting} holder (the bank custodian who physically
   * received/paid). REQ-BANK-044. The database FK is {@code ON DELETE SET NULL}; {@link
   * #counterpartyHandle} keeps the row attributable afterwards. {@code null} for transfers,
   * holder→holder Umbuchungen, reversals, the wipe reset, and for bookings where no counterparty
   * was recorded (the field is optional). Kept as a plain UUID — the booking surfaces render the
   * {@link #counterpartyHandle} snapshot, not a live join.
   */
  @Nullable
  @Column(name = "counterparty_user_id")
  private UUID counterpartyUserId;

  /**
   * Deletion-proof handle snapshot of {@link #counterpartyUserId} (the effective name at booking
   * time), mirroring the {@code bank_audit_event.actor_handle} / {@code bank_holder.handle}
   * snapshot pattern so the booking history and statements survive user deletion (REQ-BANK-044).
   * {@code null} exactly when {@link #counterpartyUserId} is {@code null} (V197 CHECK).
   */
  @Nullable
  @Column(name = "counterparty_handle", length = 255)
  private String counterpartyHandle;

  /**
   * Optional org unit the counterparty belongs to, picked from their own memberships at booking
   * time (membership is multi, so the booker chooses which one). REQ-BANK-044. The FK references
   * {@code org_unit} ({@code ON DELETE SET NULL}); only ever set together with {@link
   * #counterpartyUserId}. Kept as a plain UUID (no JPA relation) — consistent with how the rest of
   * the bank treats org-unit references during the SQUADRON soak — with {@link
   * #counterpartyOrgUnitName} carrying the display label.
   */
  @Nullable
  @Column(name = "counterparty_org_unit_id")
  private UUID counterpartyOrgUnitId;

  /**
   * Deletion-proof name snapshot of {@link #counterpartyOrgUnitId} (REQ-BANK-044), so the history
   * and statements label the counterparty's org unit without a live polymorphic org-unit load.
   * {@code null} exactly when {@link #counterpartyOrgUnitId} is {@code null} (V197 CHECK).
   */
  @Nullable
  @Column(name = "counterparty_org_unit_name", length = 255)
  private String counterpartyOrgUnitName;

  /**
   * In-game aUEC transfer fee added on top of the entered amount and borne by the debited source
   * (ADR-0052 superseding ADR-0041, REQ-BANK-033). Set by {@code BankLedgerService} on a
   * customer-facing transfer the bank makes on a member's behalf — a {@link
   * BankTransactionType#WITHDRAWAL} and an account-to-account {@link BankTransactionType#TRANSFER}
   * with a holder change; {@code 0} for {@link BankTransactionType#DEPOSIT} (the depositor bears
   * their own fee), the internal {@link BankTransactionType#HOLDER_TRANSFER} Umbuchung (the staff
   * bear that in-game fee personally), {@link BankTransactionType#WIPE_RESET}, {@link
   * BankTransactionType#REVERSAL} and same-holder transfers. The source leg is debited the gross
   * (entered amount + fee) and the destination leg credited the full entered amount, so a
   * fee-bearing TRANSFER nets to {@code -transfer_fee} across its legs (REQ-BANK-020 integrity
   * widened accordingly). Never negative (V183 CHECK).
   */
  @Column(name = "transfer_fee", nullable = false, precision = 19, scale = 4, updatable = false)
  @Builder.Default
  private BigDecimal transferFee = BigDecimal.ZERO;

  /**
   * The transaction this {@link BankTransactionType#REVERSAL} corrects; {@code null} for every
   * other type (V153 CHECK pins the equivalence). Lazy-fetched — only the reversal flows resolve
   * it.
   */
  @Nullable
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reversed_transaction_id")
  @ToString.Exclude
  private BankTransaction reversedTransaction;

  /**
   * Booking instant (UTC), stamped explicitly by {@code BankLedgerService} so the header and all
   * its {@link BankPosting} legs carry the identical timestamp (period queries must never split a
   * transaction across a range boundary).
   */
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}

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
 * Append-only bank transaction header with its 1..n {@link BankPosting} legs (REQ-BANK-004,
 * ADR-0010).
 *
 * <p>Not an {@link AbstractEntity}: rows are never updated, so there is no {@code @Version}. {@link
 * #createdAt} is stamped by {@code BankLedgerService} and shared with all legs. Corrections are
 * {@link BankTransactionType#REVERSAL} rows referencing the original via {@link
 * #reversedTransaction}, at most one per original.
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
   * Optional internal note by the booking bank employee (REQ-BANK-054), captured for every
   * transaction kind. Redacted from the member-facing history and statement (REQ-BANK-038).
   */
  @Nullable
  @Column(length = 500)
  private String staffNote;

  /**
   * The member on the far side of a {@link BankTransactionType#DEPOSIT} or {@link
   * BankTransactionType#WITHDRAWAL}, distinct from the custodian holder (REQ-BANK-044). Optional
   * and {@code null} for every other type; loose reference ({@code ON DELETE SET NULL}) whose
   * display comes from {@link #counterpartyHandle}.
   */
  @Nullable
  @Column(name = "counterparty_user_id")
  private UUID counterpartyUserId;

  /**
   * Deletion-proof handle snapshot of {@link #counterpartyUserId} at booking time (REQ-BANK-044).
   * {@code null} exactly when {@link #counterpartyUserId} is {@code null}.
   */
  @Nullable
  @Column(name = "counterparty_handle", length = 255)
  private String counterpartyHandle;

  /**
   * Optional org unit the counterparty belongs to, chosen from their memberships at booking time
   * (REQ-BANK-044). Set only together with {@link #counterpartyUserId}; loose reference labelled by
   * {@link #counterpartyOrgUnitName}.
   */
  @Nullable
  @Column(name = "counterparty_org_unit_id")
  private UUID counterpartyOrgUnitId;

  /**
   * Deletion-proof name snapshot of {@link #counterpartyOrgUnitId} (REQ-BANK-044). {@code null}
   * exactly when {@link #counterpartyOrgUnitId} is {@code null}.
   */
  @Nullable
  @Column(name = "counterparty_org_unit_name", length = 255)
  private String counterpartyOrgUnitName;

  /**
   * In-game aUEC transfer fee borne by the debited source on top of the entered amount (ADR-0052,
   * REQ-BANK-033). Non-zero only on a {@link BankTransactionType#WITHDRAWAL} and on a {@link
   * BankTransactionType#TRANSFER} with a holder change; the source leg is debited amount plus fee,
   * the destination credited the amount. Never negative.
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

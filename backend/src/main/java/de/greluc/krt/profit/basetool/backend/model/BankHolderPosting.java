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

/**
 * One signed leg of the bank's holder ledger (ADR-0039, REQ-BANK-003).
 *
 * <p>A holder's balance is the global {@code SUM(amount)} across the whole bank and may be
 * negative. Insert-only: never updated or deleted; corrections are {@code REVERSAL} transactions.
 * Amounts are signed, whole-aUEC and never zero.
 */
@Entity
@Table(name = "bank_holder_posting")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankHolderPosting {

  /** Surrogate primary key, generated client-side by Hibernate ({@code GenerationType.UUID}). */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The transaction header this leg belongs to; lazy — aggregates query by plain columns. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
  @ToString.Exclude
  private BankTransaction transaction;

  /**
   * The player physically holding/moving the money (REQ-BANK-003); mandatory on every leg. The
   * database restricts holder deletion while legs reference it ({@code ON DELETE RESTRICT}).
   */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "holder_id", nullable = false, updatable = false)
  @ToString.Exclude
  private BankHolder holder;

  /**
   * Signed whole-aUEC amount ({@code NUMERIC(19,4)}, ADR-0002): positive legs add to the holder's
   * global stash, negative legs remove from it; never zero (V180 CHECK).
   */
  @Column(nullable = false, precision = 19, scale = 4, updatable = false)
  private BigDecimal amount;

  /**
   * Booking instant (UTC), stamped by {@code BankLedgerService} with the exact same value as the
   * owning transaction's {@link BankTransaction#getCreatedAt()}. Backs the {@code (holder_id,
   * created_at)} index (REQ-BANK-020).
   */
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}

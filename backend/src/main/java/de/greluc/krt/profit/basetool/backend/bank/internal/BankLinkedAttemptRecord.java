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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedAttempt;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The Bank's immutable submission identity and frozen fee terms, with its resulting posting. */
@Entity
@Table(name = "bank_linked_attempt")
@Getter
@NoArgsConstructor
class BankLinkedAttemptRecord {
  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 32)
  private BankPurposeKind purposeKind;

  @Column(nullable = false, updatable = false)
  private UUID purposeRef;

  @Column(nullable = false, updatable = false)
  private BigDecimal feeRate;

  @Column(nullable = false, updatable = false, precision = 19, scale = 4)
  private BigDecimal fee;

  @Column(nullable = false, updatable = false)
  private boolean samePlayer;

  @Nullable @Setter private UUID transactionId;

  /**
   * Creates a submission to be persisted with its first outcome.
   *
   * @param attempt the opaque purpose and attempt identity
   * @param feeRate the shared frozen rate
   * @param fee the whole-aUEC fee
   * @param samePlayer whether this is a verified purpose change without a game transfer
   */
  BankLinkedAttemptRecord(
      @NotNull BankLinkedAttempt attempt,
      @NotNull BigDecimal feeRate,
      @NotNull BigDecimal fee,
      boolean samePlayer) {
    id = attempt.id();
    purposeKind = attempt.purpose().kind();
    purposeRef = attempt.purpose().ref();
    this.feeRate = feeRate;
    this.fee = fee;
    this.samePlayer = samePlayer;
  }
}

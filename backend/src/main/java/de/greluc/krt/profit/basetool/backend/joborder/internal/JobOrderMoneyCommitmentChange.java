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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.MoneyCommitmentChangeDto;
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
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** A retained change to an occurrence, optionally tied to its original money operation. */
@Entity
@Table(name = "job_order_money_commitment_change")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class JobOrderMoneyCommitmentChange {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Nullable
  private Long id;

  @Column(nullable = false, updatable = false)
  private UUID orderId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "commitment_id", nullable = false, updatable = false)
  private JobOrderMoneyCommitment commitment;

  @Nullable
  @Column(updatable = false)
  private UUID operationId;

  @Nullable
  @Column(updatable = false)
  private UUID correctionOperationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 32)
  private MoneyCommitmentChangeDto.Reason reason;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal beforeAmount;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal afterAmount;

  @Column(nullable = false, updatable = false)
  private Instant recordedAt;

  JobOrderMoneyCommitmentChange(
      JobOrderMoneyCommitment commitment,
      MoneyCommitmentChangeDto.Reason reason,
      BigDecimal before,
      @Nullable UUID operationId) {
    this.orderId = commitment.getOrder().getId();
    this.commitment = commitment;
    this.operationId = operationId;
    this.reason = reason;
    this.beforeAmount = before;
    this.afterAmount = commitment.getOutstanding();
    this.recordedAt = Instant.now();
  }

  static JobOrderMoneyCommitmentChange corrected(
      JobOrderMoneyCommitment commitment, BigDecimal before, UUID correctionOperationId) {
    var change =
        new JobOrderMoneyCommitmentChange(
            commitment, MoneyCommitmentChangeDto.Reason.CORRECTED, before, null);
    change.correctionOperationId = correctionOperationId;
    return change;
  }

  BigDecimal creditBasis() {
    return beforeAmount;
  }

  BigDecimal creditedAmount() {
    return beforeAmount.subtract(afterAmount);
  }

  UUID getCommitmentId() {
    return commitment.getId();
  }

  MoneyCommitmentChangeDto toDto() {
    return new MoneyCommitmentChangeDto(
        reason,
        beforeAmount.toPlainString(),
        afterAmount.toPlainString(),
        correctionOperationId == null ? operationId : correctionOperationId,
        recordedAt);
  }
}

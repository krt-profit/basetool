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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Preserves the before and after effects of a corrected original money operation. */
@Entity
@Table(name = "job_order_money_correction_entry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
class JobOrderMoneyCorrectionEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Nullable
  private Long id;

  @Column(nullable = false, updatable = false)
  private UUID orderId;

  @Column(nullable = false, updatable = false)
  private UUID correctionOperationId;

  @Column(nullable = false, updatable = false)
  private UUID originalOperationId;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal previousAmount;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal previousProvision;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal previousFee;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal amount;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal provision;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal fee;

  @Nullable
  @Column(updatable = false)
  private UUID commitmentId;

  @Nullable
  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal previousCredit;

  @Nullable
  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal credit;

  @Column(nullable = false, updatable = false)
  private boolean attributionRemoved;

  @Column(nullable = false, updatable = false)
  private boolean removeAttributionRequested;

  JobOrderMoneyCorrectionEntry(
      UUID orderId,
      UUID correctionOperationId,
      UUID originalOperationId,
      JobOrderMoneyAmounts previous,
      JobOrderMoneyAmounts revised) {
    this.orderId = orderId;
    this.correctionOperationId = correctionOperationId;
    this.originalOperationId = originalOperationId;
    this.previousAmount = previous.amount();
    this.previousProvision = previous.provision();
    this.previousFee = previous.fee();
    this.amount = revised.amount();
    this.provision = revised.provision();
    this.fee = revised.fee();
  }

  void attachCredit(
      UUID commitmentId,
      BigDecimal previous,
      BigDecimal revised,
      boolean attributionRemoved,
      boolean removeAttributionRequested) {
    this.commitmentId = commitmentId;
    this.previousCredit = previous;
    this.credit = revised;
    this.attributionRemoved = attributionRemoved;
    this.removeAttributionRequested = removeAttributionRequested;
  }

  boolean changesAmounts() {
    return amount.compareTo(previousAmount) != 0
        || provision.compareTo(previousProvision) != 0
        || fee.compareTo(previousFee) != 0;
  }

  boolean changesCredit() {
    return previousCredit != null && credit != null && previousCredit.compareTo(credit) != 0;
  }

  JobOrderMoneyAmounts revised() {
    return new JobOrderMoneyAmounts(amount, provision, fee);
  }

  JobOrderMoneyCorrectionView view() {
    return new JobOrderMoneyCorrectionView(
        originalOperationId,
        previousAmount.toPlainString(),
        amount.toPlainString(),
        previousProvision.toPlainString(),
        provision.toPlainString(),
        previousFee.toPlainString(),
        fee.toPlainString(),
        commitmentId,
        previousCredit == null ? null : previousCredit.toPlainString(),
        credit == null ? null : credit.toPlainString(),
        attributionRemoved);
  }
}

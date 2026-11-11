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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Durable submitted terms and booked effects for order money. */
@Entity
@Table(name = "job_order_money_operation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
class JobOrderMoneyOperation {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Nullable
  private UUID id;

  @Column(nullable = false, updatable = false)
  private UUID orderId;

  @Column(nullable = false, updatable = false)
  private UUID operationId;

  @Column(nullable = false, updatable = false)
  private UUID holderId;

  @Nullable private UUID actorId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private JobOrderMoneySelection.Kind selectionKind;

  @Nullable
  @Column(updatable = false)
  private UUID selectedUserId;

  @Nullable
  @Column(length = 200, updatable = false)
  private String selectedAccountlessName;

  @Column(nullable = false, updatable = false)
  private boolean bankHandling;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 24)
  private JobOrderMoneyKind kind;

  @Nullable
  @Column(updatable = false)
  private UUID destinationHolderId;

  @Nullable
  @Column(updatable = false)
  private UUID recipientId;

  @Nullable
  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal newProvision;

  @Nullable
  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal feeRate;

  @Nullable
  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal fee;

  @Column(nullable = false, updatable = false)
  private Instant recordedAt;

  JobOrderMoneyOperation(
      UUID orderId,
      UUID operationId,
      UUID holderId,
      UUID actorId,
      JobOrderMoneySelection selection,
      boolean bankHandling,
      BigDecimal amount) {
    this(
        orderId,
        operationId,
        holderId,
        actorId,
        selection,
        bankHandling,
        amount,
        JobOrderMoneyKind.PROVISION,
        null,
        null,
        null,
        null,
        null);
  }

  JobOrderMoneyOperation(
      UUID orderId,
      UUID operationId,
      UUID holderId,
      UUID actorId,
      JobOrderMoneySelection selection,
      boolean bankHandling,
      BigDecimal amount,
      JobOrderMoneyKind kind,
      @Nullable UUID destinationHolderId,
      @Nullable UUID recipientId,
      @Nullable BigDecimal newProvision,
      @Nullable BigDecimal feeRate,
      @Nullable BigDecimal fee) {
    this.orderId = orderId;
    this.kind = kind;
    this.destinationHolderId = destinationHolderId;
    this.recipientId = recipientId;
    this.newProvision = newProvision;
    this.feeRate = feeRate;
    this.fee = fee;
    this.operationId = operationId;
    this.holderId = holderId;
    this.actorId = actorId;
    this.selectionKind = selection.kind();
    this.selectedUserId =
        selectionKind == JobOrderMoneySelection.Kind.LINKED ? selection.reference() : null;
    this.selectedAccountlessName =
        selectionKind == JobOrderMoneySelection.Kind.ACCOUNTLESS ? selection.name() : null;
    this.bankHandling = bankHandling;
    this.amount = amount;
    this.recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  boolean matchesSelection(JobOrderMoneySelection selection) {
    if (selection.kind() != selectionKind) {
      return false;
    }
    return switch (selectionKind) {
      case SELF -> true;
      case EXISTING ->
          Objects.equals(
              kind == JobOrderMoneyKind.COLLECTION ? destinationHolderId : holderId,
              selection.reference());
      case LINKED -> Objects.equals(selectedUserId, selection.reference());
      case ACCOUNTLESS -> Objects.equals(selectedAccountlessName, selection.name());
    };
  }

  BigDecimal providedAmount() {
    return kind == JobOrderMoneyKind.PROVISION ? amount : Objects.requireNonNull(newProvision);
  }

  BigDecimal feeAmount() {
    return fee == null ? BigDecimal.ZERO : fee;
  }

  BigDecimal sourceEffect() {
    return kind == JobOrderMoneyKind.PROVISION
        ? amount
        : providedAmount().subtract(amount).subtract(feeAmount());
  }
}

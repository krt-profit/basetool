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

  @Nullable
  @Column(updatable = false)
  private UUID holderId;

  @Nullable
  @Column(updatable = false)
  private UUID accountHoldingId;

  @Nullable private UUID actorId;

  @Enumerated(EnumType.STRING)
  @Nullable
  @Column(updatable = false)
  private JobOrderMoneySelection.Kind selectionKind;

  @Nullable
  @Column(updatable = false)
  private UUID selectedUserId;

  @Nullable
  @Column(length = 200, updatable = false)
  private String selectedAccountlessName;

  @Nullable
  @Column(updatable = false)
  private UUID commitmentId;

  @Column(nullable = false, updatable = false)
  private boolean bankHandling;

  @Column(nullable = false, updatable = false, columnDefinition = "numeric")
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 24)
  private JobOrderMoneyKind kind;

  @Nullable
  @Column(updatable = false)
  private UUID peerOrderId;

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

  /** The patch change whose currency wipe booked this shortage; {@code null} for every other. */
  @Nullable
  @Column(updatable = false)
  private UUID patchChangeId;

  JobOrderMoneyOperation(
      UUID orderId,
      UUID operationId,
      UUID holderId,
      @Nullable UUID actorId,
      JobOrderMoneySelection selection,
      @Nullable UUID commitmentId,
      boolean bankHandling,
      BigDecimal amount) {
    this(
        orderId,
        operationId,
        holderId,
        actorId,
        selection,
        commitmentId,
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
      @Nullable UUID holderId,
      @Nullable UUID actorId,
      @Nullable JobOrderMoneySelection selection,
      @Nullable UUID commitmentId,
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
    this.selectionKind = selection == null ? null : selection.kind();
    this.selectedUserId =
        selection != null && selection.kind() == JobOrderMoneySelection.Kind.LINKED
            ? selection.reference()
            : null;
    this.selectedAccountlessName =
        selection != null && selection.kind() == JobOrderMoneySelection.Kind.ACCOUNTLESS
            ? selection.name()
            : null;
    this.commitmentId = commitmentId;
    this.bankHandling = bankHandling;
    this.amount = amount;
    this.recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  static JobOrderMoneyOperation accountOperation(
      UUID orderId,
      UUID operationId,
      UUID accountHoldingId,
      @Nullable UUID actorId,
      @Nullable UUID commitmentId,
      JobOrderMoneyKind kind,
      @Nullable UUID recipientId,
      BigDecimal amount) {
    boolean transfers =
        kind == JobOrderMoneyKind.PURPOSE_HANDOVER || kind == JobOrderMoneyKind.REQUESTER_RETURN;
    BigDecimal noGameCost = transfers ? BigDecimal.ZERO : null;
    var fact =
        new JobOrderMoneyOperation(
            orderId,
            operationId,
            null,
            actorId,
            null,
            commitmentId,
            transfers || kind == JobOrderMoneyKind.PROVISION,
            amount,
            kind,
            null,
            recipientId,
            noGameCost,
            noGameCost,
            noGameCost);
    fact.accountHoldingId = accountHoldingId;
    return fact;
  }

  /**
   * Creates the real shortage a currency wipe books on a personal holding (REQ-PATCH-044).
   *
   * @param orderId the order
   * @param holderId the canonical holder
   * @param amount the lost amount, positive whole aUEC
   * @param patchChangeId the patch change the shortage is attributed to
   * @return the shortage, recorded by no actor
   */
  static JobOrderMoneyOperation currencyWipeShortage(
      UUID orderId, UUID holderId, BigDecimal amount, UUID patchChangeId) {
    var fact =
        marker(orderId, UUID.randomUUID(), holderId, null, amount, JobOrderMoneyKind.SHORTAGE);
    fact.patchChangeId = patchChangeId;
    return fact;
  }

  /**
   * Creates the real shortage a currency wipe books on a Bank account position (REQ-PATCH-044).
   *
   * @param orderId the order
   * @param accountHoldingId the order's position
   * @param amount the lost amount, positive whole aUEC
   * @param patchChangeId the patch change the shortage is attributed to
   * @return the shortage, recorded by no actor
   */
  static JobOrderMoneyOperation accountCurrencyWipeShortage(
      UUID orderId, UUID accountHoldingId, BigDecimal amount, UUID patchChangeId) {
    var fact =
        accountOperation(
            orderId,
            UUID.randomUUID(),
            accountHoldingId,
            null,
            null,
            JobOrderMoneyKind.SHORTAGE,
            null,
            amount);
    fact.patchChangeId = patchChangeId;
    return fact;
  }

  UUID personalHolderId() {
    return Objects.requireNonNull(holderId);
  }

  static JobOrderMoneyOperation reassignment(
      UUID orderId,
      UUID peerOrderId,
      UUID operationId,
      UUID holderId,
      @Nullable UUID actorId,
      BigDecimal amount,
      JobOrderMoneyKind kind,
      JobOrderMoneySelection.Kind selectionKind) {
    var fact = marker(orderId, operationId, holderId, actorId, amount, kind);
    fact.peerOrderId = peerOrderId;
    fact.selectionKind = selectionKind;
    return fact;
  }

  static JobOrderMoneyOperation correction(
      UUID orderId, UUID operationId, UUID holderId, UUID actorId, @Nullable UUID peerOrderId) {
    var fact =
        marker(
            orderId, operationId, holderId, actorId, BigDecimal.ZERO, JobOrderMoneyKind.CORRECTION);
    fact.peerOrderId = peerOrderId;
    return fact;
  }

  private static JobOrderMoneyOperation marker(
      UUID orderId,
      UUID operationId,
      UUID holderId,
      @Nullable UUID actorId,
      BigDecimal amount,
      JobOrderMoneyKind kind) {
    return new JobOrderMoneyOperation(
        orderId,
        operationId,
        holderId,
        actorId,
        new JobOrderMoneySelection(JobOrderMoneySelection.Kind.EXISTING, holderId, null),
        null,
        false,
        amount,
        kind,
        null,
        null,
        null,
        null,
        null);
  }

  boolean matchesSelection(JobOrderMoneySelection selection) {
    if (selectionKind == null || selection.kind() != selectionKind) {
      return false;
    }
    return switch (selectionKind) {
      case SELF -> true;
      case EXISTING ->
          Objects.equals(
              (kind == JobOrderMoneyKind.COLLECTION || kind == JobOrderMoneyKind.REQUESTER_RETURN)
                  ? destinationHolderId
                  : holderId,
              selection.reference());
      case LINKED -> Objects.equals(selectedUserId, selection.reference());
      case ACCOUNTLESS -> Objects.equals(selectedAccountlessName, selection.name());
    };
  }

  BigDecimal providedAmount() {
    return kind == JobOrderMoneyKind.PROVISION
        ? amount
        : Objects.requireNonNullElse(newProvision, BigDecimal.ZERO);
  }

  BigDecimal feeAmount() {
    return fee == null ? BigDecimal.ZERO : fee;
  }

  JobOrderMoneyAmounts amounts() {
    return new JobOrderMoneyAmounts(amount, providedAmount(), feeAmount());
  }

  BigDecimal holderEffect(JobOrderMoneyAmounts effective) {
    return switch (kind) {
      case PROVISION, PURPOSE_INFLOW -> effective.amount();
      case REQUESTER_RETURN -> effective.provision().subtract(effective.fee());
      case CORRECTION -> BigDecimal.ZERO;
      case COLLECTION, HANDOVER, PURPOSE_HANDOVER, RELEASE, SHORTAGE, SETTLEMENT, PURPOSE_OUTFLOW ->
          effective.provision().subtract(effective.amount()).subtract(effective.fee());
    };
  }

  BigDecimal handoverEffect(JobOrderMoneyAmounts effective) {
    return switch (kind) {
      case HANDOVER, PURPOSE_HANDOVER -> effective.amount();
      case REQUESTER_RETURN -> effective.amount().negate();
      case PROVISION,
          COLLECTION,
          RELEASE,
          SHORTAGE,
          SETTLEMENT,
          PURPOSE_INFLOW,
          PURPOSE_OUTFLOW,
          CORRECTION ->
          BigDecimal.ZERO;
    };
  }
}

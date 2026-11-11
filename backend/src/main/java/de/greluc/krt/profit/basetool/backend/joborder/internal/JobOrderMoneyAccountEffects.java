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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Applies account-backed effects to the common order ledger under its prepared guard. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyAccountEffects {
  private final JobOrderMoneyAccountHoldingRepository holdings;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyCorrectionEntryRepository corrections;
  private final JobOrderMoneyCommitmentCredit credits;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderService lifecycle;
  private final JobOrderMoneyEffects effects;

  void updateBinding(JobOrderLinkedMoneyAttempt attempt) {
    position(attempt).recordChange();
  }

  JobOrderEdit post(JobOrder order, JobOrderLinkedMoneyAttempt attempt) {
    UUID orderId = order.getId();
    var position = position(attempt);
    UUID positionId = Objects.requireNonNull(position.getId());
    boolean delivers = attempt.getKind() == JobOrderMoneyKind.PURPOSE_HANDOVER;
    if (delivers
        && ledger
                .read(orderId)
                .availableAccountBalance(positionId, attempt.getId())
                .compareTo(attempt.getAmount())
            < 0) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    operations.saveAndFlush(
        JobOrderMoneyOperation.accountOperation(
            orderId,
            attempt.getOperationId(),
            positionId,
            attempt.getActorId(),
            attempt.getCommitmentId(),
            attempt.getKind(),
            attempt.getRecipientId(),
            attempt.getAmount()));
    if (attempt.getCommitmentId() != null) {
      credits.recordDelayedProvision(
          order, attempt.getOperationId(), attempt.getCommitmentId(), attempt.getAmount());
    }
    position.recordChange();
    boolean started = order.getStatus() == JobOrderStatus.OPEN;
    boolean completes =
        delivers
            && order.getStatus().isActive()
            && ledger.delivered(orderId).compareTo(targets.amountFor(orderId).orElseThrow()) >= 0;
    if (completes) {
      lifecycle.completeJobOrderWithinTransaction(order);
    } else if (started) {
      JobOrderService.changeLifecycle(order, JobOrderStatus.IN_PROGRESS);
    }
    effects.accountMoneyChanged(order, started || completes);
    return JobOrderEdit.audited(
        order,
        delivers ? AuditEventType.JOB_ORDER_MONEY_MOVED : AuditEventType.JOB_ORDER_MONEY_PROVIDED,
        AuditDetails.of("operationId", attempt.getOperationId())
            .with("attemptId", attempt.getId())
            .with("kind", attempt.getKind())
            .with("amount", attempt.getAmount()));
  }

  void reverse(JobOrder order, JobOrderLinkedMoneyAttempt attempt, UUID actor) {
    UUID orderId = order.getId();
    var position = position(attempt);
    var current = ledger.read(orderId);
    var original =
        operations
            .findByOrderIdAndOperationId(orderId, attempt.getOperationId())
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    UUID correctionId = UUID.randomUUID();
    var entry =
        new JobOrderMoneyCorrectionEntry(
            orderId,
            correctionId,
            original.getOperationId(),
            current.effective(original),
            new JobOrderMoneyAmounts(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    var revised = current.revised(List.of(entry));
    if (!revised.nonnegativeAvailable()) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    operations.saveAndFlush(
        JobOrderMoneyOperation.accountOperation(
            orderId,
            correctionId,
            Objects.requireNonNull(position.getId()),
            actor,
            null,
            JobOrderMoneyKind.CORRECTION,
            null,
            BigDecimal.ZERO));
    corrections.save(entry);
    position.recordChange();
    boolean reopens =
        order.getStatus() == JobOrderStatus.COMPLETED
            && revised.handedOver().compareTo(targets.amountFor(orderId).orElseThrow()) < 0;
    if (reopens) {
      lifecycle.reopenUnderfulfilledMoneyOrder(order);
    }
    effects.accountMoneyChanged(order, reopens);
  }

  private JobOrderMoneyAccountHolding position(JobOrderLinkedMoneyAttempt attempt) {
    return holdings
        .findByOrderIdAndId(
            attempt.getOrderId(), Objects.requireNonNull(attempt.getAccountHoldingId()))
        .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
  }
}

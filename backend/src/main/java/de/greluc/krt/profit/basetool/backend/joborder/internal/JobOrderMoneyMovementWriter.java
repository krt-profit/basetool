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
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Books a prepared two-sided movement and its lifecycle and contribution effects. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyMovementWriter {
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyCommitmentCredit commitmentCredit;
  private final JobOrderService orders;
  private final JobOrderMoneyEffects effects;

  Posted post(
      JobOrder order,
      JobOrderMoneyHolder source,
      @Nullable JobOrderMoneyHolder destination,
      Terms terms) {
    var movement = terms.movement();
    source.recordChange();
    UUID destinationId = null;
    boolean newLinkedHolder = false;
    if (destination != null) {
      newLinkedHolder = destination.getId() == null && destination.getUserId() != null;
      destination.recordChange();
      destinationId = Objects.requireNonNull(holders.save(destination).getId());
    }
    var fact =
        operations.saveAndFlush(
            new JobOrderMoneyOperation(
                order.getId(),
                terms.operationId(),
                movement.sourceId(),
                terms.originalActor(),
                movement.selection(),
                movement.commitmentId(),
                terms.attemptId() != null,
                movement.amount(),
                movement.kind(),
                destinationId,
                movement.recipientId(),
                movement.provision(),
                terms.rate(),
                terms.fee()));
    if (movement.commitmentId() != null) {
      if (terms.attemptId() == null) {
        commitmentCredit.recordFreshProvision(
            order, terms.operationId(), movement.commitmentId(), movement.provision());
      } else {
        commitmentCredit.recordDelayedProvision(
            order, terms.operationId(), movement.commitmentId(), movement.provision());
      }
    }
    boolean started = order.getStatus() == JobOrderStatus.OPEN;
    boolean completes = order.getStatus().isActive() && terms.completes();
    if (completes) {
      orders.completeJobOrderWithinTransaction(order);
    } else if (started) {
      JobOrderService.changeLifecycle(order, JobOrderStatus.IN_PROGRESS);
    }
    effects.movementRecorded(
        order,
        fact,
        source.getUserId(),
        terms.receivingUser(),
        terms.decisionActor(),
        started || completes,
        newLinkedHolder);
    var details =
        AuditDetails.of("operationId", terms.operationId())
            .with("kind", movement.kind())
            .with("amount", movement.amount())
            .with("fee", terms.fee())
            .with("provision", movement.provision());
    if (terms.attemptId() != null) {
      details = details.with("attemptId", terms.attemptId());
    }
    return new Posted(
        fact, JobOrderEdit.audited(order, AuditEventType.JOB_ORDER_MONEY_MOVED, details));
  }

  /** Stored movement and its synchronous audit. */
  record Posted(JobOrderMoneyOperation fact, JobOrderEdit edit) {}

  /** Validated order terms and actors for an effective movement. */
  record Terms(
      UUID operationId,
      @Nullable UUID originalActor,
      UUID decisionActor,
      JobOrderMoneyMovementTerms movement,
      @Nullable UUID receivingUser,
      BigDecimal rate,
      BigDecimal fee,
      boolean completes,
      @Nullable UUID attemptId) {}
}

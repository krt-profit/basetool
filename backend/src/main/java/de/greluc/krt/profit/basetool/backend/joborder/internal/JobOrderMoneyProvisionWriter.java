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

/** Books effective provision in the order's prepared transaction. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyProvisionWriter {
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyCommitmentCredit commitmentCredit;
  private final JobOrderMoneyEffects effects;

  Posted post(JobOrder order, JobOrderMoneyHolder holder, Terms terms) {
    return write(order, holder, terms, terms.bankHandling());
  }

  Posted postRecovered(JobOrder order, JobOrderMoneyHolder holder, Terms terms) {
    return write(order, holder, terms, true);
  }

  private Posted write(
      JobOrder order, JobOrderMoneyHolder holder, Terms terms, boolean delayedCredit) {
    final boolean newLinkedHolder =
        terms.newLinkedHolder() || (holder.getId() == null && holder.getUserId() != null);
    holder.recordChange();
    UUID holderId = Objects.requireNonNull(holders.save(holder).getId());
    JobOrderMoneyOperation operation =
        operations.saveAndFlush(
            new JobOrderMoneyOperation(
                order.getId(),
                terms.operationId(),
                holderId,
                terms.actor(),
                terms.selection(),
                terms.commitmentId(),
                terms.bankHandling(),
                terms.amount()));
    if (terms.commitmentId() != null) {
      if (delayedCredit) {
        commitmentCredit.recordDelayedProvision(
            order, terms.operationId(), terms.commitmentId(), terms.amount());
      } else {
        commitmentCredit.recordFreshProvision(
            order, terms.operationId(), terms.commitmentId(), terms.amount());
      }
    }
    boolean started = order.getStatus() == JobOrderStatus.OPEN;
    if (started) {
      JobOrderService.changeLifecycle(order, JobOrderStatus.IN_PROGRESS);
    }
    effects.provisionRecorded(
        order, holder, operation, terms.decisionActor(), started, newLinkedHolder);
    var details =
        AuditDetails.of("operationId", terms.operationId()).with("amount", terms.amount());
    if (terms.attemptId() != null) {
      details = details.with("attemptId", terms.attemptId());
    }
    return new Posted(
        operation, JobOrderEdit.audited(order, AuditEventType.JOB_ORDER_MONEY_PROVIDED, details));
  }

  /** Effective provision and its synchronous audit. */
  record Posted(JobOrderMoneyOperation operation, JobOrderEdit edit) {}

  /** Order-side terms and actors carried into effective provision. */
  record Terms(
      UUID operationId,
      @Nullable UUID actor,
      UUID decisionActor,
      JobOrderMoneySelection selection,
      @Nullable UUID commitmentId,
      boolean bankHandling,
      BigDecimal amount,
      boolean newLinkedHolder,
      @Nullable UUID attemptId) {}
}

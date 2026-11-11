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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Keeps money commitments and unexecuted Bank intents consistent with order lifecycle changes. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class JobOrderMoneyLifecycle {
  private final JobOrderMoneyCommitments commitments;
  private final JobOrderLinkedMoneyAttemptRepository attempts;
  private final ObjectProvider<JobOrderBankPort> bank;

  void close(JobOrder order) {
    if (order.getType() != JobOrderType.AUEC) {
      return;
    }
    UUID orderId = order.getId();
    if (order.getStatus() == JobOrderStatus.REJECTED
        && attempts.hasUnresolvedActualPayment(orderId)) {
      throw new BusinessConflictException("orders.money.bank.paymentRequiresSettlement");
    }
    cancelUnexecuted(orderId);
    commitments.endOpenForOrder(order);
  }

  void responsibilityLost(JobOrder order, @Nullable UUID previousUnit) {
    commitments.endForLostResponsibility(order, previousUnit);
  }

  private void cancelUnexecuted(UUID orderId) {
    var waiting = attempts.unexecutedWaiting(orderId);
    if (waiting.isEmpty()) {
      return;
    }
    var port = bank.getIfUnique();
    if (port == null) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var permit = new CleanupPermit();
    TransactionSynchronizationManager.registerSynchronization(permit);
    try {
      for (var attempt : waiting) {
        permit.attemptId = attempt.getId();
        port.cancelUnexecuted(
            Objects.requireNonNull(attempt.getRequestId()),
            attempt.getId(),
            attempt.getPurposeRef());
        if (permit.attemptId != null) {
          throw JobOrderLinkedMoneyAttempt.conflict();
        }
      }
    } finally {
      permit.attemptId = null;
    }
  }

  static void consumeCleanupPermit(UUID attemptId) {
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof CleanupPermit permit && attemptId.equals(permit.attemptId)) {
        permit.attemptId = null;
        return;
      }
    }
    throw JobOrderLinkedMoneyAttempt.conflict();
  }

  /** Allows only the current unexecuted sibling to reuse the already held order guard. */
  private static final class CleanupPermit implements TransactionSynchronization {
    @Nullable private UUID attemptId;
  }
}

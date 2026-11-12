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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Plans the shared priority guard only when a linked effect can complete or reopen the order. */
@Component
@RequiredArgsConstructor
class JobOrderLinkedMoneyQueueImpact {
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyTargetRepository targets;

  boolean posting(JobOrder order, JobOrderMoneyKind kind, BigDecimal amount) {
    if (kind.delivers() && order.getStatus().isActive()) {
      return reachesTarget(order, amount);
    }
    return kind == JobOrderMoneyKind.REQUESTER_RETURN
        && order.getStatus() == JobOrderStatus.COMPLETED
        && !reachesTarget(order, amount.negate());
  }

  boolean reversal(JobOrder order, JobOrderMoneyKind kind, BigDecimal amount) {
    if (kind.delivers() && order.getStatus() == JobOrderStatus.COMPLETED) {
      return !reachesTarget(order, amount.negate());
    }
    return kind == JobOrderMoneyKind.REQUESTER_RETURN
        && order.getStatus().isActive()
        && reachesTarget(order, amount);
  }

  private boolean reachesTarget(JobOrder order, BigDecimal deliveryChange) {
    var target = targets.amountFor(order.getId()).orElseThrow();
    return ledger.delivered(order.getId()).add(deliveryChange).compareTo(target) >= 0;
  }
}

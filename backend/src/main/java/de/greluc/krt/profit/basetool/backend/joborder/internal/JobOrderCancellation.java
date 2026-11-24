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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCaller;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientWriteRefusedException;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatusCommand;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels a job order through the web's status command for a client's write: the shared rules of a
 * client write on an existing order, then the cancel's own rule that no money operation of the
 * order was accepted yet, all decided under the order locks (REQ-OXCH-005, REQ-OXCH-006, ADR-0408).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderCancellation implements JobOrderStatusCommand {

  private final JobOrderService orders;
  private final JobOrderClientWriteRules rules;
  private final JobOrderRepository repository;

  @Override
  public void cancel(@NotNull UUID orderId, long lifecycleVersion, @NotNull JobOrderCaller caller) {
    JobOrderClientWriteRules.Write cancel =
        new JobOrderClientWriteRules.Write(
            (writes, order) -> writes.permitsStatus(order, JobOrderStatus.CANCELLED),
            JobOrder::getLifecycleVersion,
            lifecycleVersion,
            JobOrderStatus::isActive);
    orders.changeStatus(
        orderId,
        JobOrderStatus.CANCELLED,
        caller.actor(),
        rules.checks(caller, cancel).andThen(this::requireNoMoney));
  }

  /**
   * Refuses the cancel of an order a money operation of which reached its accepted, durable
   * submission boundary, the hard-delete boundary of REQ-ORDERS-063.
   *
   * @param order the loaded order
   * @throws JobOrderClientWriteRefusedException with {@code MONEY_STARTED} for such an order
   */
  private void requireNoMoney(@NotNull JobOrder order) {
    if (repository.hasDurableMoneySubmission(order.getId())) {
      throw new JobOrderClientWriteRefusedException(
          JobOrderClientWriteRefusedException.Refusal.MONEY_STARTED);
    }
  }
}

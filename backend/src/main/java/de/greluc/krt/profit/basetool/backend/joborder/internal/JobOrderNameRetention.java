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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the names in closed job orders whose last change lies more than the name retention ago
 * (REQ-DATA-047): the requester handle, accountless holders, recipients and submitted names, the
 * names of holder changes and linked attempts, and handover recipients. The amounts and the order
 * stay.
 */
@Component
@RequiredArgsConstructor
public class JobOrderNameRetention implements NameRetentionParticipant {

  private final JobOrderRepository orders;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyHolderChangeRepository holderChanges;
  private final JobOrderMoneyRecipientRepository recipients;
  private final JobOrderHandoverRepository handovers;
  private final JobOrderItemHandoverRepository itemHandovers;
  private final JobOrderMoneyPrivacy moneyPrivacy;

  @Override
  public @NotNull String area() {
    return "job_orders";
  }

  @Override
  @Transactional
  public int removeNamesBefore(@NotNull Instant cutoff) {
    String sentinel = HandleAnonymisation.SENTINEL;
    List<UUID> orderIds = holders.lockOrdersWithNamesBefore(cutoff, sentinel);
    if (orderIds.isEmpty()) {
      return 0;
    }
    int changed =
        orders.clearHandles(orderIds, sentinel)
            + holders.clearNames(orderIds, sentinel)
            + operations.clearSubmittedNames(orderIds)
            + holders.clearLinkedAttemptNames(orderIds)
            + holderChanges.clearNames(orderIds, sentinel)
            + recipients.clearNames(orderIds, sentinel)
            + handovers.clearRecipientHandles(orderIds, sentinel)
            + itemHandovers.clearRecipientHandles(orderIds, sentinel);
    moneyPrivacy.publishChanges(orderIds);
    return changed;
  }
}

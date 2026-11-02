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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockLevelDemandSource;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialRequirementResolver;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialRequirementResolver.MaterialRequirement;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService.OrderLinkedStockIndex;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports what the units' {@code OPEN} and {@code IN_PROGRESS} job orders still ask for to the
 * minimum stock levels (REQ-ORDERS-034, REQ-INV-067): a material order's and an item order's
 * derived material still to gather, and the requested game items not yet delivered.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderStockLevelDemand implements StockLevelDemandSource {

  private static final List<JobOrderStatus> OPEN_STATUSES =
      List.of(JobOrderStatus.OPEN, JobOrderStatus.IN_PROGRESS);

  private final JobOrderRepository jobOrderRepository;
  private final JobOrderMaterialRequirementResolver materialRequirementResolver;
  private final JobOrderStockProjectionService jobOrderStockProjectionService;

  @Override
  public @NotNull List<Request> outstandingRequests(
      @NotNull Collection<UUID> unitIds, @NotNull Collection<UUID> subjectIds) {
    List<JobOrder> orders = jobOrderRepository.findRequestedBy(OPEN_STATUSES, unitIds);
    if (orders.isEmpty()) {
      return List.of();
    }
    Set<UUID> subjects = Set.copyOf(subjectIds);
    OrderLinkedStockIndex stockIndex =
        jobOrderStockProjectionService.loadOrderLinkedStockIndex(
            orders.stream().map(JobOrder::getId).toList());
    List<Request> requests = new ArrayList<>();
    for (JobOrder order : orders) {
      UUID unitId = order.getRequestingOrgUnit().getId();
      addMaterialRequests(requests, order, unitId, subjects, stockIndex);
      addItemRequests(requests, order, unitId, subjects);
    }
    return requests;
  }

  private void addMaterialRequests(
      @NotNull List<Request> requests,
      @NotNull JobOrder order,
      @NotNull UUID unitId,
      @NotNull Set<UUID> subjects,
      @NotNull OrderLinkedStockIndex stockIndex) {
    List<MaterialRequirement> requirements = materialRequirementResolver.requirementsOf(order);
    double[] booked = stockIndex.bookedFor(order.getId(), requirements);
    for (int i = 0; i < requirements.size(); i++) {
      MaterialRequirement requirement = requirements.get(i);
      if (subjects.contains(requirement.material().id())) {
        add(
            requests,
            unitId,
            requirement.material().id(),
            requirement.tier().minQuality(),
            requirement.requiredAmount() - booked[i]);
      }
    }
  }

  private static void addItemRequests(
      @NotNull List<Request> requests,
      @NotNull JobOrder order,
      @NotNull UUID unitId,
      @NotNull Set<UUID> subjects) {
    for (JobOrderItem line : order.getItems()) {
      UUID itemId = line.getGameItem().getId();
      if (subjects.contains(itemId)) {
        add(requests, unitId, itemId, 0, line.getAmount() - line.getDeliveredAmount());
      }
    }
  }

  private static void add(
      @NotNull List<Request> requests,
      @NotNull UUID unitId,
      @NotNull UUID subjectId,
      int minQuality,
      double outstanding) {
    double amount = ScuAmounts.roundToScuScale(outstanding);
    if (amount > 0.0) {
      requests.add(new Request(unitId, subjectId, minQuality, amount));
    }
  }
}

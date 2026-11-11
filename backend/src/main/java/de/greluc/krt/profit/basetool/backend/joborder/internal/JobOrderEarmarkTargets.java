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

import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargets;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderEarmark;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exposes readable order identities and requirements to Inventory without granting stock authority.
 */
@Component
@RequiredArgsConstructor
public class JobOrderEarmarkTargets implements EarmarkTargets.JobOrders {

  private final JobOrderRepository jobOrderRepository;
  private final JobOrderItemService jobOrderItemService;
  private final JobOrderAccessPolicy jobOrderAccessPolicy;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Map<UUID, JobOrderEarmark> jobOrders(@NotNull Collection<UUID> ids) {
    Map<UUID, JobOrderEarmark> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    for (JobOrderEarmark jobOrder : jobOrderRepository.findEarmarks(ids)) {
      byId.put(jobOrder.id(), jobOrder);
    }
    return byId;
  }

  @Override
  @Transactional(readOnly = true)
  public @Nullable Set<UUID> requiredMaterialIds(@NotNull UUID jobOrderId) {
    return requirementsOf(
        jobOrderId, this::ordinaryEarmarkAllowed, jobOrderItemService::requiredMaterialIds);
  }

  @Override
  @Transactional(readOnly = true)
  public @Nullable Set<UUID> requiredGameItemIds(@NotNull UUID jobOrderId) {
    return requirementsOf(
        jobOrderId, this::ordinaryEarmarkAllowed, jobOrderItemService::requiredGameItemIds);
  }

  @Override
  @Transactional(readOnly = true)
  public @Nullable Set<UUID> requiredMaterialIdsInScope(@NotNull UUID jobOrderId) {
    return requirementsOf(
        jobOrderId, this::poolEarmarkAllowed, jobOrderItemService::requiredMaterialIds);
  }

  @Override
  @Transactional(readOnly = true)
  public @Nullable Set<UUID> requiredGameItemIdsInScope(@NotNull UUID jobOrderId) {
    return requirementsOf(
        jobOrderId, this::poolEarmarkAllowed, jobOrderItemService::requiredGameItemIds);
  }

  private boolean ordinaryEarmarkAllowed(JobOrder order) {
    if (!jobOrderAccessPolicy.canSeeJobOrder(order)) {
      return false;
    }
    if (order.getStatus() == JobOrderStatus.CANCELLED) {
      throw new BadRequestException("error.job_order.earmark.cancelled");
    }
    return true;
  }

  private boolean poolEarmarkAllowed(JobOrder order) {
    return order.getStatus() != JobOrderStatus.CANCELLED
        && jobOrderAccessPolicy.canSeeJobOrderInScope(order);
  }

  /**
   * Reads attachment requirements after checking the target's scope and status.
   *
   * @param jobOrderId the job order
   * @param visibility the caller's required read contract
   * @param requirements the material or item requirements
   * @return the requirements, or {@code null} for an unknown or unreadable order
   */
  private @Nullable Set<UUID> requirementsOf(
      @NotNull UUID jobOrderId,
      @NotNull Predicate<JobOrder> visibility,
      @NotNull Function<JobOrder, Set<UUID>> requirements) {
    return jobOrderRepository
        .findById(jobOrderId)
        .filter(order -> order.getType().usesStock())
        .filter(visibility)
        .map(requirements)
        .orElse(null);
  }
}

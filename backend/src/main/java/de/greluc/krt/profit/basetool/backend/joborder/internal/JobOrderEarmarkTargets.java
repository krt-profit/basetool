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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the Lager the running number of the job orders its stock is earmarked to and what an order
 * requires.
 */
@Component
@RequiredArgsConstructor
public class JobOrderEarmarkTargets implements EarmarkTargets.JobOrders {

  private final JobOrderRepository jobOrderRepository;
  private final JobOrderItemService jobOrderItemService;

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
    return jobOrderRepository
        .findById(jobOrderId)
        .map(jobOrderItemService::requiredMaterialIds)
        .orElse(null);
  }

  @Override
  @Transactional(readOnly = true)
  public @Nullable Set<UUID> requiredGameItemIds(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(jobOrderItemService::requiredGameItemIds)
        .orElse(null);
  }
}

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

import de.greluc.krt.profit.basetool.backend.inventory.api.OrderLinkedStockWriteObserver;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.AppExceptionKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Enforces responsibility validity for Inventory-owned stock operations in one batch. */
@Service
@RequiredArgsConstructor
public class JobOrderStockWriteEligibility implements OrderLinkedStockWriteObserver {

  private final JobOrderRepository orders;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderResponsibilityGuard responsibilityGuard;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void beforeOrderLinkedStockWrite(@NotNull Collection<UUID> orderIds) {
    profitHierarchy.lockForOrderWrite();
    if (orderIds.isEmpty()) {
      return;
    }
    Set<UUID> ids = Set.copyOf(orderIds);
    List<JobOrder> affected = orders.findAllById(ids);
    if (affected.size() != ids.size()) {
      throw new NotFoundException(AppExceptionKind.NOT_FOUND.detailKey());
    }
    if (responsibilityGuard.validOrderIds(affected).size() != ids.size()) {
      throw new BadRequestException("orders.responsibility.invalid");
    }
  }
}

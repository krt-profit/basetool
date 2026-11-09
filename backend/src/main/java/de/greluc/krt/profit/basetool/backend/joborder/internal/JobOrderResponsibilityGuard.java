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
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchySnapshot;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies the shared responsibility prerequisite to every user write. */
@Service
@RequiredArgsConstructor
public class JobOrderResponsibilityGuard {

  private final ProfitHierarchyService profitHierarchy;

  /**
   * Reports actual hierarchy validity without treating unknown legacy provenance as ineligibility.
   *
   * @param order the order to inspect
   * @return whether its current responsible unit is valid
   */
  public boolean isValid(@NotNull JobOrder order) {
    return isValid(order, profitHierarchy.snapshot());
  }

  private static boolean isValid(JobOrder order, ProfitHierarchySnapshot snapshot) {
    return order.getResponsibleOrgUnit() != null
        && snapshot.eligibleUnitIds().contains(order.getResponsibleOrgUnit().getId())
        && (order.getOriginalProfitOrgUnitId() == null
            || Objects.equals(snapshot.profitRootId(), order.getOriginalProfitOrgUnitId()));
  }

  /**
   * Resolves valid orders in a batch without one hierarchy lookup per order.
   *
   * @param orders the orders whose mutable rows are considered
   * @return ids of orders with valid current responsibility and original boundary
   */
  public Set<UUID> validOrderIds(Collection<JobOrder> orders) {
    var snapshot = profitHierarchy.snapshot();
    return orders.stream()
        .filter(order -> isValid(order, snapshot))
        .map(JobOrder::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Stabilizes and checks current responsibility before any user mutation.
   *
   * @param order the managed order
   * @throws BadRequestException when valid responsibility must first be restored by transfer
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void assertWritable(@NotNull JobOrder order) {
    profitHierarchy.lockForOrderWrite();
    if (!isValid(order)) {
      throw new BadRequestException("orders.responsibility.invalid");
    }
  }
}

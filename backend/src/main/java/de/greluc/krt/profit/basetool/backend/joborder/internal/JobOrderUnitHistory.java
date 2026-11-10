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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Retains only organisational parties actually observed by an authorised order command. */
@Service
@RequiredArgsConstructor
public class JobOrderUnitHistory {

  private final JobOrderUnitReadHistoryRepository repository;

  /**
   * Retains the parties before and after an authorised command in its transaction.
   *
   * @param order the persisted result
   * @param previousParties parties observed before the command, empty for creation
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void retainObservedParties(@NotNull JobOrder order, @NotNull Set<UUID> previousParties) {
    Set<UUID> observed = partyIds(order);
    observed.addAll(previousParties);
    for (UUID unit : observed) {
      repository.retainObservedUnit(order.getId(), unit);
    }
  }

  static Set<UUID> partyIds(@NotNull JobOrder order) {
    Set<UUID> ids = new LinkedHashSet<>();
    if (order.getResponsibleOrgUnit() != null) {
      ids.add(order.getResponsibleOrgUnit().getId());
    }
    if (order.getRequestingOrgUnit() != null) {
      ids.add(order.getRequestingOrgUnit().getId());
    }
    return ids;
  }
}

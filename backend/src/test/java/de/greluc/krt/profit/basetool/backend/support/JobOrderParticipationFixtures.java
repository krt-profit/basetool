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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderParticipation;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderParticipationRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import org.jetbrains.annotations.NotNull;

/** Creates the one persisted participation shared by an order's Staffel contribution fixtures. */
public final class JobOrderParticipationFixtures {
  private JobOrderParticipationFixtures() {}

  /**
   * Reuses or persists the one participation for the fixture's order and Staffel. Initial support
   * excludes the responsible Staffel's own contribution.
   *
   * @param participations the real fixture repository
   * @param order the persisted order
   * @param unit the persisted Staffel
   * @return the persisted participation for every fixture contribution in this order and Staffel
   */
  @NotNull
  public static JobOrderParticipation persistedParticipation(
      @NotNull JobOrderParticipationRepository participations,
      @NotNull JobOrder order,
      @NotNull OrgUnit unit) {
    return participations
        .findByJobOrderIdAndClaimingOrgUnitId(order.getId(), unit.getId())
        .orElseGet(() -> {
          JobOrderParticipation participation = new JobOrderParticipation();
          participation.setJobOrder(order);
          participation.setClaimingOrgUnit(unit);
          participation.setActive(
              order.getResponsibleOrgUnit() == null
                  || !order.getResponsibleOrgUnit().getId().equals(unit.getId()));
          return participations.saveAndFlush(participation);
        });
  }
}

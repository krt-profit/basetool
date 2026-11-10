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
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Owns the current claim and explicit provision authority without identity-write dependencies. */
@Service
@RequiredArgsConstructor
public class MaterialClaimAuthority {
  private final AuthHelperService actor;
  private final AccessGateService access;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderResponsibilityGuard responsibility;

  /**
   * Checks the existing responsible-SK or contextual own-Staffel claim grant.
   *
   * @param order the concrete claimable order
   * @param unitId the claiming Staffel
   * @return whether this actor may write that Staffel's promise
   */
  public boolean canManage(@NotNull JobOrder order, @NotNull UUID unitId) {
    return actor.isAdmin()
        || access.hasRoleInOrgUnit(order.getResponsibleOrgUnit().getId(), Roles.LOGISTICIAN)
        || access.canActAsLogisticianIn(unitId);
  }

  /**
   * Rejects a claim write by an actor without the matching unit grant.
   *
   * @param order the concrete claimable order
   * @param unitId the claiming Staffel
   */
  public void assertCanManage(JobOrder order, UUID unitId) {
    if (!canManage(order, unitId)) {
      throw new AccessDeniedException("orders.claim.provision.forbidden");
    }
  }

  /**
   * Checks whether the existing material-claim workflow is open for this order.
   *
   * @param order the order
   * @return whether its current kind of responsibility and lifecycle permit claims
   */
  public boolean claimable(JobOrder order) {
    return order.getResponsibleOrgUnit() != null
        && order.getResponsibleOrgUnit().getKind() == OrgUnitKind.SPECIAL_COMMAND
        && order.getStatus() != JobOrderStatus.COMPLETED
        && order.getStatus() != JobOrderStatus.REJECTED;
  }

  /**
   * Rejects a closed or non-SK order in the current material-claim workflow.
   *
   * @param order the order
   */
  public void assertClaimable(JobOrder order) {
    if (!claimable(order)) {
      throw new BadRequestException("orders.claim.provision.not_open");
    }
  }

  /**
   * Rechecks current responsibility, lifecycle, eligibility and writer before provision credit.
   *
   * @param claim the selected current contribution
   */
  public void assertCanRecordProvision(MaterialClaim claim) {
    responsibility.assertWritable(claim.getJobOrder());
    assertClaimable(claim.getJobOrder());
    if (claim.getEndedAt() != null
        || claim.getClaimingOrgUnit().getKind() != OrgUnitKind.SQUADRON
        || !profitHierarchy.isEligible(claim.getClaimingOrgUnit().getId())) {
      throw new BadRequestException("orders.claim.claimingUnit.ineligible");
    }
    assertCanManage(claim.getJobOrder(), claim.getClaimingOrgUnit().getId());
  }
}

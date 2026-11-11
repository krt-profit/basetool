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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the current claim and explicit provision authority without identity-write dependencies. */
@Service("materialClaimAuthority")
@RequiredArgsConstructor
public class MaterialClaimAuthority {
  private final AccessGateService access;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderResponsibilityGuard responsibility;
  private final JobOrderRepository orders;
  private final MaterialClaimRepository claims;
  private final JobOrderAccessPolicy orderAccess;

  /**
   * Checks write access to the selected Staffel's promise on the readable order.
   *
   * @param orderId the concrete order
   * @param unitId the selected claiming Staffel
   * @return whether the actor may submit a write for that Staffel
   */
  @Transactional(readOnly = true)
  public boolean canWritePromise(@NotNull UUID orderId, @NotNull UUID unitId) {
    return orders
        .findById(orderId)
        .filter(orderAccess::canSeeJobOrder)
        .filter(responsibility::isValid)
        .filter(this::claimable)
        .map(order -> canManage(order, unitId))
        .orElse(false);
  }

  /**
   * Checks current write access to a concrete promise owned by the requested order.
   *
   * @param orderId the concrete order
   * @param claimId the observed promise identity
   * @return whether the actor may withdraw the existing promise
   */
  @Transactional(readOnly = true)
  public boolean canWithdrawPromise(@NotNull UUID orderId, @NotNull UUID claimId) {
    return claims
        .findHistoricalById(claimId)
        .filter(claim -> orderId.equals(claim.getJobOrder().getId()))
        .filter(claim -> orderAccess.canSeeJobOrder(claim.getJobOrder()))
        .filter(claim -> responsibility.isValid(claim.getJobOrder()))
        .filter(claim -> claimable(claim.getJobOrder()))
        .map(claim -> canWithdraw(claim.getJobOrder(), claim.getClaimingOrgUnit()))
        .orElse(false);
  }

  private boolean canWithdraw(JobOrder order, OrgUnit staffel) {
    return isEligibleStaffel(staffel) && canManage(order, staffel.getId());
  }

  /**
   * Requires current contribution authority for the eligible Staffel of a retained promise.
   *
   * @param order the locked order
   * @param staffel the contributing Staffel
   */
  void assertCanWithdraw(JobOrder order, OrgUnit staffel) {
    if (!canWithdraw(order, staffel)) {
      throw new AccessDeniedException("orders.claim.provision.forbidden");
    }
  }

  /**
   * Checks the current responsible-unit or own-Staffel grant.
   *
   * @param order the concrete claimable order
   * @param unitId the claiming Staffel
   * @return whether this actor may write that Staffel's promise
   */
  public boolean canManage(@NotNull JobOrder order, @NotNull UUID unitId) {
    return canCoordinate(order) || access.canActAsLogisticianIn(unitId);
  }

  private boolean canCoordinate(JobOrder order) {
    return access.canActAsLogisticianInOwner(OrgUnit.idOf(order.getResponsibleOrgUnit()));
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
   * Checks current read, responsibility and historical contribution authority for a receipt.
   *
   * @param claim the existing contribution referenced by the receipt
   * @return whether the caller may submit an explicit correction for that contribution
   */
  public boolean mayCorrectReceiptFor(@NotNull MaterialClaim claim) {
    JobOrder order = claim.getJobOrder();
    return orderAccess.canSeeJobOrder(order)
        && responsibility.isValid(order)
        && canCorrect(order, claim.getClaimingOrgUnit().getId());
  }

  /**
   * Checks the current responsible-unit or eligible own-Staffel correction grant.
   *
   * @param order the concrete order
   * @param unitId the affected contribution's Staffel
   * @return whether this caller may correct that contribution under the current write predicate
   */
  public boolean canCorrect(@NotNull JobOrder order, @NotNull UUID unitId) {
    return canCoordinate(order)
        || (profitHierarchy.isEligible(unitId) && access.canActAsLogisticianIn(unitId));
  }

  /**
   * Rejects historical correction without the existing entry and affected unit writer grant.
   *
   * @param order the concrete order
   * @param unitId the affected contribution's Staffel
   */
  public void assertCanCorrect(@NotNull JobOrder order, @NotNull UUID unitId) {
    if (!canCorrect(order, unitId)) {
      throw new AccessDeniedException("orders.claim.provision.forbidden");
    }
  }

  /**
   * Checks whether the order is open for material promises.
   *
   * @param order the order
   * @return whether its current kind of responsibility and lifecycle permit claims
   */
  public boolean claimable(JobOrder order) {
    return order.getResponsibleOrgUnit() != null && order.getStatus().isActive();
  }

  /**
   * Rejects an order closed to material promises.
   *
   * @param order the order
   */
  public void assertClaimable(JobOrder order) {
    if (!claimable(order)) {
      throw new BadRequestException("orders.claim.provision.not_open");
    }
  }

  /**
   * Checks an existing open promise as a provision target under current authority.
   *
   * @param claim the concrete open promise, including one retained under responsibility
   * @return whether the promise can currently receive explicit provision
   */
  public boolean canRecordProvision(@NotNull MaterialClaim claim) {
    JobOrder order = claim.getJobOrder();
    return responsibility.isValid(order)
        && claimable(order)
        && isOpenEligibleClaim(claim)
        && canManage(order, claim.getClaimingOrgUnit().getId());
  }

  private boolean isOpenEligibleClaim(MaterialClaim claim) {
    return claim.getEndedAt() == null && isEligibleStaffel(claim.getClaimingOrgUnit());
  }

  private boolean isEligibleStaffel(OrgUnit unit) {
    return unit.getKind() == OrgUnitKind.SQUADRON && profitHierarchy.isEligible(unit.getId());
  }

  /**
   * Rechecks current responsibility, lifecycle, eligibility and writer before provision credit.
   *
   * @param claim the selected current contribution
   */
  public void assertCanRecordProvision(MaterialClaim claim) {
    responsibility.assertWritable(claim.getJobOrder());
    assertClaimable(claim.getJobOrder());
    if (!isOpenEligibleClaim(claim)) {
      throw new BadRequestException("orders.claim.claimingUnit.ineligible");
    }
    assertCanManage(claim.getJobOrder(), claim.getClaimingOrgUnit().getId());
  }
}

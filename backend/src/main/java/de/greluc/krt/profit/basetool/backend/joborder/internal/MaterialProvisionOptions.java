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

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryQuantityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityCorrections;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragments;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialAttributionTargetDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves concrete correction choices through current target and Inventory authority. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialProvisionOptions {
  private final MaterialProvisionCreditRepository provisions;
  private final MaterialClaimRepository contributions;
  private final JobOrderRepository orders;
  private final MaterialContributionHistory history;
  private final MaterialClaimAuthority authority;
  private final JobOrderResponsibilityGuard responsibility;
  private final StockQuantityCorrections stock;

  /**
   * Lists existing matching contributions the actor may explicitly correct to.
   *
   * @param orderId concrete readable order
   * @param provisionId provision whose original attribution the actor may correct
   * @return permitted active or ended contribution targets
   */
  public List<MaterialAttributionTargetDto> attributionTargets(UUID orderId, UUID provisionId) {
    MaterialProvisionCredit credit = authorized(orderId, provisionId);
    JobOrder order =
        Entities.require(orders.findById(orderId), () -> "JobOrder not found: " + orderId);
    var foreign =
        provisions.processedQuantityExcept(
            credit.getQuantityFragments().stream()
                .map(fragment -> fragment.origin())
                .distinct()
                .toList(),
            credit.getId());
    double available =
        StockQuantityFragments.total(
                StockQuantityFragments.subtract(credit.getQuantityFragments(), foreign))
            / 1000.0;
    return history.read(orderId).stream()
        .filter(target ->
            !target.id().equals(credit.getClaimId())
                && target.materialId().equals(credit.getMaterialId())
                && target.minQuality() <= credit.getSuppliedQuality()
                && authority.canCorrect(order, target.claimingOrgUnit().id()))
        .map(target ->
            new MaterialAttributionTargetDto(
                target.id(),
                target.version(),
                target.claimingOrgUnit(),
                target.endedAt() != null,
                target.openAmount(),
                available))
        .toList();
  }

  /**
   * Lists labelled connected physical rows only after current Inventory edit permission.
   *
   * @param orderId concrete readable order
   * @param provisionId provision whose original attribution the actor may correct
   * @return current permitted physical placements and independent versions
   */
  public List<InventoryQuantityCorrectionOptionDto> quantityTargets(
      UUID orderId, UUID provisionId) {
    MaterialProvisionCredit credit = authorized(orderId, provisionId);
    if (credit.getEvidence()
        != de.greluc.krt.profit.basetool.backend.joborder.api.MaterialProvisionEvidence.OBSERVED) {
      throw new de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException(
          "orders.claim.correction.legacy_quantity_unknown");
    }
    return stock.currentRows(credit.getQuantityFragments());
  }

  private MaterialProvisionCredit authorized(UUID orderId, UUID provisionId) {
    MaterialProvisionCredit credit =
        Entities.require(
            provisions.findById(provisionId), () -> "Material provision not found: " + provisionId);
    if (!credit.getOrderId().equals(orderId)) {
      throw new BadRequestException("orders.claim.correction.not_matching");
    }
    JobOrder order =
        Entities.require(orders.findById(orderId), () -> "JobOrder not found: " + orderId);
    if (!responsibility.isValid(order)) {
      throw new BadRequestException("orders.responsibility.invalid");
    }
    var original =
        Entities.require(
            contributions.findHistoricalById(credit.getClaimId()),
            () -> "Material contribution not found");
    authority.assertCanCorrect(order, original.getClaimingOrgUnit().getId());
    return credit;
  }
}

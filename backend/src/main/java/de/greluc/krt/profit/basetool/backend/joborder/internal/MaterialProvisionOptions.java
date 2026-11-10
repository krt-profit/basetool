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

import static de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts.fromQuanta;

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryQualityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryQuantityCorrectionOptionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityCorrections;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragments;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialAttributionTargetDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialProvisionTargetDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves provision and correction choices through current domain authority. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialProvisionOptions {
  private final MaterialProvisionCreditRepository provisions;
  private final MaterialClaimRepository contributions;
  private final MaterialClaimService claims;
  private final MaterialProvisionCredits credits;
  private final JobOrderRepository orders;
  private final MaterialContributionHistory history;
  private final MaterialClaimAuthority authority;
  private final JobOrderResponsibilityGuard responsibility;
  private final StockQuantityCorrections stock;

  /**
   * Lists suitable open promises under the current claim-workflow writer grants.
   *
   * @param orderId the concrete readable order
   * @param materialId the material declared in the ordinary stock action
   * @param quality its declared supplied quality, without requiring existing stock
   * @return matching open contributions with independently observed versions
   */
  public List<MaterialProvisionTargetDto> provisionTargets(
      UUID orderId, UUID materialId, int quality) {
    var writable =
        claims.claimingOptions(orderId).stream()
            .map(OrgUnitMembershipOptionDto::orgUnitId)
            .collect(Collectors.toSet());
    if (writable.isEmpty()) {
      return List.of();
    }
    return contributions.findByJobOrderIdOrderByCreatedAtDesc(orderId).stream()
        .filter(claim -> claim.getEndedAt() == null && claim.getAmount() > 0.0)
        .filter(claim -> claim.getMaterial().getId().equals(materialId))
        .filter(claim -> claim.getQualityTier().getMinQuality() <= quality)
        .filter(claim -> writable.contains(claim.getClaimingOrgUnit().getId()))
        .map(claim -> {
          var unit = claim.getClaimingOrgUnit();
          return new MaterialProvisionTargetDto(
              claim.getId(),
              claim.getVersion(),
              new SquadronReferenceDto(unit.getId(), unit.getName(), unit.getShorthand()),
              claim.getAmount(),
              claim.getQualityTier().getMinQuality(),
              claim.getMaterial().getQuantityType() == QuantityType.PIECE);
        })
        .toList();
  }

  /**
   * Lists existing matching contributions the actor may explicitly correct to.
   *
   * @param orderId concrete readable order
   * @param provisionId provision whose original attribution the actor may correct
   * @param suppliedQuality the corrected original quality, or null for an attribution-only change
   * @return permitted active or ended contribution targets
   */
  public List<MaterialAttributionTargetDto> attributionTargets(
      UUID orderId, UUID provisionId, @Nullable Integer suppliedQuality) {
    MaterialProvisionCredit credit = authorized(orderId, provisionId);
    JobOrder order =
        Entities.require(orders.findById(orderId), () -> "JobOrder not found: " + orderId);
    double available =
        fromQuanta(StockQuantityFragments.total(credits.availableForAttribution(credit)));
    int matchingQuality = suppliedQuality == null ? credit.getSuppliedQuality() : suppliedQuality;
    return history.read(orderId).stream()
        .filter(target ->
            (suppliedQuality != null || !target.id().equals(credit.getClaimId()))
                && target.materialId().equals(credit.getMaterialId())
                && target.minQuality() <= matchingQuality
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

  /**
   * Lists only editable current portions whose quantity is connected to the retained receipt.
   *
   * @param orderId the concrete readable order
   * @param provisionId the retained provision fact
   * @return current authorized rows with their exact known affected quantities
   */
  public List<InventoryQualityCorrectionOptionDto> qualityTargets(UUID orderId, UUID provisionId) {
    return stock.currentQualityRows(authorized(orderId, provisionId).getQuantityFragments());
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

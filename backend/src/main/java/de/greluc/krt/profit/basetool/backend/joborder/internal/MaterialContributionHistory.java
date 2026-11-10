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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderParticipation;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialProvisionCreditDto;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects retained contribution history with a bounded number of batched association queries. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialContributionHistory {
  private final JobOrderRepository orders;
  private final MaterialClaimRepository contributions;
  private final JobOrderParticipationRepository participations;
  private final MaterialProvisionCreditRepository provisions;
  private final MaterialRepository materials;
  private final QualityTierRepository tiers;
  private final OrgUnitRepository units;
  private final MaterialClaimAuthority authority;
  private final JobOrderResponsibilityGuard responsibility;

  /**
   * Reads active and ended contributions of a concretely readable order.
   *
   * @param orderId the order whose read permission the public caller enforces
   * @return contribution facts, including retained ending and correction history
   */
  public List<MaterialContributionHistoryDto> read(UUID orderId) {
    var order = Entities.require(orders.findById(orderId), () -> "JobOrder not found: " + orderId);
    List<MaterialClaim> history =
        contributions.findForOrderIncludingHistory(orderId, Pageable.unpaged());
    if (history.isEmpty()) {
      return List.of();
    }
    Map<UUID, Material> materialFacts =
        index(
            materials.findAllById(
                history.stream().map(c -> c.getMaterial().getId()).distinct().toList()),
            Material::getId);
    Map<UUID, QualityTier> tierFacts =
        index(
            tiers.findAllById(
                history.stream().map(c -> c.getQualityTier().getId()).distinct().toList()),
            QualityTier::getId);
    Map<UUID, OrgUnit> unitFacts =
        index(
            units.findAllById(
                history.stream().map(c -> c.getClaimingOrgUnit().getId()).distinct().toList()),
            OrgUnit::getId);
    Map<UUID, JobOrderParticipation> participationFacts =
        index(
            participations.findAllById(
                history.stream().map(c -> c.getParticipation().getId()).distinct().toList()),
            JobOrderParticipation::getId);
    Map<UUID, List<MaterialProvisionCredit>> credits =
        provisions.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
            .collect(Collectors.groupingBy(MaterialProvisionCredit::getClaimId));
    boolean valid = responsibility.isValid(order);
    return history.stream()
        .map(claim -> {
          Material material =
              Objects.requireNonNull(materialFacts.get(claim.getMaterial().getId()));
          QualityTier tier = Objects.requireNonNull(tierFacts.get(claim.getQualityTier().getId()));
          OrgUnit unit = Objects.requireNonNull(unitFacts.get(claim.getClaimingOrgUnit().getId()));
          JobOrderParticipation participation =
              Objects.requireNonNull(participationFacts.get(claim.getParticipation().getId()));
          boolean canCorrect = valid && authority.canManage(order, unit.getId());
          return new MaterialContributionHistoryDto(
              claim.getId(),
              claim.getVersion(),
              participation.getId(),
              participation.isActive(),
              new SquadronReferenceDto(unit.getId(), unit.getName(), unit.getShorthand()),
              material.getId(),
              material.getName(),
              material.getQuantityType().name(),
              tier.getCode(),
              tier.getMinQuality(),
              claim.getEndedAt() == null ? claim.getAmount() : 0.0,
              claim.getCreditedAmount(),
              claim.getEndedAt(),
              claim.getChanges(),
              credits.getOrDefault(claim.getId(), List.of()).stream()
                  .map(credit ->
                      new MaterialProvisionCreditDto(
                          credit.getId(),
                          credit.getVersion(),
                          credit.getAmount(),
                          credit.getQuantityAmount(),
                          credit.getCreatedAt(),
                          canCorrect,
                          credit.getEvidence()))
                  .toList());
        })
        .toList();
  }

  private static <T> Map<UUID, T> index(List<T> facts, Function<T, UUID> identity) {
    return facts.stream().collect(Collectors.toMap(identity, Function.identity()));
  }
}

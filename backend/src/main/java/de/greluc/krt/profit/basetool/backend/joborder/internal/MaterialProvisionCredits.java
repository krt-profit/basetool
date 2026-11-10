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

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialProvision;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.Quality;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Credits only explicitly selected genuine provision before final capacity reductions. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MaterialProvisionCredits {
  private final MaterialClaimRepository claims;
  private final MaterialClaimAuthority authority;
  private final MaterialProvisionCreditRepository credits;
  private final MaterialPromiseCoordinator coordinator;

  /**
   * Credits a suitable selected promise once from this action's genuinely new quantity budget.
   *
   * @param provision the Inventory-owned facts after its stock and access validation
   */
  public void record(MaterialProvision provision) {
    MaterialClaim claim =
        Entities.require(
            claims.findById(provision.selection().claimId()),
            () -> "Material claim not found: " + provision.selection().claimId());
    authority.assertCanRecordProvision(claim);
    if (!claim.getJobOrder().getId().equals(provision.orderId())
        || !claim.getMaterial().getId().equals(provision.materialId())
        || Quality.orMin(provision.quality()) < claim.getQualityTier().getMinQuality()) {
      throw new BadRequestException("orders.claim.provision.not_matching");
    }
    OptimisticLock.check(
        claim.getVersion(),
        provision.selection().claimVersion(),
        MaterialClaim.class,
        claim.getId());
    long quantum = claim.getMaterial().getQuantityType() == QuantityType.PIECE ? 1000L : 1L;
    long budget =
        Math.min(provision.budgetQuanta(), MaterialQuantityArithmetic.quanta(claim.getAmount()));
    budget = budget / quantum * quantum;
    if (budget == 0L || provision.quantity().isEmpty()) {
      return;
    }
    List<StockQuantityFragment> used =
        credits.processedQuantity(
            provision.quantity().stream().map(StockQuantityFragment::origin).distinct().toList());
    List<StockQuantityFragment> selected =
        takeUncredited(provision.quantity(), used, budget, quantum);
    long selectedQuanta =
        selected.stream().mapToLong(StockQuantityFragment::quanta).reduce(0L, Math::addExact);
    if (selectedQuanta == 0L) {
      return;
    }
    MaterialProvisionCredit credit = new MaterialProvisionCredit();
    credit.setOrderId(provision.orderId());
    credit.setClaimId(claim.getId());
    credit.setParticipationId(claim.getParticipation().getId());
    credit.setClaimingOrgUnitId(claim.getClaimingOrgUnit().getId());
    credit.setMaterialId(provision.materialId());
    credit.setQualityTierId(claim.getQualityTier().getId());
    credit.setSourceRowId(provision.rowId());
    credit.setAmount(selectedQuanta / 1000.0);
    credit.setFragments(
        selected.stream()
            .map(fragment ->
                new MaterialProvisionFragment(
                    fragment.origin(), fragment.fromQuanta(), fragment.toQuanta()))
            .toList());
    credits.save(credit);
    double previous = claim.getAmount();
    claim.setAmount(
        Math.subtractExact(MaterialQuantityArithmetic.quanta(previous), selectedQuanta) / 1000.0);
    claim.setCreditedAmount(
        MaterialQuantityArithmetic.add(
                MaterialQuantityArithmetic.quanta(claim.getCreditedAmount()), selectedQuanta)
            / 1000.0);
    coordinator.record(
        claim,
        MaterialContributionChange.Reason.PROVISION_CREDITED,
        previous,
        selectedQuanta / 1000.0);
  }

  private static List<StockQuantityFragment> takeUncredited(
      List<StockQuantityFragment> quantity,
      List<StockQuantityFragment> processed,
      long budget,
      long quantum) {
    Comparator<StockQuantityFragment> byOrigin =
        Comparator.comparing(StockQuantityFragment::origin)
            .thenComparingLong(StockQuantityFragment::fromQuanta);
    List<StockQuantityFragment> blocked = processed.stream().sorted(byOrigin).toList();
    List<StockQuantityFragment> result = new ArrayList<>();
    long remaining = budget;
    for (StockQuantityFragment candidate : quantity.stream().sorted(byOrigin).toList()) {
      long cursor = candidate.fromQuanta();
      for (StockQuantityFragment used : blocked) {
        if (!used.origin().equals(candidate.origin()) || used.toQuanta() <= cursor) {
          continue;
        }
        if (used.fromQuanta() >= candidate.toQuanta()) {
          break;
        }
        long take =
            Math.min(Math.max(0L, used.fromQuanta() - cursor), remaining) / quantum * quantum;
        if (take > 0L) {
          result.add(
              new StockQuantityFragment(
                  candidate.origin(), cursor, MaterialQuantityArithmetic.add(cursor, take)));
          remaining -= take;
        }
        cursor = Math.max(cursor, used.toQuanta());
        if (cursor >= candidate.toQuanta() || remaining == 0L) {
          break;
        }
      }
      long take =
          Math.min(Math.max(0L, candidate.toQuanta() - cursor), remaining) / quantum * quantum;
      if (take > 0L) {
        result.add(
            new StockQuantityFragment(
                candidate.origin(), cursor, MaterialQuantityArithmetic.add(cursor, take)));
        remaining -= take;
      }
      if (remaining == 0L) {
        break;
      }
    }
    return List.copyOf(result);
  }
}

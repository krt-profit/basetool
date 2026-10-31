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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage.BucketCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage.OrderCoverage;
import de.greluc.krt.profit.basetool.backend.notification.api.TimedNoticeProducer;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.events.SupplyNotices;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tells the lead and the Logisticians of a raising unit once when the job order linked to an open
 * need has order-linked stock covering the need's material and tier while the need is still open
 * (REQ-SUP-024). The marker is set by an atomic update that leaves the need's version alone, and
 * only the caller whose update changed the row publishes.
 */
@Component
@Order(5)
@RequiredArgsConstructor
public class SupplyOrderCoveredNoticeProducer implements TimedNoticeProducer {

  /** The rounding tolerance of the "covered" comparison, the one the need uses. */
  private static final double COVERED_TOLERANCE = 5e-4;

  private final SupplySettingsService settingsService;
  private final SupplyNeedRepository needRepository;
  private final JobOrderMaterialCoverage jobOrderMaterialCoverage;
  private final OrgUnitRepository orgUnitRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @NotNull
  public String kind() {
    return "supply_order_covered";
  }

  @Override
  public int produce(@NotNull Instant now, int limit) {
    if (!settingsService.isEnabled()) {
      return 0;
    }
    List<SupplyNeed> candidates = needRepository.findOrderCoverageCandidates(NeedStatus.OPEN);
    if (candidates.isEmpty()) {
      return 0;
    }
    Map<UUID, OrderCoverage> orders =
        jobOrderMaterialCoverage.coverageOf(
            candidates.stream().map(SupplyNeed::getJobOrderId).collect(Collectors.toSet()));
    Map<UUID, OrgUnit> units =
        orgUnitRepository
            .findAllById(
                candidates.stream().map(SupplyNeed::getOrgUnitId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
    int raised = 0;
    for (SupplyNeed need : candidates) {
      if (raised >= limit) {
        break;
      }
      OrderCoverage order = orders.get(need.getJobOrderId());
      OrgUnit unit = units.get(need.getOrgUnitId());
      if (order == null
          || unit == null
          || !covers(order, need.getMaterial(), need.getQualityTier())
          || needRepository.markOrderCoveredNotified(need.getId(), now) != 1) {
        continue;
      }
      eventPublisher.publishEvent(
          SupplyNotices.orderCovered(
              need.getId(),
              unit.getId(),
              unit.getKind(),
              unit.getName(),
              need.getMaterial() == null ? "—" : need.getMaterial().getName(),
              order.displayId() == null ? "—" : "#" + order.displayId()));
      raised++;
    }
    return raised;
  }

  /**
   * Tells whether the order's stock reaches its requirement for the need's bucket.
   *
   * @param order the linked order's coverage
   * @param material the need's material, or {@code null}
   * @param tier the need's tier, or {@code null}
   * @return {@code true} when the order requires the bucket and its stock reaches the requirement
   */
  static boolean covers(
      @NotNull OrderCoverage order, @Nullable Material material, @Nullable QualityTier tier) {
    if (material == null || tier == null) {
      return false;
    }
    BucketCoverage bucket = order.bucket(material.getId(), tier.getId());
    return bucket != null
        && bucket.requiredAmount() > 0
        && bucket.bookedAmount() >= bucket.requiredAmount() - COVERED_TOLERANCE;
  }
}

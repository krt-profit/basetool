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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityTypeRounding;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialRequirementResolver.MaterialRequirement;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService.OrderLinkedStockIndex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads how far the order-linked stock of job orders covers their material requirements, per {@code
 * (material, quality tier)} bucket, the same figures the material-demand overview shows
 * (REQ-ORDERS-034). Read-only; the caller decides who may see the result.
 */
@Service
@RequiredArgsConstructor
public class JobOrderMaterialCoverage {

  /** The statuses whose requirements are still being gathered. */
  private static final Set<JobOrderStatus> NON_TERMINAL_STATUSES =
      Set.of(JobOrderStatus.OPEN, JobOrderStatus.IN_PROGRESS);

  /** Loads the orders with both kinds' requirement branches. */
  private final JobOrderRepository jobOrderRepository;

  /** Normalises either order kind into its outstanding material requirement buckets. */
  private final JobOrderMaterialRequirementResolver materialRequirementResolver;

  /** Supplies the batched order-linked stock index. */
  private final JobOrderStockProjectionService jobOrderStockProjectionService;

  /**
   * Reads the coverage of the given orders in two queries for the whole set (REQ-DATA-003).
   *
   * @param jobOrderIds the orders; an unknown id is absent from the result
   * @return each existing order's coverage by id; a terminal order carries no buckets
   */
  @NotNull
  @Unmodifiable
  @Transactional(readOnly = true)
  public Map<UUID, OrderCoverage> coverageOf(@NotNull Collection<UUID> jobOrderIds) {
    if (jobOrderIds.isEmpty()) {
      return Map.of();
    }
    List<JobOrder> orders = jobOrderRepository.findAllWithMaterialRequirements(jobOrderIds);
    OrderLinkedStockIndex stockIndex =
        jobOrderStockProjectionService.loadOrderLinkedStockIndex(
            orders.stream()
                .filter(order -> NON_TERMINAL_STATUSES.contains(order.getStatus()))
                .map(JobOrder::getId)
                .toList());
    Map<UUID, OrderCoverage> result = new HashMap<>();
    for (JobOrder order : orders) {
      List<BucketCoverage> buckets =
          NON_TERMINAL_STATUSES.contains(order.getStatus())
              ? bucketsOf(order, stockIndex)
              : List.of();
      result.put(
          order.getId(),
          new OrderCoverage(order.getId(), order.getDisplayId(), order.getStatus(), buckets));
    }
    return Map.copyOf(result);
  }

  /**
   * Folds one order's requirements and attributed stock into buckets, merging lines of the same
   * material and tier.
   *
   * @param order the managed order
   * @param stockIndex the batched order-linked stock lookup
   * @return the order's buckets in first-appearance order
   */
  @NotNull
  private List<BucketCoverage> bucketsOf(
      @NotNull JobOrder order, @NotNull OrderLinkedStockIndex stockIndex) {
    List<MaterialRequirement> requirements = materialRequirementResolver.requirementsOf(order);
    double[] booked = stockIndex.bookedFor(order.getId(), requirements);
    Map<BucketKey, BucketSum> sums = new LinkedHashMap<>();
    for (int i = 0; i < requirements.size(); i++) {
      MaterialRequirement requirement = requirements.get(i);
      BucketSum sum =
          sums.computeIfAbsent(
              new BucketKey(requirement.material().id(), requirement.tier().id()),
              _ -> new BucketSum(requirement.material()));
      sum.required += requirement.requiredAmount();
      sum.booked += booked[i];
    }
    List<BucketCoverage> buckets = new ArrayList<>();
    sums.forEach(
        (key, sum) ->
            buckets.add(
                new BucketCoverage(
                    key.materialId(),
                    key.tierId(),
                    QuantityTypeRounding.roundForQuantityType(sum.required, sum.material),
                    QuantityTypeRounding.roundForQuantityType(sum.booked, sum.material))));
    return List.copyOf(buckets);
  }

  /**
   * The aggregation key of one bucket.
   *
   * @param materialId the material
   * @param tierId the quality tier
   */
  private record BucketKey(UUID materialId, UUID tierId) {}

  /** Running totals of one bucket while an order's lines are folded in. */
  private static final class BucketSum {

    /** The bucket's material, whose quantity type selects the rounding. */
    private final MaterialDto material;

    /** Summed outstanding requirement. */
    private double required;

    /** Summed attributed stock. */
    private double booked;

    /**
     * Starts a bucket.
     *
     * @param material the bucket's material
     */
    private BucketSum(@NotNull MaterialDto material) {
      this.material = material;
    }
  }

  /**
   * One order's coverage.
   *
   * @param id the order
   * @param displayId the order's number
   * @param status the order's status
   * @param buckets the order's buckets; empty for a terminal order
   */
  public record OrderCoverage(
      @NotNull UUID id,
      @Nullable Integer displayId,
      @NotNull JobOrderStatus status,
      @NotNull @Unmodifiable List<BucketCoverage> buckets) {

    /**
     * Finds the bucket of one material at one tier.
     *
     * @param materialId the material
     * @param tierId the quality tier
     * @return the bucket, or {@code null} when the order does not require it
     */
    @Nullable
    public BucketCoverage bucket(@NotNull UUID materialId, @NotNull UUID tierId) {
      for (BucketCoverage bucket : buckets) {
        if (bucket.materialId().equals(materialId) && bucket.tierId().equals(tierId)) {
          return bucket;
        }
      }
      return null;
    }
  }

  /**
   * One bucket's requirement and the order-linked stock attributed to it, rounded to the material's
   * quantity type.
   *
   * @param materialId the material
   * @param tierId the quality tier
   * @param requiredAmount the order's outstanding requirement
   * @param bookedAmount the order-linked stock at or above the tier
   */
  public record BucketCoverage(
      @NotNull UUID materialId, @NotNull UUID tierId, double requiredAmount, double bookedAmount) {}
}

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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderLinkedStockRows;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialRequirementResolver;
import de.greluc.krt.profit.basetool.backend.joborder.api.QualityBucketAllocator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/** Computes uncovered outstanding demand using the canonical shared quality allocation. */
@Service
@RequiredArgsConstructor
public class MaterialPromiseCapacity {
  private final JobOrderMaterialRequirementResolver requirements;
  private final OrderLinkedStockRows stock;

  /**
   * Reads demand and coverage of all orders in one stock query.
   *
   * @param orders the managed orders
   * @return exact capacity facts grouped by order and material-quality bucket
   */
  public Map<UUID, Map<Bucket, Capacity>> ofOrders(@NotNull Collection<JobOrder> orders) {
    List<JobOrderMaterialStockRow> rows =
        stock.materialStockOfOrders(orders.stream().map(JobOrder::getId).toList());
    Map<UUID, Map<Bucket, Capacity>> result = new LinkedHashMap<>();
    for (JobOrder order : orders) {
      Map<Bucket, Capacity> buckets = new LinkedHashMap<>();
      for (var requirement : requirements.requirementsOf(order)) {
        Bucket key = new Bucket(requirement.material().id(), requirement.tier().id());
        Capacity existing = buckets.get(key);
        long demand = MaterialQuantityArithmetic.quanta(requirement.requiredAmount());
        buckets.put(
            key,
            new Capacity(
                requirement.material(),
                requirement.tier(),
                MaterialQuantityArithmetic.add(
                    demand, existing == null ? 0L : existing.demandQuanta()),
                0L));
      }
      Map<UUID, List<QualityBucketAllocator.Demand<Bucket>>> demands = new LinkedHashMap<>();
      buckets.forEach((key, value) ->
          demands
              .computeIfAbsent(key.materialId(), _ -> new ArrayList<>())
              .add(
                  new QualityBucketAllocator.Demand<>(
                      key, value.tier().minQuality(), value.demandQuanta() / 1000.0)));
      demands.forEach((materialId, wanted) -> {
        List<QualityBucketAllocator.Supply> supplied =
            rows.stream()
                .filter(row ->
                    row.jobOrderId().equals(order.getId()) && row.materialId().equals(materialId))
                .map(row ->
                    new QualityBucketAllocator.Supply(
                        row.inventoryItemId(), row.quality(), row.amount()))
                .toList();
        var allocation = QualityBucketAllocator.allocate(wanted, supplied);
        for (var demand : wanted) {
          Capacity value = Objects.requireNonNull(buckets.get(demand.key()));
          buckets.put(
              demand.key(),
              new Capacity(
                  value.material(),
                  value.tier(),
                  value.demandQuanta(),
                  MaterialQuantityArithmetic.quanta(allocation.attributedTo(demand.key()))));
        }
      });
      result.put(order.getId(), Map.copyOf(buckets));
    }
    return Map.copyOf(result);
  }

  /**
   * Reads one managed order's complete material capacities.
   *
   * @param order the order
   * @return every existing requirement bucket
   */
  public Map<Bucket, Capacity> ofOrder(JobOrder order) {
    return Objects.requireNonNull(ofOrders(List.of(order)).get(order.getId()));
  }

  /**
   * The material-quality scope of one outstanding requirement.
   *
   * @param materialId the material
   * @param tierId the quality tier
   */
  public record Bucket(UUID materialId, UUID tierId) {}

  /**
   * Exact current demand and separately projected coverage in thousandths of a unit.
   *
   * @param material the material and its quantity type
   * @param tier the quality floor
   * @param demandQuanta remaining demand
   * @param stockQuanta matching earmarked stock
   */
  public record Capacity(
      MaterialDto material, QualityTierDto tier, long demandQuanta, long stockQuanta) {
    /**
     * Returns uncovered demand, floored to a valid material quantum.
     *
     * @return nonnegative capacity in thousandths of a unit
     */
    public long availableQuanta() {
      long quantum = "PIECE".equals(material.quantityType()) ? 1000L : 1L;
      return Math.max(0L, demandQuanta - stockQuanta) / quantum * quantum;
    }
  }
}

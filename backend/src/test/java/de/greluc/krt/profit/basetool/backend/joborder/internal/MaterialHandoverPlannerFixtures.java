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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialHandoverFact;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

/** Scalar database projections for handover service fixtures without a database. */
final class MaterialHandoverPlannerFixtures {
  private MaterialHandoverPlannerFixtures() {}

  static MaterialHandoverPlanner planner(
      @NotNull Supplier<JobOrder> order,
      @NotNull InventoryItemRepository rows,
      @NotNull StockCommands commands,
      @NotNull JobOrderRepository orders,
      @NotNull JobOrderResponsibilityGuard responsibility,
      @NotNull MaterialPromiseCoordinator promises) {
    JobOrderMaterialRepository demands = mock(JobOrderMaterialRepository.class);
    StockReads stock = mock(StockReads.class);
    lenient()
        .when(demands.handoverRequirements(any()))
        .thenAnswer(_ ->
            order.get().getMaterials().stream()
                .map(line ->
                    new MaterialHandoverRequirement(
                        line.getId(),
                        line.getVersion() == null ? 0L : line.getVersion(),
                        line.getMaterial().getId(),
                        line.getQualityTier().getId(),
                        line.getQualityTier().getCode(),
                        line.getQualityTier().getMinQuality(),
                        line.getAmount()))
                .sorted(Comparator.comparing(MaterialHandoverRequirement::id))
                .toList());
    lenient()
        .when(stock.materialHandoverFacts(any(), anyCollection()))
        .thenAnswer(invocation -> {
          UUID orderId = invocation.getArgument(0);
          Collection<UUID> ids = invocation.getArgument(1);
          return ids.stream()
              .map(rows::findByIdForUpdate)
              .flatMap(java.util.Optional::stream)
              .map(row ->
                  new MaterialHandoverFact(
                      row.getId(),
                      row.getVersion() == null ? 0L : row.getVersion(),
                      row.getMaterial() == null ? null : row.getMaterial().getId(),
                      row.getMaterial() == null ? null : row.getMaterial().getQuantityType(),
                      row.getQuality(),
                      row.getAmount(),
                      row.isEarmarkedForJobOrder(orderId)
                          ? row.jobOrderSliceAmount(orderId)
                          : null))
              .toList();
        });
    return new MaterialHandoverPlanner(
        demands, stock, commands, orders, mock(EntityManager.class), responsibility, promises);
  }
}

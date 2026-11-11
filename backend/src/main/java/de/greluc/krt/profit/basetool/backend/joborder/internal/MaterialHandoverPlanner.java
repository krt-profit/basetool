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
import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialHandoverFact;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.Quality;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Plans and stabilizes the complete consumption, fulfillment-release and queue resource set. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MaterialHandoverPlanner {
  private final JobOrderMaterialRepository requirements;
  private final StockReads stockReads;
  private final StockCommands stockCommands;
  private final JobOrderRepository orders;
  private final EntityManager entityManager;
  private final JobOrderResponsibilityGuard responsibilityGuard;
  private final MaterialPromiseCoordinator materialPromises;

  /**
   * Validates immutable input facts, locks their full effect set, and refuses any intervening
   * drift.
   *
   * @param order the initially authorized managed order
   * @param items the deliberate handover entries in request order
   * @param scopes all known demand, history and shared allocation scopes
   * @return the single booking calculation whose facts remain current under the complete locks
   * @throws BadRequestException when a selected source or requirement cannot fulfill the input
   * @throws BusinessConflictException when demand, stock or release resources changed while waiting
   */
  @NotNull
  MaterialHandoverCalculation.Booked prepare(
      @NotNull JobOrder order,
      @NotNull List<JobOrderHandoverItemCreateDto> items,
      @NotNull List<OrderMaterialScope> scopes) {
    UUID orderId = order.getId();
    final JobOrderFulfillmentContext root = JobOrderFulfillmentContext.of(order);
    List<MaterialHandoverRequirement> lines = requirements.handoverRequirements(orderId);
    Set<UUID> selected =
        items.stream()
            .map(JobOrderHandoverItemCreateDto::inventoryItemId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<UUID, MaterialHandoverFact> facts = facts(orderId, selected);
    MaterialHandoverCalculation.Booked booked = calculate(lines, facts, items);
    Set<UUID> release = releaseRows(orderId, booked);
    Set<UUID> rows = new LinkedHashSet<>(selected);
    rows.addAll(release);
    stockCommands.prepareWrites(rows, List.of(), scopes);
    if (booked.allFulfilled()) {
      orders.lockAllJobOrders();
    } else {
      orders.lockForScopedEdit(orderId);
    }
    entityManager.refresh(order);
    responsibilityGuard.assertWritable(order);
    if (!root.equals(JobOrderFulfillmentContext.of(order))
        || !lines.equals(requirements.handoverRequirements(orderId))
        || !facts.equals(facts(orderId, selected))
        || !release.equals(releaseRows(orderId, booked))
        || !Set.copyOf(scopes)
            .equals(Set.copyOf(materialPromises.materialScopesOfOrder(orderId)))) {
      throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
    }
    return booked;
  }

  private Set<UUID> releaseRows(
      @NotNull UUID orderId, @NotNull MaterialHandoverCalculation.Booked booked) {
    return Set.copyOf(
        booked.allFulfilled()
            ? stockReads.earmarkedRowIds(orderId)
            : stockReads.earmarkedMaterialRowIds(orderId, booked.fulfilledMaterials()));
  }

  private Map<UUID, MaterialHandoverFact> facts(
      @NotNull UUID orderId, @NotNull Set<UUID> selected) {
    return stockReads.materialHandoverFacts(orderId, selected).stream()
        .collect(Collectors.toMap(MaterialHandoverFact::rowId, Function.identity()));
  }

  private static MaterialHandoverCalculation.Booked calculate(
      @NotNull List<MaterialHandoverRequirement> lines,
      @NotNull Map<UUID, MaterialHandoverFact> facts,
      @NotNull List<JobOrderHandoverItemCreateDto> items) {
    List<MaterialHandoverCalculation.Input> inputs = new ArrayList<>();
    Map<UUID, Double> requested = new LinkedHashMap<>();
    for (JobOrderHandoverItemCreateDto item : items) {
      MaterialHandoverFact fact =
          Entities.require(
              java.util.Optional.ofNullable(facts.get(item.inventoryItemId())),
              "Inventory item not found");
      if (fact.orderAllocatedAmount() == null) {
        throw new BadRequestException(JobOrderStockErrors.ITEM_NOT_LINKED_TO_ORDER);
      }
      if (fact.materialId() == null) {
        throw new BadRequestException(JobOrderStockErrors.MATERIAL_ROW_REQUIRED);
      }
      if (item.amount() == null || !Double.isFinite(item.amount()) || item.amount() <= 0) {
        throw new BadRequestException(JobOrderStockErrors.POSITIVE_AMOUNT_REQUIRED);
      }
      double total = requested.merge(fact.rowId(), item.amount(), Double::sum);
      if (total > fact.amount() + MaterialHandoverCalculation.EPSILON) {
        throw new BadRequestException(JobOrderStockErrors.INSUFFICIENT_STOCK);
      }
      if (total > fact.orderAllocatedAmount() + MaterialHandoverCalculation.EPSILON) {
        throw new BadRequestException(JobOrderStockErrors.INSUFFICIENT_ALLOCATION);
      }
      if (fact.quantityType() == QuantityType.PIECE && item.amount() % 1 != 0) {
        throw new BadRequestException(JobOrderStockErrors.WHOLE_PIECE_REQUIRED);
      }
      inputs.add(
          new MaterialHandoverCalculation.Input(
              fact.rowId(),
              fact.materialId(),
              Quality.orMin(fact.quality()),
              item.amount(),
              item.qualityRequirement()));
    }
    return switch (MaterialHandoverCalculation.calculate(lines, inputs)) {
      case MaterialHandoverCalculation.Booked booked -> booked;
      case MaterialHandoverCalculation.Refused refused ->
          throw new BadRequestException(
              switch (refused.reason()) {
                case UNKNOWN_TIER -> JobOrderStockErrors.QUALITY_TIER_NOT_ON_ORDER;
                case QUALITY_BELOW_FLOOR -> JobOrderStockErrors.QUALITY_BELOW_FLOOR;
              });
    };
  }
}

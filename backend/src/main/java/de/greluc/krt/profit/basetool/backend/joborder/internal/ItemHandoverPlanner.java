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

import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import jakarta.persistence.EntityManager;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stabilizes complete item consumption, terminal release and queue effects before any mutation. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ItemHandoverPlanner {
  private final JobOrderItemRepository requirements;
  private final StockReads stock;
  private final StockCommands commands;
  private final JobOrderRepository orders;
  private final EntityManager entityManager;
  private final JobOrderResponsibilityGuard responsibility;
  private final MaterialPromiseCoordinator promises;

  /** The exact deliberate entries, delivery counters and physical consumption of one handover. */
  record Booked(
      List<JobOrderItemHandoverEntryCreateDto> entries,
      Map<UUID, Integer> delivered,
      Map<UUID, Double> handedByGameItem,
      boolean allDelivered) {
    Booked {
      entries = List.copyOf(entries);
      delivered = Map.copyOf(delivered);
      handedByGameItem = Collections.unmodifiableMap(new LinkedHashMap<>(handedByGameItem));
    }
  }

  /**
   * Plans and revalidates every material, physical and completion effect of an item handover.
   *
   * @param order the initially authorized managed ITEM order
   * @param entries the deliberate requested item deliveries
   * @param scopes all current demand, history and shared material resources
   * @return the exact stable delivery calculation
   * @throws BadRequestException when an entry is foreign or exceeds manufactured availability
   * @throws BusinessConflictException when a prepared current fact changes while waiting
   */
  @NotNull
  Booked prepare(
      @NotNull JobOrder order,
      @NotNull List<JobOrderItemHandoverEntryCreateDto> entries,
      @NotNull List<OrderMaterialScope> scopes) {
    UUID orderId = order.getId();
    final JobOrderFulfillmentContext context = JobOrderFulfillmentContext.of(order);
    List<ItemHandoverRequirement> lines = requirements.handoverRequirements(orderId);
    Set<ItemHandoverRequirement> observed = Set.copyOf(lines);
    if (!observed.equals(managedFacts(order))) {
      throw changed();
    }
    Booked booked = calculate(lines, List.copyOf(entries));
    Set<UUID> consumption =
        Set.copyOf(stock.earmarkedItemConsumptionRowIds(orderId, booked.handedByGameItem()));
    Set<UUID> released =
        booked.allDelivered() ? Set.copyOf(stock.earmarkedRowIds(orderId)) : Set.of();
    Set<UUID> rows = new LinkedHashSet<>(consumption);
    rows.addAll(released);
    commands.prepareWrites(rows, List.of(), scopes);
    if (booked.allDelivered()) {
      orders.lockAllJobOrders();
    }
    entityManager.refresh(order);
    responsibility.assertWritable(order);
    if (!context.equals(JobOrderFulfillmentContext.of(order))
        || !lines.equals(requirements.handoverRequirements(orderId))
        || !observed.equals(managedFacts(order))
        || !consumption.equals(
            Set.copyOf(stock.earmarkedItemConsumptionRowIds(orderId, booked.handedByGameItem())))
        || (booked.allDelivered() && !released.equals(Set.copyOf(stock.earmarkedRowIds(orderId))))
        || !Set.copyOf(scopes).equals(Set.copyOf(promises.materialScopesOfOrder(orderId)))) {
      throw changed();
    }
    return booked;
  }

  private static Set<ItemHandoverRequirement> managedFacts(@NotNull JobOrder order) {
    return order.getItems().stream()
        .map(line ->
            new ItemHandoverRequirement(
                line.getId(),
                Objects.requireNonNull(line.getVersion()),
                line.getGameItem() == null ? null : line.getGameItem().getId(),
                line.getAmount(),
                line.getManufacturedAmount(),
                line.getDeliveredAmount()))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Booked calculate(
      @NotNull List<ItemHandoverRequirement> lines,
      @NotNull List<JobOrderItemHandoverEntryCreateDto> entries) {
    Map<UUID, ItemHandoverRequirement> byId =
        lines.stream().collect(Collectors.toMap(ItemHandoverRequirement::id, Function.identity()));
    Map<UUID, Integer> delivered = new LinkedHashMap<>();
    Map<UUID, Long> handed = new LinkedHashMap<>();
    for (JobOrderItemHandoverEntryCreateDto entry : entries) {
      ItemHandoverRequirement line = byId.get(entry.jobOrderItemId());
      if (line == null) {
        throw new BadRequestException(JobOrderStockErrors.FOREIGN_ITEM_LINE);
      }
      long total =
          Math.addExact((long) delivered.getOrDefault(line.id(), line.delivered()), entry.amount());
      if (entry.amount() <= 0 || total > line.manufactured()) {
        throw new BadRequestException(JobOrderStockErrors.INSUFFICIENT_MANUFACTURED_ITEMS);
      }
      delivered.put(line.id(), Math.toIntExact(total));
      if (line.gameItemId() != null) {
        handed.merge(line.gameItemId(), (long) entry.amount(), Math::addExact);
      }
    }
    Map<UUID, Double> amounts = new LinkedHashMap<>();
    handed.forEach((item, amount) -> {
      double represented = amount.doubleValue();
      if (java.math.BigDecimal.valueOf(represented).longValueExact() != amount) {
        throw new IllegalArgumentException(
            "The item handover quantity is not exactly representable");
      }
      amounts.put(item, represented);
    });
    boolean complete =
        lines.stream()
            .allMatch(line -> delivered.getOrDefault(line.id(), line.delivered()) >= line.amount());
    return new Booked(entries, delivered, amounts, complete);
  }

  private static BusinessConflictException changed() {
    return new BusinessConflictException("error.inventory.concurrent_scope_changed");
  }
}

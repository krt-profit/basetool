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

import de.greluc.krt.profit.basetool.backend.inventory.api.OrderLinkedStockWriteObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.AppExceptionKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces responsibility validity for Inventory-owned stock operations in one batch; a game-fact
 * stock loss takes the same locks without the validity check (REQ-ORDERS-055, ADR-0382).
 */
@Service
@RequiredArgsConstructor
public class JobOrderStockWriteEligibility implements OrderLinkedStockWriteObserver {

  private final JobOrderRepository orders;
  private final EntityManager entityManager;
  private final MaterialPromiseCoordinator materialPromises;
  private final LiveSyncPublisher live;
  private final MaterialProvisionCredits provisionCredits;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderResponsibilityGuard responsibilityGuard;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void beforeOrderLinkedStockWrite(@NotNull Collection<UUID> orderIds) {
    List<JobOrder> affected = stabilize(orderIds);
    if (responsibilityGuard.validOrderIds(affected).size() != affected.size()) {
      throw new BadRequestException("orders.responsibility.invalid");
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void beforeGameFactStockLoss(@NotNull Collection<UUID> orderIds) {
    stabilize(orderIds);
  }

  /**
   * Takes the shared hierarchy lock and loads the affected orders.
   *
   * @param orderIds the affected orders
   * @return the orders, one per distinct id
   * @throws NotFoundException when an order does not exist
   */
  private @NotNull List<JobOrder> stabilize(@NotNull Collection<UUID> orderIds) {
    profitHierarchy.lockForOrderWrite();
    if (orderIds.isEmpty()) {
      return List.of();
    }
    Set<UUID> ids = Set.copyOf(orderIds);
    List<JobOrder> affected = orders.findAllById(ids);
    if (affected.size() != ids.size()) {
      throw new NotFoundException(AppExceptionKind.NOT_FOUND.detailKey());
    }
    return affected;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void beforeOrderLinkedMaterialWrite(
      Collection<de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope> scopes) {
    materialPromises.lock(scopes);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockOrderEarmarkTargets(@NotNull Collection<UUID> orderIds) {
    var locked = orders.lockForOrderEarmarks(orderIds);
    orders.findAllById(locked).forEach(entityManager::refresh);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void assertOrderLinkedMaterialWrite(
      Collection<de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope> scopes) {
    beforeOrderLinkedStockWrite(
        scopes.stream()
            .map(de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope::orderId)
            .toList());
    materialPromises.requireLocked(scopes);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void assertGameFactMaterialLoss(@NotNull Collection<OrderMaterialScope> scopes) {
    beforeGameFactStockLoss(scopes.stream().map(OrderMaterialScope::orderId).toList());
    materialPromises.requireLocked(scopes);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void orderLinkedStockChanged(
      @NotNull Collection<UUID> orderIds, @NotNull Collection<OrderMaterialScope> materialScopes) {
    materialPromises.requireLocked(materialScopes);
    if (orderIds.isEmpty()) {
      return;
    }
    orderIds.stream()
        .distinct()
        .forEach(order ->
            live.publishAfterCommit(
                LiveSyncTopic.of(LiveSyncTopicClass.ORDER, order),
                List.of(
                    "header",
                    "actions",
                    "dialogs",
                    "kpi",
                    "materials",
                    "aggregated",
                    "items",
                    "item-stock",
                    "handovers",
                    "item-handovers",
                    "item-handover-lines")));
    live.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue", "demand"));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordMaterialProvision(
      de.greluc.krt.profit.basetool.backend.inventory.api.MaterialProvision provision) {
    assertOrderLinkedMaterialWrite(
        List.of(
            new de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope(
                provision.orderId(), provision.materialId())));
    provisionCredits.record(provision);
  }
}

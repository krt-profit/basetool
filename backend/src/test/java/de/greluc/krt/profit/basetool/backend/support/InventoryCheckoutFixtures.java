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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockSoldForTarget;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryBulkRebookService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryCheckoutService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryEarmarkLookup;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryMergeService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryTransferNoticeService;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import org.springframework.context.ApplicationEventPublisher;

/** Constructs the real inventory checkout graph around a test's explicit collaborators. */
public final class InventoryCheckoutFixtures {
  private InventoryCheckoutFixtures() {}

  /** Builds checkout, bulk, merge and notification services over the supplied test graph. */
  public static InventoryCheckoutService create(
      InventoryItemRepository inventoryItemRepository,
      UserRepository userRepository,
      LocationRepository locationRepository,
      StockSoldForTarget stockSoldForTarget,
      InventoryEarmarkLookup earmarkTargets,
      StockOfferLookup stockOfferLookup,
      StockChangeObserver stockChangeObserver,
      InventoryItemMapper inventoryItemMapper,
      RequestScopeResolver requestScopeResolver,
      OrgUnitStampingService orgUnitStampingService,
      AuditRecorder auditRecorder,
      ApplicationEventPublisher eventPublisher) {
    var mergeService =
        new InventoryMergeService(inventoryItemRepository, stockOfferLookup, auditRecorder);
    var transferNotices = new InventoryTransferNoticeService(userRepository, eventPublisher);
    var bulkService =
        new InventoryBulkRebookService(
            inventoryItemRepository,
            userRepository,
            locationRepository,
            stockChangeObserver,
            orgUnitStampingService,
            auditRecorder,
            mergeService,
            transferNotices);
    return new InventoryCheckoutService(
        inventoryItemRepository,
        userRepository,
        locationRepository,
        stockSoldForTarget,
        earmarkTargets,
        stockChangeObserver,
        inventoryItemMapper,
        requestScopeResolver,
        orgUnitStampingService,
        auditRecorder,
        eventPublisher,
        mergeService,
        bulkService,
        transferNotices);
  }
}

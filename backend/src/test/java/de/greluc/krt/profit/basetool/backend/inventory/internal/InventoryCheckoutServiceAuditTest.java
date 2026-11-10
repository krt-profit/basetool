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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import de.greluc.krt.profit.basetool.backend.support.InventoryCheckoutFixtures;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Audit tests (REQ-AUDIT-001) for the delivered-flag toggle ({@link
 * InventoryCheckoutService#updateDelivered}) and the admin global wipe ({@link
 * InventoryCheckoutService#deleteAllGlobalInventory}) of {@link InventoryCheckoutService}, pinning
 * the exact audit events each records.
 */
@ExtendWith(MockitoExtension.class)
class InventoryCheckoutServiceAuditTest {

  @org.junit.jupiter.api.BeforeEach
  void wireCheckoutGraph() {
    service =
        InventoryCheckoutFixtures.create(
            inventoryItemRepository,
            userRepository,
            locationRepository,
            null,
            earmarkTargets,
            null,
            offerRatchet,
            inventoryItemMapper,
            requestScopeResolver,
            null,
            auditService,
            eventPublisher);
  }

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private StockChangeObserver offerRatchet;
  @Mock private AuditService auditService;

  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private InventoryEarmarkLookup earmarkTargets;
  private InventoryCheckoutService service;

  @Test
  void updateDelivered_recordsDeliveryToggledAudit() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    UUID orderId = UUID.randomUUID();
    User owner = new User();
    owner.setId(ownerId);

    JobOrder order = new JobOrder();
    order.setId(orderId);
    order.setDisplayId(42);
    when(earmarkTargets.jobOrder(order.getId())).thenReturn(new JobOrderEarmark(order.getId(), 42));

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(owner);
    item.setAmount(5.0);
    InventoryJobOrderAllocation slice =
        InventoryAllocations.addJobOrder(item, order.getId(), 5.0, false);

    UpdateDeliveredRequest request = new UpdateDeliveredRequest(true, orderId, 1L);

    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryItemMapper.toDto(item))
        .thenReturn(
            new de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto(
                itemId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                java.util.List.of(),
                0.0,
                java.util.List.of(),
                0.0,
                null,
                null,
                1L,
                null,
                null));

    service.updateDelivered(itemId, request, ownerId, false);

    assertTrue(slice.getDelivered(), "the order's slice is flipped to delivered");
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_ITEM_DELIVERY_TOGGLED),
            eq(itemId),
            any(),
            eq(ownerId),
            any());
  }

  @Test
  void deleteAllGlobalInventory_locksAuditsAndDeletesTheSameRowsAndRecordsWipedAudit() {
    ScopePredicate scope = new ScopePredicate(true, null, Set.of());
    List<UUID> locked = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(requestScopeResolver.currentScopePredicate()).thenReturn(scope);
    when(inventoryItemRepository.lockNonPersonalIdsInScope(true, null, Set.of()))
        .thenReturn(locked);
    when(inventoryItemRepository.deleteAllByIdIn(locked)).thenReturn(42);

    int removed = service.deleteAllGlobalInventory();

    assertEquals(42, removed);
    InOrder order = inOrder(inventoryItemRepository, offerRatchet);
    order.verify(inventoryItemRepository).lockNonPersonalIdsInScope(true, null, Set.of());
    order.verify(offerRatchet).beforeDelete(locked, StockChangeReason.WIPE);
    order.verify(inventoryItemRepository).deleteAllByIdIn(locked);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_WIPED), isNull(), isNull(), isNull(), details.capture());
    String rendered = details.getValue().toString();
    assertTrue(rendered.contains("removed=42"), "audit details carry the removed count");
    assertTrue(rendered.contains("scope=adminAll"), "audit details carry the wipe scope");
  }

  @Test
  void bookOut_gameItemRow_recordsConsumedAuditWithGameItemNameLabelAndDetail() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    User owner = new User();
    owner.setId(ownerId);
    Location location = new Location();
    location.setId(UUID.randomUUID());
    location.setName("ARC-L1");
    GameItem gameItem = new GameItem();
    gameItem.setId(UUID.randomUUID());
    gameItem.setName("Quantum Drive");

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(owner);
    item.setLocation(location);
    item.setGameItem(gameItem);
    item.setAmount(3.0);
    item.setPersonal(false);
    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));

    service.bookOutInventoryItem(
        itemId,
        new InventoryItemBookOutDto(
            3.0, null, null, CheckoutType.DISCARD, null, null, 1L, null, null, null, null),
        ownerId,
        false);

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_ITEM_CONSUMED),
            eq(itemId),
            eq("Quantum Drive @ ARC-L1"),
            eq(ownerId),
            details.capture());
    String rendered = details.getValue().toString();
    assertTrue(
        rendered.contains("material=Quantum Drive"),
        "the catalog-name detail falls back to the game-item name for item rows");
    assertTrue(rendered.contains("depleted=true"), "the depleted marker is carried");
  }
}

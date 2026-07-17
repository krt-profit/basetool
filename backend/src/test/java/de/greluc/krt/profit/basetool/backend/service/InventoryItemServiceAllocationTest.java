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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.exception.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationDimension;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Pure-Mockito unit tests for the Variante-C per-allocation write methods of {@link
 * InventoryItemService} (REQ-INV-027): {@code addAllocation} / {@code changeAllocation} / {@code
 * removeAllocation}. Every guard is pinned — the over-allocation 422 (rule R5), the
 * personal-no-assignment reject, the material-required gate, the duplicate-target reject, the
 * whole-number-for-PIECE reject, the optimistic-lock echo, and the not-found paths — because each
 * is a silent regression at the type level.
 *
 * <p>The three write methods live on the facade and use the injected mocks directly (they never
 * touch the aggregation / checkout sub-services), so no delegate wiring is needed.
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemServiceAllocationTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private JobOrderItemService jobOrderItemService;
  @Mock private AuditService auditService;
  @Mock private InventoryAggregationService inventoryAggregationService;
  @Mock private InventoryCheckoutService inventoryCheckoutService;

  @InjectMocks private InventoryItemService service;

  private final UUID itemId = UUID.randomUUID();
  private final UUID materialId = UUID.randomUUID();

  private Material scuMaterial() {
    Material m = new Material();
    m.setId(materialId);
    m.setName("Aslarite");
    m.setQuantityType(QuantityType.SCU);
    return m;
  }

  private InventoryItem entry(double amount) {
    User user = new User();
    user.setId(UUID.randomUUID());
    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setAmount(amount);
    item.setPersonal(false);
    item.setUser(user);
    item.setMaterial(scuMaterial());
    item.setJobOrderAllocations(new ArrayList<>());
    item.setMissionAllocations(new ArrayList<>());
    return item;
  }

  private JobOrder jobOrder(UUID id, int displayId) {
    JobOrder jo = new JobOrder();
    jo.setId(id);
    jo.setDisplayId(displayId);
    return jo;
  }

  private Mission mission(UUID id, String name) {
    Mission m = new Mission();
    m.setId(id);
    m.setName(name);
    return m;
  }

  private void addJobOrderSlice(InventoryItem item, JobOrder order, double amount) {
    InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
    slice.setInventoryItem(item);
    slice.setJobOrder(order);
    slice.setAmount(amount);
    item.getJobOrderAllocations().add(slice);
  }

  private InventoryItemDto sentinelDto() {
    return new InventoryItemDto(
        itemId, null, null, null, null, 750, 10.0, false, List.of(), 0.0, List.of(), 0.0, null,
        null, 2L, null);
  }

  // --- add ---------------------------------------------------------------------------------

  @Test
  void addAllocation_jobOrder_addsSliceAndAudits() {
    InventoryItem item = entry(10.0);
    UUID orderId = UUID.randomUUID();
    JobOrder order = jobOrder(orderId, 42);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(jobOrderItemService.requiredMaterialIds(order)).thenReturn(Set.of(materialId));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    InventoryItemDto dtoOut = sentinelDto();
    when(inventoryItemMapper.toDto(item)).thenReturn(dtoOut);

    InventoryItemDto result =
        service.addAllocation(
            itemId,
            new InventoryAllocationWriteDto(
                InventoryAllocationDimension.JOB_ORDER, orderId, 4.0, 1L));

    // The response carries the mapped DTO with the post-commit force-increment version: the entry
    // @Version (1) is bumped at commit by OPTIMISTIC_FORCE_INCREMENT, so the client must echo 2 on
    // its next write to the same entry, else a second consecutive chip edit 409s (REQ-INV-027).
    assertEquals(2L, result.version());
    assertEquals(1, item.getJobOrderAllocations().size());
    assertEquals(4.0, item.getJobOrderAllocations().get(0).getAmount(), 1e-9);
    assertSame(order, item.getJobOrderAllocations().get(0).getJobOrder());
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_ALLOCATION_ADDED),
            eq(itemId),
            any(),
            eq(item.getUser().getId()),
            any());
  }

  @Test
  void addAllocation_mission_addsSlice() {
    InventoryItem item = entry(10.0);
    UUID missionId = UUID.randomUUID();
    Mission m = mission(missionId, "Op Nightfall");
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(m));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryItemMapper.toDto(item)).thenReturn(sentinelDto());

    service.addAllocation(
        itemId,
        new InventoryAllocationWriteDto(InventoryAllocationDimension.MISSION, missionId, 6.0, 1L));

    assertEquals(1, item.getMissionAllocations().size());
    assertEquals(6.0, item.getMissionAllocations().get(0).getAmount(), 1e-9);
  }

  @Test
  void addAllocation_overAllocating_throws422() {
    InventoryItem item = entry(10.0);
    UUID orderA = UUID.randomUUID();
    UUID orderB = UUID.randomUUID();
    addJobOrderSlice(item, jobOrder(orderA, 1), 8.0);
    JobOrder b = jobOrder(orderB, 2);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(jobOrderRepository.findById(orderB)).thenReturn(Optional.of(b));
    when(jobOrderItemService.requiredMaterialIds(b)).thenReturn(Set.of(materialId));

    assertThrows(
        OverAllocationException.class,
        () ->
            service.addAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, orderB, 5.0, 1L)));
    verify(inventoryItemRepository, never()).saveAndFlush(any());
  }

  @Test
  void addAllocation_fullyAllocating_isAllowed() {
    InventoryItem item = entry(10.0);
    UUID orderA = UUID.randomUUID();
    UUID orderB = UUID.randomUUID();
    addJobOrderSlice(item, jobOrder(orderA, 1), 6.0);
    JobOrder b = jobOrder(orderB, 2);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(jobOrderRepository.findById(orderB)).thenReturn(Optional.of(b));
    when(jobOrderItemService.requiredMaterialIds(b)).thenReturn(Set.of(materialId));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryItemMapper.toDto(item)).thenReturn(sentinelDto());

    // 6 + 4 == 10 exactly — must NOT trip the over-allocation guard.
    service.addAllocation(
        itemId,
        new InventoryAllocationWriteDto(InventoryAllocationDimension.JOB_ORDER, orderB, 4.0, 1L));

    assertEquals(2, item.getJobOrderAllocations().size());
  }

  @Test
  void addAllocation_personalEntry_throwsBadRequest() {
    InventoryItem item = entry(10.0);
    item.setPersonal(true);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    assertThrows(
        BadRequestException.class,
        () ->
            service.addAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 4.0, 1L)));
    verify(inventoryItemRepository, never()).saveAndFlush(any());
  }

  @Test
  void addAllocation_materialNotRequiredByOrder_throwsBadRequest() {
    InventoryItem item = entry(10.0);
    UUID orderId = UUID.randomUUID();
    JobOrder order = jobOrder(orderId, 7);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(jobOrderItemService.requiredMaterialIds(order)).thenReturn(Set.of(UUID.randomUUID()));

    assertThrows(
        BadRequestException.class,
        () ->
            service.addAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, orderId, 4.0, 1L)));
  }

  @Test
  void addAllocation_duplicateTarget_throwsBadRequest() {
    InventoryItem item = entry(10.0);
    UUID orderId = UUID.randomUUID();
    JobOrder order = jobOrder(orderId, 9);
    addJobOrderSlice(item, order, 3.0);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(jobOrderItemService.requiredMaterialIds(order)).thenReturn(Set.of(materialId));

    assertThrows(
        BadRequestException.class,
        () ->
            service.addAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, orderId, 2.0, 1L)));
  }

  @Test
  void addAllocation_fractionalPieceAmount_throwsBadRequest() {
    InventoryItem item = entry(10.0);
    item.getMaterial().setQuantityType(QuantityType.PIECE);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service.addAllocation(
                    itemId,
                    new InventoryAllocationWriteDto(
                        InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 1.5, 1L)));
    assertEquals("A PIECE allocation amount must be a whole number", ex.getMessage());
  }

  // --- game-item rows (V220, REQ-INV-029/031) --------------------------------

  /**
   * Builds a managed-like game-item stock row sharing the fixture entry shape: gameItem set,
   * material and quality {@code null} (the V220 catalog XOR), non-personal, version 1.
   *
   * @param amount the row's amount.
   * @param gameItemId the stocked game item's id.
   * @return the assembled item row.
   */
  private InventoryItem itemEntry(double amount, UUID gameItemId) {
    InventoryItem item = entry(amount);
    item.setMaterial(null);
    item.setQuality(null);
    GameItem gameItem = new GameItem();
    gameItem.setId(gameItemId);
    gameItem.setName("Quantum Drive");
    item.setGameItem(gameItem);
    return item;
  }

  // covers REQ-INV-031 (kind dispatch: item rows gate on the order's requested game items)
  @Test
  void addAllocation_jobOrder_onItemRow_qualifyingItemOrder_addsSlice() {
    // Given an item row and an ITEM order requesting exactly its game item
    UUID gameItemId = UUID.randomUUID();
    InventoryItem item = itemEntry(10.0, gameItemId);
    UUID orderId = UUID.randomUUID();
    JobOrder order = jobOrder(orderId, 42);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(jobOrderItemService.requiredGameItemIds(order)).thenReturn(Set.of(gameItemId));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryItemMapper.toDto(item)).thenReturn(sentinelDto());

    // When
    service.addAllocation(
        itemId,
        new InventoryAllocationWriteDto(InventoryAllocationDimension.JOB_ORDER, orderId, 4.0, 1L));

    // Then — the slice is written via the gameItem gate; the material gate is never consulted
    // (the row has no material to check — the kind dispatch of REQ-INV-031).
    assertEquals(1, item.getJobOrderAllocations().size());
    assertSame(order, item.getJobOrderAllocations().get(0).getJobOrder());
    verify(jobOrderItemService, never()).requiredMaterialIds(any(JobOrder.class));
  }

  // covers REQ-INV-031 (item earmark rejected when the order does not request the game item)
  @Test
  void addAllocation_jobOrder_onItemRow_orderNotRequestingItem_throwsBadRequest() {
    // Given an item row and an order requesting a different game item (or a MATERIAL order, whose
    // requested set is empty by contract — the same rejection)
    InventoryItem item = itemEntry(10.0, UUID.randomUUID());
    UUID orderId = UUID.randomUUID();
    JobOrder order = jobOrder(orderId, 7);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(jobOrderItemService.requiredGameItemIds(order)).thenReturn(Set.of(UUID.randomUUID()));

    // When / Then
    assertThrows(
        BadRequestException.class,
        () ->
            service.addAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, orderId, 4.0, 1L)));
    verify(inventoryItemRepository, never()).saveAndFlush(any());
  }

  // covers REQ-INV-031 (item rows carry no mission dimension — addAllocation)
  @Test
  void addAllocation_mission_onItemRow_throwsBadRequest() {
    // Given an item row and a mission-dimension write
    InventoryItem item = itemEntry(10.0, UUID.randomUUID());
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    // When / Then — 400 (code BAD_REQUEST; 422 stays reserved for over-allocation), before any
    // mission lookup
    assertThrows(
        BadRequestException.class,
        () ->
            service.addAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.MISSION, UUID.randomUUID(), 2.0, 1L)));
    verify(inventoryItemRepository, never()).saveAndFlush(any());
    verifyNoMissionLookups();
  }

  // covers REQ-INV-031 (item rows carry no mission dimension — changeAllocation)
  @Test
  void changeAllocation_mission_onItemRow_throwsBadRequest() {
    // Given an item row and a mission-dimension amount change
    InventoryItem item = itemEntry(10.0, UUID.randomUUID());
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    // When / Then
    assertThrows(
        BadRequestException.class,
        () ->
            service.changeAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.MISSION, UUID.randomUUID(), 3.0, 1L)));
    verify(inventoryItemRepository, never()).saveAndFlush(any());
    verifyNoMissionLookups();
  }

  // covers REQ-INV-029 (item allocation amounts are whole units)
  @Test
  void addAllocation_fractionalAmount_onItemRow_throwsBadRequest() {
    // Given an item row and a fractional slice amount
    InventoryItem item = itemEntry(10.0, UUID.randomUUID());
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    // When / Then — item rows restrict amounts to whole numbers like PIECE materials, without any
    // material dereference (the row's material is null); the 400 detail names the item kind
    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service.addAllocation(
                    itemId,
                    new InventoryAllocationWriteDto(
                        InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 1.5, 1L)));
    assertEquals("An item allocation amount must be a whole number", ex.getMessage());
    verify(inventoryItemRepository, never()).saveAndFlush(any());
  }

  /** Asserts the mission repository was never consulted (the item-row guard fires first). */
  private void verifyNoMissionLookups() {
    verify(missionRepository, never()).findById(any());
  }

  @Test
  void addAllocation_missingAmount_throwsBadRequest() {
    InventoryItem item = entry(10.0);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    assertThrows(
        BadRequestException.class,
        () ->
            service.addAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), null, 1L)));
  }

  @Test
  void addAllocation_staleVersion_throws409() {
    InventoryItem item = entry(10.0);
    item.setVersion(5L);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () ->
            service.addAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 4.0, 1L)));
  }

  @Test
  void addAllocation_unknownEntry_throws404() {
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () ->
            service.addAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 4.0, 1L)));
  }

  // --- change ------------------------------------------------------------------------------

  @Test
  void changeAllocation_updatesAmount() {
    InventoryItem item = entry(10.0);
    UUID orderId = UUID.randomUUID();
    addJobOrderSlice(item, jobOrder(orderId, 3), 3.0);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryItemMapper.toDto(item)).thenReturn(sentinelDto());

    service.changeAllocation(
        itemId,
        new InventoryAllocationWriteDto(InventoryAllocationDimension.JOB_ORDER, orderId, 7.0, 1L));

    assertEquals(7.0, item.getJobOrderAllocations().get(0).getAmount(), 1e-9);
    verify(auditService)
        .record(eq(AuditEventType.INVENTORY_ALLOCATION_CHANGED), eq(itemId), any(), any(), any());
  }

  @Test
  void changeAllocation_overAllocating_throws422() {
    InventoryItem item = entry(10.0);
    UUID orderA = UUID.randomUUID();
    UUID orderB = UUID.randomUUID();
    addJobOrderSlice(item, jobOrder(orderA, 1), 6.0);
    addJobOrderSlice(item, jobOrder(orderB, 2), 3.0);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    // Raising A from 6 to 8 while B holds 3 → Σ = 11 > 10.
    assertThrows(
        OverAllocationException.class,
        () ->
            service.changeAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, orderA, 8.0, 1L)));
  }

  @Test
  void changeAllocation_unknownSlice_throws404() {
    InventoryItem item = entry(10.0);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    assertThrows(
        NotFoundException.class,
        () ->
            service.changeAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 2.0, 1L)));
  }

  // --- remove ------------------------------------------------------------------------------

  @Test
  void removeAllocation_removesSlice() {
    InventoryItem item = entry(10.0);
    UUID missionId = UUID.randomUUID();
    InventoryMissionAllocation slice = new InventoryMissionAllocation();
    slice.setInventoryItem(item);
    slice.setMission(mission(missionId, "Op Dawn"));
    slice.setAmount(5.0);
    item.getMissionAllocations().add(slice);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryItemMapper.toDto(item)).thenReturn(sentinelDto());

    service.removeAllocation(
        itemId,
        new InventoryAllocationWriteDto(InventoryAllocationDimension.MISSION, missionId, null, 1L));

    assertTrue(item.getMissionAllocations().isEmpty());
    verify(auditService)
        .record(eq(AuditEventType.INVENTORY_ALLOCATION_REMOVED), eq(itemId), any(), any(), any());
  }

  @Test
  void removeAllocation_unknownSlice_throws404() {
    InventoryItem item = entry(10.0);
    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    assertThrows(
        NotFoundException.class,
        () ->
            service.removeAllocation(
                itemId,
                new InventoryAllocationWriteDto(
                    InventoryAllocationDimension.MISSION, UUID.randomUUID(), null, 1L)));
  }
}

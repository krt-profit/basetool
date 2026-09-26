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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderHandoverMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverItemCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderMaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobOrderHandoverServiceTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderHandoverRepository jobOrderHandoverRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private JobOrderHandoverMapper jobOrderHandoverMapper;
  @Mock private JobOrderMaterialRepository jobOrderMaterialRepository;
  @Mock private JobOrderService jobOrderService;
  @Mock private UserService userService;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;

  @Mock
  private de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository orgUnitRepository;

  @Mock private AuditService auditService;
  @InjectMocks private JobOrderHandoverService service;

  private UUID orderId;
  private UUID inventoryId;
  private UUID materialId;
  private JobOrder order;
  private InventoryItem inventoryItem;
  private de.greluc.krt.profit.basetool.backend.model.Material material;
  private de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial jobOrderMaterial;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    inventoryId = UUID.randomUUID();
    materialId = UUID.randomUUID();
    order = new JobOrder();
    order.setId(orderId);

    material = new de.greluc.krt.profit.basetool.backend.model.Material();
    material.setId(materialId);

    jobOrderMaterial = new de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial();
    jobOrderMaterial.setId(UUID.randomUUID());
    jobOrderMaterial.setMaterial(material);
    jobOrderMaterial.setAmount(10.0);
    order.addMaterial(jobOrderMaterial);

    inventoryItem = new InventoryItem();
    inventoryItem.setId(inventoryId);
    inventoryItem.setMaterial(material);
    inventoryItem.setAmount(10.0);
    InventoryAllocations.addJobOrder(inventoryItem, order, inventoryItem.getAmount(), false);
  }

  @Test
  void createHandover_shouldRejectItemOrder_soProductionRemainsTheSoleMaterialConsumer() {
    order.setType(de.greluc.krt.profit.basetool.backend.model.JobOrderType.ITEM);
    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 4.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", "Rogue", List.of(itemDto));
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

    assertThrows(BadRequestException.class, () -> service.createHandover(orderId, createDto));
    assertEquals(10.0, inventoryItem.getAmount());
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void createHandover_shouldReduceInventoryAmount_whenAmountIsSmallerThanStock() {
    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 4.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", "Rogue", List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    assertEquals(6.0, inventoryItem.getAmount());
    assertEquals(6.0, jobOrderMaterial.getAmount());
    verify(inventoryItemRepository).save(inventoryItem);
    verify(inventoryItemRepository, never()).delete(any());
    verify(inventoryItemRepository, never())
        .deleteJobOrderAllocationsByJobOrderAndMaterial(any(), any());
    verify(jobOrderService, never()).completeJobOrderWithinTransaction(any());
    verify(jobOrderHandoverRepository).save(any(JobOrderHandover.class));
    verify(jobOrderRepository, times(2)).findById(orderId);
    verify(materialExchangeOfferRepository).clampOfferedAmountToStock(eq(inventoryId), eq(6.0));
  }

  @Test
  void createHandover_shouldAutoClampMissionEarmark_whenDualTaggedPartialHandover() {
    de.greluc.krt.profit.basetool.backend.model.Mission mission =
        new de.greluc.krt.profit.basetool.backend.model.Mission();
    mission.setId(UUID.randomUUID());
    InventoryAllocations.addMission(inventoryItem, mission, 10.0);

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 4.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", "Rogue", List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    assertEquals(6.0, inventoryItem.getAmount());
    assertEquals(6.0, inventoryItem.getJobOrderAllocations().iterator().next().getAmount());
    assertEquals(6.0, inventoryItem.getMissionAllocations().iterator().next().getAmount());
    verify(inventoryItemRepository).save(inventoryItem);
  }

  @Test
  void createHandover_shouldApplyExplicitMissionPlan_whenAmbiguousMultiMission() {
    de.greluc.krt.profit.basetool.backend.model.Mission missionA =
        new de.greluc.krt.profit.basetool.backend.model.Mission();
    missionA.setId(UUID.randomUUID());
    de.greluc.krt.profit.basetool.backend.model.Mission missionB =
        new de.greluc.krt.profit.basetool.backend.model.Mission();
    missionB.setId(UUID.randomUUID());
    InventoryAllocations.addMission(inventoryItem, missionA, 6.0);
    InventoryAllocations.addMission(inventoryItem, missionB, 4.0);

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(
            inventoryId, 4.0, List.of(new AllocationReductionDto(missionA.getId(), 4.0)));
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", "Rogue", List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    assertEquals(6.0, inventoryItem.getAmount());
    double missionAAmount =
        inventoryItem.getMissionAllocations().stream()
            .filter(a -> a.getMission().getId().equals(missionA.getId()))
            .findFirst()
            .orElseThrow()
            .getAmount();
    double missionBAmount =
        inventoryItem.getMissionAllocations().stream()
            .filter(a -> a.getMission().getId().equals(missionB.getId()))
            .findFirst()
            .orElseThrow()
            .getAmount();
    assertEquals(2.0, missionAAmount);
    assertEquals(4.0, missionBAmount);
  }

  @Test
  void createHandover_shouldReject_whenAmountExceedsOwnOrderSlice_withSiblingOrder() {
    JobOrder siblingOrder = new JobOrder();
    siblingOrder.setId(UUID.randomUUID());
    inventoryItem.getJobOrderAllocations().clear();
    inventoryItem.setAmount(100.0);
    InventoryAllocations.addJobOrder(inventoryItem, order, 20.0, false);
    InventoryAllocations.addJobOrder(inventoryItem, siblingOrder, 70.0, false);

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 50.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", "Rogue", List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service.createHandover(orderId, createDto));
    assertTrue(ex.getMessage().contains("earmarked to this job order"));
    assertEquals(100.0, inventoryItem.getAmount());
    assertEquals(2, inventoryItem.getJobOrderAllocations().size());
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void createHandover_shouldDeleteInventoryItem_whenAmountIsFullyHandedOver() {
    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 10.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));

    de.greluc.krt.profit.basetool.backend.model.JobOrderHandover[] persistedHandover =
        new de.greluc.krt.profit.basetool.backend.model.JobOrderHandover[1];
    when(jobOrderHandoverRepository.save(any()))
        .thenAnswer(
            i -> {
              persistedHandover[0] = i.getArgument(0);
              return persistedHandover[0];
            });
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    assertEquals(0.0, jobOrderMaterial.getAmount());
    verify(inventoryItemRepository).delete(inventoryItem);
    verify(inventoryItemRepository, never()).save(any());
    verify(materialExchangeOfferRepository, never()).clampOfferedAmountToStock(any(), anyDouble());
    verify(inventoryItemRepository)
        .deleteJobOrderAllocationsByJobOrderAndMaterial(orderId, materialId);
    verify(jobOrderService).completeJobOrderWithinTransaction(order);
    verify(jobOrderRepository, times(2)).findById(orderId);
    verify(jobOrderHandoverRepository).save(any(JobOrderHandover.class));
    assertNotNull(persistedHandover[0]);
    assertEquals(1, persistedHandover[0].getItems().size());
    var snapshot = persistedHandover[0].getItems().iterator().next();
    assertEquals(material, snapshot.getMaterial());
    assertEquals(10.0, snapshot.getAmount());
  }

  @Test
  void createHandover_shouldThrowException_whenAmountExceedsStock() {
    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 11.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service.createHandover(orderId, createDto));
    assertTrue(ex.getMessage().contains("Cannot hand over more than the available amount"));
  }

  @Test
  void createHandover_shouldThrowException_whenJobOrderIsNullOnInventoryItem() {
    inventoryItem.getJobOrderAllocations().clear();

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 5.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "swing-by", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service.createHandover(orderId, createDto));
    assertEquals(JobOrderHandoverService.ERROR_ITEM_NOT_LINKED_TO_ORDER, ex.getMessage());
  }

  @Test
  void createHandover_shouldReduceBothJobOrderMaterialAmounts_whenTwoItemsHandedOver() {
    UUID inventoryId2 = UUID.randomUUID();
    UUID materialId2 = UUID.randomUUID();

    de.greluc.krt.profit.basetool.backend.model.Material material2 =
        new de.greluc.krt.profit.basetool.backend.model.Material();
    material2.setId(materialId2);

    de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial jobOrderMaterial2 =
        new de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial();
    jobOrderMaterial2.setId(UUID.randomUUID());
    jobOrderMaterial2.setMaterial(material2);
    jobOrderMaterial2.setAmount(8.0);
    order.addMaterial(jobOrderMaterial2);

    InventoryItem inventoryItem2 = new InventoryItem();
    inventoryItem2.setId(inventoryId2);
    inventoryItem2.setMaterial(material2);
    inventoryItem2.setAmount(8.0);
    InventoryAllocations.addJobOrder(inventoryItem2, order, inventoryItem2.getAmount(), false);

    JobOrderHandoverItemCreateDto itemDto1 =
        new JobOrderHandoverItemCreateDto(inventoryId, 5.0, null);
    JobOrderHandoverItemCreateDto itemDto2 =
        new JobOrderHandoverItemCreateDto(inventoryId2, 8.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "swing-by", null, List.of(itemDto1, itemDto2));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId2))
        .thenReturn(Optional.of(inventoryItem2));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    assertDoesNotThrow(() -> service.createHandover(orderId, createDto));

    assertEquals(
        5.0,
        jobOrderMaterial.getAmount(),
        0.0001,
        "First material's open amount must be reduced from 10.0 to 5.0");
    assertEquals(
        0.0,
        jobOrderMaterial2.getAmount(),
        0.0001,
        "Second material's open amount must be reduced from 8.0 to 0.0");
    verify(jobOrderMaterialRepository, never()).save(any());
    verify(inventoryItemRepository).delete(inventoryItem2);
    verify(inventoryItemRepository)
        .deleteJobOrderAllocationsByJobOrderAndMaterial(orderId, materialId2);
    verify(inventoryItemRepository).save(inventoryItem);
    verify(jobOrderService, never()).completeJobOrderWithinTransaction(any());
  }

  @Test
  void createHandover_shouldSucceed_whenMultipleItemsHandedOver_andFirstItemFullyConsumed() {
    UUID inventoryId2 = UUID.randomUUID();
    UUID materialId2 = UUID.randomUUID();

    de.greluc.krt.profit.basetool.backend.model.Material material2 =
        new de.greluc.krt.profit.basetool.backend.model.Material();
    material2.setId(materialId2);

    de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial jobOrderMaterial2 =
        new de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial();
    jobOrderMaterial2.setId(UUID.randomUUID());
    jobOrderMaterial2.setMaterial(material2);
    jobOrderMaterial2.setAmount(5.0);
    order.addMaterial(jobOrderMaterial2);

    InventoryItem inventoryItem2 = new InventoryItem();
    inventoryItem2.setId(inventoryId2);
    inventoryItem2.setMaterial(material2);
    inventoryItem2.setAmount(5.0);
    InventoryAllocations.addJobOrder(inventoryItem2, order, inventoryItem2.getAmount(), false);

    JobOrderHandoverItemCreateDto itemDto1 =
        new JobOrderHandoverItemCreateDto(inventoryId, 10.0, null);
    JobOrderHandoverItemCreateDto itemDto2 =
        new JobOrderHandoverItemCreateDto(inventoryId2, 3.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "swing-by", null, List.of(itemDto1, itemDto2));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId2))
        .thenReturn(Optional.of(inventoryItem2));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    assertDoesNotThrow(() -> service.createHandover(orderId, createDto));

    verify(inventoryItemRepository).delete(inventoryItem);
    verify(inventoryItemRepository)
        .deleteJobOrderAllocationsByJobOrderAndMaterial(orderId, materialId);
    assertEquals(2.0, inventoryItem2.getAmount());
    verify(inventoryItemRepository).save(inventoryItem2);
  }

  @Test
  void createHandover_shouldNotCompleteOrder_whenMaterialStillOpen() {
    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 4.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    verify(jobOrderService, never()).updateJobOrderStatus(any(), any());
    assertEquals(6.0, jobOrderMaterial.getAmount(), 0.0001);
  }

  @Test
  void createHandover_shouldNotCompleteOrder_whenInventoryItemLinkedToOrder() {
    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 3.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    verify(inventoryItemRepository).save(inventoryItem);
    verify(inventoryItemRepository, never()).delete(any());
    verify(inventoryItemRepository, never())
        .deleteJobOrderAllocationsByJobOrderAndMaterial(any(), any());
    verify(jobOrderService, never()).completeJobOrderWithinTransaction(any());
  }

  @Test
  void createHandover_shouldThrowException_whenItemDoesNotBelongToOrder() {
    JobOrder otherOrder = new JobOrder();
    otherOrder.setId(UUID.randomUUID());
    inventoryItem.getJobOrderAllocations().clear();
    InventoryAllocations.addJobOrder(inventoryItem, otherOrder, inventoryItem.getAmount(), false);

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 5.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service.createHandover(orderId, createDto));
    assertEquals(JobOrderHandoverService.ERROR_ITEM_NOT_LINKED_TO_ORDER, ex.getMessage());
  }

  @Test
  void createHandover_shouldThrowException_whenAmountExceedsRemainingAmount() {
    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 15.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service.createHandover(orderId, createDto));
    assertTrue(
        ex.getMessage().contains("Cannot hand over more than the available amount"),
        "Exception message must indicate amount exceeds available stock");
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void createHandover_shouldCallCompleteJobOrderWithinTransaction_whenAllMaterialsHandedOver() {
    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 10.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "swing-by", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    assertDoesNotThrow(() -> service.createHandover(orderId, createDto));

    verify(jobOrderService).completeJobOrderWithinTransaction(order);
    verify(jobOrderService, never()).updateJobOrderStatus(any(), any());
    verify(jobOrderRepository, times(2)).findById(orderId);
  }

  @Test
  void
      createHandover_shouldCompleteOrder_whenLastRemainingMaterialHandedOverAfterPreviousPartialHandover() {
    jobOrderMaterial.setAmount(4.0);
    inventoryItem.setAmount(4.0);
    inventoryItem.getJobOrderAllocations().clear();
    InventoryAllocations.addJobOrder(inventoryItem, order, 4.0, false);

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 4.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "swing-by", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    assertDoesNotThrow(() -> service.createHandover(orderId, createDto));

    assertEquals(0.0, jobOrderMaterial.getAmount(), 0.0001);
    verify(jobOrderService).completeJobOrderWithinTransaction(order);
    verify(jobOrderService, never()).updateJobOrderStatus(any(), any());
    verify(jobOrderRepository, times(2)).findById(orderId);
  }

  @Test
  void createHandover_shouldThrowException_whenPieceMaterialHasDecimalAmount() {
    material.setQuantityType(QuantityType.PIECE);
    inventoryItem.setAmount(5.0);
    jobOrderMaterial.setAmount(5.0);

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 2.5, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service.createHandover(orderId, createDto));
    assertTrue(
        ex.getMessage().contains("Amount must be a whole number for PIECE materials"),
        "Exception message must indicate that only integers are allowed for PIECE materials");
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void createHandover_shouldSucceed_whenInventoryItemBelongsToForeignSquadron() {
    de.greluc.krt.profit.basetool.backend.model.Squadron squadronA =
        new de.greluc.krt.profit.basetool.backend.model.Squadron();
    squadronA.setId(UUID.randomUUID());
    squadronA.setShorthand("ALF");
    de.greluc.krt.profit.basetool.backend.model.Squadron squadronB =
        new de.greluc.krt.profit.basetool.backend.model.Squadron();
    squadronB.setId(UUID.randomUUID());
    squadronB.setShorthand("BRV");

    order.setResponsibleOrgUnit(squadronA);
    order.setRequestingOrgUnit(squadronA);
    inventoryItem.setOwningOrgUnit(squadronB);

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 3.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(
            Instant.now(), "CrossSquadronHandler", "BRV", List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    assertEquals(7.0, inventoryItem.getAmount());
    assertEquals(7.0, jobOrderMaterial.getAmount());
    verify(inventoryItemRepository).save(inventoryItem);
    verify(jobOrderHandoverRepository).save(any(JobOrderHandover.class));
  }

  @Test
  void createHandover_emitsPerItemHandedOverAudit_andHandoverCreatedWithAutoCompletedFlag() {
    UUID inventoryId2 = UUID.randomUUID();
    UUID materialId2 = UUID.randomUUID();

    de.greluc.krt.profit.basetool.backend.model.Material material2 =
        new de.greluc.krt.profit.basetool.backend.model.Material();
    material2.setId(materialId2);

    de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial jobOrderMaterial2 =
        new de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial();
    jobOrderMaterial2.setId(UUID.randomUUID());
    jobOrderMaterial2.setMaterial(material2);
    jobOrderMaterial2.setAmount(8.0);
    order.addMaterial(jobOrderMaterial2);

    InventoryItem inventoryItem2 = new InventoryItem();
    inventoryItem2.setId(inventoryId2);
    inventoryItem2.setMaterial(material2);
    inventoryItem2.setAmount(8.0);
    InventoryAllocations.addJobOrder(inventoryItem2, order, inventoryItem2.getAmount(), false);

    JobOrderHandoverItemCreateDto itemDto1 =
        new JobOrderHandoverItemCreateDto(inventoryId, 10.0, null);
    JobOrderHandoverItemCreateDto itemDto2 =
        new JobOrderHandoverItemCreateDto(inventoryId2, 8.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto1, itemDto2));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId2))
        .thenReturn(Optional.of(inventoryItem2));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    verify(auditService, times(2))
        .record(eq(AuditEventType.INVENTORY_HANDED_OVER), any(), any(), any(), any());
    ArgumentCaptor<CharSequence> detailsCaptor = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.JOB_ORDER_HANDOVER_CREATED),
            eq(orderId),
            any(),
            any(),
            detailsCaptor.capture());
    String rendered = detailsCaptor.getValue().toString();
    assertTrue(rendered.contains("items=2"), "handover-created audit must carry the item count");
    assertTrue(
        rendered.contains("autoCompleted=true"),
        "a fully-fulfilling handover must flag autoCompleted=true");
    verify(jobOrderService).completeJobOrderWithinTransaction(order);
  }

  @Test
  void createHandover_handoverCreatedAudit_flagsAutoCompletedFalse_whenPartial() {
    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 4.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    verify(auditService, times(1))
        .record(eq(AuditEventType.INVENTORY_HANDED_OVER), any(), any(), any(), any());
    ArgumentCaptor<CharSequence> detailsCaptor = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.JOB_ORDER_HANDOVER_CREATED),
            eq(orderId),
            any(),
            any(),
            detailsCaptor.capture());
    String rendered = detailsCaptor.getValue().toString();
    assertTrue(rendered.contains("items=1"), "handover-created audit must carry the item count");
    assertTrue(
        rendered.contains("autoCompleted=false"),
        "a partial handover must flag autoCompleted=false");
    verify(jobOrderService, never()).completeJobOrderWithinTransaction(any());
  }

  @Test
  void createHandover_subEpsilonResidual_deletesRowAndUnlinksMaterial() {
    inventoryItem.setAmount(10.00003);
    jobOrderMaterial.setAmount(10.00003);
    inventoryItem.getJobOrderAllocations().clear();
    InventoryAllocations.addJobOrder(inventoryItem, order, 10.00003, false);

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 10.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    verify(inventoryItemRepository).delete(inventoryItem);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository)
        .deleteJobOrderAllocationsByJobOrderAndMaterial(orderId, materialId);
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_HANDED_OVER),
            eq(inventoryId),
            any(),
            any(),
            argThat(
                d ->
                    d != null
                        && d.toString().contains("depleted=true")
                        && d.toString().contains("remaining=0.0")));
  }

  @Test
  void createHandover_residualAboveEpsilon_savesRow_withoutDeleteOrUnlink() {
    inventoryItem.setAmount(10.0002);
    jobOrderMaterial.setAmount(50.0);
    inventoryItem.getJobOrderAllocations().clear();
    InventoryAllocations.addJobOrder(inventoryItem, order, 10.0002, false);

    JobOrderHandoverItemCreateDto itemDto =
        new JobOrderHandoverItemCreateDto(inventoryId, 10.0, null);
    JobOrderHandoverCreateDto createDto =
        new JobOrderHandoverCreateDto(Instant.now(), "HanSolo", null, List.of(itemDto));

    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));

    service.createHandover(orderId, createDto);

    assertEquals(0.0002, inventoryItem.getAmount(), 1e-6);
    verify(inventoryItemRepository).save(inventoryItem);
    verify(inventoryItemRepository, never()).delete(any());
    verify(inventoryItemRepository, never())
        .deleteJobOrderAllocationsByJobOrderAndMaterial(any(), any());
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_HANDED_OVER),
            eq(inventoryId),
            any(),
            any(),
            argThat(d -> d != null && d.toString().contains("depleted=false")));
  }
}

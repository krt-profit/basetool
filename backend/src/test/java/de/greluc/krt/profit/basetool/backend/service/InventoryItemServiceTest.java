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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.exception.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.*;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InventoryItemServiceTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;

  @Mock private MaterialMapper materialMapper;
  @Mock private OwnerScopeService ownerScopeService;

  @Mock private JobOrderItemService jobOrderItemService;

  @Mock private AuditService auditService;
  @InjectMocks private InventoryItemService inventoryItemService;

  private InventoryAggregationService realAggregationService;
  private InventoryCheckoutService realCheckoutService;

  @BeforeEach
  void wireDelegates() {
    // The facade delegates every read/aggregation to InventoryAggregationService and every checkout
    // write to InventoryCheckoutService (the L2 split, #921). Mockito does not inject one
    // @InjectMocks target into another, so build the real sub-services from the same mocks and set
    // them on the facade. createInventoryItem / updateNote stay on the facade
    // and use the mocks directly.
    realAggregationService =
        new InventoryAggregationService(
            inventoryItemRepository,
            userRepository,
            materialRepository,
            jobOrderRepository,
            inventoryItemMapper,
            materialMapper,
            ownerScopeService);
    realCheckoutService =
        new InventoryCheckoutService(
            inventoryItemRepository,
            userRepository,
            locationRepository,
            missionFinanceEntryRepository,
            missionParticipantRepository,
            materialExchangeOfferRepository,
            inventoryItemMapper,
            ownerScopeService,
            auditService);
    ReflectionTestUtils.setField(
        inventoryItemService, "inventoryAggregationService", realAggregationService);
    ReflectionTestUtils.setField(
        inventoryItemService, "inventoryCheckoutService", realCheckoutService);
  }

  @Test
  void getMissionInventory_mapsEveryItemLinkedToTheMission() {
    // #1138: the mission economy moved off the embedded MissionDto.inventoryEntries field onto a
    // dedicated read that lists every item linked to the mission and maps each to its display DTO.
    UUID missionId = UUID.randomUUID();
    InventoryItem a = new InventoryItem();
    InventoryItem b = new InventoryItem();
    InventoryItemDto da = mock(InventoryItemDto.class);
    InventoryItemDto db = mock(InventoryItemDto.class);
    when(inventoryItemRepository.findByMissionId(missionId)).thenReturn(List.of(a, b));
    when(inventoryItemMapper.toDto(a)).thenReturn(da);
    when(inventoryItemMapper.toDto(b)).thenReturn(db);

    List<InventoryItemDto> result = inventoryItemService.getMissionInventory(missionId);

    assertEquals(List.of(da, db), result);
    verify(inventoryItemRepository).findByMissionId(missionId);
  }

  @Test
  void getAggregatedInventory_shouldReturnPage() {
    // material, weighted-avg quality, MAX quality, total amount (REQ-INV-027 max-quality column).
    Object[] obj = new Object[] {new Material(), 10.0, 900, 5L};
    Page<Object[]> page = new PageImpl<Object[]>(List.<Object[]>of(obj));
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.getAggregatedInventory(
            anyBoolean(), any(), any(), any(Pageable.class)))
        .thenReturn(page);
    when(materialMapper.toDto(any())).thenReturn(null);

    Page<AggregatedInventoryDto> result =
        inventoryItemService.getAggregatedInventory(Pageable.unpaged());

    assertNotNull(result);
    assertEquals(1, result.getTotalElements());
    assertEquals(10, result.getContent().get(0).quality());
    assertEquals(900, result.getContent().get(0).maxQuality());
    assertEquals(5L, result.getContent().get(0).amount());
  }

  @Test
  void getInventoryByMaterial_shouldReturnPage() {
    UUID materialId = UUID.randomUUID();
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(new Material()));
    // Post-fix #5: getInventoryByMaterial routes through the scoped repository variant so
    // a Lager-direct drilldown stays strictly squadron-isolated.
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.findByMaterialAndPersonalFalseScoped(
            any(), anyBoolean(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(new InventoryItem())));
    when(inventoryItemMapper.toDto(any())).thenReturn(null);

    Page<InventoryItemDto> result =
        inventoryItemService.getInventoryByMaterial(materialId, Pageable.unpaged());

    assertNotNull(result);
    assertEquals(1, result.getTotalElements());
  }

  @Test
  void getUserInventory_shouldReturnPage() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));
    when(inventoryItemRepository.findByUser(any(), any()))
        .thenReturn(new PageImpl<>(List.of(new InventoryItem())));
    when(inventoryItemMapper.toDto(any())).thenReturn(null);

    Page<InventoryItemDto> result =
        inventoryItemService.getUserInventory(userId, Pageable.unpaged());

    assertNotNull(result);
    assertEquals(1, result.getTotalElements());
  }

  @Test
  void getAllInventory_shouldReturnAll_whenMaterialIdIsNull() {
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.findGlobalByFilters(
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            anyBoolean(),
            any(),
            any(),
            any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(new InventoryItem())));
    when(inventoryItemMapper.toDto(any())).thenReturn(null);

    Page<InventoryItemDto> result =
        inventoryItemService.getAllInventory(null, null, Pageable.unpaged());

    assertNotNull(result);
    assertEquals(1, result.getTotalElements());
  }

  @Test
  void getAllInventory_shouldPassJobOrderAndMissionFilters() {
    // Given
    UUID jobOrderId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    List<UUID> jobOrderIds = List.of(jobOrderId);
    List<UUID> missionIds = List.of(missionId);

    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.findGlobalByFilters(
            eq(false),
            eq(null),
            eq(null),
            eq(true),
            eq(jobOrderIds),
            eq(true),
            eq(missionIds),
            anyBoolean(),
            any(),
            any(),
            any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(new InventoryItem())));
    when(inventoryItemMapper.toDto(any())).thenReturn(null);

    // When
    Page<InventoryItemDto> result =
        inventoryItemService.getAllInventory(
            null, null, jobOrderIds, missionIds, Pageable.unpaged());

    // Then
    assertNotNull(result);
    assertEquals(1, result.getTotalElements());
    verify(inventoryItemRepository)
        .findGlobalByFilters(
            eq(false),
            eq(null),
            eq(null),
            eq(true),
            eq(jobOrderIds),
            eq(true),
            eq(missionIds),
            anyBoolean(),
            any(),
            any(),
            any(Pageable.class));
  }

  @Test
  void getMyAggregatedInventory_shouldPassJobOrderAndMissionFilters() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    List<UUID> jobOrderIds = List.of(jobOrderId);
    List<UUID> missionIds = List.of(missionId);
    User user = new User();
    user.setId(userId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(null),
            eq(true),
            eq(jobOrderIds),
            eq(true),
            eq(missionIds),
            eq(false),
            eq(false)))
        .thenReturn(List.of());

    // When
    List<GroupedInventoryDto> result =
        inventoryItemService.getMyAggregatedInventory(userId, jobOrderIds, missionIds);

    // Then
    assertNotNull(result);
    verify(inventoryItemRepository)
        .findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(null),
            eq(true),
            eq(jobOrderIds),
            eq(true),
            eq(missionIds),
            eq(false),
            eq(false));
  }

  @Test
  void getMyAggregatedInventory_withoutFilters_shouldPassEmptyFlags() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(false)))
        .thenReturn(List.of());

    List<GroupedInventoryDto> result = inventoryItemService.getMyAggregatedInventory(userId);

    assertNotNull(result);
    verify(inventoryItemRepository)
        .findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(false));
  }

  @Test
  void getMyAggregatedInventory_shouldPassMaterialAndMinQualityFilters() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    List<UUID> materialIds = List.of(materialId);
    Integer minQuality = 500;
    User user = new User();
    user.setId(userId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(true),
            eq(materialIds),
            eq(minQuality),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(false)))
        .thenReturn(List.of());

    // When
    List<GroupedInventoryDto> result =
        inventoryItemService.getMyAggregatedInventory(userId, materialIds, minQuality, null, null);

    // Then
    assertNotNull(result);
    verify(inventoryItemRepository)
        .findUserStacks(
            eq(userId),
            eq(true),
            eq(materialIds),
            eq(minQuality),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(false));
  }

  @Test
  void getMyAggregatedInventory_personalOnly_forwardsFlagToRepository() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(true),
            eq(false)))
        .thenReturn(List.of());

    // When
    List<GroupedInventoryDto> result =
        inventoryItemService.getMyAggregatedInventory(userId, null, null, null, null, true, false);

    // Then
    assertNotNull(result);
    verify(inventoryItemRepository)
        .findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(true),
            eq(false));
  }

  @Test
  void getMyAggregatedInventory_nonPersonalOnly_forwardsFlagToRepository() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(true)))
        .thenReturn(List.of());

    // When
    List<GroupedInventoryDto> result =
        inventoryItemService.getMyAggregatedInventory(userId, null, null, null, null, false, true);

    // Then
    assertNotNull(result);
    verify(inventoryItemRepository)
        .findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(true));
  }

  @Test
  void createInventoryItem_shouldCreateItem() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId, materialId, locationId, 100, 10.0, false, null, null, null, null, null, null);

    User user = new User();
    user.setId(userId);

    Material material = new Material();
    material.setId(materialId);

    Location location = new Location();
    location.setId(locationId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));

    InventoryItem savedItem = new InventoryItem();
    when(inventoryItemRepository.save(any(InventoryItem.class))).thenReturn(savedItem);

    InventoryItemDto expectedDto =
        new InventoryItemDto(
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            null,
            null);
    when(inventoryItemMapper.toDto(savedItem)).thenReturn(expectedDto);

    InventoryItemDto result = inventoryItemService.createInventoryItem(dto, userId, false);

    assertNotNull(result);
    verify(inventoryItemRepository).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_withSplitAtCheckIn_writesEachAllocation() {
    // Variante C (REQ-INV-027, R4): split at check-in — the entry earmarks parts of its amount to
    // several job orders and a mission with their own amounts.
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID orderAId = UUID.randomUUID();
    UUID orderBId = UUID.randomUUID();
    UUID missionXId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            locationId,
            100,
            10.0,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(
                new InventoryAllocationInput(orderAId, 3.0),
                new InventoryAllocationInput(orderBId, 4.0)),
            java.util.List.of(new InventoryAllocationInput(missionXId, 5.0)));

    User user = new User();
    user.setId(userId);
    Material material = new Material();
    material.setId(materialId);
    Location location = new Location();
    location.setId(locationId);
    JobOrder orderA = new JobOrder();
    orderA.setId(orderAId);
    JobOrder orderB = new JobOrder();
    orderB.setId(orderBId);
    Mission missionX = new Mission();
    missionX.setId(missionXId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(jobOrderRepository.findById(orderAId)).thenReturn(Optional.of(orderA));
    when(jobOrderRepository.findById(orderBId)).thenReturn(Optional.of(orderB));
    when(missionRepository.findById(missionXId)).thenReturn(Optional.of(missionX));
    when(jobOrderItemService.requiredMaterialIds(any(JobOrder.class)))
        .thenReturn(Set.of(materialId));
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    inventoryItemService.createInventoryItem(dto, userId, false);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    InventoryItem saved = captor.getValue();
    assertEquals(2, saved.getJobOrderAllocations().size(), "two job-order slices written");
    assertEquals(1, saved.getMissionAllocations().size(), "one mission slice written");
    assertEquals(
        7.0,
        saved.getJobOrderAllocations().stream().mapToDouble(a -> a.getAmount()).sum(),
        "Σ job-order slices");
    assertEquals(5.0, saved.getMissionAllocations().get(0).getAmount());
  }

  @Test
  void createInventoryItem_withSplitExceedingAmount_throwsOverAllocation() {
    // R5: Σ per dimension must stay within the entry amount; 8 + 5 > 10 => 422.
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID orderAId = UUID.randomUUID();
    UUID orderBId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            locationId,
            100,
            10.0,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(
                new InventoryAllocationInput(orderAId, 8.0),
                new InventoryAllocationInput(orderBId, 5.0)),
            null);

    User user = new User();
    user.setId(userId);
    Material material = new Material();
    material.setId(materialId);
    Location location = new Location();
    location.setId(locationId);
    JobOrder orderA = new JobOrder();
    orderA.setId(orderAId);
    JobOrder orderB = new JobOrder();
    orderB.setId(orderBId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(jobOrderRepository.findById(orderAId)).thenReturn(Optional.of(orderA));
    when(jobOrderRepository.findById(orderBId)).thenReturn(Optional.of(orderB));
    when(jobOrderItemService.requiredMaterialIds(any(JobOrder.class)))
        .thenReturn(Set.of(materialId));

    assertThrows(
        OverAllocationException.class,
        () -> inventoryItemService.createInventoryItem(dto, userId, false));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_withDuplicateSplitTarget_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID orderAId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            locationId,
            100,
            10.0,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(
                new InventoryAllocationInput(orderAId, 3.0),
                new InventoryAllocationInput(orderAId, 4.0)),
            null);

    User user = new User();
    user.setId(userId);
    Material material = new Material();
    material.setId(materialId);
    Location location = new Location();
    location.setId(locationId);
    JobOrder orderA = new JobOrder();
    orderA.setId(orderAId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(jobOrderRepository.findById(orderAId)).thenReturn(Optional.of(orderA));
    when(jobOrderItemService.requiredMaterialIds(any(JobOrder.class)))
        .thenReturn(Set.of(materialId));

    assertThrows(
        BadRequestException.class,
        () -> inventoryItemService.createInventoryItem(dto, userId, false));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_personalWithSplitAllocations_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            locationId,
            100,
            10.0,
            true,
            null,
            null,
            null,
            null,
            java.util.List.of(new InventoryAllocationInput(UUID.randomUUID(), 3.0)),
            null);

    User user = new User();
    user.setId(userId);
    Material material = new Material();
    material.setId(materialId);
    Location location = new Location();
    location.setId(locationId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));

    assertThrows(
        BadRequestException.class,
        () -> inventoryItemService.createInventoryItem(dto, userId, false));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_shouldRejectWhenMaterialNotRequiredByJobOrder() {
    // REQ-ORDERS-018: a material may only be linked to an order that requires it; otherwise the
    // link
    // binds stock to the order while staying invisible in its (requirement-driven) material view.
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            locationId,
            100,
            10.0,
            false,
            null,
            jobOrderId,
            null,
            null,
            null,
            null);

    User user = new User();
    user.setId(userId);
    Material material = new Material();
    material.setId(materialId);
    Location location = new Location();
    location.setId(locationId);
    JobOrder jobOrder = new JobOrder();
    jobOrder.setId(jobOrderId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.of(jobOrder));
    // The order requires a DIFFERENT material than the one being linked.
    when(jobOrderItemService.requiredMaterialIds(jobOrder)).thenReturn(Set.of(UUID.randomUUID()));

    assertThrows(
        BadRequestException.class,
        () -> inventoryItemService.createInventoryItem(dto, userId, false));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_shouldAllowWhenMaterialRequiredByJobOrder() {
    // REQ-ORDERS-018: linking is permitted when the order actually requires the material.
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            locationId,
            100,
            10.0,
            false,
            null,
            jobOrderId,
            null,
            null,
            null,
            null);

    User user = new User();
    user.setId(userId);
    Material material = new Material();
    material.setId(materialId);
    Location location = new Location();
    location.setId(locationId);
    JobOrder jobOrder = new JobOrder();
    jobOrder.setId(jobOrderId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderItemService.requiredMaterialIds(jobOrder)).thenReturn(Set.of(materialId));
    InventoryItem saved = new InventoryItem();
    when(inventoryItemRepository.save(any(InventoryItem.class))).thenReturn(saved);
    when(inventoryItemMapper.toDto(saved)).thenReturn(null);

    inventoryItemService.createInventoryItem(dto, userId, false);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    // Variante C (REQ-INV-027): a single-assignment create writes one job-order slice for the
    // entry's full amount, so the earmark lives on the allocation, not a vestigial entry scalar.
    assertSame(jobOrder, captor.getValue().getJobOrderAllocations().get(0).getJobOrder());
  }

  @Test
  void createInventoryItem_shouldRoundAmountToThreeDecimals() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    double inputAmount = 0.3289;

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            locationId,
            100,
            inputAmount,
            false,
            null,
            null,
            null,
            null,
            null,
            null);

    User user = new User();
    user.setId(userId);

    Material material = new Material();
    material.setId(materialId);

    Location location = new Location();
    location.setId(locationId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));

    when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    // When
    inventoryItemService.createInventoryItem(dto, userId, false);

    // Then
    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertEquals(0.329, captor.getValue().getAmount());
  }

  @Test
  void createInventoryItem_shouldThrowAccessDenied_whenCreatingForOtherUserAsNonAdmin() {
    UUID currentUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            targetUserId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            100,
            10.0,
            false,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.createInventoryItem(dto, currentUserId, false));
  }

  @Test
  void bookOutInventoryItem_shouldSubtractAmount_whenNotDepleted() {
    UUID itemId = UUID.randomUUID();
    UUID currentUserId = UUID.randomUUID();

    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            5.0, null, null, CheckoutType.DISCARD, null, null, 1L, null, null, null, null);

    InventoryItem existingItem = new InventoryItem();
    existingItem.setId(itemId);
    existingItem.setVersion(1L);
    existingItem.setAmount(10.0);
    User user = new User();
    user.setId(currentUserId);
    existingItem.setUser(user);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));

    inventoryItemService.bookOutInventoryItem(itemId, dto, currentUserId, false);

    assertEquals(5.0, existingItem.getAmount());
    verify(inventoryItemRepository).saveAndFlush(existingItem);
  }

  @Test
  void bookOutInventoryItem_shouldThrowOptimisticLockingFailure_whenVersionsMismatch() {
    UUID itemId = UUID.randomUUID();
    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            5.0, null, null, CheckoutType.DISCARD, null, null, 2L, null, null, null, null);

    InventoryItem existingItem = new InventoryItem();
    existingItem.setId(itemId);
    existingItem.setVersion(1L);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> inventoryItemService.bookOutInventoryItem(itemId, dto, UUID.randomUUID(), false));
  }

  @Test
  void bookOutInventoryItem_shouldCreateNewItem_whenTargetUserProvided() {
    UUID itemId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();

    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            5.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L, null, null, null, null);

    InventoryItem existingItem = new InventoryItem();
    existingItem.setId(itemId);
    existingItem.setVersion(1L);
    existingItem.setAmount(10.0);
    User owner = new User();
    owner.setId(UUID.randomUUID());
    existingItem.setUser(owner);

    User targetUser = new User();
    targetUser.setId(targetUserId);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
    when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
    // Realistic save: return the persisted entity (the transfer's new target row) so the post-write
    // stock-merge check receives a non-null row (it no-ops here — the row carries no material).
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    inventoryItemService.bookOutInventoryItem(itemId, dto, adminId, true);

    // Partial TRANSFER -> new target row save()d once, reduced source row saveAndFlush()ed once
    // (change #7: the source is flushed so its @Version stays current within the transaction).
    verify(inventoryItemRepository).save(any(InventoryItem.class));
    verify(inventoryItemRepository).saveAndFlush(existingItem);
    assertEquals(5.0, existingItem.getAmount());
  }

  @Test
  void bookOutInventoryItem_shouldThrowAccessDenied_whenUserIsNotOwnerAndNotAdmin() {
    UUID itemId = UUID.randomUUID();
    UUID currentUserId = UUID.randomUUID();

    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            5.0, null, null, CheckoutType.DISCARD, null, null, 1L, null, null, null, null);

    InventoryItem existingItem = new InventoryItem();
    existingItem.setId(itemId);
    existingItem.setVersion(1L);
    User user = new User();
    user.setId(UUID.randomUUID()); // different user
    existingItem.setUser(user);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));

    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.bookOutInventoryItem(itemId, dto, currentUserId, false));
  }

  @Test
  void bookOutInventoryItem_shouldDelete_whenDepleted() {
    UUID itemId = UUID.randomUUID();
    UUID currentUserId = UUID.randomUUID();

    InventoryItem existingItem = new InventoryItem();
    existingItem.setId(itemId);
    existingItem.setAmount(10.0);
    existingItem.setVersion(1L);
    User user = new User();
    user.setId(currentUserId);
    existingItem.setUser(user);

    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            10.0, null, null, CheckoutType.DISCARD, null, null, 1L, null, null, null, null);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));

    inventoryItemService.bookOutInventoryItem(itemId, dto, currentUserId, false);

    verify(inventoryItemRepository).delete(existingItem);
  }

  @Test
  void bookOutInventoryItem_shouldThrowBadRequest_whenAmountExceedsAvailable() {
    UUID itemId = UUID.randomUUID();
    UUID currentUserId = UUID.randomUUID();

    InventoryItem existingItem = new InventoryItem();
    existingItem.setId(itemId);
    existingItem.setAmount(10.0);
    existingItem.setVersion(1L);
    User user = new User();
    user.setId(currentUserId);
    existingItem.setUser(user);

    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            15.0, null, null, CheckoutType.DISCARD, null, null, 1L, null, null, null, null);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));

    assertThrows(
        BadRequestException.class,
        () -> inventoryItemService.bookOutInventoryItem(itemId, dto, currentUserId, false));
  }

  @Test
  void bookOutInventoryItem_shouldCreateFinanceEntry_whenSoldFromMission() {
    UUID itemId = UUID.randomUUID();
    UUID currentUserId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();

    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            5.0,
            null,
            null,
            CheckoutType.SELL,
            "Terminal 1",
            new java.math.BigDecimal("100.50"),
            1L,
            null,
            null,
            null,
            java.util.List.of(
                new de.greluc.krt.profit.basetool.backend.model.dto.AllocationReductionDto(
                    missionId, 5.0)));

    Mission mission = new Mission();
    mission.setId(missionId);

    Material material = new Material();
    material.setName("Test Material");

    User user = new User();
    user.setId(currentUserId);

    InventoryItem existingItem = new InventoryItem();
    existingItem.setId(itemId);
    existingItem.setVersion(1L);
    existingItem.setAmount(10.0);
    existingItem.setUser(user);
    existingItem.setMaterial(material);
    // Variante C (REQ-INV-027): the mission earmark lives on a mission allocation, not a scalar.
    // Size it at 5.0 (the post-book-out remainder) so the R5 fit check still passes after the SELL
    // consumes 5.0 — the test's intent is the mission finance entry, not over-allocation.
    InventoryAllocations.addMission(existingItem, mission, 5.0);

    MissionParticipant participant = new MissionParticipant();
    participant.setUser(user);
    participant.setMission(mission);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(existingItem));
    when(missionParticipantRepository.findByMissionIdAndUserId(missionId, currentUserId))
        .thenReturn(Optional.of(participant));

    inventoryItemService.bookOutInventoryItem(itemId, dto, currentUserId, false);

    verify(inventoryItemRepository).saveAndFlush(existingItem);
    assertEquals(5.0, existingItem.getAmount());

    verify(missionFinanceEntryRepository).save(any(MissionFinanceEntry.class));
  }

  @Test
  void updateNote_asOwner_shouldPersistNoteAndReturnDto() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(3L);
    User owner = new User();
    owner.setId(ownerId);
    item.setUser(owner);

    InventoryItemNoteUpdateRequest req =
        new InventoryItemNoteUpdateRequest("Ready for mission", 3L);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    inventoryItemService.updateNote(itemId, req, ownerId, false);

    assertEquals("Ready for mission", item.getNote());
    verify(inventoryItemRepository).saveAndFlush(item);
  }

  @Test
  void updateNote_asStranger_withoutLogistician_shouldThrowAccessDenied() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID strangerId = UUID.randomUUID();
    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    User owner = new User();
    owner.setId(ownerId);
    item.setUser(owner);

    InventoryItemNoteUpdateRequest req = new InventoryItemNoteUpdateRequest("nope", 1L);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));

    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.updateNote(itemId, req, strangerId, false));
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void updateNote_asStranger_withLogistician_shouldSucceed() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID strangerId = UUID.randomUUID();
    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    User owner = new User();
    owner.setId(ownerId);
    item.setUser(owner);

    InventoryItemNoteUpdateRequest req = new InventoryItemNoteUpdateRequest("admin hint", 1L);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    inventoryItemService.updateNote(itemId, req, strangerId, true);

    assertEquals("admin hint", item.getNote());
    verify(inventoryItemRepository).saveAndFlush(item);
  }

  @Test
  void updateNote_withVersionMismatch_shouldThrowOptimisticLockingFailure() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(5L);
    User owner = new User();
    owner.setId(ownerId);
    item.setUser(owner);

    InventoryItemNoteUpdateRequest req = new InventoryItemNoteUpdateRequest("x", 2L);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> inventoryItemService.updateNote(itemId, req, ownerId, false));
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void updateNote_withBlankNote_shouldClearNoteToNull() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setNote("existing");
    User owner = new User();
    owner.setId(ownerId);
    item.setUser(owner);

    InventoryItemNoteUpdateRequest req = new InventoryItemNoteUpdateRequest("   ", 1L);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    inventoryItemService.updateNote(itemId, req, ownerId, false);

    assertNull(item.getNote());
    verify(inventoryItemRepository).saveAndFlush(item);
  }

  // ---- getMaterialCollection ----

  @Test
  void getMaterialCollection_shouldReturnMappedEntries() {
    // Given
    UUID jobOrderId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();

    JobOrder jobOrder = new JobOrder();
    jobOrder.setId(jobOrderId);

    User user = new User();
    user.setId(userId);
    user.setUsername("testuser");
    user.setDisplayName("Test User");

    Location location = new Location();
    location.setId(locationId);
    location.setName("Port Olisar");

    Material material = new Material();
    material.setName("Laranite");

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(user);
    item.setLocation(location);
    item.setMaterial(material);
    item.setQuality(100);
    item.setAmount(5.0);
    // The order's own slice is delivered and earmarks only PART of the entry (3 of the 5 SCU): the
    // collection must read the slice (Variante C, REQ-INV-027) — delivered and the order-relevant
    // quantity live on the job-order allocation, not on the entry. quantity stays the entry total
    // (5), allocatedQuantity is the slice's 3.
    InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
    slice.setInventoryItem(item);
    slice.setJobOrder(jobOrder);
    slice.setAmount(3.0);
    slice.setDelivered(true);
    item.getJobOrderAllocations().add(slice);

    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findByJobOrderIdOrdered(jobOrderId)).thenReturn(List.of(item));

    // When
    List<de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto> result =
        inventoryItemService.getMaterialCollection(jobOrderId);

    // Then
    assertEquals(1, result.size());
    de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto dto = result.get(0);
    assertEquals(itemId, dto.inventoryEntryId());
    assertEquals(1L, dto.version());
    assertEquals("Test User", dto.ownerName());
    assertEquals(userId, dto.ownerId());
    assertEquals("Port Olisar", dto.location());
    assertEquals(locationId, dto.locationId());
    assertEquals("Laranite", dto.materialName());
    assertEquals(5.0, dto.quantity(), "quantity is the entry's total physical stock");
    assertEquals(
        3.0, dto.allocatedQuantity(), "allocatedQuantity is this order's earmarked slice, not 5");
    assertTrue(dto.delivered(), "delivered reads the order's slice, not the entry scalar");
  }

  @Test
  void getMaterialCollection_shouldThrowWhenJobOrderNotFound() {
    // Given
    UUID jobOrderId = UUID.randomUUID();
    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.empty());

    // When / Then
    assertThrows(
        NotFoundException.class, () -> inventoryItemService.getMaterialCollection(jobOrderId));
  }

  // ---- updateDelivered ----

  @Test
  void updateDelivered_shouldUpdateDeliveredFlag() {
    // Given
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    UUID orderId = UUID.randomUUID();
    User owner = new User();
    owner.setId(ownerId);

    JobOrder order = new JobOrder();
    order.setId(orderId);
    order.setDisplayId(7);

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(owner);
    // Variante A (REQ-INV-027): delivered lives on the job-order slice, not the entry.
    InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
    slice.setInventoryItem(item);
    slice.setJobOrder(order);
    slice.setAmount(3.0);
    slice.setDelivered(false);
    item.getJobOrderAllocations().add(slice);

    de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest request =
        new de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest(
            true, orderId, 1L);

    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class)))
        .thenReturn(minimalInventoryDto(itemId));

    // When
    inventoryItemService.updateDelivered(itemId, request, ownerId, false);

    // Then
    assertTrue(slice.getDelivered(), "the order's slice is delivered");
    verify(inventoryItemRepository).saveAndFlush(item);
  }

  /**
   * A minimal {@link de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto} for stubbing
   * {@code inventoryItemMapper.toDto(...)} on the force-increment write paths (delivered toggle,
   * allocation writes), which wrap the mapped DTO with the post-commit version via {@code
   * withVersion}.
   *
   * @param id the entry id to carry.
   * @return a minimal DTO (version {@code 1L}).
   */
  private static de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto
      minimalInventoryDto(UUID id) {
    return new de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto(
        id, null, null, null, null, null, null, null, null, null, null, List.of(), 0.0, List.of(),
        0.0, null, null, 1L, null);
  }

  /**
   * Pins that {@code updateDelivered} maps its response from a {@code saveAndFlush}, not a plain
   * {@code save}: the material-collection delivered checkbox syncs the returned {@code @Version}
   * onto the row in place (no reload), so a plain {@code save} would return the stale pre-flush
   * version and a second consecutive toggle of the same row would 409.
   */
  @Test
  void updateDelivered_flushesSoVersionIsFresh() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    UUID orderId = UUID.randomUUID();
    User owner = new User();
    owner.setId(ownerId);

    JobOrder order = new JobOrder();
    order.setId(orderId);
    order.setDisplayId(9);

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(owner);
    // Variante A (REQ-INV-027): delivered lives on the job-order slice, not the entry.
    InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
    slice.setInventoryItem(item);
    slice.setJobOrder(order);
    slice.setAmount(3.0);
    slice.setDelivered(false);
    item.getJobOrderAllocations().add(slice);

    de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest request =
        new de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest(
            true, orderId, 1L);

    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryItemMapper.toDto(any(InventoryItem.class)))
        .thenReturn(minimalInventoryDto(itemId));

    de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto result =
        inventoryItemService.updateDelivered(itemId, request, ownerId, false);

    verify(inventoryItemRepository).saveAndFlush(item);
    verify(inventoryItemRepository, never()).save(item);
    // OPTIMISTIC_FORCE_INCREMENT bumps the entry @Version (1) at commit, so the client must echo 2
    // on its next write to the same row — else a second consecutive delivered toggle 409s
    // (the MaterialCollectionDeliveredInPlaceE2eTest regression, REQ-INV-027).
    assertEquals(2L, result.version(), "response carries the post-commit force-increment version");
  }

  @Test
  void updateDelivered_shouldThrowOnVersionConflict() {
    // Given
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    User owner = new User();
    owner.setId(ownerId);

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(2L);
    item.setUser(owner);

    de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest request =
        new de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest(
            true, UUID.randomUUID(), 1L);

    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    // When / Then
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> inventoryItemService.updateDelivered(itemId, request, ownerId, false));
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void updateDelivered_shouldThrowAccessDeniedForNonOwnerNonLogistician() {
    // Given
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();

    User owner = new User();
    owner.setId(ownerId);

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(owner);

    de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest request =
        new de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest(
            true, UUID.randomUUID(), 1L);

    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    // When / Then
    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.updateDelivered(itemId, request, otherUserId, false));
  }

  @Test
  void deleteAllGlobalInventory_shouldDelegateToRepositoryAndReturnDeletedCount() {
    // Given: admin in "all squadrons" mode (no active scope) — adminAllScope=true wipes across.
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.deleteAllNonPersonal(true, null, java.util.Set.of()))
        .thenReturn(42);

    // When
    int removed = inventoryItemService.deleteAllGlobalInventory();

    // Then
    assertEquals(42, removed);
    verify(inventoryItemRepository).deleteAllNonPersonal(true, null, java.util.Set.of());
  }

  @Test
  void deleteAllGlobalInventory_onEmptyGlobalInventory_shouldReturnZero() {
    // Given
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.deleteAllNonPersonal(true, null, java.util.Set.of()))
        .thenReturn(0);

    // When
    int removed = inventoryItemService.deleteAllGlobalInventory();

    // Then
    assertEquals(0, removed);
    verify(inventoryItemRepository).deleteAllNonPersonal(true, null, java.util.Set.of());
  }

  @Test
  void deleteAllGlobalInventory_inFocusedMode_shouldScopeToActiveSquadron() {
    // Given: focused admin / member in squadron A — only their own stock gets wiped.
    UUID scope = UUID.randomUUID();
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, scope, java.util.Set.of()));
    when(inventoryItemRepository.deleteAllNonPersonal(false, scope, java.util.Set.of()))
        .thenReturn(7);

    int removed = inventoryItemService.deleteAllGlobalInventory();

    assertEquals(7, removed);
    verify(inventoryItemRepository).deleteAllNonPersonal(false, scope, java.util.Set.of());
  }

  // --- R5.d picker output (owningOrgUnitId) ---------------------------------
  // The membership-validation + Squadron-resolution logic itself is centralised on
  // OwnerScopeService.resolveSquadronForPickerOutput and pinned by OwnerScopeServiceTest. These two
  // tests just verify that InventoryItemService.createInventoryItem delegates to the helper and
  // honours / propagates its outcome.

  @Test
  void createInventoryItem_delegatesPickerResolutionToOwnerScopeService() {
    UUID userId = UUID.randomUUID();
    UUID pickedOrgUnitId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            100,
            10.0,
            false,
            null,
            null,
            pickedOrgUnitId,
            null,
            null,
            null);

    User user = new User();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(dto.materialId())).thenReturn(Optional.of(new Material()));
    when(locationRepository.findById(dto.locationId())).thenReturn(Optional.of(new Location()));

    Squadron resolved = new Squadron();
    resolved.setId(pickedOrgUnitId);
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, pickedOrgUnitId))
        .thenReturn(resolved);
    when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    inventoryItemService.createInventoryItem(dto, userId, true);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertSame(
        resolved,
        captor.getValue().getOwningOrgUnit(),
        "owner-picker resolution must be honoured verbatim");
  }

  @Test
  void createInventoryItem_propagatesBadRequestFromOwnerScopeService() {
    UUID userId = UUID.randomUUID();
    UUID foreignOrgUnitId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            100,
            10.0,
            false,
            null,
            null,
            foreignOrgUnitId,
            null,
            null,
            null);

    User user = new User();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(dto.materialId())).thenReturn(Optional.of(new Material()));
    when(locationRepository.findById(dto.locationId())).thenReturn(Optional.of(new Location()));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, foreignOrgUnitId))
        .thenThrow(new BadRequestException("not a membership"));

    assertThrows(
        BadRequestException.class,
        () -> inventoryItemService.createInventoryItem(dto, userId, true));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_alwaysInsertsSeparateRow() {
    // Append-only Lager: create always inserts its own brand-new row and is never folded into any
    // existing stack — not even one matching every stock-identity dimension. The saved row carries
    // exactly the DTO amount and the resolved owning org unit.
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID pickedOrgUnitId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            locationId,
            100,
            10.0,
            false,
            null,
            null,
            pickedOrgUnitId,
            null,
            null,
            null);

    User user = new User();
    user.setId(userId);
    Material material = new Material();
    material.setId(materialId);
    Location location = new Location();
    location.setId(locationId);

    Squadron orgB = new Squadron();
    orgB.setId(pickedOrgUnitId);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, pickedOrgUnitId))
        .thenReturn(orgB);
    when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    inventoryItemService.createInventoryItem(dto, userId, true);

    // A brand-new row is saved, stamped with the resolved org unit and carrying the DTO amount.
    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertSame(orgB, captor.getValue().getOwningOrgUnit());
    assertEquals(10.0, captor.getValue().getAmount(), "saved row carries the DTO amount verbatim");
  }
}

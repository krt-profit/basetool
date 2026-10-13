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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.MissionEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderEarmarkTargets;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeStockOfferLookup;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.model.dto.*;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class InventoryItemServiceTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private InventoryEarmarkLookup earmarkTargets;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private StockChangeObserver offerRatchet;
  @Mock private InventoryItemMapper inventoryItemMapper;

  @Mock private MaterialMapper materialMapper;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private InventoryAccessPolicy inventoryAccessPolicy;

  @Mock private JobOrderItemService jobOrderItemService;

  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;
  private InventoryItemService inventoryItemService;

  private InventoryAggregationService realAggregationService;
  private InventoryCheckoutService realCheckoutService;

  @BeforeEach
  void wireDelegates() {
    realAggregationService =
        new InventoryAggregationService(
            inventoryItemRepository,
            userRepository,
            materialRepository,
            gameItemRepository,
            inventoryItemMapper,
            materialMapper,
            ownerScopeService);
    realCheckoutService =
        new InventoryCheckoutService(
            inventoryItemRepository,
            userRepository,
            locationRepository,
            new de.greluc.krt.profit.basetool.backend.mission.internal.MissionSaleIncome(
                missionParticipantRepository, missionFinanceEntryRepository),
            earmarkTargets,
            new MaterialExchangeStockOfferLookup(materialExchangeOfferRepository),
            offerRatchet,
            inventoryItemMapper,
            ownerScopeService,
            auditService,
            eventPublisher);
    inventoryItemService =
        new InventoryItemService(
            inventoryItemRepository,
            userRepository,
            materialRepository,
            gameItemRepository,
            locationRepository,
            earmarkTargets,
            inventoryItemMapper,
            ownerScopeService,
            inventoryAccessPolicy,
            new InventoryStockCommands(
                null, null, null, null, null, inventoryAccessPolicy, null, null),
            new JobOrderEarmarkTargets(jobOrderRepository, jobOrderItemService),
            auditService,
            realAggregationService,
            realCheckoutService,
            new de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties(false));
  }

  @Test
  void getMissionInventory_mapsEveryItemLinkedToTheMission() {
    UUID missionId = UUID.randomUUID();
    InventoryItem a = new InventoryItem();
    InventoryItem b = new InventoryItem();
    InventoryItemDto da = mock(InventoryItemDto.class);
    InventoryItemDto db = mock(InventoryItemDto.class);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.findByMissionIdScoped(missionId, true, null, java.util.Set.of()))
        .thenReturn(List.of(a, b));
    when(inventoryItemMapper.toDto(a)).thenReturn(da);
    when(inventoryItemMapper.toDto(b)).thenReturn(db);

    List<InventoryItemDto> result = inventoryItemService.getMissionInventory(missionId);

    assertEquals(List.of(da, db), result);
    verify(inventoryItemRepository)
        .findByMissionIdScoped(missionId, true, null, java.util.Set.of());
    verify(inventoryItemRepository, never()).findByMissionId(missionId);
  }

  @Test
  void getAggregatedInventory_shouldReturnPage() {
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
    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(new User()));
    when(inventoryItemRepository.findMaterialRowsByUser(any(), any()))
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
            eq(false),
            isNull(),
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
    UUID jobOrderId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    List<UUID> jobOrderIds = List.of(jobOrderId);
    List<UUID> missionIds = List.of(missionId);

    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.findGlobalByFilters(
            eq(false),
            eq(null),
            eq(false),
            isNull(),
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

    Page<InventoryItemDto> result =
        inventoryItemService.getAllInventory(
            null, null, null, jobOrderIds, missionIds, Pageable.unpaged());

    assertNotNull(result);
    assertEquals(1, result.getTotalElements());
    verify(inventoryItemRepository)
        .findGlobalByFilters(
            eq(false),
            eq(null),
            eq(false),
            isNull(),
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
    UUID userId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    List<UUID> jobOrderIds = List.of(jobOrderId);
    List<UUID> missionIds = List.of(missionId);
    User user = new User();
    user.setId(userId);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(false),
            isNull(),
            eq(null),
            eq(true),
            eq(jobOrderIds),
            eq(true),
            eq(missionIds),
            eq(false),
            eq(false),
            eq(false),
            eq(false)))
        .thenReturn(List.of());

    List<GroupedInventoryDto> result =
        inventoryItemService.getMyAggregatedInventory(userId, jobOrderIds, missionIds);

    assertNotNull(result);
    verify(inventoryItemRepository)
        .findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(false),
            isNull(),
            eq(null),
            eq(true),
            eq(jobOrderIds),
            eq(true),
            eq(missionIds),
            eq(false),
            eq(false),
            eq(false),
            eq(false));
  }

  @Test
  void getMyAggregatedInventory_withoutFilters_shouldPassEmptyFlags() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(false),
            isNull(),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(false),
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
            eq(false),
            isNull(),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(false),
            eq(false),
            eq(false));
  }

  @Test
  void getMyAggregatedInventory_shouldPassMaterialAndMinQualityFilters() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    List<UUID> materialIds = List.of(materialId);
    Integer minQuality = 500;
    User user = new User();
    user.setId(userId);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(true),
            eq(materialIds),
            eq(false),
            isNull(),
            eq(minQuality),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(false),
            eq(false),
            eq(false)))
        .thenReturn(List.of());

    List<GroupedInventoryDto> result =
        inventoryItemService.getMyAggregatedInventory(userId, materialIds, minQuality, null, null);

    assertNotNull(result);
    verify(inventoryItemRepository)
        .findUserStacks(
            eq(userId),
            eq(true),
            eq(materialIds),
            eq(false),
            isNull(),
            eq(minQuality),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(false),
            eq(false),
            eq(false));
  }

  @Test
  void getMyAggregatedInventory_personalOnly_forwardsFlagToRepository() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(false),
            isNull(),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(true),
            eq(false),
            eq(false),
            eq(false)))
        .thenReturn(List.of());

    List<GroupedInventoryDto> result =
        inventoryItemService.getMyAggregatedInventory(
            userId, null, null, null, null, null, true, false, false, false);

    assertNotNull(result);
    verify(inventoryItemRepository)
        .findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(false),
            isNull(),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(true),
            eq(false),
            eq(false),
            eq(false));
  }

  @Test
  void getMyAggregatedInventory_nonPersonalOnly_forwardsFlagToRepository() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(inventoryItemRepository.findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(false),
            isNull(),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(true),
            eq(false),
            eq(false)))
        .thenReturn(List.of());

    List<GroupedInventoryDto> result =
        inventoryItemService.getMyAggregatedInventory(
            userId, null, null, null, null, null, false, true, false, false);

    assertNotNull(result);
    verify(inventoryItemRepository)
        .findUserStacks(
            eq(userId),
            eq(false),
            eq(null),
            eq(false),
            isNull(),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(null),
            eq(false),
            eq(true),
            eq(false),
            eq(false));
  }

  @Test
  void createInventoryItem_shouldCreateItem() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            null,
            locationId,
            100,
            10.0,
            false,
            null,
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
            null,
            false,
            false,
            java.util.List.of(),
            0.0,
            java.util.List.of(),
            0.0,
            null,
            null,
            null,
            null,
            null);
    when(inventoryItemMapper.toDto(savedItem)).thenReturn(expectedDto);

    InventoryItemDto result = inventoryItemService.createInventoryItem(dto, userId);

    assertNotNull(result);
    verify(inventoryItemRepository).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_stolenIsRefusedWhileTheMarkerIsSwitchedOff() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            null,
            locationId,
            100,
            10.0,
            false,
            true,
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
    lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    lenient().when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    lenient().when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));

    assertThrows(
        de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException.class,
        () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_stolenIsStoredWhenTheMarkerIsSwitchedOn() {
    InventoryItemService switchedOn =
        new InventoryItemService(
            inventoryItemRepository,
            userRepository,
            materialRepository,
            gameItemRepository,
            locationRepository,
            earmarkTargets,
            inventoryItemMapper,
            ownerScopeService,
            inventoryAccessPolicy,
            new InventoryStockCommands(
                null, null, null, null, null, inventoryAccessPolicy, null, null),
            new JobOrderEarmarkTargets(jobOrderRepository, jobOrderItemService),
            auditService,
            realAggregationService,
            realCheckoutService,
            new de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties(true));
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            null,
            locationId,
            100,
            10.0,
            false,
            true,
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
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    switchedOn.createInventoryItem(dto, userId);

    ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(saved.capture());
    assertEquals(Boolean.TRUE, saved.getValue().getStolen());
  }

  @Test
  void createInventoryItem_withSplitAtCheckIn_writesEachAllocation() {
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
            null,
            locationId,
            100,
            10.0,
            false,
            null,
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
    when(earmarkTargets.mission(missionXId))
        .thenReturn(
            new MissionEarmark(missionXId, missionX.getName(), missionX.getPlannedStartTime()));
    when(jobOrderItemService.requiredMaterialIds(any(JobOrder.class)))
        .thenReturn(Set.of(materialId));
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    inventoryItemService.createInventoryItem(dto, userId);

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
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID orderAId = UUID.randomUUID();
    UUID orderBId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            null,
            locationId,
            100,
            10.0,
            false,
            null,
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
        OverAllocationException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
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
            null,
            locationId,
            100,
            10.0,
            false,
            null,
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
        BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
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
            null,
            locationId,
            100,
            10.0,
            true,
            null,
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
        BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_shouldRejectWhenMaterialNotRequiredByJobOrder() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            null,
            locationId,
            100,
            10.0,
            false,
            null,
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
    when(jobOrderItemService.requiredMaterialIds(jobOrder)).thenReturn(Set.of(UUID.randomUUID()));

    assertThrows(
        BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_shouldAllowWhenMaterialRequiredByJobOrder() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            null,
            locationId,
            100,
            10.0,
            false,
            null,
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

    inventoryItemService.createInventoryItem(dto, userId);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertEquals(
        jobOrder.getId(), captor.getValue().getJobOrderAllocations().get(0).getJobOrderId());
  }

  @Test
  void createInventoryItem_shouldRoundAmountToThreeDecimals() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    double inputAmount = 0.3289;

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            null,
            locationId,
            100,
            inputAmount,
            false,
            null,
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

    inventoryItemService.createInventoryItem(dto, userId);

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
            null,
            UUID.randomUUID(),
            100,
            10.0,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.createInventoryItem(dto, currentUserId));
  }

  /**
   * Creating stock for a target outside the caller's scope is refused (REQ-SEC-005), and the scope
   * check runs before the user lookup so the endpoint does not reveal whether the user exists.
   */
  @Test
  void createInventoryItem_shouldThrowAccessDenied_whenTargetIsOutsideTheCallersScope() {
    UUID currentUserId = UUID.randomUUID();
    UUID foreignUserId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            foreignUserId,
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            100,
            10.0,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(inventoryAccessPolicy.mayBookInFor(foreignUserId)).thenReturn(false);

    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.createInventoryItem(dto, currentUserId));

    verify(inventoryAccessPolicy).mayBookInFor(foreignUserId);
    verify(userRepository, never()).findById(any());
  }

  /** A foreign member's PRIVATE pool is never an on-behalf target on this endpoint. */
  @Test
  void createInventoryItem_shouldThrowAccessDenied_whenBookingPersonalStockForAnotherUser() {
    UUID currentUserId = UUID.randomUUID();
    UUID foreignUserId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            foreignUserId,
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            100,
            10.0,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(inventoryAccessPolicy.mayBookInFor(foreignUserId)).thenReturn(true);

    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.createInventoryItem(dto, currentUserId));

    verify(userRepository, never()).findById(any());
  }

  /** The ordinary on-behalf Einbuchen - a SHARED booking, in scope - still goes through. */
  @Test
  void createInventoryItem_shouldBookSharedStockForAnotherUser_whenScopeAllows() {
    UUID currentUserId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            targetUserId,
            materialId,
            null,
            locationId,
            100,
            10.0,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    User target = new User();
    target.setId(targetUserId);
    when(inventoryAccessPolicy.mayBookInFor(targetUserId)).thenReturn(true);
    when(userRepository.findById(targetUserId)).thenReturn(Optional.of(target));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(new Material()));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(new Location()));
    when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));

    inventoryItemService.createInventoryItem(dto, currentUserId);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertSame(
        target, captor.getValue().getUser(), "the row must be booked for the requested receiver");
  }

  /**
   * Builds a well-formed game-item create payload for the fixed owner/location pair (quality {@code
   * null}, amount 5.0, non-personal).
   *
   * @param userId the target owner
   * @param gameItemId the game-item reference
   * @param locationId the storage location
   * @param missionId the single mission reference, or {@code null}
   * @param jobOrderAllocations the job-order split list, or {@code null}
   * @param missionAllocations the mission split list, or {@code null}
   * @return the assembled payload
   */
  private static InventoryItemCreateDto itemCreateDto(
      UUID userId,
      UUID gameItemId,
      UUID locationId,
      UUID missionId,
      List<InventoryAllocationInput> jobOrderAllocations,
      List<InventoryAllocationInput> missionAllocations) {
    return new InventoryItemCreateDto(
        userId,
        null,
        gameItemId,
        locationId,
        null,
        5.0,
        false,
        null,
        missionId,
        null,
        null,
        null,
        jobOrderAllocations,
        missionAllocations);
  }

  /**
   * Stubs the owner / game-item / location resolution for an item-row create and returns the
   * resolved {@link GameItem}.
   *
   * @param userId the target owner id
   * @param gameItemId the game-item id
   * @param locationId the location id
   * @return the stubbed game item
   */
  private GameItem stubItemCreateResolution(UUID userId, UUID gameItemId, UUID locationId) {
    User user = new User();
    user.setId(userId);
    GameItem gameItem = new GameItem();
    gameItem.setId(gameItemId);
    gameItem.setName("Quantum Drive");
    Location location = new Location();
    location.setId(locationId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(gameItemRepository.findById(gameItemId)).thenReturn(Optional.of(gameItem));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    return gameItem;
  }

  @Test
  void createInventoryItem_gameItemRow_resolvesGameItemAndLeavesMaterialNull() {
    UUID userId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto = itemCreateDto(userId, gameItemId, locationId, null, null, null);
    GameItem gameItem = stubItemCreateResolution(userId, gameItemId, locationId);
    when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    inventoryItemService.createInventoryItem(dto, userId);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertSame(gameItem, captor.getValue().getGameItem());
    assertNull(captor.getValue().getMaterial());
    assertNull(captor.getValue().getQuality());
    assertEquals(5.0, captor.getValue().getAmount());
    verify(materialRepository, never()).findById(any());
  }

  @Test
  void createInventoryItem_unknownGameItem_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        itemCreateDto(userId, gameItemId, UUID.randomUUID(), null, null, null);
    User user = new User();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(gameItemRepository.findById(gameItemId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_gameItemWithMissionId_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        itemCreateDto(userId, gameItemId, locationId, UUID.randomUUID(), null, null);
    stubItemCreateResolution(userId, gameItemId, locationId);

    assertThrows(
        BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_gameItemWithMissionAllocations_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        itemCreateDto(
            userId,
            gameItemId,
            locationId,
            null,
            null,
            java.util.List.of(new InventoryAllocationInput(UUID.randomUUID(), 2.0)));
    stubItemCreateResolution(userId, gameItemId, locationId);

    assertThrows(
        BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_gameItemSlice_orderNotRequestingItem_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        itemCreateDto(
            userId,
            gameItemId,
            locationId,
            null,
            java.util.List.of(new InventoryAllocationInput(jobOrderId, 2.0)),
            null);
    stubItemCreateResolution(userId, gameItemId, locationId);
    JobOrder jobOrder = new JobOrder();
    jobOrder.setId(jobOrderId);
    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.of(jobOrder));
    when(jobOrderItemService.requiredGameItemIds(jobOrder)).thenReturn(Set.of(UUID.randomUUID()));

    assertThrows(
        BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_gameItemSlice_materialOrder_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        itemCreateDto(
            userId,
            gameItemId,
            locationId,
            null,
            java.util.List.of(new InventoryAllocationInput(jobOrderId, 2.0)),
            null);
    stubItemCreateResolution(userId, gameItemId, locationId);
    JobOrder materialOrder = new JobOrder();
    materialOrder.setId(jobOrderId);
    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.of(materialOrder));
    when(jobOrderItemService.requiredGameItemIds(materialOrder)).thenReturn(Set.of());

    assertThrows(
        BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_gameItemSlice_qualifyingItemOrder_writesSlice() {
    UUID userId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        itemCreateDto(
            userId,
            gameItemId,
            locationId,
            null,
            java.util.List.of(new InventoryAllocationInput(jobOrderId, 2.0)),
            null);
    stubItemCreateResolution(userId, gameItemId, locationId);
    JobOrder itemOrder = new JobOrder();
    itemOrder.setId(jobOrderId);
    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.of(itemOrder));
    when(jobOrderItemService.requiredGameItemIds(itemOrder)).thenReturn(Set.of(gameItemId));
    when(inventoryItemRepository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(null);

    inventoryItemService.createInventoryItem(dto, userId);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertEquals(
        itemOrder.getId(), captor.getValue().getJobOrderAllocations().get(0).getJobOrderId());
    assertEquals(2.0, captor.getValue().getJobOrderAllocations().get(0).getAmount());
    verify(jobOrderItemService, never()).requiredMaterialIds(any(JobOrder.class));
  }

  @Test
  void createInventoryItem_gameItemFractionalSliceAmount_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        itemCreateDto(
            userId,
            gameItemId,
            locationId,
            null,
            java.util.List.of(new InventoryAllocationInput(UUID.randomUUID(), 2.5)),
            null);
    stubItemCreateResolution(userId, gameItemId, locationId);

    assertThrows(
        BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_bothCatalogReferences_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            gameItemId,
            locationId,
            100,
            5.0,
            false,
            null,
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
    GameItem gameItem = new GameItem();
    gameItem.setId(gameItemId);
    Location location = new Location();
    location.setId(locationId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
    when(gameItemRepository.findById(gameItemId)).thenReturn(Optional.of(gameItem));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    assertEquals("Exactly one of materialId and gameItemId must be set", ex.getMessage());
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_neitherCatalogReference_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            null,
            null,
            locationId,
            null,
            5.0,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    User user = new User();
    user.setId(userId);
    Location location = new Location();
    location.setId(locationId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    assertEquals("Exactly one of materialId and gameItemId must be set", ex.getMessage());
    verify(materialRepository, never()).findById(any());
    verify(gameItemRepository, never()).findById(any());
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_materialWithoutQuality_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            null,
            locationId,
            null,
            5.0,
            false,
            null,
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

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    assertEquals("Material stock requires a quality", ex.getMessage());
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_gameItemWithQuality_throwsBadRequest() {
    UUID userId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            null,
            gameItemId,
            locationId,
            100,
            5.0,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    stubItemCreateResolution(userId, gameItemId, locationId);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    assertEquals("Game-item stock carries no quality", ex.getMessage());
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
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
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    inventoryItemService.bookOutInventoryItem(itemId, dto, adminId, true);

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
    user.setId(UUID.randomUUID());
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
                new de.greluc.krt.profit.basetool.backend.inventory.api.AllocationReductionDto(
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
    InventoryAllocations.addMission(existingItem, mission.getId(), 5.0);

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

  @Test
  void updateDelivered_shouldUpdateDeliveredFlag() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    UUID orderId = UUID.randomUUID();
    User owner = new User();
    owner.setId(ownerId);

    JobOrder order = new JobOrder();
    order.setId(orderId);
    order.setDisplayId(7);
    when(earmarkTargets.jobOrder(order.getId())).thenReturn(new JobOrderEarmark(order.getId(), 7));

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(owner);
    InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
    slice.setInventoryItem(item);
    slice.setJobOrderId(order.getId());
    slice.setAmount(3.0);
    slice.setDelivered(false);
    item.getJobOrderAllocations().add(slice);

    de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest request =
        new de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest(
            true, orderId, 1L);

    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryItemMapper.toDto(any(InventoryItem.class)))
        .thenReturn(minimalInventoryDto(itemId));

    inventoryItemService.updateDelivered(itemId, request, ownerId, false);

    assertTrue(slice.getDelivered(), "the order's slice is delivered");
    verify(inventoryItemRepository).saveAndFlush(item);
  }

  /**
   * A minimal {@link de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto} for
   * stubbing {@code inventoryItemMapper.toDto(...)} on the force-increment write paths (delivered
   * toggle, allocation writes), which wrap the mapped DTO with the post-commit version via {@code
   * withVersion}.
   *
   * @param id the entry id to carry.
   * @return a minimal DTO (version {@code 1L}).
   */
  private static de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto
      minimalInventoryDto(UUID id) {
    return new de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto(
        id, null, null, null, null, null, null, null, false, List.of(), 0.0, List.of(), 0.0, null,
        null, 1L, null, null);
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
    when(earmarkTargets.jobOrder(order.getId())).thenReturn(new JobOrderEarmark(order.getId(), 9));

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(owner);
    InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
    slice.setInventoryItem(item);
    slice.setJobOrderId(order.getId());
    slice.setAmount(3.0);
    slice.setDelivered(false);
    item.getJobOrderAllocations().add(slice);

    de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest request =
        new de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest(
            true, orderId, 1L);

    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryItemMapper.toDto(any(InventoryItem.class)))
        .thenReturn(minimalInventoryDto(itemId));

    de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto result =
        inventoryItemService.updateDelivered(itemId, request, ownerId, false);

    verify(inventoryItemRepository).saveAndFlush(item);
    verify(inventoryItemRepository, never()).save(item);
    assertEquals(2L, result.version(), "response carries the post-commit force-increment version");
  }

  @Test
  void updateDelivered_shouldThrowOnVersionConflict() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    User owner = new User();
    owner.setId(ownerId);

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(2L);
    item.setUser(owner);

    de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest request =
        new de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest(
            true, UUID.randomUUID(), 1L);

    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> inventoryItemService.updateDelivered(itemId, request, ownerId, false));
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void updateDelivered_shouldThrowAccessDeniedForNonOwnerNonLogistician() {
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();

    User owner = new User();
    owner.setId(ownerId);

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(owner);

    de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest request =
        new de.greluc.krt.profit.basetool.backend.inventory.internal.UpdateDeliveredRequest(
            true, UUID.randomUUID(), 1L);

    when(inventoryItemRepository.findByIdForAllocationWrite(itemId)).thenReturn(Optional.of(item));

    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.updateDelivered(itemId, request, otherUserId, false));
  }

  @Test
  void deleteAllGlobalInventory_shouldDelegateToRepositoryAndReturnDeletedCount() {
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.deleteAllNonPersonal(true, null, java.util.Set.of()))
        .thenReturn(42);

    int removed = inventoryItemService.deleteAllGlobalInventory();

    assertEquals(42, removed);
    verify(inventoryItemRepository).deleteAllNonPersonal(true, null, java.util.Set.of());
  }

  @Test
  void deleteAllGlobalInventory_onEmptyGlobalInventory_shouldReturnZero() {
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, java.util.Set.of()));
    when(inventoryItemRepository.deleteAllNonPersonal(true, null, java.util.Set.of()))
        .thenReturn(0);

    int removed = inventoryItemService.deleteAllGlobalInventory();

    assertEquals(0, removed);
    verify(inventoryItemRepository).deleteAllNonPersonal(true, null, java.util.Set.of());
  }

  @Test
  void deleteAllGlobalInventory_inFocusedMode_shouldScopeToActiveSquadron() {
    UUID scope = UUID.randomUUID();
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, scope, java.util.Set.of()));
    when(inventoryItemRepository.deleteAllNonPersonal(false, scope, java.util.Set.of()))
        .thenReturn(7);

    int removed = inventoryItemService.deleteAllGlobalInventory();

    assertEquals(7, removed);
    verify(inventoryItemRepository).deleteAllNonPersonal(false, scope, java.util.Set.of());
  }

  @Test
  void createInventoryItem_delegatesPickerResolutionToOwnerScopeService() {
    UUID userId = UUID.randomUUID();
    UUID pickedOrgUnitId = UUID.randomUUID();
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            100,
            10.0,
            false,
            null,
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

    inventoryItemService.createInventoryItem(dto, userId);

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
            null,
            UUID.randomUUID(),
            100,
            10.0,
            false,
            null,
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
        BadRequestException.class, () -> inventoryItemService.createInventoryItem(dto, userId));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
  }

  @Test
  void createInventoryItem_alwaysInsertsSeparateRow() {
    UUID userId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID pickedOrgUnitId = UUID.randomUUID();

    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            userId,
            materialId,
            null,
            locationId,
            100,
            10.0,
            false,
            null,
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

    inventoryItemService.createInventoryItem(dto, userId);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertSame(orgB, captor.getValue().getOwningOrgUnit());
    assertEquals(10.0, captor.getValue().getAmount(), "saved row carries the DTO amount verbatim");
  }
}

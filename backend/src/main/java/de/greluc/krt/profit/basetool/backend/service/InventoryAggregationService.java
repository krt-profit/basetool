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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryStackDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryItemStackAggregate;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate;
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the inventory: the aggregated, grouped, flat and per-stack projections behind the
 * squadron Lager and the personal "Mein Inventar" views.
 *
 * <p>All methods are read-only and scoped through {@code OwnerScopeService}, so no read widens
 * visibility beyond the caller's org units. Material reads exclude game-item rows; each has a
 * game-item sibling ({@code *Item*}) keyed on the quality-less item stack (REQ-INV-029).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryAggregationService {

  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final JobOrderRepository jobOrderRepository;
  private final InventoryItemMapper inventoryItemMapper;
  private final MaterialMapper materialMapper;
  private final OwnerScopeService ownerScopeService;

  /**
   * Sums the caller's own stock into one SCU total per material and quality for the blueprint
   * craftability calculation. Owner-scoped only, including personal and shared rows.
   *
   * @param userId the owning user; never {@code null}
   * @return one slice per (material, quality) the user owns, with the summed SCU; never {@code
   *     null}
   */
  public List<OwnedStockSlice> getOwnedStockSlices(@NotNull UUID userId) {
    return inventoryItemRepository.sumOwnedStockByMaterialAndQuality(userId);
  }

  /**
   * Aggregated per-material inventory view — used by the squadron-wide inventory page.
   *
   * @param pageable page request
   * @return paged aggregated DTOs (material + total amount + average quality)
   */
  public Page<AggregatedInventoryDto> getAggregatedInventory(Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .getAggregatedInventory(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds(), pageable)
        .map(
            obj ->
                new AggregatedInventoryDto(
                    materialMapper.toDto((Material) obj[0]),
                    null,
                    obj[1] != null
                        ? Math.round(((Number) obj[1]).doubleValue() * 100.0) / 100.0
                        : 0.0,
                    obj[2] != null ? ((Number) obj[2]).doubleValue() : 0.0,
                    obj[3] != null ? ((Number) obj[3]).doubleValue() : 0.0));
  }

  /**
   * Game-item sibling of {@link #getAggregatedInventory(Pageable)}: one row per game item with the
   * summed non-personal amount in the caller's scope; quality columns are {@code null}.
   *
   * @param pageable page request (whitelisted {@code gameItem.name} / {@code amount} sort)
   * @return paged aggregated DTOs carrying the game-item reference and the total amount
   */
  public Page<AggregatedInventoryDto> getAggregatedItemInventory(Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .getAggregatedItemInventory(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds(), pageable)
        .map(
            obj ->
                new AggregatedInventoryDto(
                    null,
                    inventoryItemMapper.gameItemToReferenceDto((GameItem) obj[0]),
                    null,
                    null,
                    obj[1] != null ? ((Number) obj[1]).doubleValue() : 0.0));
  }

  /**
   * Lists every non-personal inventory row of the given material in the caller's scope.
   *
   * @param materialId material to drill into
   * @param pageable page request
   * @return paged inventory items (excludes personal items)
   * @throws NotFoundException when the material id is unknown
   */
  public Page<InventoryItemDto> getInventoryByMaterial(UUID materialId, Pageable pageable) {
    Material material =
        Entities.require(materialRepository.findById(materialId), "Material not found");
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findByMaterialAndPersonalFalseScoped(
            material,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Game-item sibling of {@link #getInventoryByMaterial(UUID, Pageable)}: every non-personal stock
   * row of one game item in the caller's scope.
   *
   * @param gameItemId game item to drill into
   * @param pageable page request
   * @return paged inventory rows stocking that game item (excludes personal rows)
   * @throws NotFoundException when the game-item id is unknown
   */
  public Page<InventoryItemDto> getInventoryByGameItem(UUID gameItemId, Pageable pageable) {
    GameItem gameItem =
        Entities.require(gameItemRepository.findById(gameItemId), "Game item not found");
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findByGameItemAndPersonalFalseScoped(
            gameItem,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Lists the user's material inventory rows, excluding personal rows and game-item rows.
   *
   * @param userId owner id
   * @param pageable page request
   * @return paged material inventory rows owned by the user
   */
  public Page<InventoryItemDto> getUserInventory(UUID userId, Pageable pageable) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    return inventoryItemRepository
        .findMaterialRowsByUser(user, pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Game-item sibling of {@link #getUserInventory(UUID, Pageable)}: the game-item stock rows owned
   * by the user.
   *
   * @param userId owner id
   * @param pageable page request (whitelisted {@code gameItem.name} / {@code amount} sort)
   * @return paged game-item inventory rows owned by the user
   * @throws NotFoundException when the user id is unknown
   */
  public Page<InventoryItemDto> getUserItemInventory(UUID userId, Pageable pageable) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    return inventoryItemRepository
        .findItemRowsByUser(user, pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Unfiltered convenience overload for {@link #getMyAggregatedInventory(UUID, List, Integer, List,
   * List)}.
   *
   * @param userId owner id
   * @return aggregated items grouped by material
   */
  public List<GroupedInventoryDto> getMyAggregatedInventory(UUID userId) {
    return getMyAggregatedInventory(userId, null, null, null, null);
  }

  /**
   * Aggregates the user's inventory filtered only by job orders and missions.
   *
   * @param userId owner id
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @return aggregated items
   */
  public List<GroupedInventoryDto> getMyAggregatedInventory(
      UUID userId, List<UUID> jobOrderIds, List<UUID> missionIds) {
    return getMyAggregatedInventory(userId, null, null, jobOrderIds, missionIds);
  }

  /**
   * Overload of {@link #getMyAggregatedInventory(UUID, List, List, Integer, List, List, boolean,
   * boolean)} without location filter and without personal/shared narrowing.
   *
   * @param userId owner id
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @return aggregated items
   * @throws NotFoundException when the user id is unknown
   */
  public List<GroupedInventoryDto> getMyAggregatedInventory(
      UUID userId,
      List<UUID> materialIds,
      Integer minQuality,
      List<UUID> jobOrderIds,
      List<UUID> missionIds) {
    return getMyAggregatedInventory(
        userId, materialIds, null, minQuality, jobOrderIds, missionIds, false, false);
  }

  /**
   * Aggregates the user's material stock into the Material to Stack shape of the {@code /grouped}
   * view, applying all filters.
   *
   * @param userId owner id
   * @param materialIds optional material filter
   * @param locationIds optional storage-location filter; empty or {@code null} means every location
   *     (REQ-INV-040)
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @param personalOnly {@code true} to return only personal rows
   * @param nonPersonalOnly {@code true} to return only shared rows; mutually exclusive with {@code
   *     personalOnly}, both {@code false} returns both
   * @return aggregated items
   * @throws NotFoundException when the user id is unknown
   */
  public List<GroupedInventoryDto> getMyAggregatedInventory(
      UUID userId,
      List<UUID> materialIds,
      List<UUID> locationIds,
      Integer minQuality,
      List<UUID> jobOrderIds,
      List<UUID> missionIds,
      boolean personalOnly,
      boolean nonPersonalOnly) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    boolean hasMaterials = materialIds != null && !materialIds.isEmpty();
    boolean hasLocations = locationIds != null && !locationIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    boolean hasMissions = missionIds != null && !missionIds.isEmpty();
    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findUserStacks(
            user.getId(),
            hasMaterials,
            hasMaterials ? materialIds : null,
            hasLocations,
            hasLocations ? locationIds : null,
            minQuality,
            hasJobOrders,
            hasJobOrders ? jobOrderIds : null,
            hasMissions,
            hasMissions ? missionIds : null,
            personalOnly,
            nonPersonalOnly);

    return buildGroupedFromStacks(stacks);
  }

  /**
   * Overload of {@link #getAllAggregatedInventory(List, List, Integer, List, List)} without
   * location, job-order and mission filters.
   *
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @return aggregated squadron-wide items
   */
  public List<GroupedInventoryDto> getAllAggregatedInventory(
      List<UUID> materialIds, Integer minQuality) {
    return getAllAggregatedInventory(materialIds, null, minQuality, null, null);
  }

  /**
   * Aggregates the material stock of all users in the caller's scope into the {@code /grouped}
   * shape.
   *
   * @param materialIds optional material filter
   * @param locationIds optional storage-location filter; empty or {@code null} means every location
   *     (REQ-INV-040)
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @return aggregated items grouped by material
   */
  public List<GroupedInventoryDto> getAllAggregatedInventory(
      List<UUID> materialIds,
      List<UUID> locationIds,
      Integer minQuality,
      List<UUID> jobOrderIds,
      List<UUID> missionIds) {
    boolean hasMaterials = materialIds != null && !materialIds.isEmpty();
    boolean hasLocations = locationIds != null && !locationIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    boolean hasMissions = missionIds != null && !missionIds.isEmpty();
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findGlobalStacks(
            hasMaterials,
            hasMaterials ? materialIds : null,
            hasLocations,
            hasLocations ? locationIds : null,
            minQuality,
            hasJobOrders,
            hasJobOrders ? jobOrderIds : null,
            hasMissions,
            hasMissions ? missionIds : null,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds());

    return buildGroupedFromStacks(stacks);
  }

  /**
   * Game-item sibling of {@link #getMyAggregatedInventory(UUID, List, List, Integer, List, List,
   * boolean, boolean)}: the user's game-item stock grouped by item and stack. Items have no quality
   * or mission filter (REQ-INV-031).
   *
   * @param userId owner id
   * @param gameItemIds optional game-item filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param jobOrderIds optional job-order filter
   * @param personalOnly when {@code true}, narrows to the caller's private stock rows
   * @param nonPersonalOnly when {@code true}, narrows to the caller's shared stock rows
   * @return item groups, each carrying its sorted stacks and item-wide total
   * @throws NotFoundException when the user id is unknown
   */
  public List<GroupedInventoryDto> getMyAggregatedItemInventory(
      UUID userId,
      List<UUID> gameItemIds,
      List<UUID> locationIds,
      List<UUID> jobOrderIds,
      boolean personalOnly,
      boolean nonPersonalOnly) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    boolean hasGameItems = gameItemIds != null && !gameItemIds.isEmpty();
    boolean hasLocations = locationIds != null && !locationIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    List<InventoryItemStackAggregate> stacks =
        inventoryItemRepository.findUserItemStacks(
            user.getId(),
            hasGameItems,
            hasGameItems ? gameItemIds : null,
            hasLocations,
            hasLocations ? locationIds : null,
            hasJobOrders,
            hasJobOrders ? jobOrderIds : null,
            personalOnly,
            nonPersonalOnly);
    return buildGroupedFromItemStacks(stacks);
  }

  /**
   * Returns the ids of every material entry the user owns that matches the "Mein Lager" filters,
   * across all stacks, for select-all (REQ-INV-034). Uses the same filter contract as the grouped
   * view.
   *
   * @param userId owner id
   * @param materialIds optional material filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @param personalOnly when {@code true}, narrows to the caller's private stock rows
   * @param nonPersonalOnly when {@code true}, narrows to the caller's shared stock rows
   * @return the ids of every matching material entry, in creation order; never {@code null}
   * @throws NotFoundException when the user id is unknown
   */
  public List<UUID> getMyEntryIds(
      UUID userId,
      List<UUID> materialIds,
      List<UUID> locationIds,
      Integer minQuality,
      List<UUID> jobOrderIds,
      List<UUID> missionIds,
      boolean personalOnly,
      boolean nonPersonalOnly) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    boolean hasMaterials = materialIds != null && !materialIds.isEmpty();
    boolean hasLocations = locationIds != null && !locationIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    boolean hasMissions = missionIds != null && !missionIds.isEmpty();
    return inventoryItemRepository.findUserEntryIds(
        user.getId(),
        hasMaterials,
        hasMaterials ? materialIds : null,
        hasLocations,
        hasLocations ? locationIds : null,
        minQuality,
        hasJobOrders,
        hasJobOrders ? jobOrderIds : null,
        hasMissions,
        hasMissions ? missionIds : null,
        personalOnly,
        nonPersonalOnly);
  }

  /**
   * Game-item sibling of {@link #getMyEntryIds}: the ids of every game-item entry the user owns
   * that matches the item filters (REQ-INV-034).
   *
   * @param userId owner id
   * @param gameItemIds optional game-item filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param jobOrderIds optional job-order filter
   * @param personalOnly when {@code true}, narrows to the caller's private stock rows
   * @param nonPersonalOnly when {@code true}, narrows to the caller's shared stock rows
   * @return the ids of every matching game-item entry, in creation order; never {@code null}
   * @throws NotFoundException when the user id is unknown
   */
  public List<UUID> getMyItemEntryIds(
      UUID userId,
      List<UUID> gameItemIds,
      List<UUID> locationIds,
      List<UUID> jobOrderIds,
      boolean personalOnly,
      boolean nonPersonalOnly) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    boolean hasGameItems = gameItemIds != null && !gameItemIds.isEmpty();
    boolean hasLocations = locationIds != null && !locationIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    return inventoryItemRepository.findUserItemEntryIds(
        user.getId(),
        hasGameItems,
        hasGameItems ? gameItemIds : null,
        hasLocations,
        hasLocations ? locationIds : null,
        hasJobOrders,
        hasJobOrders ? jobOrderIds : null,
        personalOnly,
        nonPersonalOnly);
  }

  /**
   * Game-item sibling of {@link #getAllAggregatedInventory(List, List, Integer, List, List)}: the
   * scoped game-item stock grouped by item and stack. No quality or mission filter (REQ-INV-031).
   *
   * @param gameItemIds optional game-item filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param jobOrderIds optional job-order filter
   * @return item groups, each carrying its sorted stacks and item-wide total
   */
  public List<GroupedInventoryDto> getAllAggregatedItemInventory(
      List<UUID> gameItemIds, List<UUID> locationIds, List<UUID> jobOrderIds) {
    boolean hasGameItems = gameItemIds != null && !gameItemIds.isEmpty();
    boolean hasLocations = locationIds != null && !locationIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    List<InventoryItemStackAggregate> stacks =
        inventoryItemRepository.findGlobalItemStacks(
            hasGameItems,
            hasGameItems ? gameItemIds : null,
            hasLocations,
            hasLocations ? locationIds : null,
            hasJobOrders,
            hasJobOrders ? jobOrderIds : null,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds());
    return buildGroupedFromItemStacks(stacks);
  }

  /**
   * Groups SQL-computed per-stack aggregates by material into the {@code /grouped} shape. Entries
   * are not included; they load lazily per stack (ADR-0003).
   *
   * @param aggregates the SQL-grouped per-stack rows for the current scope/filter
   * @return the materials, each carrying its sorted stacks and material-wide totals
   */
  private List<GroupedInventoryDto> buildGroupedFromStacks(
      @NotNull List<InventoryStackAggregate> aggregates) {
    return aggregates.stream()
        .collect(
            Collectors.groupingBy(
                aggregate -> aggregate.material().getId(), LinkedHashMap::new, Collectors.toList()))
        .values()
        .stream()
        .map(this::buildMaterialGroup)
        .sorted(Comparator.comparing(g -> g.material().name()))
        .toList();
  }

  /**
   * Builds one material group from its stacks: sorted stacks plus summed amount, amount-weighted
   * mean quality and max quality, computed from the raw database sums.
   *
   * @param matStacks every per-stack aggregate of one material in the current scope; never empty
   * @return the populated material group with its nested stacks
   */
  @NotNull
  private GroupedInventoryDto buildMaterialGroup(@NotNull List<InventoryStackAggregate> matStacks) {
    List<InventoryStackDto> stacks = new ArrayList<>(matStacks.size());
    MaterialReferenceDto material = null;
    double totalAmount = 0.0;
    double weightedQualitySum = 0.0;
    int maxQuality = 0;
    for (InventoryStackAggregate aggregate : matStacks) {
      InventoryItemDto refs = mapAggregateRefs(aggregate);
      if (material == null) {
        material = refs.material();
      }
      double amt = aggregate.totalAmount() != null ? aggregate.totalAmount() : 0.0;
      double wqs = aggregate.weightedQualitySum() != null ? aggregate.weightedQualitySum() : 0.0;
      int mq = aggregate.maxQuality() != null ? aggregate.maxQuality() : 0;
      double stackAvg = amt > 0 ? Math.round((wqs / amt) * 100.0) / 100.0 : 0.0;
      stacks.add(
          new InventoryStackDto(
              refs.user(),
              refs.location(),
              refs.quality(),
              refs.personal(),
              refs.owningSquadron(),
              amt,
              stackAvg,
              mq,
              aggregate.entryCount() != null ? aggregate.entryCount().intValue() : 0));
      totalAmount += amt;
      weightedQualitySum += wqs;
      if (mq > maxQuality) {
        maxQuality = mq;
      }
    }
    stacks.sort(STACK_ORDER);
    double avgQuality =
        totalAmount > 0 ? Math.round((weightedQualitySum / totalAmount) * 100.0) / 100.0 : 0.0;
    return new GroupedInventoryDto(material, null, totalAmount, avgQuality, maxQuality, stacks);
  }

  /**
   * Game-item sibling of {@link #buildGroupedFromStacks(List)}: groups per-stack item aggregates by
   * game item, sorted by item name.
   *
   * @param aggregates the SQL-grouped per-item-stack rows for the current scope/filter
   * @return the game-item groups, each carrying its sorted stacks and item-wide total
   */
  private List<GroupedInventoryDto> buildGroupedFromItemStacks(
      @NotNull List<InventoryItemStackAggregate> aggregates) {
    return aggregates.stream()
        .collect(
            Collectors.groupingBy(
                aggregate -> aggregate.gameItem().getId(), LinkedHashMap::new, Collectors.toList()))
        .values()
        .stream()
        .map(this::buildItemGroup)
        .sorted(Comparator.comparing(g -> g.gameItem().name()))
        .toList();
  }

  /**
   * Builds one game-item group from its stacks: stacks sorted by {@link #STACK_ORDER} plus the
   * summed amount; quality figures stay {@code null}.
   *
   * @param itemStacks every per-stack aggregate of one game item in the current scope; never empty
   * @return the populated game-item group with its nested stacks
   */
  @NotNull
  private GroupedInventoryDto buildItemGroup(
      @NotNull List<InventoryItemStackAggregate> itemStacks) {
    List<InventoryStackDto> stacks = new ArrayList<>(itemStacks.size());
    InventoryGameItemReferenceDto gameItem = null;
    double totalAmount = 0.0;
    for (InventoryItemStackAggregate aggregate : itemStacks) {
      InventoryItemDto refs = mapItemAggregateRefs(aggregate);
      if (gameItem == null) {
        gameItem = refs.gameItem();
      }
      double amt = aggregate.totalAmount() != null ? aggregate.totalAmount() : 0.0;
      stacks.add(
          new InventoryStackDto(
              refs.user(),
              refs.location(),
              null,
              refs.personal(),
              refs.owningSquadron(),
              amt,
              null,
              null,
              aggregate.entryCount() != null ? aggregate.entryCount().intValue() : 0));
      totalAmount += amt;
    }
    stacks.sort(STACK_ORDER);
    return new GroupedInventoryDto(null, gameItem, totalAmount, null, null, stacks);
  }

  /**
   * Game-item counterpart of {@link #mapAggregateRefs(InventoryStackAggregate)}: maps an item
   * stack's identity through a transient probe {@link InventoryItem} so redaction behaves as for a
   * real entry.
   *
   * @param aggregate the per-stack item aggregate whose shared identity to project
   * @return an inventory-item DTO carrying only the mapped reference fields (amount/version/id
   *     null)
   */
  private InventoryItemDto mapItemAggregateRefs(InventoryItemStackAggregate aggregate) {
    InventoryItem probe = new InventoryItem();
    probe.setUser(aggregate.user());
    probe.setGameItem(aggregate.gameItem());
    probe.setLocation(aggregate.location());
    probe.setPersonal(aggregate.personal());
    probe.setOwningOrgUnit(aggregate.owningOrgUnit());
    return inventoryItemMapper.toDto(probe);
  }

  /**
   * Maps a stack's shared identity through the inventory-item mapper using a transient, never
   * persisted probe {@link InventoryItem}, so PII redaction and the org-unit projection behave as
   * for a real entry.
   *
   * @param aggregate the per-stack aggregate whose shared identity to project
   * @return an inventory-item DTO carrying only the mapped reference fields (amount/version/id
   *     null)
   */
  private InventoryItemDto mapAggregateRefs(InventoryStackAggregate aggregate) {
    InventoryItem probe = new InventoryItem();
    probe.setUser(aggregate.user());
    probe.setMaterial(aggregate.material());
    probe.setLocation(aggregate.location());
    probe.setQuality(aggregate.quality());
    probe.setPersonal(aggregate.personal());
    probe.setOwningOrgUnit(aggregate.owningOrgUnit());
    return inventoryItemMapper.toDto(probe);
  }

  /**
   * Returns one page of the entries of one of the caller's own stacks, oldest first. A {@code null}
   * owning-org-unit argument matches rows without one; personal and shared stacks are distinct.
   *
   * @param userId the calling owner whose stack to drill into
   * @param materialId the stack's material
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param personal whether the stack is private stock ({@code null} means {@code false})
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request; ordering is fixed to oldest first
   * @return one page of the stack's entries, oldest-first
   */
  public Page<InventoryItemDto> getMyStackEntries(
      UUID userId,
      UUID materialId,
      UUID locationId,
      Integer quality,
      Boolean personal,
      UUID owningOrgUnitId,
      Pageable pageable) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    return inventoryItemRepository
        .findUserStackEntries(
            user.getId(),
            materialId,
            locationId,
            quality,
            personal != null ? personal : Boolean.FALSE,
            owningOrgUnitId,
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Returns one page of the entries of a stack in the squadron Lager, oldest first, under the same
   * scope as the grouped view. A {@code null} owning-org-unit argument matches rows without one.
   *
   * @param materialId the stack's material
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request; ordering is fixed to oldest first
   * @return one page of the stack's entries, oldest-first
   */
  public Page<InventoryItemDto> getAllStackEntries(
      UUID materialId,
      UUID userId,
      UUID locationId,
      Integer quality,
      UUID owningOrgUnitId,
      Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalStackEntries(
            materialId,
            userId,
            locationId,
            quality,
            owningOrgUnitId,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Game-item sibling of {@link #getMyStackEntries(UUID, UUID, UUID, Integer, Boolean, UUID,
   * Pageable)}, addressed by game item without a quality key.
   *
   * @param userId the calling owner whose stack to drill into
   * @param gameItemId the stack's game item
   * @param locationId the stack's storage location
   * @param personal whether the stack is private stock ({@code null} means {@code false})
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request; ordering is fixed to oldest first
   * @return one page of the stack's entries, oldest-first
   * @throws NotFoundException when the user id is unknown
   */
  public Page<InventoryItemDto> getMyItemStackEntries(
      UUID userId,
      UUID gameItemId,
      UUID locationId,
      Boolean personal,
      UUID owningOrgUnitId,
      Pageable pageable) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    return inventoryItemRepository
        .findUserItemStackEntries(
            user.getId(),
            gameItemId,
            locationId,
            personal != null ? personal : Boolean.FALSE,
            owningOrgUnitId,
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Game-item sibling of {@link #getAllStackEntries(UUID, UUID, UUID, Integer, UUID, Pageable)},
   * addressed by game item without a quality key and under the same scope.
   *
   * @param gameItemId the stack's game item
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request; ordering is fixed to oldest first
   * @return one page of the stack's entries, oldest-first
   */
  public Page<InventoryItemDto> getAllItemStackEntries(
      UUID gameItemId, UUID userId, UUID locationId, UUID owningOrgUnitId, Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalItemStackEntries(
            gameItemId,
            userId,
            locationId,
            owningOrgUnitId,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Flat scoped inventory list filtered only by material and minimum quality.
   *
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @param pageable page request
   * @return paged inventory items
   */
  public Page<InventoryItemDto> getAllInventory(
      List<UUID> materialIds, Integer minQuality, Pageable pageable) {
    return getAllInventory(materialIds, null, minQuality, null, null, pageable);
  }

  /**
   * Flat paged inventory list in the caller's scope, one row per {@code InventoryItem}.
   *
   * @param materialIds optional material filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @param pageable page request
   * @return paged inventory items
   */
  public Page<InventoryItemDto> getAllInventory(
      List<UUID> materialIds,
      List<UUID> locationIds,
      Integer minQuality,
      List<UUID> jobOrderIds,
      List<UUID> missionIds,
      Pageable pageable) {
    boolean hasMaterials = materialIds != null && !materialIds.isEmpty();
    boolean hasLocations = locationIds != null && !locationIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    boolean hasMissions = missionIds != null && !missionIds.isEmpty();
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalByFilters(
            hasMaterials,
            hasMaterials ? materialIds : null,
            hasLocations,
            hasLocations ? locationIds : null,
            minQuality,
            hasJobOrders,
            hasJobOrders ? jobOrderIds : null,
            hasMissions,
            hasMissions ? missionIds : null,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Game-item sibling of {@link #getAllInventory(List, List, Integer, List, List, Pageable)}: the
   * flat scoped list of game-item rows. No quality or mission filter (REQ-INV-031).
   *
   * @param gameItemIds optional game-item filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param jobOrderIds optional job-order filter
   * @param pageable page request (whitelisted {@code gameItem.name} / {@code amount} sort)
   * @return paged game-item inventory rows
   */
  public Page<InventoryItemDto> getAllItemInventory(
      List<UUID> gameItemIds, List<UUID> locationIds, List<UUID> jobOrderIds, Pageable pageable) {
    boolean hasGameItems = gameItemIds != null && !gameItemIds.isEmpty();
    boolean hasLocations = locationIds != null && !locationIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalItemsByFilters(
            hasGameItems,
            hasGameItems ? gameItemIds : null,
            hasLocations,
            hasLocations ? locationIds : null,
            hasJobOrders,
            hasJobOrders ? jobOrderIds : null,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Returns all inventory items linked to the given job order, sorted server-side by owner name,
   * location, material name, quality (desc), quantity (desc).
   *
   * @param jobOrderId the UUID of the job order
   * @return sorted list of {@link MaterialCollectionEntryDto}
   * @throws NotFoundException when the job order is unknown
   */
  public List<MaterialCollectionEntryDto> getMaterialCollection(UUID jobOrderId) {
    Entities.require(jobOrderRepository.findById(jobOrderId), "Job order not found");
    return inventoryItemRepository.findByJobOrderIdOrdered(jobOrderId).stream()
        .map(
            item -> {
              String ownerName =
                  item.getUser().getDisplayName() != null
                      ? item.getUser().getDisplayName()
                      : item.getUser().getUsername();
              Optional<InventoryJobOrderAllocation> slice =
                  item.getJobOrderAllocations().stream()
                      .filter(
                          a ->
                              a.getJobOrder() != null && jobOrderId.equals(a.getJobOrder().getId()))
                      .findFirst();
              boolean delivered =
                  slice.map(a -> Boolean.TRUE.equals(a.getDelivered())).orElse(false);
              Double allocatedQuantity = slice.map(a -> a.getAmount()).orElse(item.getAmount());
              return new MaterialCollectionEntryDto(
                  item.getId(),
                  item.getVersion() != null ? item.getVersion() : 0L,
                  ownerName,
                  item.getUser().getId(),
                  item.getLocation().getName(),
                  item.getLocation().getId(),
                  item.getMaterial().getName(),
                  item.getQuality() != null ? item.getQuality().doubleValue() : null,
                  item.getAmount(),
                  allocatedQuantity,
                  delivered);
            })
        .toList();
  }

  /**
   * Returns the game-item stock earmarked to the given job order, grouped per game item and sorted
   * by name (REQ-ORDERS-028). Each group carries the ordered and manufactured amounts of the
   * order's matching item lines, {@code 0} when the order no longer requests that item.
   *
   * @param jobOrderId the UUID of the job order
   * @return name-sorted list of {@link JobOrderItemStockGroupDto}; empty when no game-item stock is
   *     earmarked to the order
   * @throws NotFoundException when the job order is unknown
   */
  @NotNull
  public List<JobOrderItemStockGroupDto> getItemStockForJobOrder(UUID jobOrderId) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(jobOrderId), "Job order not found");

    Map<UUID, int[]> lineTotals = new HashMap<>();
    for (JobOrderItem line : jobOrder.getItems()) {
      if (line.getGameItem() == null) {
        continue;
      }
      int[] totals = lineTotals.computeIfAbsent(line.getGameItem().getId(), k -> new int[2]);
      totals[0] += line.getAmount() != null ? line.getAmount() : 0;
      totals[1] += line.getManufacturedAmount() != null ? line.getManufacturedAmount() : 0;
    }

    Map<UUID, List<InventoryItem>> byGameItem = new LinkedHashMap<>();
    for (InventoryItem row :
        inventoryItemRepository.findGameItemRowsByJobOrderIdOrdered(jobOrderId)) {
      byGameItem.computeIfAbsent(row.getGameItem().getId(), k -> new ArrayList<>()).add(row);
    }

    List<JobOrderItemStockGroupDto> groups = new ArrayList<>();
    byGameItem.forEach(
        (gameItemId, rows) -> {
          GameItem gameItem = rows.getFirst().getGameItem();
          List<JobOrderItemStockEntryDto> entries = new ArrayList<>();
          long allocatedTotal = 0L;
          for (InventoryItem row : rows) {
            String ownerName =
                row.getUser().getDisplayName() != null
                    ? row.getUser().getDisplayName()
                    : row.getUser().getUsername();
            Optional<InventoryJobOrderAllocation> slice =
                row.getJobOrderAllocations().stream()
                    .filter(
                        a -> a.getJobOrder() != null && jobOrderId.equals(a.getJobOrder().getId()))
                    .findFirst();
            long allocatedQuantity =
                Math.round(
                    slice.map(InventoryJobOrderAllocation::getAmount).orElse(row.getAmount()));
            allocatedTotal += allocatedQuantity;
            entries.add(
                new JobOrderItemStockEntryDto(
                    row.getId(),
                    row.getVersion() != null ? row.getVersion() : 0L,
                    ownerName,
                    row.getUser().getId(),
                    row.getLocation().getName(),
                    row.getLocation().getId(),
                    Math.round(row.getAmount()),
                    allocatedQuantity,
                    slice.map(a -> Boolean.TRUE.equals(a.getDelivered())).orElse(false)));
          }
          int[] totals = lineTotals.getOrDefault(gameItemId, new int[2]);
          groups.add(
              new JobOrderItemStockGroupDto(
                  inventoryItemMapper.gameItemToReferenceDto(gameItem),
                  totals[0],
                  totals[1],
                  allocatedTotal,
                  entries));
        });
    groups.sort(
        Comparator.<JobOrderItemStockGroupDto, String>comparing(
                g -> g.gameItem() != null && g.gameItem().name() != null ? g.gameItem().name() : "",
                String.CASE_INSENSITIVE_ORDER)
            .thenComparing(g -> g.gameItem() != null ? String.valueOf(g.gameItem().id()) : ""));
    return groups;
  }

  /**
   * Display order of stacks within a group: highest quality first, then location name ascending,
   * then largest amount first.
   */
  private static final Comparator<InventoryStackDto> STACK_ORDER =
      Comparator.<InventoryStackDto, Integer>comparing(s -> s.quality() != null ? s.quality() : 0)
          .reversed()
          .thenComparing(
              s -> s.location() != null && s.location().name() != null ? s.location().name() : "")
          .thenComparing(
              Comparator.<InventoryStackDto, Double>comparing(
                      s -> s.totalAmount() != null ? s.totalAmount() : 0.0)
                  .reversed());
}

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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
  private final InventoryItemMapper inventoryItemMapper;
  private final MaterialMapper materialMapper;
  private final OwnerScopeService ownerScopeService;
  private final InventoryItemViewRepository inventoryItemViewRepository;
  private final StockLevelService stockLevelService;

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
    LowStockSubjects low = stockLevelService.lowSubjects();
    return inventoryItemRepository
        .getAggregatedInventory(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds(), pageable)
        .map(obj -> {
          Material material = (Material) obj[0];
          return new AggregatedInventoryDto(
              materialMapper.toDto(material),
              null,
              obj[1] != null ? Math.round(((Number) obj[1]).doubleValue() * 100.0) / 100.0 : 0.0,
              obj[2] != null ? ((Number) obj[2]).doubleValue() : 0.0,
              obj[3] != null ? ((Number) obj[3]).doubleValue() : 0.0,
              low.lowMaterial(material.getId()));
        });
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
    LowStockSubjects low = stockLevelService.lowSubjects();
    return inventoryItemRepository
        .getAggregatedItemInventory(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds(), pageable)
        .map(obj ->
            new AggregatedInventoryDto(
                null,
                inventoryItemMapper.gameItemToReferenceDto((GameItem) obj[0]),
                null,
                null,
                obj[1] != null ? ((Number) obj[1]).doubleValue() : 0.0,
                low.lowGameItem(((GameItem) obj[0]).getId())));
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
   * Unfiltered convenience overload for {@link #getMyAggregatedInventory(UUID, LagerFilter)}.
   *
   * @param userId owner id
   * @return aggregated items grouped by material
   */
  public List<GroupedInventoryDto> getMyAggregatedInventory(UUID userId) {
    return getMyAggregatedInventory(userId, LagerFilter.NONE);
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
      UUID userId, @Nullable List<UUID> jobOrderIds, @Nullable List<UUID> missionIds) {
    return getMyAggregatedInventory(
        userId, LagerFilter.builder().jobOrderIds(jobOrderIds).missionIds(missionIds).build());
  }

  /**
   * Aggregates the user's inventory filtered by material, quality, job order and mission only.
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
      @Nullable List<UUID> materialIds,
      @Nullable Integer minQuality,
      @Nullable List<UUID> jobOrderIds,
      @Nullable List<UUID> missionIds) {
    return getMyAggregatedInventory(
        userId,
        LagerFilter.builder()
            .materialIds(materialIds)
            .minQuality(minQuality)
            .jobOrderIds(jobOrderIds)
            .missionIds(missionIds)
            .build());
  }

  /**
   * Aggregates the user's material stock into the Material to Stack shape of the {@code /grouped}
   * view, applying the material, location, quality, job-order, mission, personal and stolen filters
   * of {@code filter} (REQ-INV-040, REQ-INV-053).
   *
   * @param userId owner id
   * @param filter the Lager filters; a contradictory flag pair answers empty
   * @return aggregated items
   * @throws NotFoundException when the user id is unknown
   */
  public List<GroupedInventoryDto> getMyAggregatedInventory(
      UUID userId, @NotNull LagerFilter filter) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    if (filter.contradictory()) {
      return List.of();
    }
    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findUserStacks(
            user.getId(),
            filter.hasMaterials(),
            filter.materialIdsOrNull(),
            filter.hasLocations(),
            filter.locationIdsOrNull(),
            filter.minQuality(),
            filter.hasJobOrders(),
            filter.jobOrderIdsOrNull(),
            filter.hasMissions(),
            filter.missionIdsOrNull(),
            filter.personalOnly(),
            filter.nonPersonalOnly(),
            filter.stolenOnly(),
            filter.nonStolenOnly());

    return buildGroupedFromStacks(stacks);
  }

  /**
   * Overload of {@link #getAllAggregatedInventory(LagerFilter)} filtered by material and minimum
   * quality only.
   *
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @return aggregated squadron-wide items
   */
  public List<GroupedInventoryDto> getAllAggregatedInventory(
      @Nullable List<UUID> materialIds, @Nullable Integer minQuality) {
    return getAllAggregatedInventory(
        LagerFilter.builder().materialIds(materialIds).minQuality(minQuality).build());
  }

  /**
   * Aggregates the material stock of all users in the caller's scope into the {@code /grouped}
   * shape, applying the material, location, quality, job-order, mission and stolen filters of
   * {@code filter}; the personal flags do not apply squadron-wide.
   *
   * @param filter the Lager filters; a contradictory stolen pair answers empty
   * @return aggregated items grouped by material
   */
  public List<GroupedInventoryDto> getAllAggregatedInventory(@NotNull LagerFilter filter) {
    if (filter.stolenContradiction()) {
      return List.of();
    }
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findGlobalStacks(
            filter.hasMaterials(),
            filter.materialIdsOrNull(),
            filter.hasLocations(),
            filter.locationIdsOrNull(),
            filter.minQuality(),
            filter.hasJobOrders(),
            filter.jobOrderIdsOrNull(),
            filter.hasMissions(),
            filter.missionIdsOrNull(),
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            filter.stolenOnly(),
            filter.nonStolenOnly());

    LowStockSubjects low = stockLevelService.lowSubjects();
    return buildGroupedFromStacks(stacks).stream()
        .map(group ->
            group.withLowStock(
                low.lowMaterial(group.material() == null ? null : group.material().id())))
        .toList();
  }

  /**
   * Game-item sibling of {@link #getMyAggregatedInventory(UUID, LagerFilter)}: the user's game-item
   * stock grouped by item and stack. Items have no quality or mission filter (REQ-INV-031); the
   * category, component-type, size, grade and class filters apply (REQ-INV-063).
   *
   * @param userId owner id
   * @param filter the Lager filters; a contradictory flag pair answers empty
   * @return item groups, each carrying its sorted stacks and item-wide total
   * @throws NotFoundException when the user id is unknown
   */
  public List<GroupedInventoryDto> getMyAggregatedItemInventory(
      UUID userId, @NotNull LagerFilter filter) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    if (filter.contradictory()) {
      return List.of();
    }
    ItemAttributeFilter attributes = filter.attributes();
    List<InventoryItemStackAggregate> stacks =
        inventoryItemViewRepository.findUserItemStacks(
            user.getId(),
            filter.hasGameItems(),
            filter.gameItemIdsOrNull(),
            filter.hasLocations(),
            filter.locationIdsOrNull(),
            filter.hasJobOrders(),
            filter.jobOrderIdsOrNull(),
            filter.personalOnly(),
            filter.nonPersonalOnly(),
            filter.stolenOnly(),
            filter.nonStolenOnly(),
            attributes.hasKinds(),
            attributes.kindsOrNull(),
            attributes.hasWikiTypes(),
            attributes.wikiTypesOrNull(),
            attributes.hasSizes(),
            attributes.sizesOrNull(),
            attributes.hasGrades(),
            attributes.gradesOrNull(),
            attributes.hasItemClasses(),
            attributes.itemClassesOrNull());
    return buildGroupedFromItemStacks(stacks);
  }

  /**
   * Returns the ids of every material entry the user owns that matches the "Mein Lager" filters,
   * across all stacks, for select-all (REQ-INV-034). Uses the same filter contract as the grouped
   * view.
   *
   * @param userId owner id
   * @param filter the Lager filters; a contradictory flag pair answers empty
   * @return the ids of every matching material entry, in creation order; never {@code null}
   * @throws NotFoundException when the user id is unknown
   */
  public List<UUID> getMyEntryIds(UUID userId, @NotNull LagerFilter filter) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    if (filter.contradictory()) {
      return List.of();
    }
    return inventoryItemRepository.findUserEntryIds(
        user.getId(),
        filter.hasMaterials(),
        filter.materialIdsOrNull(),
        filter.hasLocations(),
        filter.locationIdsOrNull(),
        filter.minQuality(),
        filter.hasJobOrders(),
        filter.jobOrderIdsOrNull(),
        filter.hasMissions(),
        filter.missionIdsOrNull(),
        filter.personalOnly(),
        filter.nonPersonalOnly(),
        filter.stolenOnly(),
        filter.nonStolenOnly());
  }

  /**
   * Game-item sibling of {@link #getMyEntryIds}: the ids of every game-item entry the user owns
   * that matches the item filters (REQ-INV-034).
   *
   * @param userId owner id
   * @param filter the Lager filters; a contradictory flag pair answers empty
   * @return the ids of every matching game-item entry, in creation order; never {@code null}
   * @throws NotFoundException when the user id is unknown
   */
  public List<UUID> getMyItemEntryIds(UUID userId, @NotNull LagerFilter filter) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    if (filter.contradictory()) {
      return List.of();
    }
    ItemAttributeFilter attributes = filter.attributes();
    return inventoryItemViewRepository.findUserItemEntryIds(
        user.getId(),
        filter.hasGameItems(),
        filter.gameItemIdsOrNull(),
        filter.hasLocations(),
        filter.locationIdsOrNull(),
        filter.hasJobOrders(),
        filter.jobOrderIdsOrNull(),
        filter.personalOnly(),
        filter.nonPersonalOnly(),
        filter.stolenOnly(),
        filter.nonStolenOnly(),
        attributes.hasKinds(),
        attributes.kindsOrNull(),
        attributes.hasWikiTypes(),
        attributes.wikiTypesOrNull(),
        attributes.hasSizes(),
        attributes.sizesOrNull(),
        attributes.hasGrades(),
        attributes.gradesOrNull(),
        attributes.hasItemClasses(),
        attributes.itemClassesOrNull());
  }

  /**
   * Game-item sibling of {@link #getAllAggregatedInventory(LagerFilter)}: the scoped game-item
   * stock grouped by item and stack. No quality or mission filter (REQ-INV-031); the personal flags
   * do not apply squadron-wide.
   *
   * @param filter the Lager filters; a contradictory stolen pair answers empty
   * @return item groups, each carrying its sorted stacks and item-wide total
   */
  public List<GroupedInventoryDto> getAllAggregatedItemInventory(@NotNull LagerFilter filter) {
    if (filter.stolenContradiction()) {
      return List.of();
    }
    ItemAttributeFilter attributes = filter.attributes();
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    List<InventoryItemStackAggregate> stacks =
        inventoryItemViewRepository.findGlobalItemStacks(
            filter.hasGameItems(),
            filter.gameItemIdsOrNull(),
            filter.hasLocations(),
            filter.locationIdsOrNull(),
            filter.hasJobOrders(),
            filter.jobOrderIdsOrNull(),
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            filter.stolenOnly(),
            filter.nonStolenOnly(),
            attributes.hasKinds(),
            attributes.kindsOrNull(),
            attributes.hasWikiTypes(),
            attributes.wikiTypesOrNull(),
            attributes.hasSizes(),
            attributes.sizesOrNull(),
            attributes.hasGrades(),
            attributes.gradesOrNull(),
            attributes.hasItemClasses(),
            attributes.itemClassesOrNull());
    LowStockSubjects low = stockLevelService.lowSubjects();
    return buildGroupedFromItemStacks(stacks).stream()
        .map(group ->
            group.withLowStock(
                low.lowGameItem(group.gameItem() == null ? null : group.gameItem().id())))
        .toList();
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
        .sorted(Comparator.comparing(g -> Objects.requireNonNull(g.material()).name()))
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
              refs.stolen(),
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
        .sorted(Comparator.comparing(g -> Objects.requireNonNull(g.gameItem()).name()))
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
              refs.stolen(),
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
    probe.setStolen(aggregate.stolen());
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
    probe.setStolen(aggregate.stolen());
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
   * @param stolen whether the stack holds stock marked „gestohlen" ({@code null} means {@code
   *     false})
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
      Boolean stolen,
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
            stolen != null ? stolen : Boolean.FALSE,
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
   * @param stolen whether the stack holds stock marked „gestohlen" ({@code null} means {@code
   *     false})
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request; ordering is fixed to oldest first
   * @return one page of the stack's entries, oldest-first
   */
  public Page<InventoryItemDto> getAllStackEntries(
      UUID materialId,
      UUID userId,
      UUID locationId,
      Integer quality,
      Boolean stolen,
      UUID owningOrgUnitId,
      Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalStackEntries(
            materialId,
            userId,
            locationId,
            quality,
            stolen != null ? stolen : Boolean.FALSE,
            owningOrgUnitId,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Game-item sibling of {@link #getMyStackEntries(UUID, UUID, UUID, Integer, Boolean, Boolean,
   * UUID, Pageable)}, addressed by game item without a quality key.
   *
   * @param userId the calling owner whose stack to drill into
   * @param gameItemId the stack's game item
   * @param locationId the stack's storage location
   * @param personal whether the stack is private stock ({@code null} means {@code false})
   * @param stolen whether the stack holds stock marked „gestohlen" ({@code null} means {@code
   *     false})
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
      Boolean stolen,
      UUID owningOrgUnitId,
      Pageable pageable) {
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    return inventoryItemRepository
        .findUserItemStackEntries(
            user.getId(),
            gameItemId,
            locationId,
            personal != null ? personal : Boolean.FALSE,
            stolen != null ? stolen : Boolean.FALSE,
            owningOrgUnitId,
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Game-item sibling of {@link #getAllStackEntries(UUID, UUID, UUID, Integer, Boolean, UUID,
   * Pageable)}, addressed by game item without a quality key and under the same scope.
   *
   * @param gameItemId the stack's game item
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param stolen whether the stack holds stock marked „gestohlen" ({@code null} means {@code
   *     false})
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request; ordering is fixed to oldest first
   * @return one page of the stack's entries, oldest-first
   */
  public Page<InventoryItemDto> getAllItemStackEntries(
      UUID gameItemId,
      UUID userId,
      UUID locationId,
      Boolean stolen,
      UUID owningOrgUnitId,
      Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalItemStackEntries(
            gameItemId,
            userId,
            locationId,
            stolen != null ? stolen : Boolean.FALSE,
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
      @Nullable List<UUID> materialIds, @Nullable Integer minQuality, Pageable pageable) {
    return getAllInventory(
        LagerFilter.builder().materialIds(materialIds).minQuality(minQuality).build(), pageable);
  }

  /**
   * Flat paged inventory list in the caller's scope, one row per {@code InventoryItem}, filtered by
   * the material, location, quality, job-order and mission filters of {@code filter}.
   *
   * @param filter the Lager filters
   * @param pageable page request
   * @return paged inventory items
   */
  public Page<InventoryItemDto> getAllInventory(
      @NotNull LagerFilter filter, @NotNull Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalByFilters(
            filter.hasMaterials(),
            filter.materialIdsOrNull(),
            filter.hasLocations(),
            filter.locationIdsOrNull(),
            filter.minQuality(),
            filter.hasJobOrders(),
            filter.jobOrderIdsOrNull(),
            filter.hasMissions(),
            filter.missionIdsOrNull(),
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Game-item sibling of {@link #getAllInventory(LagerFilter, Pageable)}: the flat scoped list of
   * game-item rows. No quality or mission filter (REQ-INV-031).
   *
   * @param filter the Lager filters; the game-item, location and job-order filters apply
   * @param pageable page request (whitelisted {@code gameItem.name} / {@code amount} sort)
   * @return paged game-item inventory rows
   */
  public Page<InventoryItemDto> getAllItemInventory(
      @NotNull LagerFilter filter, @NotNull Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalItemsByFilters(
            filter.hasGameItems(),
            filter.gameItemIdsOrNull(),
            filter.hasLocations(),
            filter.locationIdsOrNull(),
            filter.hasJobOrders(),
            filter.jobOrderIdsOrNull(),
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Display order of stacks within a group: highest quality first, then location name ascending,
   * then a legitimate stack before its stolen twin, then largest amount first.
   */
  private static final Comparator<InventoryStackDto> STACK_ORDER =
      Comparator.<InventoryStackDto, Integer>comparing(s -> s.quality() != null ? s.quality() : 0)
          .reversed()
          .thenComparing(
              s -> s.location() != null && s.location().name() != null ? s.location().name() : "")
          .thenComparing(s -> Boolean.TRUE.equals(s.stolen()))
          .thenComparing(
              Comparator.<InventoryStackDto, Double>comparing(
                      s -> s.totalAmount() != null ? s.totalAmount() : 0.0)
                  .reversed());
}

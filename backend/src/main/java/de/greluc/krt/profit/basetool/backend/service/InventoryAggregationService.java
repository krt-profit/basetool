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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryStackDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryItemStackAggregate;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate;
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the inventory aggregate — every aggregated / drilldown / stack-entry projection the
 * squadron inventory page and the personal "Mein Inventar" view render.
 *
 * <p>Extracted from {@code InventoryItemService} (#921, L2) as the read cluster of the former
 * god-class: the per-material aggregation ({@link #getAggregatedInventory}), the {@code /grouped}
 * Material→Stack roll-up ({@link #getMyAggregatedInventory} / {@link #getAllAggregatedInventory}
 * over the SQL-computed {@link InventoryStackAggregate} rows), the lazy per-stack drilldowns
 * ({@link #getMyStackEntries} / {@link #getAllStackEntries}), the flat and per-material listings,
 * the craftability stock slices ({@link #getOwnedStockSlices}) and the job-order material
 * collection ({@link #getMaterialCollection}). {@code InventoryItemService} keeps the identical
 * public method signatures and delegates to this service, so controllers and callers are unchanged.
 *
 * <p>Every method is a pure read — the class is {@code @Transactional(readOnly = true)} and holds
 * no write repositories. Multi-org-unit scoping goes through {@code OwnerScopeService.currentScope
 * Predicate()} exactly as before, so an aggregation can never widen visibility beyond the caller's
 * org-unit slice.
 *
 * <p>Catalog-discriminated since V220 (REQ-INV-029, ADR-0100): the historical methods serve the
 * material catalog (their queries exclude game-item rows), and each grouped / aggregated / flat /
 * stack-entry read has a game-item sibling ({@code *Item*} methods) keyed on the quality-less item
 * stack identity. The controller dispatches between the two families on its {@code catalog} query
 * parameter.
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
   * Pools the caller's entire "My Inventory" stock into one SCU total per (material, quality) pair
   * for the blueprint craftability calculation (#781). Strictly owner-scoped to {@code userId}
   * (both personal and shared rows the user owns count, matching the default {@code /inventory/my}
   * view); never org-unit-scoped, because craftability answers "what can I craft from my stock".
   * The quality is preserved in the result so the calculator can consume the best-quality slices
   * first.
   *
   * @param userId the owning user; never {@code null}
   * @return one slice per (material, quality) the user owns, with the summed SCU; never {@code
   *     null}
   */
  public List<OwnedStockSlice> getOwnedStockSlices(@org.jetbrains.annotations.NotNull UUID userId) {
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
   * Game-item sibling of {@link #getAggregatedInventory(Pageable)} — the {@code catalog=ITEM}
   * variant of the aggregated Lager overview (REQ-INV-028/029): one row per game item with the
   * summed non-personal amount in the caller's scope. The quality columns of the material variant
   * have no item counterpart (items carry no quality dimension) and map to {@code null}.
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
   * Per-material drilldown — lists every individual inventory row for the given material. Used by
   * the inventory drilldown page.
   *
   * @param materialId material to drill into
   * @param pageable page request
   * @return paged inventory items (excludes personal items)
   * @throws NotFoundException when the material id is unknown
   */
  public Page<InventoryItemDto> getInventoryByMaterial(UUID materialId, Pageable pageable) {
    Material material =
        materialRepository
            .findById(materialId)
            .orElseThrow(() -> new NotFoundException("Material not found"));
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
   * Game-item sibling of {@link #getInventoryByMaterial(UUID, Pageable)} — the drilldown behind
   * {@code GET /api/v1/inventory/game-item/{gameItemId}} (REQ-INV-029): every non-personal stock
   * row of one game item, under the same strict-staffel scope predicate as the material drilldown.
   *
   * @param gameItemId game item to drill into
   * @param pageable page request
   * @return paged inventory rows stocking that game item (excludes personal rows)
   * @throws NotFoundException when the game-item id is unknown
   */
  public Page<InventoryItemDto> getInventoryByGameItem(UUID gameItemId, Pageable pageable) {
    GameItem gameItem =
        gameItemRepository
            .findById(gameItemId)
            .orElseThrow(() -> new NotFoundException("Game item not found"));
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
   * User-scoped material inventory list ({@code catalog=MATERIAL}). Excludes personal-inventory
   * records (those have their own dedicated service) and game-item rows (served by {@link
   * #getUserItemInventory(UUID, Pageable)}).
   *
   * @param userId owner id
   * @param pageable page request
   * @return paged material inventory rows owned by the user
   */
  public Page<InventoryItemDto> getUserInventory(UUID userId, Pageable pageable) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    return inventoryItemRepository
        .findMaterialRowsByUser(user, pageable)
        .map(inventoryItemMapper::toDto);
  }

  /**
   * Game-item sibling of {@link #getUserInventory(UUID, Pageable)} — the flat "my inventory" list
   * for {@code catalog=ITEM} (REQ-INV-029): the game-item stock rows owned by the caller.
   *
   * @param userId owner id
   * @param pageable page request (whitelisted {@code gameItem.name} / {@code amount} sort)
   * @return paged game-item inventory rows owned by the user
   * @throws NotFoundException when the user id is unknown
   */
  public Page<InventoryItemDto> getUserItemInventory(UUID userId, Pageable pageable) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
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
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getMyAggregatedInventory(UUID userId) {
    return getMyAggregatedInventory(userId, null, null, null, null);
  }

  /**
   * Job-order/mission-filtered convenience overload.
   *
   * @param userId owner id
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @return aggregated items
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getMyAggregatedInventory(UUID userId, List<UUID> jobOrderIds, List<UUID> missionIds) {
    return getMyAggregatedInventory(userId, null, null, jobOrderIds, missionIds);
  }

  /**
   * Filter-only convenience overload of {@link #getMyAggregatedInventory(UUID, List, Integer, List,
   * List, boolean, boolean)} that returns both the caller's shared and personal stacks (no
   * personal-/non-personal-only narrowing).
   *
   * @param userId owner id
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @return aggregated items
   * @throws NotFoundException when the user id is unknown
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getMyAggregatedInventory(
          UUID userId,
          List<UUID> materialIds,
          Integer minQuality,
          List<UUID> jobOrderIds,
          List<UUID> missionIds) {
    return getMyAggregatedInventory(
        userId, materialIds, minQuality, jobOrderIds, missionIds, false, false);
  }

  /**
   * Full-filter user-scoped aggregation. Loads the user's items via the parameterized repository
   * query and groups them in memory — the {@code GroupedInventoryDto} shape is what the {@code
   * /grouped} frontend endpoint returns directly.
   *
   * @param userId owner id
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @param personalOnly when {@code true}, narrows the result to the caller's private stock ({@code
   *     personal = true} rows) — the "Mein Lager" personal-entries-only filter
   * @param nonPersonalOnly when {@code true}, narrows the result to the caller's shared stock
   *     ({@code personal = false} rows) — the "Mein Lager" non-personal-entries-only filter;
   *     mutually exclusive with {@code personalOnly}, and when both are {@code false} both shared
   *     and personal stacks are returned
   * @return aggregated items
   * @throws NotFoundException when the user id is unknown
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getMyAggregatedInventory(
          UUID userId,
          List<UUID> materialIds,
          Integer minQuality,
          List<UUID> jobOrderIds,
          List<UUID> missionIds,
          boolean personalOnly,
          boolean nonPersonalOnly) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    boolean hasMaterials = materialIds != null && !materialIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    boolean hasMissions = missionIds != null && !missionIds.isEmpty();
    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findUserStacks(
            user.getId(),
            hasMaterials,
            hasMaterials ? materialIds : null,
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
   * Convenience overload of {@link #getAllAggregatedInventory(List, Integer, List, List)} without
   * job-order/mission filters.
   *
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @return aggregated squadron-wide items
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getAllAggregatedInventory(List<UUID> materialIds, Integer minQuality) {
    return getAllAggregatedInventory(materialIds, minQuality, null, null);
  }

  /**
   * Squadron-wide aggregated inventory with the full filter surface. Mirrors {@link
   * #getMyAggregatedInventory} but scopes to all users (admin/logistician view).
   *
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @return aggregated items grouped by material
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getAllAggregatedInventory(
          List<UUID> materialIds,
          Integer minQuality,
          List<UUID> jobOrderIds,
          List<UUID> missionIds) {
    boolean hasMaterials = materialIds != null && !materialIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    boolean hasMissions = missionIds != null && !missionIds.isEmpty();
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findGlobalStacks(
            hasMaterials,
            hasMaterials ? materialIds : null,
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
   * Game-item sibling of {@link #getMyAggregatedInventory(UUID, List, Integer, List, List, boolean,
   * boolean)} — the {@code catalog=ITEM} variant of the "my inventory" {@code /grouped} view
   * (REQ-INV-029): the caller's game-item stock rolled up GameItem → Stack over the quality-less
   * item stack key. Item filter surface only ({@code gameItemIds}, {@code jobOrderIds}) plus the
   * mutually exclusive {@code personalOnly} / {@code nonPersonalOnly} narrowing toggles; the
   * quality floor and mission filter of the material variant do not exist for items (REQ-INV-031)
   * and are rejected upstream by the controller.
   *
   * @param userId owner id
   * @param gameItemIds optional game-item filter
   * @param jobOrderIds optional job-order filter
   * @param personalOnly when {@code true}, narrows to the caller's private stock rows
   * @param nonPersonalOnly when {@code true}, narrows to the caller's shared stock rows
   * @return item groups, each carrying its sorted stacks and item-wide total
   * @throws NotFoundException when the user id is unknown
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getMyAggregatedItemInventory(
          UUID userId,
          List<UUID> gameItemIds,
          List<UUID> jobOrderIds,
          boolean personalOnly,
          boolean nonPersonalOnly) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    boolean hasGameItems = gameItemIds != null && !gameItemIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    List<InventoryItemStackAggregate> stacks =
        inventoryItemRepository.findUserItemStacks(
            user.getId(),
            hasGameItems,
            hasGameItems ? gameItemIds : null,
            hasJobOrders,
            hasJobOrders ? jobOrderIds : null,
            personalOnly,
            nonPersonalOnly);
    return buildGroupedFromItemStacks(stacks);
  }

  /**
   * Game-item sibling of {@link #getAllAggregatedInventory(List, Integer, List, List)} — the {@code
   * catalog=ITEM} variant of the squadron-wide {@code /grouped} view (REQ-INV-029), scoped by the
   * caller's org-unit predicate exactly like the material variant. Item filter surface only ({@code
   * gameItemIds}, {@code jobOrderIds}); no quality floor, no mission filter (REQ-INV-031).
   *
   * @param gameItemIds optional game-item filter
   * @param jobOrderIds optional job-order filter
   * @return item groups, each carrying its sorted stacks and item-wide total
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getAllAggregatedItemInventory(List<UUID> gameItemIds, List<UUID> jobOrderIds) {
    boolean hasGameItems = gameItemIds != null && !gameItemIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    List<InventoryItemStackAggregate> stacks =
        inventoryItemRepository.findGlobalItemStacks(
            hasGameItems,
            hasGameItems ? gameItemIds : null,
            hasJobOrders,
            hasJobOrders ? jobOrderIds : null,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds());
    return buildGroupedFromItemStacks(stacks);
  }

  /**
   * Assembles the Material → Stack shape the {@code /grouped} views render from the SQL-computed
   * per-stack aggregates. Outer grouping is by material; the individual entries are no longer
   * materialised here — append-only rows grow unboundedly per stack, so a stack's entries are
   * loaded lazily and paginated on expand (ADR-0003, REQ-INV-002, see {@link #getMyStackEntries} /
   * {@link #getAllStackEntries}). Each {@link InventoryStackAggregate} row is one display stack.
   *
   * @param aggregates the SQL-grouped per-stack rows for the current scope/filter
   * @return the materials, each carrying its sorted stacks and material-wide totals
   */
  private List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      buildGroupedFromStacks(List<InventoryStackAggregate> aggregates) {
    return aggregates.stream()
        .collect(
            java.util.stream.Collectors.groupingBy(
                aggregate -> aggregate.material().getId(),
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList()))
        .values()
        .stream()
        .map(this::buildMaterialGroup)
        .sorted(java.util.Comparator.comparing(g -> g.material().name()))
        .toList();
  }

  /**
   * Builds one material roll-up from its per-stack aggregates: the stacks (sorted quality desc,
   * location asc, amount desc) plus the material-wide totals (summed amount, amount-weighted mean
   * quality, max quality) accumulated from the raw {@code SUM(amount)} / {@code SUM(amount *
   * quality)} the database returned, so the material average stays independent of per-stack
   * rounding — identical to the previous over-the-entries computation.
   *
   * @param matStacks every per-stack aggregate of one material in the current scope; never empty
   * @return the populated material group with its nested stacks
   */
  private de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto buildMaterialGroup(
      List<InventoryStackAggregate> matStacks) {
    List<InventoryStackDto> stacks = new java.util.ArrayList<>(matStacks.size());
    de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto material = null;
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
    return new de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto(
        material, null, totalAmount, avgQuality, maxQuality, stacks);
  }

  /**
   * Game-item sibling of {@link #buildGroupedFromStacks(List)}: assembles the GameItem → Stack
   * shape the {@code catalog=ITEM} {@code /grouped} views render from the SQL-computed per-stack
   * item aggregates. Outer grouping is by game item, sorted by item name; entries stay lazy
   * (REQ-INV-005) exactly like the material variant.
   *
   * @param aggregates the SQL-grouped per-item-stack rows for the current scope/filter
   * @return the game-item groups, each carrying its sorted stacks and item-wide total
   */
  private List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      buildGroupedFromItemStacks(List<InventoryItemStackAggregate> aggregates) {
    return aggregates.stream()
        .collect(
            java.util.stream.Collectors.groupingBy(
                aggregate -> aggregate.gameItem().getId(),
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList()))
        .values()
        .stream()
        .map(this::buildItemGroup)
        .sorted(java.util.Comparator.comparing(g -> g.gameItem().name()))
        .toList();
  }

  /**
   * Builds one game-item roll-up from its per-stack aggregates: the stacks (sorted by the shared
   * {@link #STACK_ORDER}, whose quality key is a constant {@code null} for item stacks, leaving
   * location asc / amount desc) plus the item-wide summed amount. The quality figures of the
   * material group have no item counterpart and stay {@code null} (REQ-INV-028/029).
   *
   * @param itemStacks every per-stack aggregate of one game item in the current scope; never empty
   * @return the populated game-item group with its nested stacks
   */
  private de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto buildItemGroup(
      List<InventoryItemStackAggregate> itemStacks) {
    List<InventoryStackDto> stacks = new java.util.ArrayList<>(itemStacks.size());
    de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto gameItem = null;
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
    return new de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto(
        null, gameItem, totalAmount, null, null, stacks);
  }

  /**
   * Game-item counterpart of {@link #mapAggregateRefs(InventoryStackAggregate)}: projects one item
   * stack aggregate's shared identity entities through the inventory-item mapper via a transient
   * probe {@link InventoryItem}, so PII redaction, the {@code owningOrgUnit → owningSquadron}
   * projection and the game-item reference (incl. manufacturer name) behave exactly as for a real
   * entry. The probe's {@code material} / {@code quality} stay {@code null} — the item stack key
   * carries neither (REQ-INV-029).
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
   * Projects one stack aggregate's shared identity entities through the inventory-item mapper to
   * obtain the redaction-safe reference DTOs (user, material, location, owning squadron) and the
   * flattened job-order / mission ids the stack DTO carries. A transient probe {@link
   * InventoryItem} is fed to the mapper so PII redaction and the {@code owningOrgUnit ->
   * owningSquadron} projection behave exactly as they do for a real entry — the probe is never
   * persisted and only its identity fields are read.
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
   * Lazily loads one of the caller's own stacks' entries, oldest-first, paginated — the per-stack
   * drill-down for the "my inventory" view. Scoped to the caller ({@code userId}); the {@code
   * personal} flag is part of the stock identity, so a private and a shared stack at the same
   * location/quality drill down separately. {@code null} job-order / mission / owning-org-unit
   * arguments match rows where that association is itself {@code null}.
   *
   * @param userId the calling owner whose stack to drill into
   * @param materialId the stack's material
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param personal whether the stack is private stock (defaults to {@code false} when {@code
   *     null})
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request (the query forces oldest-first by creation instant)
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
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
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
   * Lazily loads one global stack's entries, oldest-first, paginated — the per-stack drill-down for
   * the squadron-wide Lager view. The same scope predicate as the grouped view is applied so the
   * drill-down can never widen visibility beyond the caller's org-unit slice; the stack's owner is
   * an explicit argument because a global stack is per-owner. {@code null} job-order / mission /
   * owning-org-unit arguments match rows where that association is itself {@code null}.
   *
   * @param materialId the stack's material
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request (the query forces oldest-first by creation instant)
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
   * Pageable)} — the {@code catalog=ITEM} per-stack drill-down of the "my inventory" view
   * (REQ-INV-005/029). The stack is addressed by {@code gameItemId} with no quality key (items
   * carry no quality dimension); owner-scoping and the personal/owning-org-unit identity dimensions
   * behave exactly like the material variant.
   *
   * @param userId the calling owner whose stack to drill into
   * @param gameItemId the stack's game item
   * @param locationId the stack's storage location
   * @param personal whether the stack is private stock (defaults to {@code false} when {@code
   *     null})
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request (the query forces oldest-first by creation instant)
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
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
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
   * Game-item sibling of {@link #getAllStackEntries(UUID, UUID, UUID, Integer, UUID, Pageable)} —
   * the {@code catalog=ITEM} per-stack drill-down of the squadron-wide Lager view
   * (REQ-INV-005/029). Addressed by {@code gameItemId} with no quality key; the same scope
   * predicate as the grouped item view applies, so the drill-down can never widen visibility beyond
   * the caller's org-unit slice.
   *
   * @param gameItemId the stack's game item
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request (the query forces oldest-first by creation instant)
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
   * Convenience overload without job-order/mission filters.
   *
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @param pageable page request
   * @return paged inventory items
   */
  public Page<InventoryItemDto> getAllInventory(
      List<UUID> materialIds, Integer minQuality, Pageable pageable) {
    return getAllInventory(materialIds, minQuality, null, null, pageable);
  }

  /**
   * Flat paged squadron-wide inventory with optional filters. Not aggregated — one row per {@code
   * InventoryItem}.
   *
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @param pageable page request
   * @return paged inventory items
   */
  public Page<InventoryItemDto> getAllInventory(
      List<UUID> materialIds,
      Integer minQuality,
      List<UUID> jobOrderIds,
      List<UUID> missionIds,
      Pageable pageable) {
    boolean hasMaterials = materialIds != null && !materialIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    boolean hasMissions = missionIds != null && !missionIds.isEmpty();
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalByFilters(
            hasMaterials,
            hasMaterials ? materialIds : null,
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
   * Game-item sibling of {@link #getAllInventory(List, Integer, List, List, Pageable)} — the flat
   * squadron-wide list for {@code catalog=ITEM} (REQ-INV-029), scoped by the caller's org-unit
   * predicate. Item filter surface only ({@code gameItemIds}, {@code jobOrderIds}); the quality
   * floor and mission filter of the material variant do not exist for items (REQ-INV-031). Not
   * aggregated — one row per {@code InventoryItem}.
   *
   * @param gameItemIds optional game-item filter
   * @param jobOrderIds optional job-order filter
   * @param pageable page request (whitelisted {@code gameItem.name} / {@code amount} sort)
   * @return paged game-item inventory rows
   */
  public Page<InventoryItemDto> getAllItemInventory(
      List<UUID> gameItemIds, List<UUID> jobOrderIds, Pageable pageable) {
    boolean hasGameItems = gameItemIds != null && !gameItemIds.isEmpty();
    boolean hasJobOrders = jobOrderIds != null && !jobOrderIds.isEmpty();
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findGlobalItemsByFilters(
            hasGameItems,
            hasGameItems ? gameItemIds : null,
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
    jobOrderRepository
        .findById(jobOrderId)
        .orElseThrow(() -> new NotFoundException("Job order not found"));
    return inventoryItemRepository.findByJobOrderIdOrdered(jobOrderId).stream()
        .map(
            item -> {
              String ownerName =
                  item.getUser().getDisplayName() != null
                      ? item.getUser().getDisplayName()
                      : item.getUser().getUsername();
              // Variante C (REQ-INV-027): both the delivered flag and the order-relevant quantity
              // are per-order — read this order's own job-order slice (batched via @BatchSize), not
              // the whole entry. `delivered` is the slice's flag (an entry serving several orders
              // shows the right state for each); `allocatedQuantity` is the slice's amount, i.e.
              // the
              // share actually earmarked to THIS order, which is what counts toward its fulfilment.
              // `quantity` stays the entry's total physical stock — it backs the full-row owner /
              // location transfer (data-amount) and is shown as context alongside the allocated
              // share.
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
   * Display order of the stacks within a material: highest quality first, then location name
   * ascending, then largest total amount first — mirrors the previous per-row ordering.
   */
  private static final java.util.Comparator<InventoryStackDto> STACK_ORDER =
      java.util.Comparator.<InventoryStackDto, Integer>comparing(
              s -> s.quality() != null ? s.quality() : 0)
          .reversed()
          .thenComparing(
              s -> s.location() != null && s.location().name() != null ? s.location().name() : "")
          .thenComparing(
              java.util.Comparator.<InventoryStackDto, Double>comparing(
                      s -> s.totalAmount() != null ? s.totalAmount() : 0.0)
                  .reversed());
}

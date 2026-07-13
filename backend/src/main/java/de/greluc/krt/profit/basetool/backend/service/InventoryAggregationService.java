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
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryStackDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate;
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
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
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryAggregationService {

  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final MaterialRepository materialRepository;
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
                    obj[1] != null
                        ? Math.round(((Number) obj[1]).doubleValue() * 100.0) / 100.0
                        : 0.0,
                    obj[2] != null ? ((Number) obj[2]).doubleValue() : 0.0));
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
   * User-scoped inventory list. Excludes personal items because those have their own dedicated
   * service.
   *
   * @param userId owner id
   * @param pageable page request
   * @return paged inventory items owned by the user
   */
  public Page<InventoryItemDto> getUserInventory(UUID userId, Pageable pageable) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    return inventoryItemRepository.findByUser(user, pageable).map(inventoryItemMapper::toDto);
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
        material, totalAmount, avgQuality, maxQuality, stacks);
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
                  Boolean.TRUE.equals(item.getDelivered()));
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

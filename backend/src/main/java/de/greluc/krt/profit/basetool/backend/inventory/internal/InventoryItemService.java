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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInRule;
import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargets;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.MissionEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.kernel.AppException;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory-item facade for the squadron's physical stock, used by {@code InventoryItemController}
 * and the material-collection and craftability callers.
 *
 * <p>Implements create, update, note and allocation writes itself; reads and aggregations delegate
 * to {@link InventoryAggregationService}, checkout writes to {@link InventoryCheckoutService}.
 * Delegating writes carry their own {@code @Transactional}, so the read-write transaction opens
 * here.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryItemService {

  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final LocationRepository locationRepository;
  private final InventoryEarmarkLookup earmarkTargets;
  private final InventoryItemMapper inventoryItemMapper;
  private final OwnerScopeService ownerScopeService;
  private final InventoryAccessPolicy inventoryAccessPolicy;
  private final StockCommands stockCommands;
  private final EarmarkTargets.JobOrders jobOrders;
  private final AuditRecorder auditRecorder;
  private final InventoryAggregationService inventoryAggregationService;
  private final InventoryCheckoutService inventoryCheckoutService;
  private final InventoryProperties inventoryProperties;

  /**
   * Aggregated per-material inventory view — used by the squadron-wide inventory page.
   *
   * @param pageable page request
   * @return paged aggregated DTOs (material + total amount + average quality)
   */
  public Page<AggregatedInventoryDto> getAggregatedInventory(Pageable pageable) {
    return inventoryAggregationService.getAggregatedInventory(pageable);
  }

  /**
   * Lists every inventory row of one material for the drilldown page.
   *
   * @param materialId material to drill into
   * @param pageable page request
   * @return paged inventory items (excludes personal items)
   * @throws NotFoundException when the material id is unknown
   */
  public Page<InventoryItemDto> getInventoryByMaterial(UUID materialId, Pageable pageable) {
    return inventoryAggregationService.getInventoryByMaterial(materialId, pageable);
  }

  /**
   * Lists the user's inventory rows, excluding personal items.
   *
   * @param userId owner id
   * @param pageable page request
   * @return paged inventory items owned by the user
   */
  public Page<InventoryItemDto> getUserInventory(UUID userId, Pageable pageable) {
    return inventoryAggregationService.getUserInventory(userId, pageable);
  }

  /**
   * Unfiltered convenience overload for {@link #getMyAggregatedInventory(UUID, List, Integer, List,
   * List)}.
   *
   * @param userId owner id
   * @return aggregated items grouped by material
   */
  public List<GroupedInventoryDto> getMyAggregatedInventory(UUID userId) {
    return inventoryAggregationService.getMyAggregatedInventory(userId);
  }

  /**
   * Aggregates the user's stock filtered only by job order and mission.
   *
   * @param userId owner id
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @return aggregated items
   */
  public List<GroupedInventoryDto> getMyAggregatedInventory(
      UUID userId, List<UUID> jobOrderIds, List<UUID> missionIds) {
    return inventoryAggregationService.getMyAggregatedInventory(userId, jobOrderIds, missionIds);
  }

  /**
   * Aggregates the user's shared and personal stock with the given filters, without location
   * narrowing; see {@link #getMyAggregatedInventory(UUID, List, List, Integer, List, List, boolean,
   * boolean, boolean, boolean)}.
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
    return inventoryAggregationService.getMyAggregatedInventory(
        userId, materialIds, minQuality, jobOrderIds, missionIds);
  }

  /**
   * Aggregates the user's stock with the full filter surface into the {@code /grouped} shape.
   *
   * @param userId owner id
   * @param materialIds optional material filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @param personalOnly {@code true} to return only private ({@code personal = true}) stock
   * @param nonPersonalOnly {@code true} to return only shared stock; mutually exclusive with {@code
   *     personalOnly}, both {@code false} returns everything
   * @param stolenOnly when {@code true}, narrows to stock marked „gestohlen" (REQ-INV-053)
   * @param nonStolenOnly when {@code true}, narrows to stock not marked „gestohlen"
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
      boolean nonPersonalOnly,
      boolean stolenOnly,
      boolean nonStolenOnly) {
    return inventoryAggregationService.getMyAggregatedInventory(
        userId,
        materialIds,
        locationIds,
        minQuality,
        jobOrderIds,
        missionIds,
        personalOnly,
        nonPersonalOnly,
        stolenOnly,
        nonStolenOnly);
  }

  /**
   * Returns the ids of every material entry the user owns that matches the {@link
   * #getMyAggregatedInventory} filters, unpaginated, for the "Mein Lager" select-all (REQ-INV-034).
   *
   * @param userId owner id
   * @param materialIds optional material filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @param personalOnly {@code true} to match only private stock rows
   * @param nonPersonalOnly {@code true} to match only shared stock rows
   * @param stolenOnly when {@code true}, narrows to stock marked „gestohlen" (REQ-INV-053)
   * @param nonStolenOnly when {@code true}, narrows to stock not marked „gestohlen"
   * @return the matching entry ids, in creation order
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
      boolean nonPersonalOnly,
      boolean stolenOnly,
      boolean nonStolenOnly) {
    return inventoryAggregationService.getMyEntryIds(
        userId,
        materialIds,
        locationIds,
        minQuality,
        jobOrderIds,
        missionIds,
        personalOnly,
        nonPersonalOnly,
        stolenOnly,
        nonStolenOnly);
  }

  /**
   * Aggregates squadron-wide stock by material and quality only; see {@link
   * #getAllAggregatedInventory(List, List, Integer, List, List, boolean, boolean)}.
   *
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @return aggregated squadron-wide items
   */
  public List<GroupedInventoryDto> getAllAggregatedInventory(
      List<UUID> materialIds, Integer minQuality) {
    return inventoryAggregationService.getAllAggregatedInventory(materialIds, minQuality);
  }

  /**
   * Aggregates squadron-wide stock across all users with the full filter surface.
   *
   * @param materialIds optional material filter
   * @param locationIds optional storage-location filter (REQ-INV-040)
   * @param minQuality optional min-quality filter
   * @param jobOrderIds optional job order filter
   * @param missionIds optional mission filter
   * @param stolenOnly when {@code true}, narrows to stock marked „gestohlen" (REQ-INV-053)
   * @param nonStolenOnly when {@code true}, narrows to stock not marked „gestohlen"
   * @return aggregated items grouped by material
   */
  public List<GroupedInventoryDto> getAllAggregatedInventory(
      List<UUID> materialIds,
      List<UUID> locationIds,
      Integer minQuality,
      List<UUID> jobOrderIds,
      List<UUID> missionIds,
      boolean stolenOnly,
      boolean nonStolenOnly) {
    return inventoryAggregationService.getAllAggregatedInventory(
        materialIds, locationIds, minQuality, jobOrderIds, missionIds, stolenOnly, nonStolenOnly);
  }

  /**
   * Pages the entries of one of the caller's own stacks, oldest first. {@code null} job-order,
   * mission or org-unit arguments match rows where that association is {@code null}.
   *
   * @param userId the calling owner whose stack to drill into
   * @param materialId the stack's material
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param personal whether the stack is private stock ({@code null} means {@code false})
   * @param stolen whether the stack holds stock marked „gestohlen" ({@code null} means {@code
   *     false})
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request (sorting is forced oldest-first)
   * @return one page of the stack's entries
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
    return inventoryAggregationService.getMyStackEntries(
        userId, materialId, locationId, quality, personal, stolen, owningOrgUnitId, pageable);
  }

  /**
   * Pages the entries of one squadron-wide stack, oldest first, within the caller's org-unit scope.
   * {@code null} job-order, mission or org-unit arguments match rows where that association is
   * {@code null}.
   *
   * @param materialId the stack's material
   * @param userId the stack's owning user
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param stolen whether the stack holds stock marked „gestohlen" ({@code null} means {@code
   *     false})
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request (sorting is forced oldest-first)
   * @return one page of the stack's entries
   */
  public Page<InventoryItemDto> getAllStackEntries(
      UUID materialId,
      UUID userId,
      UUID locationId,
      Integer quality,
      Boolean stolen,
      UUID owningOrgUnitId,
      Pageable pageable) {
    return inventoryAggregationService.getAllStackEntries(
        materialId, userId, locationId, quality, stolen, owningOrgUnitId, pageable);
  }

  /**
   * Pages squadron-wide inventory filtered by material and quality only.
   *
   * @param materialIds optional material filter
   * @param minQuality optional min-quality filter
   * @param pageable page request
   * @return paged inventory items
   */
  public Page<InventoryItemDto> getAllInventory(
      List<UUID> materialIds, Integer minQuality, Pageable pageable) {
    return inventoryAggregationService.getAllInventory(materialIds, minQuality, pageable);
  }

  /**
   * Pages squadron-wide inventory, one row per {@code InventoryItem}, with optional filters.
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
    return inventoryAggregationService.getAllInventory(
        materialIds, locationIds, minQuality, jobOrderIds, missionIds, pageable);
  }

  /**
   * Lists every inventory item linked to a mission for the mission-detail "Lagereinträge" table.
   * Visible to any member.
   *
   * @param missionId the mission whose linked inventory to list
   * @return the mission's inventory items (empty when none)
   */
  @Transactional(readOnly = true)
  public List<InventoryItemDto> getMissionInventory(UUID missionId) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return inventoryItemRepository
        .findByMissionIdScoped(
            missionId, scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds())
        .stream()
        .map(inventoryItemMapper::toDto)
        .toList();
  }

  /**
   * Creates an inventory item, resolving all referenced ids. Exactly one of {@code materialId} /
   * {@code gameItemId} is set (REQ-INV-029).
   *
   * <p>A job-order link requires the material to be required by the order (REQ-ORDERS-018) or the
   * game item to be requested by an ITEM order (REQ-INV-031); game-item rows may not reference a
   * mission.
   *
   * @throws NotFoundException when any referenced id is unknown
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the job-order
   *     requirement, catalog-kind or quality rules are violated, or a game-item row names a mission
   */
  @Transactional
  public InventoryItemDto createInventoryItem(
      @NotNull InventoryItemCreateDto dto, UUID currentUserId) {
    UUID targetUserId = dto.userId() != null ? dto.userId() : currentUserId;
    stockCommands.requireBookIn(
        targetUserId,
        currentUserId,
        false,
        Boolean.TRUE.equals(dto.personal()),
        BookInRule.lager());

    final User user = Entities.require(userRepository.findById(targetUserId), "User not found");
    final Material material =
        dto.materialId() != null
            ? Entities.require(materialRepository.findById(dto.materialId()), "Material not found")
            : null;
    final GameItem gameItem =
        dto.gameItemId() != null
            ? Entities.require(gameItemRepository.findById(dto.gameItemId()), "GameItem not found")
            : null;
    final Location location =
        Entities.require(locationRepository.findById(dto.locationId()), "Location not found");

    if ((material == null) == (gameItem == null)) {
      throw new BadRequestException("Exactly one of materialId and gameItemId must be set");
    }
    if (material != null && dto.quality() == null) {
      throw new BadRequestException("Material stock requires a quality");
    }
    if (gameItem != null && dto.quality() != null) {
      throw new BadRequestException("Game-item stock carries no quality");
    }
    if (gameItem != null
        && (dto.missionId() != null
            || (dto.missionAllocations() != null && !dto.missionAllocations().isEmpty()))) {
      throw new BadRequestException("Game-item stock cannot be assigned to a mission");
    }

    boolean isStolen = Boolean.TRUE.equals(dto.stolen());
    if (isStolen && !inventoryProperties.stolenMarkingEnabled()) {
      throw new BusinessConflictException("error.inventory.stolen.disabled");
    }
    Boolean isPersonal = dto.personal() != null ? dto.personal() : false;

    final OrgUnit owningOrgUnit =
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, dto.owningOrgUnitId());

    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setOwningOrgUnit(owningOrgUnit);
    item.setMaterial(material);
    item.setGameItem(gameItem);
    item.setLocation(location);
    item.setQuality(dto.quality());
    item.setAmount(ScuAmounts.roundToScuScale(dto.amount().doubleValue()));
    item.setPersonal(isPersonal);
    item.setStolen(isStolen);
    List<InventoryAllocationInput> jobAllocations =
        effectiveAllocations(dto.jobOrderAllocations(), dto.jobOrderId(), item.getAmount());
    List<InventoryAllocationInput> missionAllocations =
        effectiveAllocations(dto.missionAllocations(), dto.missionId(), item.getAmount());
    if (Boolean.TRUE.equals(isPersonal)
        && (!jobAllocations.isEmpty() || !missionAllocations.isEmpty())) {
      throw new BadRequestException("Personal items cannot be assigned to a mission or job order");
    }
    boolean wholeUnits =
        gameItem != null || (material != null && material.getQuantityType() == QuantityType.PIECE);
    Set<UUID> seenOrders = new HashSet<>();
    for (InventoryAllocationInput allocation : jobAllocations) {
      if (!seenOrders.add(allocation.targetId())) {
        throw new BadRequestException("A job order may be assigned at most once at check-in");
      }
      requireWholeUnits(wholeUnits, gameItem != null, allocation.amount());
      if (gameItem != null) {
        assertGameItemRequiredByJobOrder(gameItem, allocation.targetId());
      } else {
        assertMaterialRequiredByJobOrder(Objects.requireNonNull(material), allocation.targetId());
      }
      InventoryAllocations.addJobOrder(
          item,
          allocation.targetId(),
          ScuAmounts.roundToScuScale(allocation.amount().doubleValue()),
          false);
    }
    Set<UUID> seenMissions = new HashSet<>();
    for (InventoryAllocationInput allocation : missionAllocations) {
      if (!seenMissions.add(allocation.targetId())) {
        throw new BadRequestException("A mission may be assigned at most once at check-in");
      }
      requireWholeUnits(wholeUnits, gameItem != null, allocation.amount());
      Entities.require(
          Optional.ofNullable(earmarkTargets.mission(allocation.targetId())), "Mission not found");
      InventoryAllocations.addMission(
          item,
          allocation.targetId(),
          ScuAmounts.roundToScuScale(allocation.amount().doubleValue()));
    }
    if (!InventoryAllocations.fits(item)) {
      throw new OverAllocationException();
    }

    InventoryItem saved = inventoryItemRepository.save(item);
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_CREATED,
        item.getId(),
        InventoryAuditLabels.label(item),
        item.getUser().getId(),
        AuditDetails.of("qty", item.getAmount())
            .with("q", item.getQuality())
            .with("personal", item.getPersonal())
            .with("jobOrder", InventoryAuditLabels.jobOrderRef(item, this::jobOrderDisplayIdOf))
            .with("mission", InventoryAuditLabels.missionName(item, this::missionNameOf)));
    InventoryItem merged =
        inventoryCheckoutService.mergeStockIfRequested(
            saved, Boolean.TRUE.equals(dto.mergeStock()));
    return inventoryItemMapper.toDto(merged);
  }

  /**
   * Probes whether an Einbuchen with the given material stock identity would find a row to merge
   * with (REQ-INV-026), resolving owner and owning org unit exactly as {@link #createInventoryItem}
   * does.
   *
   * <p>Runs without a surrounding transaction so a refused org-unit resolution answers {@code
   * false} instead of marking a shared transaction rollback-only.
   *
   * @param userId the member booked for, or {@code null} for the caller
   * @param currentUserId the caller's id
   * @param materialId the material being booked in
   * @param locationId the target location
   * @param quality the quality grade
   * @param personal whether the row would be personal
   * @param stolen whether the row would be marked „gestohlen"
   * @param owningOrgUnitId the picked owning org unit, or {@code null} for the auto-stamp
   * @return {@code true} when at least one mergeable row exists; {@code false} as well when the
   *     booking itself would be refused
   * @throws AccessDeniedException when the caller may not book for {@code userId}
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public boolean hasMergeCandidates(
      UUID userId,
      @NotNull UUID currentUserId,
      @NotNull UUID materialId,
      @NotNull UUID locationId,
      int quality,
      boolean personal,
      boolean stolen,
      UUID owningOrgUnitId) {
    UUID targetUserId = userId != null ? userId : currentUserId;
    boolean onBehalfOfSomeoneElse = !targetUserId.equals(currentUserId);
    if (onBehalfOfSomeoneElse && !inventoryAccessPolicy.canManageUserInventory(targetUserId)) {
      throw new AccessDeniedException(
          "You are not allowed to inspect inventory items of other users");
    }
    if ((onBehalfOfSomeoneElse && personal)
        || (stolen && !inventoryProperties.stolenMarkingEnabled())) {
      return false;
    }
    User user = userRepository.findById(targetUserId).orElse(null);
    if (user == null) {
      return false;
    }
    OrgUnit owningOrgUnit;
    try {
      owningOrgUnit =
          ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, owningOrgUnitId);
    } catch (AppException refused) {
      return false;
    }
    return inventoryItemRepository.countMergeCandidates(
            targetUserId,
            materialId,
            locationId,
            quality,
            personal,
            stolen,
            owningOrgUnit != null ? owningOrgUnit.getId() : null)
        > 0;
  }

  /**
   * Epsilon for the over-allocation comparison. Both an entry's amount and every slice amount are
   * SCU-rounded to three decimals at the persistence boundary, so a proposed Σ that lands a hair
   * over the entry's amount purely through {@code double} noise (rather than a real
   * over-allocation) is tolerated; anything beyond this margin is a genuine 422.
   */
  private static final double OVER_ALLOCATION_EPSILON = 1e-6;

  /**
   * Earmarks part of a non-personal entry to a job order or mission (REQ-INV-027).
   *
   * <p>Each target may be allocated once, and a dimension's total must not exceed the entry's
   * amount. Bumps the entry's {@code @Version}.
   *
   * @param id the inventory entry id
   * @param dto dimension, target, amount and echoed entry version
   * @param currentUserId the authenticated caller
   * @param isLogistician whether the caller is a logistician or above
   * @return the updated entry with its new version
   * @throws NotFoundException when the entry, job order or mission is unknown
   * @throws AccessDeniedException when the caller neither owns the entry nor is a logistician
   * @throws BadRequestException when the entry is personal, the material is not required, the
   *     amount is invalid, or the target is already allocated
   * @throws OverAllocationException when the dimension total would exceed the entry's amount
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echoed version
   *     is stale
   */
  @Transactional
  public InventoryItemDto addAllocation(
      UUID id, InventoryAllocationWriteDto dto, UUID currentUserId, boolean isLogistician) {
    InventoryItem item = loadForAllocationWrite(id, dto, currentUserId, isLogistician);
    assertNotPersonal(item);
    double amount = requireWriteAmount(dto, item);
    switch (dto.field()) {
      case JOB_ORDER -> {
        if (item.getGameItem() != null) {
          assertGameItemRequiredByJobOrder(item.getGameItem(), dto.targetId());
        } else {
          assertMaterialRequiredByJobOrder(
              Objects.requireNonNull(item.getMaterial()), dto.targetId());
        }
        if (findJobOrderSlice(item, dto.targetId()) != null) {
          throw new BadRequestException("error.inventory.allocation.duplicate.jobOrder");
        }
        assertFits(sumJobOrderAllocated(item, null) + amount, item.getAmount());
        InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
        slice.setInventoryItem(item);
        slice.setJobOrderId(dto.targetId());
        slice.setAmount(amount);
        item.getJobOrderAllocations().add(slice);
        recordAllocation(
            AuditEventType.INVENTORY_ALLOCATION_ADDED,
            item,
            dto.field(),
            "#" + jobOrderDisplayIdOf(dto.targetId()),
            amount);
      }
      case MISSION -> {
        assertMissionDimensionAllowed(item);
        final MissionEarmark mission =
            Entities.require(
                Optional.ofNullable(earmarkTargets.mission(dto.targetId())), "Mission not found");
        if (findMissionSlice(item, dto.targetId()) != null) {
          throw new BadRequestException("error.inventory.allocation.duplicate.mission");
        }
        assertFits(sumMissionAllocated(item, null) + amount, item.getAmount());
        InventoryMissionAllocation slice = new InventoryMissionAllocation();
        slice.setInventoryItem(item);
        slice.setMissionId(dto.targetId());
        slice.setAmount(amount);
        item.getMissionAllocations().add(slice);
        recordAllocation(
            AuditEventType.INVENTORY_ALLOCATION_ADDED, item, dto.field(), mission.name(), amount);
      }
      case null -> throw new NullPointerException("allocation dimension");
    }
    return mapWithForcedVersion(inventoryItemRepository.saveAndFlush(item));
  }

  /**
   * Changes the amount of an existing allocation slice (REQ-INV-027); the dimension total must stay
   * within the entry's amount. Bumps the entry's {@code @Version}.
   *
   * @param id the inventory entry id
   * @param dto dimension, target, new amount and echoed entry version
   * @param currentUserId the authenticated caller
   * @param isLogistician whether the caller is a logistician or above
   * @return the updated entry
   * @throws NotFoundException when the entry or the target slice is unknown
   * @throws AccessDeniedException when the caller neither owns the entry nor is a logistician
   * @throws BadRequestException when the entry is personal or the amount is invalid
   * @throws OverAllocationException when the dimension total would exceed the entry's amount
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echoed version
   *     is stale
   */
  @Transactional
  public InventoryItemDto changeAllocation(
      UUID id, InventoryAllocationWriteDto dto, UUID currentUserId, boolean isLogistician) {
    InventoryItem item = loadForAllocationWrite(id, dto, currentUserId, isLogistician);
    assertNotPersonal(item);
    double amount = requireWriteAmount(dto, item);
    switch (dto.field()) {
      case JOB_ORDER -> {
        InventoryJobOrderAllocation slice = findJobOrderSlice(item, dto.targetId());
        if (slice == null) {
          throw new NotFoundException("Job-order allocation not found");
        }
        assertFits(sumJobOrderAllocated(item, dto.targetId()) + amount, item.getAmount());
        slice.setAmount(amount);
        recordAllocation(
            AuditEventType.INVENTORY_ALLOCATION_CHANGED,
            item,
            dto.field(),
            "#" + jobOrderDisplayIdOf(slice.getJobOrderId()),
            amount);
      }
      case MISSION -> {
        assertMissionDimensionAllowed(item);
        InventoryMissionAllocation slice = findMissionSlice(item, dto.targetId());
        if (slice == null) {
          throw new NotFoundException("Mission allocation not found");
        }
        assertFits(sumMissionAllocated(item, dto.targetId()) + amount, item.getAmount());
        slice.setAmount(amount);
        recordAllocation(
            AuditEventType.INVENTORY_ALLOCATION_CHANGED,
            item,
            dto.field(),
            Objects.requireNonNull(earmarkTargets.mission(slice.getMissionId())).name(),
            amount);
      }
      case null -> throw new NullPointerException("allocation dimension");
    }
    return mapWithForcedVersion(inventoryItemRepository.saveAndFlush(item));
  }

  /**
   * Removes an allocation slice, returning its amount to the unallocated remainder (REQ-INV-027).
   * Bumps the entry's {@code @Version}.
   *
   * @param id the inventory entry id
   * @param dto dimension, target and echoed entry version; amount is ignored
   * @param currentUserId the authenticated caller
   * @param isLogistician whether the caller is a logistician or above
   * @return the updated entry
   * @throws NotFoundException when the entry or the target slice is unknown
   * @throws AccessDeniedException when the caller neither owns the entry nor is a logistician
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echoed version
   *     is stale
   */
  @Transactional
  public InventoryItemDto removeAllocation(
      UUID id, InventoryAllocationWriteDto dto, UUID currentUserId, boolean isLogistician) {
    InventoryItem item = loadForAllocationWrite(id, dto, currentUserId, isLogistician);
    switch (dto.field()) {
      case JOB_ORDER -> {
        InventoryJobOrderAllocation slice = findJobOrderSlice(item, dto.targetId());
        if (slice == null) {
          throw new NotFoundException("Job-order allocation not found");
        }
        JobOrderEarmark target = earmarkTargets.jobOrder(slice.getJobOrderId());
        String ref = target != null ? "#" + target.displayId() : "-";
        item.getJobOrderAllocations().remove(slice);
        recordAllocation(AuditEventType.INVENTORY_ALLOCATION_REMOVED, item, dto.field(), ref, null);
      }
      case MISSION -> {
        InventoryMissionAllocation slice = findMissionSlice(item, dto.targetId());
        if (slice == null) {
          throw new NotFoundException("Mission allocation not found");
        }
        MissionEarmark target = earmarkTargets.mission(slice.getMissionId());
        String ref = target != null ? target.name() : "-";
        item.getMissionAllocations().remove(slice);
        recordAllocation(AuditEventType.INVENTORY_ALLOCATION_REMOVED, item, dto.field(), ref, null);
      }
      case null -> throw new NullPointerException("allocation dimension");
    }
    return mapWithForcedVersion(inventoryItemRepository.saveAndFlush(item));
  }

  /**
   * Maps a just-flushed entry to its DTO with the version it will carry after the commit-time
   * forced increment ({@link InventoryAllocations#forcedNextVersion}), so the client echoes the
   * right value.
   *
   * @param saved the just-flushed entry; never {@code null}
   * @return the entry DTO with the version to echo next
   */
  private InventoryItemDto mapWithForcedVersion(InventoryItem saved) {
    return inventoryItemMapper
        .toDto(saved)
        .withVersion(InventoryAllocations.forcedNextVersion(saved));
  }

  /**
   * Loads an entry under a forced version increment, refuses a caller who neither owns it nor is a
   * logistician, and checks the echoed version, making the entry's {@code @Version} the concurrency
   * token for its allocation slices.
   *
   * @param id the inventory entry id
   * @param dto the write payload carrying the echoed version
   * @param currentUserId the authenticated caller
   * @param isLogistician whether the caller is a logistician or above
   * @return the managed entry
   * @throws NotFoundException when the entry is unknown
   * @throws AccessDeniedException when the caller neither owns the entry nor is a logistician
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echoed version
   *     is stale
   */
  private InventoryItem loadForAllocationWrite(
      UUID id, InventoryAllocationWriteDto dto, UUID currentUserId, boolean isLogistician) {
    InventoryItem item =
        Entities.require(
            inventoryItemRepository.findByIdForAllocationWrite(id), "Inventory item not found");
    boolean isOwner = item.getUser() != null && item.getUser().getId().equals(currentUserId);
    if (!isOwner && !isLogistician) {
      throw new AccessDeniedException(
          "You are not allowed to change the allocations of this inventory item");
    }
    OptimisticLock.checkOptionalClient(item.getVersion(), dto.version(), InventoryItem.class, id);
    return item;
  }

  /**
   * Rejects an allocation write on a personal entry, which carries no job-order or mission
   * assignment.
   *
   * @param item the entry being written
   * @throws BadRequestException when the entry is personal
   */
  private void assertNotPersonal(InventoryItem item) {
    if (Boolean.TRUE.equals(item.getPersonal())) {
      throw new BadRequestException("Personal items cannot be assigned to a mission or job order");
    }
  }

  /**
   * Validates an add/change amount (present, positive, whole for a {@code PIECE} material or
   * game-item row) and rounds it to SCU precision.
   *
   * @param dto the write payload
   * @param item the entry, for its catalog kind and quantity type
   * @return the validated, SCU-rounded amount
   * @throws BadRequestException when the amount is missing, non-positive, or fractional for a
   *     whole-unit entry
   */
  private double requireWriteAmount(@NotNull InventoryAllocationWriteDto dto, InventoryItem item) {
    Double raw = dto.amount();
    if (raw == null) {
      throw new BadRequestException("An allocation amount is required");
    }
    if (raw <= 0) {
      throw new BadRequestException("An allocation amount must be positive");
    }
    boolean itemRow = item.getGameItem() != null;
    boolean wholeUnits =
        itemRow
            || (item.getMaterial() != null
                && item.getMaterial().getQuantityType() == QuantityType.PIECE);
    if (wholeUnits && raw % 1 != 0) {
      throw new BadRequestException(
          itemRow
              ? "An item allocation amount must be a whole number"
              : "A PIECE allocation amount must be a whole number");
    }
    return ScuAmounts.roundToScuScale(raw.doubleValue());
  }

  /**
   * Ensures a dimension's proposed total does not exceed the entry's amount, comparing SCU-rounded
   * values.
   *
   * @param proposedSum the dimension's total after the write
   * @param capacity the entry's amount
   * @throws OverAllocationException when {@code proposedSum} exceeds {@code capacity} beyond {@link
   *     #OVER_ALLOCATION_EPSILON}
   */
  private void assertFits(double proposedSum, double capacity) {
    if (ScuAmounts.roundToScuScale(proposedSum) - capacity > OVER_ALLOCATION_EPSILON) {
      throw new OverAllocationException();
    }
  }

  /**
   * Sums the entry's job-order allocations, optionally excluding one target's slice.
   *
   * @param item the entry
   * @param excludeTargetId the job-order id to exclude, or {@code null} to sum all
   * @return the total of the included job-order slices
   */
  private double sumJobOrderAllocated(@NotNull InventoryItem item, @Nullable UUID excludeTargetId) {
    return item.getJobOrderAllocations().stream()
        .filter(a -> a.getJobOrderId() != null)
        .filter(a -> excludeTargetId == null || !excludeTargetId.equals(a.getJobOrderId()))
        .mapToDouble(a -> a.getAmount() == null ? 0.0 : a.getAmount())
        .sum();
  }

  /**
   * Looks up the running number of a job order the Lager's stock is earmarked to.
   *
   * @param jobOrderId the job order
   * @return its running number, or {@code null} when it is unknown or not assigned
   */
  private @Nullable Integer jobOrderDisplayIdOf(UUID jobOrderId) {
    JobOrderEarmark jobOrder = earmarkTargets.jobOrder(jobOrderId);
    return jobOrder == null ? null : jobOrder.displayId();
  }

  /**
   * Looks up the name of a mission the Lager's stock is earmarked to.
   *
   * @param missionId the mission
   * @return its name, or {@code null} when it is unknown or unnamed
   */
  private @Nullable String missionNameOf(UUID missionId) {
    MissionEarmark mission = earmarkTargets.mission(missionId);
    return mission == null ? null : mission.name();
  }

  /**
   * Mission counterpart of {@link #sumJobOrderAllocated}.
   *
   * @param item the entry.
   * @param excludeTargetId the mission id whose slice to exclude, or {@code null} to sum all.
   * @return the Σ of the (non-excluded) mission slice amounts.
   */
  private double sumMissionAllocated(@NotNull InventoryItem item, @Nullable UUID excludeTargetId) {
    return item.getMissionAllocations().stream()
        .filter(a -> a.getMissionId() != null)
        .filter(a -> excludeTargetId == null || !excludeTargetId.equals(a.getMissionId()))
        .mapToDouble(a -> a.getAmount() == null ? 0.0 : a.getAmount())
        .sum();
  }

  /**
   * Finds the entry's allocation slice for a job order.
   *
   * @param item the entry
   * @param targetId the job-order id
   * @return the matching slice, or {@code null} when the order is not allocated
   */
  private @Nullable InventoryJobOrderAllocation findJobOrderSlice(
      @NotNull InventoryItem item, UUID targetId) {
    return item.getJobOrderAllocations().stream()
        .filter(a -> targetId.equals(a.getJobOrderId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Mission counterpart of {@link #findJobOrderSlice}.
   *
   * @param item the entry.
   * @param targetId the mission id.
   * @return the matching slice, or {@code null}.
   */
  private @Nullable InventoryMissionAllocation findMissionSlice(
      @NotNull InventoryItem item, UUID targetId) {
    return item.getMissionAllocations().stream()
        .filter(a -> targetId.equals(a.getMissionId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Records the audit event for an allocation add, change or remove (REQ-AUDIT-001) with PII-free
   * details only.
   *
   * @param type the allocation event type
   * @param item the entry the slice belongs to
   * @param field the dimension written
   * @param ref the target reference (order display id or mission name)
   * @param amount the slice amount, or {@code null} for a removal
   */
  private void recordAllocation(
      AuditEventType type,
      InventoryItem item,
      InventoryAllocationDimension field,
      @Nullable String ref,
      @Nullable Double amount) {
    AuditDetails details = AuditDetails.of("dim", field).with("ref", ref);
    if (amount != null) {
      details = details.with("qty", amount);
    }
    auditRecorder.record(
        type, item.getId(), InventoryAuditLabels.label(item), item.getUser().getId(), details);
  }

  /**
   * Rejects linking an item to a job order that does not require its material, since such stock
   * would never appear in the order (REQ-ORDERS-018). Must run inside a transaction.
   *
   * @param material the inventory item's material
   * @param jobOrderId the order the item is being linked to
   * @throws NotFoundException when the order does not exist
   * @throws BadRequestException when the order does not require the material
   */
  private void assertMaterialRequiredByJobOrder(Material material, UUID jobOrderId) {
    Set<UUID> required =
        Entities.require(
            Optional.ofNullable(jobOrders.requiredMaterialIds(jobOrderId)), "JobOrder not found");
    if (!required.contains(material.getId())) {
      throw new BadRequestException(
          "Material "
              + material.getId()
              + " is not required by job order "
              + jobOrderId
              + "; an inventory item can only be linked to an order that needs its material.");
    }
  }

  /**
   * Rejects linking a game-item row to a job order that does not request that game item; a MATERIAL
   * order never accepts item stock (REQ-INV-031). Must run inside a transaction.
   *
   * @param gameItem the inventory row's game item
   * @param jobOrderId the order the row is being linked to
   * @throws NotFoundException when the order does not exist
   * @throws BadRequestException when the order does not request the game item
   */
  private void assertGameItemRequiredByJobOrder(GameItem gameItem, UUID jobOrderId) {
    Set<UUID> required =
        Entities.require(
            Optional.ofNullable(jobOrders.requiredGameItemIds(jobOrderId)), "JobOrder not found");
    if (!required.contains(gameItem.getId())) {
      throw new BadRequestException(
          "Game item "
              + gameItem.getId()
              + " is not requested by job order "
              + jobOrderId
              + "; an item stock row can only be linked to an ITEM order that requests its game"
              + " item.");
    }
  }

  /**
   * Rejects a mission allocation on a game-item row, which may be allocated only to ITEM job orders
   * (REQ-INV-031).
   *
   * @param item the entry being written
   * @throws BadRequestException when the entry is a game-item row
   */
  private void assertMissionDimensionAllowed(InventoryItem item) {
    if (item.getGameItem() != null) {
      throw new BadRequestException("Game-item stock cannot be assigned to a mission");
    }
  }

  /**
   * Resolves the check-in allocations for one dimension (REQ-INV-027): the explicit list when
   * non-empty, else a single full-amount allocation for {@code singleId}, else none.
   *
   * @param explicit the per-target split list; may be {@code null} or empty
   * @param singleId the single job-order or mission id; may be {@code null}
   * @param fullAmount the entry's amount, used for the single-id fallback
   * @return the allocations to write; never {@code null}
   */
  private static List<InventoryAllocationInput> effectiveAllocations(
      List<InventoryAllocationInput> explicit, UUID singleId, double fullAmount) {
    if (explicit != null && !explicit.isEmpty()) {
      return explicit;
    }
    if (singleId != null) {
      return List.of(new InventoryAllocationInput(singleId, fullAmount));
    }
    return List.of();
  }

  /**
   * Rejects a fractional allocation amount on a whole-unit entry ({@code PIECE} material or
   * game-item row).
   *
   * @param wholeUnits whether the entry only allows whole units
   * @param itemRow whether the entry is a game-item row (affects the message only)
   * @param amount the allocation amount to check
   * @throws BadRequestException when {@code wholeUnits} and {@code amount} is not whole
   */
  private static void requireWholeUnits(boolean wholeUnits, boolean itemRow, double amount) {
    if (wholeUnits && amount % 1 != 0) {
      throw new BadRequestException(
          itemRow
              ? "Amount must be a whole number for item stock"
              : "Amount must be a whole number for PIECE materials");
    }
  }

  /**
   * Sets, updates or removes an inventory item's note. The owner may always edit it; others only
   * when {@code isLogistician}. A blank note is stored as {@code null}; the supplied {@code
   * version} is checked.
   *
   * @throws NotFoundException when the item is unknown
   */
  @Transactional
  public InventoryItemDto updateNote(
      UUID id, InventoryItemNoteUpdateRequest request, UUID currentUserId, boolean isLogistician) {
    InventoryItem item =
        Entities.require(inventoryItemRepository.findById(id), "Inventory item not found");

    boolean isOwner = item.getUser().getId().equals(currentUserId);
    if (!isOwner && !isLogistician) {
      throw new AccessDeniedException(
          "You are not allowed to modify the note of this inventory item");
    }

    OptimisticLock.checkOptionalClient(
        item.getVersion(), request.version(), InventoryItem.class, id);

    String normalizedNote = StringNormalization.trimToNull(request.note());
    item.setNote(normalizedNote);

    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_NOTE_UPDATED,
        item.getId(),
        InventoryAuditLabels.label(item),
        item.getUser().getId(),
        normalizedNote == null
            ? "note=cleared"
            : "note=present(" + normalizedNote.length() + " chars)");
    return inventoryItemMapper.toDto(saved);
  }

  /**
   * Books out an inventory item as DISCARD, TRANSFER or SELL; delegates to {@link
   * InventoryCheckoutService#bookOutInventoryItem}. A depleted row is deleted.
   *
   * @param id the source inventory row id
   * @param dto type, amount, version and transfer/sell fields
   * @param currentUserId the caller's user id
   * @param isAdmin whether the caller is an admin (bypasses the owner check)
   * @return the reduced source or new target row, or {@code null} when the row is depleted
   * @throws NotFoundException when the item is unknown
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the amount
   *     exceeds stock, a SELL lacks terminal or amount, or a TRANSFER has no target (REQ-INV-025)
   */
  @Nullable
  @Transactional
  public InventoryItemDto bookOutInventoryItem(
      UUID id, InventoryItemBookOutDto dto, UUID currentUserId, boolean isAdmin) {
    return inventoryCheckoutService.bookOutInventoryItem(id, dto, currentUserId, isAdmin);
  }

  /**
   * Moves part or all of a row between the owner's personal pool and the shared pool (REQ-INV-007);
   * delegates to {@link InventoryCheckoutService#rebookPersonal}.
   *
   * @param id the source inventory row id
   * @param dto amount, version and target org-unit pool
   * @param currentUserId the caller's user id
   * @param isAdmin whether the caller is an admin (bypasses the owner check)
   * @return the new row holding the moved quantity
   * @throws NotFoundException when the source row or the picked org unit is unknown
   * @throws BadRequestException when the amount is invalid or the personal invariant would break
   */
  @Transactional
  public InventoryItemDto rebookPersonal(
      UUID id, InventoryItemPersonalRebookDto dto, UUID currentUserId, boolean isAdmin) {
    return inventoryCheckoutService.rebookPersonal(id, dto, currentUserId, isAdmin);
  }

  /**
   * Deletes every non-personal inventory item ("globales Lager leeren"); delegates to {@link
   * InventoryCheckoutService#deleteAllGlobalInventory}.
   *
   * @return number of rows deleted
   */
  @Transactional
  public int deleteAllGlobalInventory() {
    return inventoryCheckoutService.deleteAllGlobalInventory();
  }

  /**
   * Bulk-checkout: removes all inventory items with the given IDs that belong to the authenticated
   * user. Delegates to {@link InventoryCheckoutService#bulkCheckout}.
   *
   * @param request the bulk checkout request containing item IDs
   * @param currentUserId the UUID of the authenticated user (JWT sub)
   */
  @Transactional
  public void bulkCheckout(BulkCheckoutRequest request, UUID currentUserId) {
    inventoryCheckoutService.bulkCheckout(request, currentUserId);
  }

  /**
   * Rebooks the listed rows of the caller's own inventory in one action (REQ-INV-036); rows already
   * at the target are skipped, any other obstacle aborts. Delegates to {@link
   * InventoryCheckoutService#bulkRebook}.
   *
   * @param request the selection, mode and target fields
   * @param currentUserId the caller's user id
   * @return the moved and skipped counts
   */
  @Transactional
  public BulkRebookResultDto bulkRebook(BulkRebookRequest request, UUID currentUserId) {
    return inventoryCheckoutService.bulkRebook(request, currentUserId);
  }

  /**
   * Updates an inventory item's delivered flag; delegates to {@link
   * InventoryCheckoutService#updateDelivered}.
   *
   * @param id the inventory item id
   * @param request delivered flag and version
   * @param currentUserId the caller's user id
   * @param isLogistician whether the caller has logistician rights or higher
   * @return the updated {@link InventoryItemDto}
   * @throws NotFoundException when the item is unknown
   */
  @Transactional
  public InventoryItemDto updateDelivered(
      UUID id, UpdateDeliveredRequest request, UUID currentUserId, boolean isLogistician) {
    return inventoryCheckoutService.updateDelivered(id, request, currentUserId, isLogistician);
  }
}

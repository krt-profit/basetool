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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.exception.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationDimension;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationInput;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.support.InventoryAuditLabels;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory-item facade — the single service seam the {@code InventoryItemController} and the
 * material-collection / craftability callers depend on for the squadron's physical stock of refined
 * and raw materials.
 *
 * <p>Each item links to a material (UEX commodity), optionally to a user (the owner, or null for
 * shared/squadron stock), and optionally to a job order or mission. This class implements the
 * create/update/note cycle directly and delegates the two large cohesive clusters carved out in the
 * L2 split (#921) so the public API stays byte-for-byte stable:
 *
 * <ul>
 *   <li>every read / aggregation projection (per-material aggregate, {@code /grouped} roll-ups,
 *       per-stack drilldowns, flat and per-material listings, craftability stock slices, job-order
 *       material collection) → {@link InventoryAggregationService};
 *   <li>every checkout write (book-out consume/transfer/sell, personal↔shared rebooking, bulk
 *       checkout, delivered toggle, admin global wipe) → {@link InventoryCheckoutService}.
 * </ul>
 *
 * <p>The delegating write methods keep their own {@code @Transactional} so the read-write
 * transaction opens here and the sub-service's {@code @Transactional} joins it (REQUIRED
 * propagation) — a delegating write is never trapped in the class-level {@code readOnly} default.
 * This facade stays in the {@code staffelScopedServicesMustWireOwnerScopeOrAuthHelper} whitelist
 * and keeps its {@code OwnerScopeService} dependency (used by {@link #createInventoryItem}) so the
 * multi-tenant org-unit stamp cannot be dropped.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryItemService {

  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final MaterialRepository materialRepository;
  private final LocationRepository locationRepository;
  private final JobOrderRepository jobOrderRepository;
  private final MissionRepository missionRepository;
  private final InventoryItemMapper inventoryItemMapper;
  private final OwnerScopeService ownerScopeService;
  private final JobOrderItemService jobOrderItemService;
  private final AuditService auditService;
  private final InventoryAggregationService inventoryAggregationService;
  private final InventoryCheckoutService inventoryCheckoutService;

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
    return inventoryAggregationService.getOwnedStockSlices(userId);
  }

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
   * Per-material drilldown — lists every individual inventory row for the given material. Used by
   * the inventory drilldown page.
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
   * User-scoped inventory list. Excludes personal items because those have their own dedicated
   * service.
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
  public List<de.greluc.krt.profit.basetool.backend.model.dto.GroupedInventoryDto>
      getMyAggregatedInventory(UUID userId) {
    return inventoryAggregationService.getMyAggregatedInventory(userId);
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
    return inventoryAggregationService.getMyAggregatedInventory(userId, jobOrderIds, missionIds);
  }

  /**
   * Filter-only convenience overload of {@link #getMyAggregatedInventory(UUID, List, Integer, List,
   * List, boolean)} that returns both the caller's shared and personal stacks (no personal-only
   * narrowing).
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
    return inventoryAggregationService.getMyAggregatedInventory(
        userId, materialIds, minQuality, jobOrderIds, missionIds);
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
    return inventoryAggregationService.getMyAggregatedInventory(
        userId, materialIds, minQuality, jobOrderIds, missionIds, personalOnly, nonPersonalOnly);
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
    return inventoryAggregationService.getAllAggregatedInventory(materialIds, minQuality);
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
    return inventoryAggregationService.getAllAggregatedInventory(
        materialIds, minQuality, jobOrderIds, missionIds);
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
    return inventoryAggregationService.getMyStackEntries(
        userId, materialId, locationId, quality, personal, owningOrgUnitId, pageable);
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
    return inventoryAggregationService.getAllStackEntries(
        materialId, userId, locationId, quality, owningOrgUnitId, pageable);
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
    return inventoryAggregationService.getAllInventory(materialIds, minQuality, pageable);
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
    return inventoryAggregationService.getAllInventory(
        materialIds, minQuality, jobOrderIds, missionIds, pageable);
  }

  /**
   * Lists every inventory item linked to {@code missionId} as display DTOs for the mission-detail
   * Wirtschaft "Lagereinträge" table (#1138). Replaces the former eagerly embedded {@code
   * MissionDto.inventoryEntries} with a dedicated read. Deliberately unscoped among members — it
   * reproduces exactly the removed field's behaviour, the shared mission-stockpile view visible to
   * any member (the {@code /api/v1/inventory/**} filter rule already keeps guests out).
   *
   * @param missionId the mission whose linked inventory to list
   * @return the mission's inventory items as DTOs (empty when none)
   */
  @Transactional(readOnly = true)
  public List<InventoryItemDto> getMissionInventory(UUID missionId) {
    return inventoryItemRepository.findByMissionId(missionId).stream()
        .map(inventoryItemMapper::toDto)
        .toList();
  }

  /**
   * Creates a new inventory item. Resolves every shallow id reference (material, location, owner,
   * mission, job order) and rejects with 404 / 400 for unknown ids. Job-order link triggers an
   * eligibility check (material must match the order's material list).
   *
   * @throws NotFoundException when any referenced id is unknown
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the material
   *     does not satisfy the job order's requirements
   */
  @Transactional
  public InventoryItemDto createInventoryItem(
      InventoryItemCreateDto dto, UUID currentUserId, boolean isAdmin) {
    UUID targetUserId = dto.userId() != null ? dto.userId() : currentUserId;
    if (!targetUserId.equals(currentUserId) && !isAdmin) {
      throw new AccessDeniedException(
          "You are not allowed to create inventory items for other users");
    }

    final User user =
        userRepository
            .findById(targetUserId)
            .orElseThrow(() -> new NotFoundException("User not found"));
    final Material material =
        materialRepository
            .findById(dto.materialId())
            .orElseThrow(() -> new NotFoundException("Material not found"));
    final Location location =
        locationRepository
            .findById(dto.locationId())
            .orElseThrow(() -> new NotFoundException("Location not found"));

    Boolean isPersonal = dto.personal() != null ? dto.personal() : false;

    // The owning org unit is the eighth dimension of an inventory stack's identity. Resolve it up
    // front (validating the picker output) so the new row is stamped with the correct org-unit
    // pool. Inventory is append-only by default: every create inserts its own row and rows that
    // share the stack identity are grouped only for display (group-on-read, see
    // aggregateInventoryItems). The scoped exception is the write-time stock merge below
    // (REQ-INV-026, ADR-0097): a PIECE row (or an SCU row with the per-action opt-in) is folded
    // into
    // a matching stack, re-introducing the pessimistic merge lock only on that one path.
    final OrgUnit owningOrgUnit =
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, dto.owningOrgUnitId());

    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setOwningOrgUnit(owningOrgUnit);
    item.setMaterial(material);
    item.setLocation(location);
    item.setQuality(dto.quality());
    item.setAmount(InventoryItem.roundToScuScale(dto.amount()));
    item.setPersonal(isPersonal);
    // Variante C (REQ-INV-027, R4): split at check-in. The explicit per-dimension allocation lists
    // take precedence; otherwise the single jobOrderId / missionId writes one full-amount slice
    // (backward compatible). Guards mirror the per-allocation write endpoints: a personal entry
    // carries no assignment, a job-order target's material must be one the order needs, no target
    // twice, PIECE amounts are whole, and per dimension the Σ of the slices must stay within the
    // entry amount (R5) — else 422.
    List<InventoryAllocationInput> jobAllocations =
        effectiveAllocations(dto.jobOrderAllocations(), dto.jobOrderId(), item.getAmount());
    List<InventoryAllocationInput> missionAllocations =
        effectiveAllocations(dto.missionAllocations(), dto.missionId(), item.getAmount());
    if (Boolean.TRUE.equals(isPersonal)
        && (!jobAllocations.isEmpty() || !missionAllocations.isEmpty())) {
      throw new BadRequestException("Personal items cannot be assigned to a mission or job order");
    }
    boolean piece = material.getQuantityType() == QuantityType.PIECE;
    Set<UUID> seenOrders = new HashSet<>();
    for (InventoryAllocationInput allocation : jobAllocations) {
      if (!seenOrders.add(allocation.targetId())) {
        throw new BadRequestException("A job order may be assigned at most once at check-in");
      }
      requireWholeForPiece(piece, allocation.amount());
      JobOrder order =
          jobOrderRepository
              .findById(allocation.targetId())
              .orElseThrow(() -> new NotFoundException("JobOrder not found"));
      assertMaterialRequiredByJobOrder(material, order);
      InventoryAllocations.addJobOrder(
          item, order, InventoryItem.roundToScuScale(allocation.amount()), false);
    }
    Set<UUID> seenMissions = new HashSet<>();
    for (InventoryAllocationInput allocation : missionAllocations) {
      if (!seenMissions.add(allocation.targetId())) {
        throw new BadRequestException("A mission may be assigned at most once at check-in");
      }
      requireWholeForPiece(piece, allocation.amount());
      Mission missionTarget =
          missionRepository
              .findById(allocation.targetId())
              .orElseThrow(() -> new NotFoundException("Mission not found"));
      InventoryAllocations.addMission(
          item, missionTarget, InventoryItem.roundToScuScale(allocation.amount()));
    }
    if (!InventoryAllocations.fits(item)) {
      throw new OverAllocationException();
    }

    InventoryItem saved = inventoryItemRepository.save(item);
    auditService.record(
        AuditEventType.INVENTORY_ITEM_CREATED,
        item.getId(),
        InventoryAuditLabels.label(item),
        item.getUser().getId(),
        AuditDetails.of("qty", item.getAmount())
            .with("q", item.getQuality())
            .with("personal", item.getPersonal())
            .with("jobOrder", InventoryAuditLabels.jobOrderRef(item))
            .with("mission", InventoryAuditLabels.missionName(item)));
    // Stock merge (REQ-INV-026): a PIECE row is folded into a matching stack unconditionally; an
    // SCU row only when the caller ticked the per-action opt-in. Returns the surviving row.
    InventoryItem merged =
        inventoryCheckoutService.mergeStockIfRequested(
            saved, Boolean.TRUE.equals(dto.mergeStock()));
    return inventoryItemMapper.toDto(merged);
  }

  /**
   * Epsilon for the over-allocation comparison. Both an entry's amount and every slice amount are
   * SCU-rounded to three decimals at the persistence boundary, so a proposed Σ that lands a hair
   * over the entry's amount purely through {@code double} noise (rather than a real
   * over-allocation) is tolerated; anything beyond this margin is a genuine 422.
   */
  private static final double OVER_ALLOCATION_EPSILON = 1e-6;

  /**
   * Adds a quantity slice earmarking part of the entry to a job order or mission (Variante C,
   * REQ-INV-027). Guards: the entry must not be personal (personal stock carries no assignment); a
   * job-order slice's material must be required by the order (REQ-ORDERS-018); the same target may
   * be allocated only once (the per-dimension unique constraint); and the new Σ of the dimension
   * must stay within the entry's amount (rule R5, else 422). The write bumps the entry's
   * {@code @Version} (loaded under a forced increment), so the returned DTO carries the version the
   * client must echo on its next edit.
   *
   * @param id the inventory entry id.
   * @param dto the allocation write payload (dimension, target, amount, echoed entry version).
   * @return the updated entry DTO (new version + both refreshed slice lists).
   * @throws NotFoundException when the entry, job order or mission is unknown.
   * @throws BadRequestException when the entry is personal, the material is not required by the
   *     order, the amount is missing/non-positive/fractional-for-PIECE, or the target is already
   *     allocated.
   * @throws OverAllocationException when the new dimension Σ would exceed the entry's amount.
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echoed version
   *     is stale.
   */
  @Transactional
  public InventoryItemDto addAllocation(UUID id, InventoryAllocationWriteDto dto) {
    InventoryItem item = loadForAllocationWrite(id, dto);
    assertNotPersonal(item);
    double amount = requireWriteAmount(dto, item);
    switch (dto.field()) {
      case JOB_ORDER -> {
        JobOrder jobOrder =
            jobOrderRepository
                .findById(dto.targetId())
                .orElseThrow(() -> new NotFoundException("JobOrder not found"));
        assertMaterialRequiredByJobOrder(item.getMaterial(), jobOrder);
        if (findJobOrderSlice(item, dto.targetId()) != null) {
          throw new BadRequestException("This job order is already allocated on the entry");
        }
        assertFits(sumJobOrderAllocated(item, null) + amount, item.getAmount());
        InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
        slice.setInventoryItem(item);
        slice.setJobOrder(jobOrder);
        slice.setAmount(amount);
        item.getJobOrderAllocations().add(slice);
        recordAllocation(
            AuditEventType.INVENTORY_ALLOCATION_ADDED,
            item,
            dto.field(),
            "#" + jobOrder.getDisplayId(),
            amount);
      }
      case MISSION -> {
        final Mission mission =
            missionRepository
                .findById(dto.targetId())
                .orElseThrow(() -> new NotFoundException("Mission not found"));
        if (findMissionSlice(item, dto.targetId()) != null) {
          throw new BadRequestException("This mission is already allocated on the entry");
        }
        assertFits(sumMissionAllocated(item, null) + amount, item.getAmount());
        InventoryMissionAllocation slice = new InventoryMissionAllocation();
        slice.setInventoryItem(item);
        slice.setMission(mission);
        slice.setAmount(amount);
        item.getMissionAllocations().add(slice);
        recordAllocation(
            AuditEventType.INVENTORY_ALLOCATION_ADDED,
            item,
            dto.field(),
            mission.getName(),
            amount);
      }
      default -> throw new IllegalStateException("Unhandled allocation dimension: " + dto.field());
    }
    return mapWithForcedVersion(inventoryItemRepository.saveAndFlush(item));
  }

  /**
   * Changes the amount of an existing quantity slice (Variante C, REQ-INV-027). The target slice
   * must exist; the new Σ of the dimension (with this slice's new amount substituted for its old
   * one) must stay within the entry's amount (rule R5, else 422). Same {@code @Version} bump and
   * personal guard as {@link #addAllocation}.
   *
   * @param id the inventory entry id.
   * @param dto the allocation write payload (dimension, target, new amount, echoed entry version).
   * @return the updated entry DTO.
   * @throws NotFoundException when the entry or the target slice is unknown.
   * @throws BadRequestException when the entry is personal or the amount is
   *     missing/non-positive/fractional-for-PIECE.
   * @throws OverAllocationException when the new dimension Σ would exceed the entry's amount.
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echoed version
   *     is stale.
   */
  @Transactional
  public InventoryItemDto changeAllocation(UUID id, InventoryAllocationWriteDto dto) {
    InventoryItem item = loadForAllocationWrite(id, dto);
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
            "#" + slice.getJobOrder().getDisplayId(),
            amount);
      }
      case MISSION -> {
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
            slice.getMission().getName(),
            amount);
      }
      default -> throw new IllegalStateException("Unhandled allocation dimension: " + dto.field());
    }
    return mapWithForcedVersion(inventoryItemRepository.saveAndFlush(item));
  }

  /**
   * Removes a quantity slice, releasing its amount back to the entry's unallocated remainder
   * (Variante C, REQ-INV-027). Removal only lowers a dimension's Σ, so there is no over-allocation
   * check and no personal guard (a personal entry simply has no slices to remove → 404). The target
   * slice must exist. Same {@code @Version} bump as {@link #addAllocation}.
   *
   * @param id the inventory entry id.
   * @param dto the allocation write payload (dimension, target, echoed entry version; amount
   *     ignored).
   * @return the updated entry DTO.
   * @throws NotFoundException when the entry or the target slice is unknown.
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echoed version
   *     is stale.
   */
  @Transactional
  public InventoryItemDto removeAllocation(UUID id, InventoryAllocationWriteDto dto) {
    InventoryItem item = loadForAllocationWrite(id, dto);
    switch (dto.field()) {
      case JOB_ORDER -> {
        InventoryJobOrderAllocation slice = findJobOrderSlice(item, dto.targetId());
        if (slice == null) {
          throw new NotFoundException("Job-order allocation not found");
        }
        String ref = slice.getJobOrder() != null ? "#" + slice.getJobOrder().getDisplayId() : "-";
        item.getJobOrderAllocations().remove(slice);
        recordAllocation(AuditEventType.INVENTORY_ALLOCATION_REMOVED, item, dto.field(), ref, null);
      }
      case MISSION -> {
        InventoryMissionAllocation slice = findMissionSlice(item, dto.targetId());
        if (slice == null) {
          throw new NotFoundException("Mission allocation not found");
        }
        String ref = slice.getMission() != null ? slice.getMission().getName() : "-";
        item.getMissionAllocations().remove(slice);
        recordAllocation(AuditEventType.INVENTORY_ALLOCATION_REMOVED, item, dto.field(), ref, null);
      }
      default -> throw new IllegalStateException("Unhandled allocation dimension: " + dto.field());
    }
    return mapWithForcedVersion(inventoryItemRepository.saveAndFlush(item));
  }

  /**
   * Maps a just-flushed entry to its DTO carrying the post-commit force-increment version (see
   * {@link InventoryAllocations#forcedNextVersion}). The allocation writes only mutate an
   * inverse-side slice and force-bump the entry {@code @Version} via {@code
   * OPTIMISTIC_FORCE_INCREMENT}, which Hibernate applies at commit — so the client must echo {@code
   * loaded + 1}, not the pre-increment value the entity still shows here, or its next write to the
   * same entry 409s (REQ-FE-003).
   *
   * @param saved the just-flushed entry; never {@code null}.
   * @return the entry DTO carrying the version the client should echo next.
   */
  private InventoryItemDto mapWithForcedVersion(InventoryItem saved) {
    return inventoryItemMapper
        .toDto(saved)
        .withVersion(InventoryAllocations.forcedNextVersion(saved));
  }

  /**
   * Loads an entry for a per-allocation write under a forced version increment and enforces the
   * optimistic-lock echo. The forced increment (see {@code
   * InventoryItemRepository.findByIdForAllocationWrite}) makes the entry's {@code @Version} the
   * single concurrency token for both its splits, since a slice change on the inverse collection
   * side would not otherwise dirty the entry row.
   *
   * @param id the inventory entry id.
   * @param dto the write payload carrying the echoed version.
   * @return the managed entry, ready for slice mutation.
   * @throws NotFoundException when the entry is unknown.
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echoed version
   *     is stale.
   */
  private InventoryItem loadForAllocationWrite(UUID id, InventoryAllocationWriteDto dto) {
    InventoryItem item =
        inventoryItemRepository
            .findByIdForAllocationWrite(id)
            .orElseThrow(() -> new NotFoundException("Inventory item not found"));
    OptimisticLock.checkOptionalClient(item.getVersion(), dto.version(), InventoryItem.class, id);
    return item;
  }

  /**
   * Rejects an allocation write on a personal entry — personal stock is attributable solely to its
   * owner and carries no job-order/mission assignment (the same invariant {@link
   * #createInventoryItem} and the allocation writes enforce).
   *
   * @param item the entry being written.
   * @throws BadRequestException when the entry is personal.
   */
  private void assertNotPersonal(InventoryItem item) {
    if (Boolean.TRUE.equals(item.getPersonal())) {
      throw new BadRequestException("Personal items cannot be assigned to a mission or job order");
    }
  }

  /**
   * Validates and normalizes an add/change amount: present, strictly positive, whole for a {@code
   * PIECE} material, then SCU-rounded to storage precision. The material is read off the already
   * managed entry rather than a separate lookup.
   *
   * @param dto the write payload.
   * @param item the entry (for its material's quantity type).
   * @return the validated, SCU-rounded amount.
   * @throws BadRequestException when the amount is missing, non-positive, or fractional for a PIECE
   *     material.
   */
  private double requireWriteAmount(InventoryAllocationWriteDto dto, InventoryItem item) {
    Double raw = dto.amount();
    if (raw == null) {
      throw new BadRequestException("An allocation amount is required");
    }
    if (raw <= 0) {
      throw new BadRequestException("An allocation amount must be positive");
    }
    if (item.getMaterial() != null
        && item.getMaterial().getQuantityType() == QuantityType.PIECE
        && raw % 1 != 0) {
      throw new BadRequestException("A PIECE allocation amount must be a whole number");
    }
    return InventoryItem.roundToScuScale(raw);
  }

  /**
   * Enforces rule R5: a dimension's proposed Σ must not exceed the entry's own amount. Both
   * operands are SCU-rounded so floating-point noise near equality does not spuriously trip the
   * guard.
   *
   * @param proposedSum the dimension's Σ after the write.
   * @param capacity the entry's amount (the shared budget for the dimension).
   * @throws OverAllocationException when {@code proposedSum} exceeds {@code capacity} beyond {@link
   *     #OVER_ALLOCATION_EPSILON}.
   */
  private void assertFits(double proposedSum, double capacity) {
    if (InventoryItem.roundToScuScale(proposedSum) - capacity > OVER_ALLOCATION_EPSILON) {
      throw new OverAllocationException();
    }
  }

  /**
   * Sums the job-order dimension's currently allocated amount, optionally excluding one target's
   * slice (used by the change path so the slice being edited does not count against its own new
   * amount).
   *
   * @param item the entry.
   * @param excludeTargetId the job-order id whose slice to exclude, or {@code null} to sum all.
   * @return the Σ of the (non-excluded) job-order slice amounts.
   */
  private double sumJobOrderAllocated(InventoryItem item, UUID excludeTargetId) {
    return item.getJobOrderAllocations().stream()
        .filter(a -> a.getJobOrder() != null)
        .filter(a -> excludeTargetId == null || !excludeTargetId.equals(a.getJobOrder().getId()))
        .mapToDouble(a -> a.getAmount() == null ? 0.0 : a.getAmount())
        .sum();
  }

  /**
   * Mission counterpart of {@link #sumJobOrderAllocated}.
   *
   * @param item the entry.
   * @param excludeTargetId the mission id whose slice to exclude, or {@code null} to sum all.
   * @return the Σ of the (non-excluded) mission slice amounts.
   */
  private double sumMissionAllocated(InventoryItem item, UUID excludeTargetId) {
    return item.getMissionAllocations().stream()
        .filter(a -> a.getMission() != null)
        .filter(a -> excludeTargetId == null || !excludeTargetId.equals(a.getMission().getId()))
        .mapToDouble(a -> a.getAmount() == null ? 0.0 : a.getAmount())
        .sum();
  }

  /**
   * Finds the entry's job-order slice for a target order, or {@code null} when the order is not
   * allocated on the entry.
   *
   * @param item the entry.
   * @param targetId the job-order id.
   * @return the matching slice, or {@code null}.
   */
  private InventoryJobOrderAllocation findJobOrderSlice(InventoryItem item, UUID targetId) {
    return item.getJobOrderAllocations().stream()
        .filter(a -> a.getJobOrder() != null && targetId.equals(a.getJobOrder().getId()))
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
  private InventoryMissionAllocation findMissionSlice(InventoryItem item, UUID targetId) {
    return item.getMissionAllocations().stream()
        .filter(a -> a.getMission() != null && targetId.equals(a.getMission().getId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Records the REQ-AUDIT-001 event for an allocation add/change/remove. The details carry only
   * PII-free tokens: the dimension, the target reference ({@code #<displayId>} for an order, the
   * mission name — the same non-PII reference the entry-level create/update audit already emits)
   * and the amount (omitted for a removal).
   *
   * @param type the allocation event type.
   * @param item the entry the slice belongs to.
   * @param field the dimension written.
   * @param ref the target reference token (order display id or mission name).
   * @param amount the slice amount, or {@code null} for a removal.
   */
  private void recordAllocation(
      AuditEventType type,
      InventoryItem item,
      InventoryAllocationDimension field,
      String ref,
      Double amount) {
    AuditDetails details = AuditDetails.of("dim", field).with("ref", ref);
    if (amount != null) {
      details = details.with("qty", amount);
    }
    auditService.record(
        type, item.getId(), InventoryAuditLabels.label(item), item.getUser().getId(), details);
  }

  /**
   * Rejects linking an inventory item to a job order whose requirements do not include the item's
   * material (REQ-ORDERS-018). An order's material view is built solely from its requirements
   * (material lines for a MATERIAL order, blueprint-derived materials for an ITEM order) with
   * linked stock matched onto those rows; a link for a non-required material therefore never
   * surfaces in the order — it would bind stock to the order while staying invisible (an orphaned
   * link). The authoritative required-material set is {@link
   * JobOrderItemService#requiredMaterialIds(JobOrder)}, which covers both order kinds. Both call
   * sites are {@code @Transactional}, so the helper's lazy walk of the order's item/material
   * collections is safe.
   *
   * @param material the inventory item's material.
   * @param jobOrder the order the item is being linked to.
   * @throws BadRequestException when the order does not require the material.
   */
  private void assertMaterialRequiredByJobOrder(Material material, JobOrder jobOrder) {
    if (!jobOrderItemService.requiredMaterialIds(jobOrder).contains(material.getId())) {
      throw new BadRequestException(
          "Material "
              + material.getId()
              + " is not required by job order "
              + jobOrder.getId()
              + "; an inventory item can only be linked to an order that needs its material.");
    }
  }

  /**
   * Resolves the effective split-at-check-in allocations for one dimension (Variante C,
   * REQ-INV-027, R4): the caller-supplied {@code explicit} list when it carries any entry;
   * otherwise, for backward compatibility, a single full-amount allocation from the legacy scalar
   * {@code singleId}; otherwise none.
   *
   * @param explicit the per-target split list from the create payload; may be {@code null}/empty.
   * @param singleId the legacy single job-order / mission id; may be {@code null}.
   * @param fullAmount the entry's amount, used when falling back to the single id.
   * @return the effective allocations to write for this dimension; never {@code null}.
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
   * Rejects a fractional allocation amount for a {@code PIECE} material (whole units only),
   * mirroring the entry-amount rule and the per-allocation write endpoints.
   *
   * @param piece whether the entry's material is measured in whole PIECEs.
   * @param amount the allocation amount to check.
   * @throws BadRequestException when {@code piece} and {@code amount} is not a whole number.
   */
  private static void requireWholeForPiece(boolean piece, double amount) {
    if (piece && amount % 1 != 0) {
      throw new BadRequestException("Amount must be a whole number for PIECE materials");
    }
  }

  /**
   * Sets, updates or removes the free-text note of an inventory item.
   *
   * <p>Access rules:
   *
   * <ul>
   *   <li>The owner of the item may always modify its note.
   *   <li>A non-owner may only modify the note when {@code isLogistician} is {@code true} (i.e.
   *       they hold {@code ROLE_LOGISTICIAN} or a role that inherits it such as {@code
   *       ROLE_OFFICER}/{@code ROLE_ADMIN}).
   * </ul>
   *
   * <p>A blank or empty note is normalized to {@code null} and thus effectively removes the note.
   * Optimistic locking is enforced via the supplied {@code version}.
   *
   * @throws NotFoundException when the item is unknown
   */
  @Transactional
  public InventoryItemDto updateNote(
      UUID id, InventoryItemNoteUpdateRequest request, UUID currentUserId, boolean isLogistician) {
    InventoryItem item =
        inventoryItemRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("Inventory item not found"));

    boolean isOwner = item.getUser().getId().equals(currentUserId);
    if (!isOwner && !isLogistician) {
      throw new AccessDeniedException(
          "You are not allowed to modify the note of this inventory item");
    }

    OptimisticLock.checkOptionalClient(
        item.getVersion(), request.version(), InventoryItem.class, id);

    String normalizedNote = StringNormalization.trimToNull(request.note());
    item.setNote(normalizedNote);

    // saveAndFlush so the response carries the post-increment @Version —
    // otherwise editing a note right after an association change 409s.
    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    // PII: the note body is user free text — record only its presence/length, never the content.
    auditService.record(
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
   * Consumes or transfers an inventory item — delegates to {@link
   * InventoryCheckoutService#bookOutInventoryItem}.
   *
   * <p>The {@code type} discriminator selects DISCARD (just decrement), TRANSFER (append-only move
   * to the target location/owner) or SELL (decrement plus a mission finance entry). When the
   * post-decrement quantity is floating-point-zero the row is removed entirely. An explicit {@code
   * TRANSFER} carrying neither a target user nor a target location is rejected up front
   * (REQ-INV-025).
   *
   * @param id the source inventory row id
   * @param dto the book-out payload (type, amount, version, transfer/sell fields)
   * @param currentUserId the authenticated caller's user id
   * @param isAdmin whether the caller holds an admin role (bypasses the owner check)
   * @return the reduced source (or new target) row DTO, or {@code null} when the row is depleted
   * @throws NotFoundException when the item is unknown
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the requested
   *     amount exceeds the available quantity, when a SELL is missing its terminal or a valid sell
   *     amount, or when a {@code TRANSFER} carries neither a target user nor a target location
   */
  @Transactional
  public InventoryItemDto bookOutInventoryItem(
      UUID id, InventoryItemBookOutDto dto, UUID currentUserId, boolean isAdmin) {
    return inventoryCheckoutService.bookOutInventoryItem(id, dto, currentUserId, isAdmin);
  }

  /**
   * Rebooks (Umbuchung) part or all of an inventory row between the owner's personal pool and the
   * shared squadron pool by toggling its {@code personal} marker (REQ-INV-007) — delegates to
   * {@link InventoryCheckoutService#rebookPersonal}.
   *
   * @param id the source inventory row id
   * @param dto the rebooking payload (amount, version, target org-unit pool)
   * @param currentUserId the authenticated caller's user id
   * @param isAdmin whether the caller holds an admin role (bypasses the owner check)
   * @return the persisted new-row DTO (the moved quantity in its new pool)
   * @throws NotFoundException when the source row or the picked org unit is unknown
   * @throws BadRequestException when the amount is non-positive, exceeds the available quantity, or
   *     a personalize would violate the personal/association invariant
   */
  @Transactional
  public InventoryItemDto rebookPersonal(
      UUID id, InventoryItemPersonalRebookDto dto, UUID currentUserId, boolean isAdmin) {
    return inventoryCheckoutService.rebookPersonal(id, dto, currentUserId, isAdmin);
  }

  /**
   * Removes every non-personal inventory item from the database — the admin "globales Lager leeren"
   * action. Personal entries are kept on purpose. Delegates to {@link
   * InventoryCheckoutService#deleteAllGlobalInventory}.
   *
   * @return number of inventory rows deleted (0 if the global inventory was already empty)
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
   * Returns all inventory items linked to the given job order, sorted server-side by owner name,
   * location, material name, quality (desc), quantity (desc). Delegates to {@link
   * InventoryAggregationService#getMaterialCollection}.
   *
   * @param jobOrderId the UUID of the job order
   * @return sorted list of {@link MaterialCollectionEntryDto}
   * @throws NotFoundException when the job order is unknown
   */
  public List<MaterialCollectionEntryDto> getMaterialCollection(UUID jobOrderId) {
    return inventoryAggregationService.getMaterialCollection(jobOrderId);
  }

  /**
   * Updates the delivered status of an inventory item. Delegates to {@link
   * InventoryCheckoutService#updateDelivered}.
   *
   * @param id the UUID of the inventory item
   * @param request the update request containing delivered flag and version
   * @param currentUserId the UUID of the authenticated user
   * @param isLogistician whether the user has logistician or higher role
   * @return updated {@link InventoryItemDto}
   * @throws NotFoundException when the item is unknown
   */
  @Transactional
  public InventoryItemDto updateDelivered(
      UUID id, UpdateDeliveredRequest request, UUID currentUserId, boolean isLogistician) {
    return inventoryCheckoutService.updateDelivered(id, request, currentUserId, isLogistician);
  }
}

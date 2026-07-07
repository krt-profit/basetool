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
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemUpdateDto;
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
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.util.List;
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
   *     personal = true} rows) — the "Mein Lager" personal-entries-only filter; when {@code false},
   *     both shared and personal stacks are returned
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
          boolean personalOnly) {
    return inventoryAggregationService.getMyAggregatedInventory(
        userId, materialIds, minQuality, jobOrderIds, missionIds, personalOnly);
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
   * @param jobOrderId the stack's job-order id, or {@code null}
   * @param missionId the stack's mission id, or {@code null}
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
      UUID jobOrderId,
      UUID missionId,
      Boolean personal,
      UUID owningOrgUnitId,
      Pageable pageable) {
    return inventoryAggregationService.getMyStackEntries(
        userId,
        materialId,
        locationId,
        quality,
        jobOrderId,
        missionId,
        personal,
        owningOrgUnitId,
        pageable);
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
   * @param jobOrderId the stack's job-order id, or {@code null}
   * @param missionId the stack's mission id, or {@code null}
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null}
   * @param pageable the page request (the query forces oldest-first by creation instant)
   * @return one page of the stack's entries, oldest-first
   */
  public Page<InventoryItemDto> getAllStackEntries(
      UUID materialId,
      UUID userId,
      UUID locationId,
      Integer quality,
      UUID jobOrderId,
      UUID missionId,
      UUID owningOrgUnitId,
      Pageable pageable) {
    return inventoryAggregationService.getAllStackEntries(
        materialId, userId, locationId, quality, jobOrderId, missionId, owningOrgUnitId, pageable);
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

    Mission mission = null;
    if (dto.missionId() != null) {
      mission =
          missionRepository
              .findById(dto.missionId())
              .orElseThrow(() -> new NotFoundException("Mission not found"));
    }

    JobOrder jobOrder = null;
    if (dto.jobOrderId() != null) {
      jobOrder =
          jobOrderRepository
              .findById(dto.jobOrderId())
              .orElseThrow(() -> new NotFoundException("JobOrder not found"));
      assertMaterialRequiredByJobOrder(material, jobOrder);
    }

    Boolean isPersonal = dto.personal() != null ? dto.personal() : false;

    if (Boolean.TRUE.equals(isPersonal) && (mission != null || jobOrder != null)) {
      throw new BadRequestException("Personal items cannot be assigned to a mission or job order");
    }

    // The owning org unit is the eighth dimension of an inventory stack's identity. Resolve it up
    // front (validating the picker output) so the new row is stamped with the correct org-unit
    // pool. Inventory is append-only: every create inserts its own row and is never folded into an
    // existing stack — rows that share the stack identity are grouped only for display
    // (group-on-read, see aggregateInventoryItems). This also removes the read-add-write race the
    // former merge path had to guard with a pessimistic lock.
    final OrgUnit owningOrgUnit =
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, dto.owningOrgUnitId());

    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setOwningOrgUnit(owningOrgUnit);
    item.setMaterial(material);
    item.setLocation(location);
    item.setQuality(dto.quality());
    item.setAmount(roundAmount(dto.amount()));
    item.setPersonal(isPersonal);
    item.setMission(mission);
    item.setJobOrder(jobOrder);

    InventoryItem saved = inventoryItemRepository.save(item);
    auditService.record(
        AuditEventType.INVENTORY_ITEM_CREATED,
        item.getId(),
        inventoryLabel(item),
        item.getUser().getId(),
        AuditDetails.of("qty", item.getAmount())
            .with("q", item.getQuality())
            .with("personal", item.getPersonal())
            .with("jobOrder", jobOrderRef(item))
            .with("mission", item.getMission() != null ? item.getMission().getName() : "-"));
    return inventoryItemMapper.toDto(saved);
  }

  /**
   * Updates the soft associations of an inventory item (mission, job order, owner). Quantity and
   * material identity are NOT mutable here — those go through {@link #bookOutInventoryItem} so the
   * audit trail stays consistent.
   *
   * @throws NotFoundException when any referenced id is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     version is stale
   */
  @Transactional
  public InventoryItemDto updateInventoryItem(
      UUID id, InventoryItemUpdateDto dto, UUID currentUserId, boolean isLogistician) {
    InventoryItem item =
        inventoryItemRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("Inventory item not found"));

    if (!item.getUser().getId().equals(currentUserId)) {
      if (item.getPersonal() || !isLogistician) {
        throw new AccessDeniedException("You are not allowed to update this inventory item");
      }
    }

    OptimisticLock.checkOptionalClient(item.getVersion(), dto.version(), InventoryItem.class, id);

    Boolean isPersonal = dto.personal() != null ? dto.personal() : item.getPersonal();
    item.setPersonal(isPersonal);

    if (Boolean.TRUE.equals(isPersonal) && (dto.missionId() != null || dto.jobOrderId() != null)) {
      throw new BadRequestException("Personal items cannot be assigned to a mission or job order");
    }

    Material material =
        materialRepository
            .findById(dto.materialId())
            .orElseThrow(() -> new NotFoundException("Material not found"));
    item.setMaterial(material);

    Location location =
        locationRepository
            .findById(dto.locationId())
            .orElseThrow(() -> new NotFoundException("Location not found"));
    item.setLocation(location);

    item.setQuality(dto.quality());
    item.setAmount(roundAmount(dto.amount()));

    if (dto.jobOrderId() != null) {
      JobOrder jobOrder =
          jobOrderRepository
              .findById(dto.jobOrderId())
              .orElseThrow(() -> new NotFoundException("JobOrder not found"));
      assertMaterialRequiredByJobOrder(material, jobOrder);
      item.setJobOrder(jobOrder);
    } else {
      item.setJobOrder(null);
    }

    if (dto.missionId() != null) {
      Mission mission =
          missionRepository
              .findById(dto.missionId())
              .orElseThrow(() -> new NotFoundException("Mission not found"));
      item.setMission(mission);
    } else {
      item.setMission(null);
    }

    // Append-only: an update edits the row in place and is never folded into another matching
    // stack.
    // Rows that now share a stack identity are grouped only for display (group-on-read).
    // saveAndFlush (not save): this method's @Transactional commits AFTER it returns, so a plain
    // save() leaves the @Version increment unflushed and the mapped DTO carries the STALE version.
    // The client writes that back, and the user's NEXT in-place edit of the same row then 409s.
    // Flushing here makes the response @Version authoritative (REQ-FE-003).
    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    auditService.record(
        AuditEventType.INVENTORY_ITEM_UPDATED,
        item.getId(),
        inventoryLabel(item),
        item.getUser().getId(),
        AuditDetails.of("qty", item.getAmount())
            .with("q", item.getQuality())
            .with("personal", item.getPersonal())
            .with("jobOrder", jobOrderRef(item))
            .with("mission", item.getMission() != null ? item.getMission().getName() : "-"));
    return inventoryItemMapper.toDto(saved);
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

    // saveAndFlush so the response carries the post-increment @Version (see updateInventoryItem) —
    // otherwise editing a note right after an association change 409s.
    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    // PII: the note body is user free text — record only its presence/length, never the content.
    auditService.record(
        AuditEventType.INVENTORY_ITEM_NOTE_UPDATED,
        item.getId(),
        inventoryLabel(item),
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
   * <p>The {@code type} discriminator selects CONSUME (just decrement), TRANSFER (append-only move
   * to the target location/owner) or SELL (decrement plus a mission finance entry). When the
   * post-decrement quantity is floating-point-zero the row is removed entirely.
   *
   * @param id the source inventory row id
   * @param dto the book-out payload (type, amount, version, transfer/sell fields)
   * @param currentUserId the authenticated caller's user id
   * @param isAdmin whether the caller holds an admin role (bypasses the owner check)
   * @return the reduced source (or new target) row DTO, or {@code null} when the row is depleted
   * @throws NotFoundException when the item is unknown
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the requested
   *     amount exceeds the available quantity
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

  /**
   * Composes the audit subject label for an inventory row — {@code material @ location}, the
   * deletion-proof identity snapshot stored on each audit event (REQ-AUDIT-001).
   *
   * @param item the inventory row (associations may be lazily loaded but are within the tx)
   * @return the {@code material @ location} label
   */
  private static String inventoryLabel(InventoryItem item) {
    String mat = item.getMaterial() != null ? item.getMaterial().getName() : "—";
    String loc = item.getLocation() != null ? item.getLocation().getName() : "—";
    return mat + " @ " + loc;
  }

  /**
   * Renders an inventory row's job-order reference for an audit details payload.
   *
   * @param item the inventory row
   * @return {@code #<displayId>} when linked, {@code -} otherwise
   */
  private static String jobOrderRef(InventoryItem item) {
    return item.getJobOrder() != null ? "#" + item.getJobOrder().getDisplayId() : "-";
  }

  private Double roundAmount(Double amount) {
    if (amount == null) {
      return null;
    }
    return java.math.BigDecimal.valueOf(amount)
        .setScale(3, java.math.RoundingMode.HALF_UP)
        .doubleValue();
  }
}

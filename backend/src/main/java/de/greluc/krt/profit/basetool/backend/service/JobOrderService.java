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

import de.greluc.krt.profit.basetool.backend.event.JobOrderCreatedEvent;
import de.greluc.krt.profit.basetool.backend.event.JobOrderUpdatedByRequesterEvent;
import de.greluc.krt.profit.basetool.backend.event.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderItemRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the lifecycle of job orders: create, full and per-field update, status transitions (OPEN
 * → IN_PROGRESS → COMPLETED / REJECTED), priority reorder, assignees and material/inventory
 * unlinking.
 *
 * <p>{@link
 * #completeJobOrderWithinTransaction(de.greluc.krt.profit.basetool.backend.model.JobOrder)} works
 * on an already-managed entity inside the caller's transaction, so completion never causes a second
 * {@code @Version} bump.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderService {

  private final JobOrderRepository jobOrderRepository;
  private final MaterialRepository materialRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final JobOrderAssigneeService jobOrderAssigneeService;
  private final OrgUnitRepository orgUnitRepository;
  private final JobOrderOrgUnitResolver jobOrderOrgUnitResolver;
  private final AuthHelperService authHelperService;
  private final ApplicationEventPublisher eventPublisher;
  private final MaterialClaimService materialClaimService;
  private final AuditService auditService;
  private final JobOrderItemService jobOrderItemService;
  private final JobOrderStockProjectionService jobOrderStockProjectionService;
  private final JobOrderPriorityService jobOrderPriorityService;

  /**
   * Persists a new job order in the next free priority slot (1 is highest), taking each material's
   * minimum quality verbatim from the DTO (650 or {@code null} for none).
   *
   * @param createDto create payload
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when a referenced
   *     material or user id is unknown
   */
  @Transactional
  public JobOrderDto createJobOrder(CreateJobOrderDto createDto) {
    jobOrderRepository.lockAllJobOrders();
    Integer newPriority = jobOrderRepository.findMaxPriority().orElse(0) + 1;

    OrgUnit responsible =
        jobOrderOrgUnitResolver.resolveResponsibleOrgUnit(createDto.responsibleOrgUnitId());
    OrgUnit requesting =
        jobOrderOrgUnitResolver.resolveRequestingOrgUnit(createDto.requestingOrgUnitId());

    JobOrder jobOrder =
        JobOrder.builder()
            .handle(createDto.handle())
            .comment(StringNormalization.trimToNull(createDto.comment()))
            .priority(newPriority)
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requesting)
            .build();

    for (CreateJobOrderMaterialDto matDto : createDto.materials()) {
      Material material =
          Entities.require(
              materialRepository.findById(matDto.materialId()),
              () -> "Material not found: " + matDto.materialId());

      JobOrderMaterial jobOrderMaterial =
          JobOrderMaterial.builder()
              .material(material)
              .minQuality(matDto.minQuality())
              .amount(matDto.amount())
              .build();

      jobOrder.addMaterial(jobOrderMaterial);
    }

    jobOrder = jobOrderRepository.save(jobOrder);
    jobOrderRepository.flush();
    jobOrderPriorityService.normalizePriorities();
    publishJobOrderCreated(jobOrder);
    auditService.record(
        AuditEventType.JOB_ORDER_CREATED,
        jobOrder.getId(),
        orderLabel(jobOrder),
        null,
        AuditDetails.of("type", "MATERIAL")
            .with("materials", jobOrder.getMaterials().size())
            .with("responsibleOrgUnit", orgUnitRef(responsible))
            .with("requestingOrgUnit", orgUnitRef(requesting))
            .with("priority", jobOrder.getPriority()));
    return jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
  }

  /**
   * Persists a new {@code ITEM} job order; org-unit stamping and priority match {@link
   * #createJobOrder(CreateJobOrderDto)}, and each line's materials are derived from its blueprint
   * by {@link JobOrderItemService}.
   *
   * @param createDto item-order create payload
   * @return the persisted order with derived materials and aggregation
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when a referenced
   *     game item or blueprint id is unknown
   * @throws BadRequestException when a blueprint does not produce its line's item, or org-unit
   *     stamping cannot be resolved
   */
  @Transactional
  public JobOrderDto createItemJobOrder(CreateJobOrderItemRequestDto createDto) {
    jobOrderRepository.lockAllJobOrders();
    Integer newPriority = jobOrderRepository.findMaxPriority().orElse(0) + 1;

    OrgUnit responsible =
        jobOrderOrgUnitResolver.resolveResponsibleOrgUnit(createDto.responsibleOrgUnitId());
    OrgUnit requesting =
        jobOrderOrgUnitResolver.resolveRequestingOrgUnit(createDto.requestingOrgUnitId());

    JobOrder jobOrder =
        JobOrder.builder()
            .handle(createDto.handle())
            .comment(StringNormalization.trimToNull(createDto.comment()))
            .priority(newPriority)
            .type(JobOrderType.ITEM)
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requesting)
            .build();

    reconcileItemLines(jobOrder, createDto.items());

    jobOrder = jobOrderRepository.save(jobOrder);
    jobOrderRepository.flush();
    jobOrderPriorityService.normalizePriorities();
    publishJobOrderCreated(jobOrder);
    auditService.record(
        AuditEventType.JOB_ORDER_ITEM_CREATED,
        jobOrder.getId(),
        orderLabel(jobOrder),
        null,
        AuditDetails.of("type", "ITEM")
            .with("lines", jobOrder.getItems().size())
            .with("responsibleOrgUnit", orgUnitRef(responsible))
            .with("requestingOrgUnit", orgUnitRef(requesting))
            .with("priority", jobOrder.getPriority()));
    return jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
  }

  /**
   * Publishes a {@link JobOrderCreatedEvent} for after-commit notification, reading only scalars
   * and never re-saving the order. The actor is the current user, empty for anonymous creates.
   *
   * @param jobOrder the persisted, flushed job order
   */
  private void publishJobOrderCreated(@NotNull JobOrder jobOrder) {
    OrgUnit responsible = jobOrder.getResponsibleOrgUnit();
    OrgUnit requesting = jobOrder.getRequestingOrgUnit();
    eventPublisher.publishEvent(
        new JobOrderCreatedEvent(
            jobOrder.getId(),
            jobOrder.getDisplayId(),
            jobOrder.getHandle(),
            new OrgUnitRef(responsible.getId(), responsible.getKind()),
            responsible.getShorthand(),
            requesting == null ? null : new OrgUnitRef(requesting.getId(), requesting.getKind()),
            jobOrder.getType() == null ? null : jobOrder.getType().name(),
            authHelperService.currentUserId().orElse(null)));
  }

  /**
   * Publishes a {@link JobOrderUpdatedByRequesterEvent} so the responsible org unit's officers and
   * leads are notified after commit (REQ-ORDERS-023). Reads only scalars and never re-saves the
   * order.
   *
   * @param jobOrder the persisted, flushed job order after the requester edit
   */
  private void publishJobOrderUpdatedByRequester(@NotNull JobOrder jobOrder) {
    OrgUnit responsible = jobOrder.getResponsibleOrgUnit();
    OrgUnit requesting = jobOrder.getRequestingOrgUnit();
    eventPublisher.publishEvent(
        new JobOrderUpdatedByRequesterEvent(
            jobOrder.getId(),
            jobOrder.getDisplayId(),
            jobOrder.getHandle(),
            new OrgUnitRef(responsible.getId(), responsible.getKind()),
            responsible.getShorthand(),
            requesting == null ? null : new OrgUnitRef(requesting.getId(), requesting.getKind()),
            requesting == null ? null : requesting.getShorthand(),
            authHelperService.currentUserId().orElse(null)));
  }

  /**
   * Updates the status of a job order with its own load, save and flush. Must not be called inside
   * a transaction that already modified the same order, since the double save fails the version
   * check; use {@link #completeJobOrderWithinTransaction(JobOrder)} there.
   *
   * @param id job order primary key
   * @param dto the new status and expected version
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException for illegal
   *     transitions
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public JobOrderDto updateJobOrderStatus(UUID id, UpdateJobOrderStatusDto dto) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);

    OptimisticLock.checkOptionalClient(jobOrder.getVersion(), dto.version(), JobOrder.class, id);

    JobOrderStatus status = dto.status();
    final JobOrderStatus previousStatus = jobOrder.getStatus();
    boolean isTerminal = (status == JobOrderStatus.COMPLETED || status == JobOrderStatus.REJECTED);
    boolean wasTerminal =
        (jobOrder.getStatus() == JobOrderStatus.COMPLETED
            || jobOrder.getStatus() == JobOrderStatus.REJECTED);

    if (isTerminal && !wasTerminal && jobOrder.getPriority() != null) {
      jobOrder.setPriority(null);
    } else if (!isTerminal && wasTerminal) {
      jobOrderRepository.lockAllJobOrders();
      Integer newPriority = jobOrderRepository.findMaxPriority().orElse(0) + 1;
      jobOrder.setPriority(newPriority);
    }

    jobOrder.setStatus(status);
    jobOrder = jobOrderRepository.save(jobOrder);
    jobOrderRepository.flush();

    if (isTerminal != wasTerminal) {
      jobOrderPriorityService.normalizePriorities();
    }

    if (isTerminal && !wasTerminal) {
      inventoryItemRepository.deleteJobOrderAllocationsByJobOrder(jobOrder.getId());
    }

    if (status == JobOrderStatus.COMPLETED && previousStatus != JobOrderStatus.COMPLETED) {
      auditService.record(
          AuditEventType.JOB_ORDER_COMPLETED,
          jobOrder.getId(),
          orderLabel(jobOrder),
          null,
          AuditDetails.of("from", previousStatus).with("autoCompleted", "false"));
    } else {
      auditService.record(
          AuditEventType.JOB_ORDER_STATUS_CHANGED,
          jobOrder.getId(),
          orderLabel(jobOrder),
          null,
          AuditDetails.of("from", previousStatus).with("to", status));
    }

    return jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
  }

  /**
   * Moves a job order to a new priority position, shifting adjacent orders. Concurrent reorders are
   * serialized by a pessimistic lock on the whole priority sequence.
   *
   * @param id job order primary key
   * @param newPriority target slot (1-based)
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  @Transactional
  public JobOrderDto updateJobOrderPriority(UUID id, Integer newPriority) {
    return jobOrderPriorityService.updateJobOrderPriority(id, newPriority);
  }

  /**
   * Sets whether the item order's blueprint coverage counts cosmetic variants of the ordered items
   * ({@code true}) or only exact-name blueprints ({@code false}) (REQ-ORDERS-021). A call that
   * requests the current mode changes nothing and records no audit event.
   *
   * @param id the order id
   * @param countWithVariants the requested counting mode
   * @param version the expected optimistic-lock version
   * @return the order DTO, with the bumped version if the mode changed
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no order matches
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the order is
   *     not an item order
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public JobOrderDto updateBlueprintVariantCounting(
      UUID id, boolean countWithVariants, Long version) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);

    if (jobOrder.getType() != JobOrderType.ITEM) {
      throw new BadRequestException("Blueprint variant counting applies only to item orders");
    }

    OptimisticLock.checkOptionalClient(jobOrder.getVersion(), version, JobOrder.class, id);

    if (jobOrder.isCountBlueprintsWithVariants() == countWithVariants) {
      return jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
    }

    jobOrder.setCountBlueprintsWithVariants(countWithVariants);
    jobOrder = jobOrderRepository.saveAndFlush(jobOrder);

    auditService.record(
        AuditEventType.JOB_ORDER_BLUEPRINT_COUNTING_CHANGED,
        jobOrder.getId(),
        orderLabel(jobOrder),
        null,
        AuditDetails.of("countWithVariants", countWithVariants));
    return jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
  }

  /**
   * Fully updates the order's metadata and replaces its materials; removed materials are deleted,
   * kept ones retain their inventory links.
   *
   * @param id job order primary key
   * @param updateDto update payload with the expected version
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public JobOrderDto updateJobOrder(UUID id, CreateJobOrderDto updateDto) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);

    OptimisticLock.checkOptionalClient(
        jobOrder.getVersion(), updateDto.version(), JobOrder.class, id);

    if (updateDto.requestingOrgUnitId() != null) {
      jobOrder.setRequestingOrgUnit(
          jobOrderOrgUnitResolver.resolveRequestingOrgUnit(updateDto.requestingOrgUnitId()));
    }
    jobOrder.setHandle(updateDto.handle());
    jobOrder.setComment(StringNormalization.trimToNull(updateDto.comment()));

    MaterialReplaceOutcome outcome =
        replaceMaterialsWithinTransaction(id, jobOrder, updateDto.materials());
    auditService.record(
        AuditEventType.JOB_ORDER_UPDATED,
        outcome.order().getId(),
        orderLabel(outcome.order()),
        null,
        AuditDetails.of("materialsRemoved", outcome.removedCount())
            .with("materials", outcome.order().getMaterials().size())
            .with("orphanedClaimsWithdrawn", outcome.orphanedClaimsWithdrawn()));
    return jobOrderStockProjectionService.mapToDtoWithStock(outcome.order());
  }

  /**
   * Replaces the order's material lines and unlinks the inventory of every removed material, shared
   * by the logistician and requester edits.
   *
   * <p>The aggregate is saved and flushed before the context-clearing bulk unlinks run, and the
   * order is then re-fetched, so the save never degrades into a stale merge and the returned
   * version is current.
   *
   * @param id the order id, used for the re-fetch and the bulk unlink
   * @param managed the managed order whose scalars the caller has already set
   * @param materials the new material lines
   * @return the re-fetched order plus the removed-line and withdrawn-claim counts
   */
  @NotNull
  private MaterialReplaceOutcome replaceMaterialsWithinTransaction(
      UUID id, JobOrder managed, @NotNull List<CreateJobOrderMaterialDto> materials) {
    List<UUID> newMaterialIds =
        materials.stream().map(CreateJobOrderMaterialDto::materialId).toList();
    Set<UUID> removedMaterialIds = new LinkedHashSet<>();
    for (JobOrderMaterial mat : managed.getMaterials()) {
      UUID matId = mat.getMaterial().getId();
      if (!newMaterialIds.contains(matId)) {
        removedMaterialIds.add(matId);
      }
    }

    managed.getMaterials().clear();
    for (CreateJobOrderMaterialDto matDto : materials) {
      Material material =
          Entities.require(
              materialRepository.findById(matDto.materialId()),
              () -> "Material not found: " + matDto.materialId());
      managed.addMaterial(
          JobOrderMaterial.builder()
              .material(material)
              .minQuality(matDto.minQuality())
              .amount(matDto.amount())
              .build());
    }
    jobOrderRepository.saveAndFlush(managed);

    for (UUID removedId : removedMaterialIds) {
      inventoryItemRepository.deleteJobOrderAllocationsByJobOrderAndMaterial(id, removedId);
    }

    JobOrder refreshed =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    int orphanedClaimsWithdrawn =
        materialClaimService.withdrawOrphanedClaimsWithinTransaction(refreshed);
    return new MaterialReplaceOutcome(
        refreshed, removedMaterialIds.size(), orphanedClaimsWithdrawn);
  }

  /**
   * Outcome of {@link #replaceMaterialsWithinTransaction(UUID, JobOrder, List)}: the re-fetched
   * order and the counts recorded in the audit payload.
   *
   * @param order the re-fetched managed job order
   * @param removedCount number of removed material lines
   * @param orphanedClaimsWithdrawn number of orphaned claims withdrawn
   */
  private record MaterialReplaceOutcome(
      JobOrder order, int removedCount, int orphanedClaimsWithdrawn) {}

  /**
   * Fully edits an {@code ITEM} order's lines and metadata via {@link #reconcileItemLines}; matched
   * lines are updated in place so booked production survives (REQ-ORDERS-032). Allowed only while
   * the order has no item handover.
   *
   * <p>Claims on buckets the new lines no longer require are withdrawn afterwards, without bumping
   * the order's version. The responsible org unit is not changed here.
   *
   * @param id the order id
   * @param updateDto the new item lines and metadata with the expected version
   * @return the persisted order DTO with re-derived materials and aggregation
   * @throws NotFoundException when the order, a game item or a blueprint id is unknown
   * @throws BadRequestException when the order is not an item order, has item handovers, or a
   *     blueprint does not produce its line's item
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public JobOrderDto updateItemJobOrder(UUID id, CreateJobOrderItemRequestDto updateDto) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);

    if (jobOrder.getType() != JobOrderType.ITEM) {
      throw new BadRequestException(
          "Order " + id + " is not an item order; use the material-order update endpoint.");
    }
    OptimisticLock.checkOptionalClient(
        jobOrder.getVersion(), updateDto.version(), JobOrder.class, id);
    if (jobOrder.getItemHandovers() != null && !jobOrder.getItemHandovers().isEmpty()) {
      throw new BadRequestException(
          "Item order " + id + " already has handovers and can no longer be edited.");
    }

    if (updateDto.requestingOrgUnitId() != null) {
      jobOrder.setRequestingOrgUnit(
          jobOrderOrgUnitResolver.resolveRequestingOrgUnit(updateDto.requestingOrgUnitId()));
    }
    jobOrder.setHandle(updateDto.handle());
    jobOrder.setComment(StringNormalization.trimToNull(updateDto.comment()));

    reconcileItemLines(jobOrder, updateDto.items());

    jobOrder = jobOrderRepository.save(jobOrder);
    jobOrderRepository.flush();

    int orphanedClaimsWithdrawn =
        materialClaimService.withdrawOrphanedClaimsWithinTransaction(jobOrder);
    auditService.record(
        AuditEventType.JOB_ORDER_ITEM_UPDATED,
        jobOrder.getId(),
        orderLabel(jobOrder),
        null,
        AuditDetails.of("lines", jobOrder.getItems().size())
            .with("orphanedClaimsWithdrawn", orphanedClaimsWithdrawn));
    return jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
  }

  /**
   * Reconciles the order's item lines against the payload, re-deriving each line's materials from
   * its blueprint and wiring sub-assembly provenance from the {@code clientLineId} / {@code
   * parentClientLineId} hints.
   *
   * <p>A line whose {@code id} belongs to this order is updated in place, keeping its booked
   * amounts (REQ-ORDERS-032); a {@code null} or foreign id adds a line; an omitted line is removed
   * only if {@link #assertLineRemovable} allows it.
   *
   * @param jobOrder the order whose lines to reconcile (mutated in place)
   * @param lines the desired end state of the item lines
   * @throws BadRequestException when the payload would discard or under-run booked production
   */
  private void reconcileItemLines(JobOrder jobOrder, List<CreateJobOrderItemLineDto> lines) {
    Map<UUID, JobOrderItem> existingById = new HashMap<>();
    for (JobOrderItem existing : jobOrder.getItems()) {
      if (existing.getId() != null) {
        existingById.put(existing.getId(), existing);
      }
    }

    Map<Integer, JobOrderItem> byClientId = new HashMap<>();
    List<JobOrderItem> resolved = new ArrayList<>();
    Set<UUID> keptIds = new LinkedHashSet<>();
    for (CreateJobOrderItemLineDto line : lines) {
      JobOrderItem match = line.id() == null ? null : existingById.get(line.id());
      if (match != null) {
        if (!keptIds.add(match.getId())) {
          throw new BadRequestException(
              "Item line " + match.getId() + " appears more than once in the update payload.");
        }
        assertLineEditable(match, line);
        jobOrderItemService.applyItemLine(match, line);
      } else {
        match = jobOrderItemService.buildItemLine(line);
        jobOrder.addItem(match);
      }
      resolved.add(match);
      if (line.clientLineId() != null) {
        byClientId.put(line.clientLineId(), match);
      }
    }

    List<JobOrderItem> removed =
        jobOrder.getItems().stream()
            .filter(item -> item.getId() != null && !keptIds.contains(item.getId()))
            .toList();
    for (JobOrderItem gone : removed) {
      assertLineRemovable(gone, jobOrder.getId());
    }

    resolved.forEach(item -> item.setParentItem(null));
    for (int i = 0; i < lines.size(); i++) {
      Integer parentClientId = lines.get(i).parentClientLineId();
      if (parentClientId == null) {
        continue;
      }
      JobOrderItem parent = byClientId.get(parentClientId);
      if (parent != null && parent != resolved.get(i)) {
        resolved.get(i).setParentItem(parent);
      }
    }
    removed.forEach(jobOrder.getItems()::remove);
  }

  /**
   * Guards an in-place line update against booked production (REQ-ORDERS-032): once {@code
   * manufacturedAmount > 0} the ordered item is frozen and the amount may not drop below it; the
   * blueprint may still change.
   *
   * @param existing the managed line being updated
   * @param line the payload with its new state
   * @throws BadRequestException when the update would orphan or under-run booked production
   */
  private static void assertLineEditable(
      @NotNull JobOrderItem existing, CreateJobOrderItemLineDto line) {
    int manufactured =
        existing.getManufacturedAmount() == null ? 0 : existing.getManufacturedAmount();
    if (manufactured <= 0) {
      return;
    }
    if (existing.getGameItem() != null
        && !existing.getGameItem().getId().equals(line.gameItemId())) {
      throw new BadRequestException(
          "Item line "
              + existing.getId()
              + " already has "
              + manufactured
              + " manufactured unit(s); its ordered item can no longer be changed.");
    }
    if (line.amount() != null && line.amount() < manufactured) {
      throw new BadRequestException(
          "Item line "
              + existing.getId()
              + " already has "
              + manufactured
              + " manufactured unit(s); the amount cannot be lowered below that.");
    }
  }

  /**
   * Refuses to remove a line that carries booked production or a recorded delivery
   * (REQ-ORDERS-032).
   *
   * @param gone the line the payload dropped
   * @param orderId the owning order id, for the error message
   * @throws BadRequestException when the line carries production or delivery
   */
  private static void assertLineRemovable(@NotNull JobOrderItem gone, UUID orderId) {
    int manufactured = gone.getManufacturedAmount() == null ? 0 : gone.getManufacturedAmount();
    int delivered = gone.getDeliveredAmount() == null ? 0 : gone.getDeliveredAmount();
    if (manufactured > 0 || delivered > 0) {
      throw new BadRequestException(
          "Item line "
              + gone.getId()
              + " of order "
              + orderId
              + " already has booked production and cannot be removed.");
    }
  }

  /**
   * Requester-side full edit of a {@code MATERIAL} order (REQ-ORDERS-023): quantities, material
   * lines, min-quality and comment, allowed only while the order is fully undelivered. Handle, org
   * units, status and priority in the DTO are ignored.
   *
   * <p>Removed materials are unlinked from inventory, the responsible org unit is notified on
   * commit, and the edit is audited as {@code JOB_ORDER_UPDATED} with {@code byRequester=true}. The
   * delivery freeze is re-checked here in addition to the endpoint gate.
   *
   * @param id the order id
   * @param updateDto the new material lines and comment with the expected version
   * @return the persisted order DTO
   * @throws NotFoundException when the order or a material id is unknown
   * @throws BadRequestException when the order is not a material order or already has a delivery
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public JobOrderDto updateJobOrderAsRequester(UUID id, CreateJobOrderDto updateDto) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    if (jobOrder.getType() != JobOrderType.MATERIAL) {
      throw new BadRequestException(
          "Order " + id + " is not a material order; use the requester item-update endpoint.");
    }
    OptimisticLock.checkOptionalClient(
        jobOrder.getVersion(), updateDto.version(), JobOrder.class, id);
    assertRequesterEditable(jobOrder);

    jobOrder.setComment(StringNormalization.trimToNull(updateDto.comment()));

    MaterialReplaceOutcome outcome =
        replaceMaterialsWithinTransaction(id, jobOrder, updateDto.materials());
    auditService.record(
        AuditEventType.JOB_ORDER_UPDATED,
        outcome.order().getId(),
        orderLabel(outcome.order()),
        null,
        AuditDetails.of("materialsRemoved", outcome.removedCount())
            .with("materials", outcome.order().getMaterials().size())
            .with("orphanedClaimsWithdrawn", outcome.orphanedClaimsWithdrawn())
            .with("byRequester", true));
    publishJobOrderUpdatedByRequester(outcome.order());
    return jobOrderStockProjectionService.mapToDtoWithStock(outcome.order());
  }

  /**
   * Requester-side full edit of an {@code ITEM} order (REQ-ORDERS-023): item lines and comment,
   * allowed only while the order is fully undelivered; lines are reconciled in place via {@link
   * #reconcileItemLines}.
   *
   * <p>Unlike {@link #updateItemJobOrder}, it unlinks inventory of materials no longer required and
   * drops game-item allocations of items no longer ordered (REQ-INV-031). Notifies the responsible
   * org unit on commit; audited as {@code JOB_ORDER_ITEM_UPDATED} with {@code byRequester=true}.
   *
   * @param id the order id
   * @param updateDto the new item lines and comment with the expected version
   * @return the persisted order DTO with re-derived materials
   * @throws NotFoundException when the order, a game item or a blueprint id is unknown
   * @throws BadRequestException when the order is not an item order or already has a delivery
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public JobOrderDto updateItemJobOrderAsRequester(
      UUID id, CreateJobOrderItemRequestDto updateDto) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    if (jobOrder.getType() != JobOrderType.ITEM) {
      throw new BadRequestException(
          "Order " + id + " is not an item order; use the requester material-update endpoint.");
    }
    OptimisticLock.checkOptionalClient(
        jobOrder.getVersion(), updateDto.version(), JobOrder.class, id);
    assertRequesterEditable(jobOrder);

    jobOrder.setComment(StringNormalization.trimToNull(updateDto.comment()));

    final Set<UUID> requiredBefore =
        new LinkedHashSet<>(jobOrderItemService.requiredMaterialIds(jobOrder));
    final Set<UUID> requiredGameItemsBefore =
        new LinkedHashSet<>(jobOrderItemService.requiredGameItemIds(jobOrder));

    reconcileItemLines(jobOrder, updateDto.items());

    jobOrderRepository.saveAndFlush(jobOrder);

    Set<UUID> noLongerRequired = new LinkedHashSet<>(requiredBefore);
    noLongerRequired.removeAll(jobOrderItemService.requiredMaterialIds(jobOrder));
    Set<UUID> noLongerRequestedGameItems = new LinkedHashSet<>(requiredGameItemsBefore);
    noLongerRequestedGameItems.removeAll(jobOrderItemService.requiredGameItemIds(jobOrder));
    for (UUID removedMaterialId : noLongerRequired) {
      inventoryItemRepository.deleteJobOrderAllocationsByJobOrderAndMaterial(id, removedMaterialId);
    }
    for (UUID removedGameItemId : noLongerRequestedGameItems) {
      inventoryItemRepository.deleteJobOrderAllocationsByJobOrderAndGameItem(id, removedGameItemId);
    }

    JobOrder refreshed =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    int orphanedClaimsWithdrawn =
        materialClaimService.withdrawOrphanedClaimsWithinTransaction(refreshed);
    auditService.record(
        AuditEventType.JOB_ORDER_ITEM_UPDATED,
        refreshed.getId(),
        orderLabel(refreshed),
        null,
        AuditDetails.of("lines", refreshed.getItems().size())
            .with("orphanedClaimsWithdrawn", orphanedClaimsWithdrawn)
            .with("byRequester", true));
    publishJobOrderUpdatedByRequester(refreshed);
    return jobOrderStockProjectionService.mapToDtoWithStock(refreshed);
  }

  /**
   * Re-checks that the order has neither a material nor an item handover before a requester edit
   * (REQ-ORDERS-023), closing the gap between the endpoint gate and the commit.
   *
   * @param jobOrder the managed order being edited
   * @throws BadRequestException when the order already has a delivery
   */
  private static void assertRequesterEditable(@NotNull JobOrder jobOrder) {
    boolean hasDelivery =
        (jobOrder.getHandovers() != null && !jobOrder.getHandovers().isEmpty())
            || (jobOrder.getItemHandovers() != null && !jobOrder.getItemHandovers().isEmpty());
    if (hasDelivery) {
      throw new BadRequestException(
          "Order "
              + jobOrder.getId()
              + " already has a delivery and can no longer be edited by the requester.");
    }
  }

  /**
   * Hard-deletes a job order. Backend rejects the delete when linked inventory items exist (must be
   * unlinked first via {@link #unlinkInventoryItem}).
   *
   * @param id job order primary key
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  @Transactional
  public void deleteJobOrder(UUID id) {
    jobOrderRepository.lockAllJobOrders();
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);

    final Integer priority = jobOrder.getPriority();
    final UUID deletedId = jobOrder.getId();
    final String deletedLabel = orderLabel(jobOrder);
    jobOrderRepository.delete(jobOrder);
    jobOrderRepository.flush();
    if (priority != null) {
      jobOrderPriorityService.normalizePriorities();
    }
    auditService.record(
        AuditEventType.JOB_ORDER_DELETED,
        deletedId,
        deletedLabel,
        null,
        AuditDetails.of("priorityWas", priority));
  }

  /**
   * Adds an assignee to a job order; re-adding the same user is a no-op.
   *
   * @param jobOrderId job order primary key
   * @param userId user to add
   * @return the persisted order with the refreshed assignee list
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when either id is
   *     unknown
   */
  @Transactional
  public JobOrderDto addAssignee(UUID jobOrderId, UUID userId) {
    return jobOrderAssigneeService.addAssignee(jobOrderId, userId);
  }

  /**
   * Removes a material requirement from the order and unlinks the inventory items linked to it via
   * a bulk update.
   *
   * @param jobOrderId job order primary key
   * @param materialId material to unlink
   */
  @Transactional
  public void unlinkMaterial(UUID jobOrderId, UUID materialId) {
    JobOrder jobOrder =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);

    boolean exists =
        jobOrder.getMaterials().stream().anyMatch(m -> m.getMaterial().getId().equals(materialId));
    if (!exists) {
      throw new NotFoundException("Material not linked to job order: " + materialId);
    }

    final String label = orderLabel(jobOrder);
    inventoryItemRepository.deleteJobOrderAllocationsByJobOrderAndMaterial(jobOrderId, materialId);

    jobOrder.getMaterials().removeIf(m -> m.getMaterial().getId().equals(materialId));
    jobOrderRepository.save(jobOrder);
    auditService.record(
        AuditEventType.JOB_ORDER_MATERIAL_UNLINKED,
        jobOrderId,
        label,
        null,
        AuditDetails.of("material", materialId));
  }

  /**
   * Detaches a single inventory item from the order. The item stays in the user's inventory; it
   * just stops counting toward the order's completion.
   *
   * @param jobOrderId job order primary key
   * @param inventoryItemId inventory item to detach
   */
  @Transactional
  public void unlinkInventoryItem(UUID jobOrderId, UUID inventoryItemId) {
    final JobOrder jobOrder =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);

    InventoryItem item =
        Entities.require(
            inventoryItemRepository.findById(inventoryItemId),
            () -> "InventoryItem not found: " + inventoryItemId);

    if (item.getJobOrderAllocations().stream()
        .noneMatch(a -> a.getJobOrder() != null && a.getJobOrder().getId().equals(jobOrderId))) {
      throw new NotFoundException("InventoryItem not linked to job order: " + inventoryItemId);
    }

    item.getJobOrderAllocations()
        .removeIf(a -> a.getJobOrder() != null && a.getJobOrder().getId().equals(jobOrderId));
    auditService.record(
        AuditEventType.JOB_ORDER_INVENTORY_UNLINKED,
        jobOrderId,
        orderLabel(jobOrder),
        null,
        AuditDetails.of("inventoryItem", inventoryItemId));
  }

  /**
   * Removes an assignee from a job order.
   *
   * @param jobOrderId job order primary key
   * @param userId user to remove
   * @return the persisted order
   */
  @Transactional
  public JobOrderDto removeAssignee(UUID jobOrderId, UUID userId) {
    return jobOrderAssigneeService.removeAssignee(jobOrderId, userId);
  }

  /**
   * Creates or replaces the free-text note on a user's assignee entry, locked on the assignee
   * entry's own version so the order's version is never bumped.
   *
   * @param jobOrderId job order primary key
   * @param userId the assignee whose note is changed
   * @param note the new note text, already length-validated
   * @param version the assignee entry version last seen, or {@code null} to skip the check
   * @return the persisted order with the refreshed assignee list
   * @throws NotFoundException when the order or the assignee entry is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code version} is
   *     stale
   */
  @Transactional
  public JobOrderDto updateAssigneeNote(UUID jobOrderId, UUID userId, String note, Long version) {
    return jobOrderAssigneeService.updateAssigneeNote(jobOrderId, userId, note, version);
  }

  /**
   * Clears the note on a user's assignee entry, with the same locking as {@link
   * #updateAssigneeNote}.
   *
   * @param jobOrderId job order primary key
   * @param userId the assignee whose note is cleared
   * @param version the assignee entry version last seen, or {@code null} to skip the check
   * @return the persisted order with the refreshed assignee list
   * @throws NotFoundException when the order or the assignee entry is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code version} is
   *     stale
   */
  @Transactional
  public JobOrderDto deleteAssigneeNote(UUID jobOrderId, UUID userId, Long version) {
    return jobOrderAssigneeService.deleteAssigneeNote(jobOrderId, userId, version);
  }

  /**
   * Marks a managed job order as COMPLETED inside the caller's active transaction, relying on dirty
   * checking instead of a second load and save.
   *
   * @param jobOrder the managed {@link JobOrder} entity to complete
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void completeJobOrderWithinTransaction(@NotNull JobOrder jobOrder) {
    boolean wasTerminal =
        (jobOrder.getStatus() == JobOrderStatus.COMPLETED
            || jobOrder.getStatus() == JobOrderStatus.REJECTED);

    if (!wasTerminal && jobOrder.getPriority() != null) {
      jobOrder.setPriority(null);
    }

    jobOrder.setStatus(JobOrderStatus.COMPLETED);

    if (!wasTerminal) {
      jobOrderRepository.flush();
      jobOrderPriorityService.normalizePriorities();
      inventoryItemRepository.deleteJobOrderAllocationsByJobOrder(jobOrder.getId());
      auditService.record(
          AuditEventType.JOB_ORDER_COMPLETED,
          jobOrder.getId(),
          orderLabel(jobOrder),
          null,
          "autoCompleted=true");
    }
  }

  /**
   * Reassigns the responsible org unit of an order; the target must be profit-eligible.
   *
   * <ul>
   *   <li>Admins may reassign to any profit-eligible squadron or SK, in any direction.
   *   <li>A non-admin Logistician/Officer may only escalate a squadron-responsible order to an SK,
   *       and only if they may edit its current squadron ({@link
   *       AuthHelperService#canEditOrgUnit}).
   * </ul>
   *
   * @param id job order id
   * @param newResponsibleOrgUnitId the target responsible org unit id
   * @return the updated order DTO
   * @throws NotFoundException when the order does not exist
   * @throws BadRequestException when the target is unknown or not profit-eligible
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     perform the reassignment
   */
  @Transactional
  public JobOrderDto reassignResponsibleOrgUnit(UUID id, UUID newResponsibleOrgUnitId) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);

    OrgUnit target =
        orgUnitRepository
            .findById(newResponsibleOrgUnitId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "responsibleOrgUnitId does not resolve to a known org unit: "
                            + newResponsibleOrgUnitId));
    if (!target.isProfitEligible()) {
      throw new BadRequestException(
          "The selected responsible org unit is not profit-eligible and cannot process orders: "
              + newResponsibleOrgUnitId);
    }

    if (!authHelperService.isAdmin()) {
      OrgUnit current = jobOrder.getResponsibleOrgUnit();
      boolean currentIsSquadron = current != null && current.getKind() == OrgUnitKind.SQUADRON;
      boolean targetIsSpecialCommand = target.getKind() == OrgUnitKind.SPECIAL_COMMAND;
      boolean mayEditCurrent = current != null && authHelperService.canEditOrgUnit(current.getId());
      if (!(currentIsSquadron && targetIsSpecialCommand && mayEditCurrent)) {
        throw new AccessDeniedException(
            "Only an admin may reassign freely; a squadron logistician/officer may only escalate"
                + " their own squadron's order to a Spezialkommando.");
      }
    }

    OrgUnit previous = jobOrder.getResponsibleOrgUnit();
    jobOrder.setResponsibleOrgUnit(target);
    jobOrder = jobOrderRepository.save(jobOrder);
    log.info(
        "Job order {} responsible org unit reassigned: {} ({}) → {} ({})",
        jobOrder.getId(),
        previous != null ? previous.getId() : null,
        previous != null ? previous.getKind() : null,
        target.getId(),
        target.getKind());

    int claimsWithdrawn = 0;
    if (target.getKind() == OrgUnitKind.SQUADRON) {
      claimsWithdrawn = materialClaimService.withdrawAllForOrderWithinTransaction(jobOrder);
    }
    auditService.record(
        AuditEventType.JOB_ORDER_REASSIGNED,
        jobOrder.getId(),
        orderLabel(jobOrder),
        null,
        AuditDetails.of("fromOrgUnit", orgUnitRef(previous))
            .with("toOrgUnit", orgUnitRef(target))
            .with("claimsWithdrawn", claimsWithdrawn));
    return jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
  }

  /**
   * Composes the audit subject label {@code #<displayId> '<handle>'} for a job order
   * (REQ-AUDIT-001). The handle names the order's contact person, so the label is personal data and
   * is excluded from the data-subject export (REQ-SEC-058).
   *
   * @param jobOrder the order
   * @return the {@code #<displayId> '<handle>'} label
   */
  private static String orderLabel(@NotNull JobOrder jobOrder) {
    return JobOrderAuditLabel.of(jobOrder.getDisplayId());
  }

  /**
   * Renders an org unit for an audit details payload as {@code <id>(<KIND>)}.
   *
   * @param orgUnit the org unit, or {@code null}
   * @return {@code <id>(<KIND>)} or {@code -} when {@code null}
   */
  @NotNull
  private static String orgUnitRef(OrgUnit orgUnit) {
    return orgUnit == null ? "-" : orgUnit.getId() + "(" + orgUnit.getKind() + ")";
  }
}

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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientNames;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemPlan;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderClosedEvent;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the lifecycle of job orders: create, full and per-field update, status transitions (OPEN
 * → IN_PROGRESS → COMPLETED / REJECTED), priority reorder, assignees and material/inventory
 * unlinking.
 *
 * <p>{@link #completeJobOrderWithinTransaction(JobOrder)} works on an already-managed entity inside
 * the caller's transaction, so completion never causes a second {@code @Version} bump.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderService {

  private final JobOrderRepository jobOrderRepository;
  private final JobOrderItemFulfillmentGuard itemFulfillment;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderResponsibilityGuard responsibilityGuard;
  private final JobOrderWriters writers;
  private final JobOrderMaterialLines materialLines;
  private final StockReads stockReads;
  private final StockCommands stockCommands;
  private final JobOrderAssigneeService jobOrderAssigneeService;
  private final OrgUnitRepository orgUnitRepository;
  private final JobOrderOrgUnitResolver jobOrderOrgUnitResolver;
  private final AuthHelperService authHelperService;
  private final JobOrderChangeEffects effects;
  private final MaterialClaimService materialClaimService;
  private final AuditRecorder auditRecorder;
  private final JobOrderItemService jobOrderItemService;
  private final JobOrderItemReconciliation itemReconciliation;
  private final JobOrderStockProjectionService jobOrderStockProjectionService;
  private final JobOrderPriorityService jobOrderPriorityService;

  /** Runs the load, gate, version check, audit, notices and projection of every order edit. */
  private final JobOrderEditor jobOrderEditor;

  private final LiveSyncPublisher liveSyncPublisher;
  private final JobOrderUnitHistory unitHistory;
  private final JobOrderParticipationCommands participationCommands;
  private final JobOrderMoneyLifecycle moneyLifecycle;
  private final JobOrderClientNames clientNames;

  /**
   * Persists a new job order in the next free priority slot (1 is highest), taking each material's
   * minimum quality verbatim from the DTO (650 or {@code null} for none). After the commit the
   * order overview and the material demand overview are refreshed (REQ-ORDERS-056).
   *
   * @param createDto create payload
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when a referenced
   *     material or user id is unknown
   */
  @Transactional
  public JobOrderDto createJobOrder(CreateJobOrderDto createDto) {
    JobOrder order =
        createOrder(
            new NewOrder(
                createDto.responsibleOrgUnitId(),
                createDto.requestingOrgUnitId(),
                createDto.handle(),
                createDto.comment(),
                JobOrderType.MATERIAL,
                null),
            jobOrder ->
                materialLines.build(createDto.materials(), Set.of()).forEach(jobOrder::addMaterial),
            JobOrderWriterRule.CONTEXTUAL,
            webActor());
    return jobOrderStockProjectionService.mapToDtoWithStock(order);
  }

  /**
   * Persists an aUEC order's shared information and queue entry, and refreshes the order overview
   * after the commit (REQ-ORDERS-056).
   *
   * @param order the pair, contact and comment; the caller stores the target
   * @param rule the authority that decides whether the actor writes the pair
   * @param actor who creates
   * @param origin runs on the new, unsaved order after the pair checks and before the insert: it
   *     records where the create came from, and an exception it throws refuses the create
   * @return the persisted order
   */
  @Transactional(propagation = Propagation.MANDATORY)
  JobOrder createAuecJobOrder(
      @NotNull JobOrderCreateCommand.AuecOrder order,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderActor actor,
      @NotNull Consumer<JobOrder> origin) {
    return createOrder(
        new NewOrder(
            order.responsibleOrgUnitId(),
            order.requestingOrgUnitId(),
            order.handle(),
            order.comment(),
            JobOrderType.AUEC,
            order.target()),
        origin,
        rule,
        actor);
  }

  /**
   * Persists a new {@code ITEM} job order; org-unit stamping and priority match {@link
   * #createJobOrder(CreateJobOrderDto)}, and each line's materials are derived from its blueprint
   * by {@link JobOrderItemService}. After the commit the order overview and the material demand
   * overview are refreshed (REQ-ORDERS-056).
   *
   * @param createDto item-order create payload
   * @return the persisted order with derived materials and aggregation
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when a referenced game
   *     item or blueprint id is unknown
   * @throws BadRequestException when a blueprint does not produce its line's item, or org-unit
   *     stamping cannot be resolved
   */
  @Transactional
  public JobOrderDto createItemJobOrder(CreateJobOrderItemRequestDto createDto) {
    JobOrder order =
        createOrder(
            new NewOrder(
                createDto.responsibleOrgUnitId(),
                createDto.requestingOrgUnitId(),
                createDto.handle(),
                createDto.comment(),
                JobOrderType.ITEM,
                null),
            jobOrder ->
                itemReconciliation.apply(
                    jobOrder, jobOrderItemService.prepareItemLines(createDto.items(), List.of())),
            JobOrderWriterRule.CONTEXTUAL,
            webActor());
    return jobOrderStockProjectionService.mapToDtoWithStock(order);
  }

  /**
   * The one create path of every kind: under the order locks it resolves both units, rechecks the
   * pair with the given rule, adds the kind's lines, and records the audit event, the created
   * notice and the queue frame.
   */
  private JobOrder createOrder(
      @NotNull NewOrder draft,
      @NotNull Consumer<JobOrder> lines,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderActor actor) {
    profitHierarchy.lockForOrderWrite();
    jobOrderRepository.lockAllJobOrders();
    Integer newPriority = jobOrderRepository.findMaxPriority().orElse(0) + 1;

    OrgUnit responsible =
        jobOrderOrgUnitResolver.resolveResponsibleOrgUnit(draft.responsibleOrgUnitId());
    OrgUnit requesting =
        jobOrderOrgUnitResolver.resolveRequestingOrgUnit(draft.requestingOrgUnitId());
    if (!writers.resolve(rule, actor).permitsPair(responsible.getId(), requesting.getId())) {
      throw new AccessDeniedException("The writer rule does not let the actor write this pair.");
    }
    JobOrder jobOrder =
        JobOrder.builder()
            .handle(draft.handle())
            .comment(StringNormalization.trimToNull(draft.comment()))
            .priority(newPriority)
            .type(draft.type())
            .originalProfitOrgUnitId(profitHierarchy.requireEligible(responsible.getId()))
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requesting)
            .build();
    lines.accept(jobOrder);

    jobOrder = jobOrderRepository.save(jobOrder);
    jobOrderRepository.flush();
    unitHistory.retainObservedParties(jobOrder, Set.of());
    jobOrderPriorityService.normalizePriorities();
    effects.publish(JobOrderLifecycleNotices.created(jobOrder, actor, clientNames.appOf(actor)));
    publishQueueSections(jobOrder);
    auditRecorder.record(
        JobOrderResponsibilityAudit.createdType(draft.type(), actor),
        jobOrder.getId(),
        JobOrderAuditLabel.of(jobOrder),
        null,
        JobOrderResponsibilityAudit.created(jobOrder, actor, draft.target()));
    return jobOrder;
  }

  private @Nullable UUID currentMember() {
    return authHelperService.currentUserId().orElse(null);
  }

  private @NotNull JobOrderActor webActor() {
    return new JobOrderActor.Web(currentMember());
  }

  /**
   * The shared terms of a new order of any kind, as submitted.
   *
   * @param responsibleOrgUnitId the proposed responsible unit, required by the resolver
   * @param requestingOrgUnitId the proposed requesting unit, required by the resolver
   * @param handle the contact
   * @param comment the optional comment
   * @param type the order kind
   * @param target an aUEC order's target, {@code null} for the other kinds
   */
  private record NewOrder(
      @Nullable UUID responsibleOrgUnitId,
      @Nullable UUID requestingOrgUnitId,
      String handle,
      @Nullable String comment,
      @NotNull JobOrderType type,
      @Nullable BigDecimal target) {}

  /**
   * Reports a terminal outcome after commit.
   *
   * @param jobOrder the completed, rejected or deleted order
   * @param statusCode the order's final status code
   */
  private void publishJobOrderFinished(@NotNull JobOrder jobOrder, @NotNull String statusCode) {
    effects.publish(JobOrderLifecycleNotices.finished(jobOrder, statusCode, currentMember(), null));
  }

  private void publishJobOrderClosed(@NotNull JobOrder order) {
    effects.publish(closedEvent(order, currentMember()));
  }

  private JobOrderClosedEvent closedEvent(@NotNull JobOrder order, @Nullable UUID actor) {
    return new JobOrderClosedEvent(
        order.getId(),
        actor,
        JobOrderLifecycleNotices.revision(order),
        effects.captureActionPromptIds(order.getId()));
  }

  /**
   * Updates the status of a job order for the web caller, under the contextual authorities with the
   * honoured pin, the contextual read and the request's member, with its own load, save and flush.
   * Must not be called inside a transaction that already modified the same order, since the double
   * save fails the version check; use {@link #completeJobOrderWithinTransaction(JobOrder)} there.
   *
   * @param id job order primary key
   * @param dto the new status and expected version
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException for illegal
   *     transitions
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public JobOrderDto updateJobOrderStatus(UUID id, UpdateJobOrderStatusDto dto) {
    return jobOrderStockProjectionService.mapToDtoWithStock(
        changeStatus(id, dto.status(), dto.version(), JobOrderCaller.web(currentMember())));
  }

  /** Changes the status under the caller's writer rule, read check and actor (ADR-0408). */
  @Transactional(propagation = Propagation.MANDATORY)
  JobOrder changeStatus(
      @NotNull UUID id,
      @NotNull JobOrderStatus status,
      @Nullable Long lifecycleVersion,
      @NotNull JobOrderCaller caller) {
    return jobOrderEditor.editLifecycle(
        id,
        lifecycleVersion,
        caller.read(),
        order -> writers.resolve(caller.rule(), caller.actor()).permitsStatus(order, status),
        order -> statusPlan(order, status, caller.actor()));
  }

  private JobOrderEditPlan statusPlan(
      @NotNull JobOrder order, @NotNull JobOrderStatus status, @NotNull JobOrderActor actor) {
    if (order.getType() == JobOrderType.AUEC && status == JobOrderStatus.COMPLETED) {
      throw new BadRequestException("orders.money.completion.requiresHandover");
    }
    if (order.getType() == JobOrderType.ITEM) {
      itemFulfillment.lock(order.getId());
    }
    JobOrderStatus previous = order.getStatus();
    if (order.getType() == JobOrderType.AUEC && previous == JobOrderStatus.COMPLETED) {
      throw new BadRequestException("orders.money.completion.requiresHandover");
    }
    if ((status == JobOrderStatus.CANCELLED && !previous.isActive())
        || (previous == JobOrderStatus.CANCELLED && status != JobOrderStatus.OPEN)) {
      throw new BadRequestException("orders.status.transition.invalid");
    }
    boolean wasTerminal = terminal(previous);
    boolean becomesTerminal = terminal(status);
    return new JobOrderEditPlan(
        Set.of(),
        becomesTerminal && !wasTerminal
            ? Set.copyOf(stockReads.earmarkedRowIds(order.getId()))
            : Set.of(),
        wasTerminal != becomesTerminal,
        current -> applyStatus(current, status, actor));
  }

  private static boolean terminal(JobOrderStatus status) {
    return !status.isActive();
  }

  static void changeLifecycle(JobOrder order, JobOrderStatus status) {
    order.setStatus(status);
    order.setLifecycleVersion(Math.incrementExact(order.getLifecycleVersion()));
  }

  private JobOrderEdit applyStatus(
      @NotNull JobOrder jobOrder, @NotNull JobOrderStatus status, @NotNull JobOrderActor actor) {
    final JobOrderStatus previousStatus = jobOrder.getStatus();
    if (previousStatus == status) {
      return JobOrderEdit.unchanged(jobOrder);
    }
    boolean isTerminal = terminal(status);
    boolean wasTerminal = terminal(jobOrder.getStatus());

    if (isTerminal && !wasTerminal && jobOrder.getPriority() != null) {
      jobOrder.setPriority(null);
    } else if (!isTerminal && wasTerminal) {
      Integer newPriority = jobOrderRepository.findMaxPriority().orElse(0) + 1;
      jobOrder.setPriority(newPriority);
    }

    changeLifecycle(jobOrder, status);
    jobOrder = jobOrderRepository.save(jobOrder);
    jobOrderRepository.flush();

    if (isTerminal != wasTerminal) {
      jobOrderPriorityService.normalizePriorities();
    }

    List<Object> notices = new ArrayList<>();
    if (isTerminal && !wasTerminal) {
      moneyLifecycle.close(jobOrder);
      stockCommands.releaseJobOrderEarmarks(jobOrder.getId());
      notices.add(closedEvent(jobOrder, actor.member()));
      notices.add(
          JobOrderLifecycleNotices.finished(
              jobOrder, status.name(), actor.member(), clientNames.appOf(actor)));
    } else if (previousStatus == JobOrderStatus.CANCELLED
        || (previousStatus == JobOrderStatus.COMPLETED
            && jobOrder.getType() == JobOrderType.AUEC)) {
      notices.add(JobOrderLifecycleNotices.reopened(jobOrder, actor.member()));
    }

    JobOrderEdit edit =
        status == JobOrderStatus.COMPLETED && previousStatus != JobOrderStatus.COMPLETED
            ? JobOrderEdit.audited(
                jobOrder,
                AuditEventType.JOB_ORDER_COMPLETED,
                AuditDetails.of("from", previousStatus).with("autoCompleted", "false"))
            : JobOrderEdit.audited(
                jobOrder,
                AuditEventType.JOB_ORDER_STATUS_CHANGED,
                AuditDetails.of("from", previousStatus).with("to", status));
    publishLifecycleSections(jobOrder);
    return edit.notifying(notices);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  void reopenUnderfulfilledMoneyOrder(JobOrder order) {
    JobOrderEdit edit = applyStatus(order, JobOrderStatus.IN_PROGRESS, webActor());
    auditRecorder.record(
        AuditEventType.JOB_ORDER_STATUS_CHANGED,
        order.getId(),
        JobOrderAuditLabel.of(order),
        null,
        edit.details());
    edit.notices().forEach(effects::publish);
  }

  private void publishLifecycleSections(JobOrder order) {
    liveSyncPublisher.publishAfterCommit(
        LiveSyncTopic.of(LiveSyncTopicClass.ORDER, order.getId()),
        order.getType() == JobOrderType.AUEC
            ? JobOrderMoneyEffects.LIFECYCLE_SECTIONS
            : List.of(
                "header",
                "actions",
                "dialogs",
                "kpi",
                "participations",
                "materials",
                "aggregated",
                "items",
                "handovers",
                "item-handovers",
                "item-handover-lines"));
    publishQueueSections(order);
  }

  /**
   * Refreshes the order overview after the commit, and the material demand overview for the stock
   * kinds; an aUEC order adds no material demand.
   */
  private void publishQueueSections(JobOrder order) {
    liveSyncPublisher.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE),
        order.getType() == JobOrderType.AUEC ? List.of("queue") : List.of("queue", "demand"));
  }

  /**
   * Moves a job order to a new priority position, shifting adjacent orders. Concurrent reorders are
   * serialized by a pessimistic lock on the whole priority sequence.
   *
   * @param id job order primary key
   * @param newPriority target slot (1-based)
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no order matches
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the order is not
   *     an item order
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public JobOrderDto updateBlueprintVariantCounting(
      UUID id, boolean countWithVariants, Long version) {
    return jobOrderEditor.edit(
        id,
        JobOrderGate.EDIT,
        version,
        jobOrder -> {
          if (jobOrder.getType() != JobOrderType.ITEM) {
            throw new BadRequestException("Blueprint variant counting applies only to item orders");
          }
          if (jobOrder.isCountBlueprintsWithVariants() == countWithVariants) {
            return JobOrderEdit.unchanged(jobOrder);
          }
          jobOrder.setCountBlueprintsWithVariants(countWithVariants);
          return JobOrderEdit.audited(
              jobOrderRepository.saveAndFlush(jobOrder),
              AuditEventType.JOB_ORDER_BLUEPRINT_COUNTING_CHANGED,
              AuditDetails.of("countWithVariants", countWithVariants));
        });
  }

  /**
   * Fully updates the order's metadata and its requested materials in place; removed materials are
   * deleted, kept lines retain their handed-over credit and inventory links.
   *
   * @param id job order primary key
   * @param updateDto update payload with the expected order and comment versions
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the order or
   *     comment version is missing or stale
   */
  @Transactional
  public JobOrderDto updateJobOrder(UUID id, CreateJobOrderDto updateDto) {
    List<CreateJobOrderMaterialDto> materials = List.copyOf(updateDto.materials());
    return jobOrderEditor.editPlannedWithComment(
        id,
        JobOrderGate.EDIT,
        updateDto.version(),
        updateDto.commentVersion(),
        plannedOrder ->
            materialReplacementPlan(
                plannedOrder,
                materials,
                jobOrder -> {
                  final var before = JobOrderNeedSnapshot.capture(jobOrder);
                  if (updateDto.requestingOrgUnitId() != null) {
                    jobOrder.setRequestingOrgUnit(
                        jobOrderOrgUnitResolver.resolveRequestingOrgUnit(
                            updateDto.requestingOrgUnitId()));
                  }
                  jobOrder.setHandle(updateDto.handle());
                  JobOrderComment.replace(jobOrder, updateDto.comment(), webActor());
                  MaterialReplaceOutcome outcome =
                      replaceMaterialsWithinTransaction(id, jobOrder, materials);
                  return notifyChangedNeed(
                      before,
                      JobOrderEdit.audited(
                          outcome.order(),
                          AuditEventType.JOB_ORDER_UPDATED,
                          AuditDetails.of("materialsRemoved", outcome.removedCount())
                              .with("materials", outcome.order().getMaterials().size())
                              .with("orphanedClaimsWithdrawn", outcome.orphanedClaimsWithdrawn())));
                }));
  }

  private JobOrderEditPlan materialReplacementPlan(
      JobOrder order,
      List<CreateJobOrderMaterialDto> materials,
      java.util.function.Function<JobOrder, JobOrderEdit> mutation) {
    if (order.getType() != JobOrderType.MATERIAL) {
      throw new BadRequestException("orders.kind.material.required");
    }
    Set<UUID> targets =
        materials.stream()
            .map(CreateJobOrderMaterialDto::materialId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Set<UUID> removed =
        order.getMaterials().stream()
            .map(line -> line.getMaterial().getId())
            .filter(material -> !targets.contains(material))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return new JobOrderEditPlan(
        targets,
        Set.copyOf(stockReads.earmarkedMaterialRowIds(order.getId(), removed)),
        false,
        mutation);
  }

  /**
   * Reconciles the order's material lines with the edited requested amounts in place and unlinks
   * the inventory of every removed material, shared by the logistician and requester edits.
   *
   * <p>Kept lines keep their identity and handed-over credit (ADR-0400). The aggregate is saved and
   * flushed before the context-clearing bulk unlinks run, and the order is then re-fetched, so the
   * save never degrades into a stale merge and the returned version is current.
   *
   * @param id the order id, used for the re-fetch and the bulk unlink
   * @param managed the managed order whose scalars the caller has already set
   * @param materials the edited material lines with their requested amounts
   * @return the re-fetched order plus the removed-material and withdrawn-claim counts
   * @throws BadRequestException when a line would fall below its handed-over amount, or a line with
   *     handed-over credit would be removed or moved to another tier
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

    materialLines.reconcile(managed, materials);
    jobOrderRepository.saveAndFlush(managed);

    for (UUID removedId : removedMaterialIds) {
      stockCommands.releaseJobOrderMaterialEarmarks(id, removedId);
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
   * @param removedCount number of materials the edit removed from the order
   * @param orphanedClaimsWithdrawn number of orphaned claims withdrawn
   */
  private record MaterialReplaceOutcome(
      JobOrder order, int removedCount, int orphanedClaimsWithdrawn) {}

  /**
   * Fully edits an {@code ITEM} order's lines and metadata via {@link JobOrderItemReconciliation};
   * matched lines are updated in place so booked production survives (REQ-ORDERS-032). Allowed only
   * while the order has no item handover.
   *
   * <p>Claims on buckets the new lines no longer require are withdrawn afterwards, without bumping
   * the order's version. The responsible org unit is not changed here.
   *
   * @param id the order id
   * @param updateDto the new item lines and metadata with the expected order and comment versions
   * @return the persisted order DTO with re-derived materials and aggregation
   * @throws NotFoundException when the order, a game item or a blueprint id is unknown
   * @throws BadRequestException when the order is not an item order, has item handovers, or a
   *     blueprint does not produce its line's item
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the order or
   *     comment version is missing or stale
   */
  @Transactional
  public JobOrderDto updateItemJobOrder(UUID id, CreateJobOrderItemRequestDto updateDto) {
    return jobOrderEditor.editPlannedWithComment(
        id,
        JobOrderGate.EDIT,
        updateDto.version(),
        updateDto.commentVersion(),
        jobOrder -> itemReplacementPlan(jobOrder, updateDto, false));
  }

  private JobOrderEditPlan itemReplacementPlan(
      @NotNull JobOrder order, @NotNull CreateJobOrderItemRequestDto dto, boolean requester) {
    itemFulfillment.lock(order.getId());
    assertItemOrderEditable(order, requester);
    JobOrderItemPlan prepared = jobOrderItemService.prepareItemLines(dto.items(), order.getItems());
    Set<UUID> rows = new LinkedHashSet<>();
    if (requester) {
      Set<UUID> removedMaterials =
          new LinkedHashSet<>(jobOrderItemService.requiredMaterialIds(order));
      removedMaterials.removeAll(prepared.materialIds());
      Set<UUID> removedItems = new LinkedHashSet<>(jobOrderItemService.requiredGameItemIds(order));
      prepared.lines().forEach(line -> removedItems.remove(line.gameItemId()));
      rows.addAll(stockReads.earmarkedMaterialRowIds(order.getId(), removedMaterials));
      rows.addAll(stockReads.earmarkedGameItemRowIds(order.getId(), removedItems));
    }
    return new JobOrderEditPlan(
        prepared.materialIds(),
        rows,
        false,
        current ->
            requester
                ? rewriteItemOrderAsRequester(current, dto, prepared)
                : rewriteItemOrder(current, dto, prepared));
  }

  private JobOrderEdit rewriteItemOrder(
      @NotNull JobOrder jobOrder,
      @NotNull CreateJobOrderItemRequestDto updateDto,
      @NotNull JobOrderItemPlan prepared) {
    assertItemOrderEditable(jobOrder, false);
    final var before = JobOrderNeedSnapshot.capture(jobOrder);
    itemReconciliation.apply(jobOrder, prepared);

    if (updateDto.requestingOrgUnitId() != null) {
      jobOrder.setRequestingOrgUnit(
          jobOrderOrgUnitResolver.resolveRequestingOrgUnit(updateDto.requestingOrgUnitId()));
    }
    jobOrder.setHandle(updateDto.handle());
    JobOrderComment.replace(jobOrder, updateDto.comment(), webActor());

    jobOrder = jobOrderRepository.save(jobOrder);
    jobOrderRepository.flush();

    int orphanedClaimsWithdrawn =
        materialClaimService.withdrawOrphanedClaimsWithinTransaction(jobOrder);
    return notifyChangedNeed(
        before,
        JobOrderEdit.audited(
            jobOrder,
            AuditEventType.JOB_ORDER_ITEM_UPDATED,
            AuditDetails.of("lines", jobOrder.getItems().size())
                .with("orphanedClaimsWithdrawn", orphanedClaimsWithdrawn)));
  }

  private JobOrderEdit notifyChangedNeed(JobOrderNeedSnapshot before, JobOrderEdit edit) {
    JobOrder order = edit.order();
    if (before.equals(JobOrderNeedSnapshot.capture(order))) {
      return edit;
    }
    return edit.notifying(
        List.of(JobOrderLifecycleNotices.needChanged(order, currentMember(), null)));
  }

  /**
   * Edits the requested MATERIAL amounts and the requester comment while retaining booked
   * handovers; no line may fall below its handed-over amount.
   *
   * <p>Removed materials are unlinked from Inventory and current promises are reconciled
   * atomically.
   *
   * @param id the order id
   * @param updateDto the new material lines and comment with the expected order and comment
   *     versions
   * @return the persisted order DTO
   * @throws NotFoundException when the order or a material id is unknown
   * @throws BadRequestException when the order is not a material order
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the order or
   *     comment version is missing or stale
   */
  @Transactional
  public JobOrderDto updateJobOrderAsRequester(UUID id, CreateJobOrderDto updateDto) {
    List<CreateJobOrderMaterialDto> materials = List.copyOf(updateDto.materials());
    return jobOrderEditor.editPlannedWithComment(
        id,
        JobOrderGate.REQUESTER,
        updateDto.version(),
        updateDto.commentVersion(),
        plannedOrder ->
            materialReplacementPlan(
                plannedOrder,
                materials,
                jobOrder -> {
                  JobOrderComment.replace(jobOrder, updateDto.comment(), webActor());
                  MaterialReplaceOutcome outcome =
                      replaceMaterialsWithinTransaction(id, jobOrder, materials);
                  return JobOrderEdit.audited(
                          outcome.order(),
                          AuditEventType.JOB_ORDER_UPDATED,
                          AuditDetails.of("materialsRemoved", outcome.removedCount())
                              .with("materials", outcome.order().getMaterials().size())
                              .with("orphanedClaimsWithdrawn", outcome.orphanedClaimsWithdrawn())
                              .with("byRequester", true))
                      .notifying(
                          List.of(
                              JobOrderLifecycleNotices.requesterChanged(
                                  outcome.order(), currentMember(), null)));
                }));
  }

  /**
   * Edits requested ITEM lines and the requester comment while retaining booked work.
   *
   * <p>Unlike {@link #updateItemJobOrder}, it unlinks inventory of materials no longer required and
   * drops game-item allocations of items no longer ordered (REQ-INV-031). Notifies the responsible
   * org unit on commit; audited as {@code JOB_ORDER_ITEM_UPDATED} with {@code byRequester=true}.
   *
   * @param id the order id
   * @param updateDto the new item lines and comment with the expected order and comment versions
   * @return the persisted order DTO with re-derived materials
   * @throws NotFoundException when the order, a game item or a blueprint id is unknown
   * @throws BadRequestException when the order is not an item order or the edit discards booked
   *     work
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the order or
   *     comment version is missing or stale
   */
  @Transactional
  public JobOrderDto updateItemJobOrderAsRequester(
      UUID id, CreateJobOrderItemRequestDto updateDto) {
    return jobOrderEditor.editPlannedWithComment(
        id,
        JobOrderGate.REQUESTER,
        updateDto.version(),
        updateDto.commentVersion(),
        jobOrder -> itemReplacementPlan(jobOrder, updateDto, true));
  }

  private JobOrderEdit rewriteItemOrderAsRequester(
      @NotNull JobOrder jobOrder,
      @NotNull CreateJobOrderItemRequestDto updateDto,
      @NotNull JobOrderItemPlan prepared) {
    final UUID id = jobOrder.getId();
    assertItemOrderEditable(jobOrder, true);

    final Set<UUID> requiredBefore =
        new LinkedHashSet<>(jobOrderItemService.requiredMaterialIds(jobOrder));
    final Set<UUID> requiredGameItemsBefore =
        new LinkedHashSet<>(jobOrderItemService.requiredGameItemIds(jobOrder));

    itemReconciliation.apply(jobOrder, prepared);
    JobOrderComment.replace(jobOrder, updateDto.comment(), webActor());

    jobOrderRepository.saveAndFlush(jobOrder);

    Set<UUID> noLongerRequired = new LinkedHashSet<>(requiredBefore);
    noLongerRequired.removeAll(jobOrderItemService.requiredMaterialIds(jobOrder));
    Set<UUID> noLongerRequestedGameItems = new LinkedHashSet<>(requiredGameItemsBefore);
    noLongerRequestedGameItems.removeAll(jobOrderItemService.requiredGameItemIds(jobOrder));
    for (UUID removedMaterialId : noLongerRequired) {
      stockCommands.releaseJobOrderMaterialEarmarks(id, removedMaterialId);
    }
    for (UUID removedGameItemId : noLongerRequestedGameItems) {
      stockCommands.releaseJobOrderGameItemEarmarks(id, removedGameItemId);
    }

    JobOrder refreshed =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    int orphanedClaimsWithdrawn =
        materialClaimService.withdrawOrphanedClaimsWithinTransaction(refreshed);
    return JobOrderEdit.audited(
            refreshed,
            AuditEventType.JOB_ORDER_ITEM_UPDATED,
            AuditDetails.of("lines", refreshed.getItems().size())
                .with("orphanedClaimsWithdrawn", orphanedClaimsWithdrawn)
                .with("byRequester", true))
        .notifying(
            List.of(JobOrderLifecycleNotices.requesterChanged(refreshed, currentMember(), null)));
  }

  private static void assertItemOrderEditable(@NotNull JobOrder order, boolean requester) {
    if (order.getType() != JobOrderType.ITEM) {
      throw new BadRequestException(
          "Order "
              + order.getId()
              + " is not an item order; use the "
              + (requester ? "requester material-update" : "material-order update")
              + " endpoint.");
    }
    if (!requester && order.getItemHandovers() != null && !order.getItemHandovers().isEmpty()) {
      throw new BadRequestException(
          "Item order " + order.getId() + " already has handovers and can no longer be edited.");
    }
  }

  /**
   * Hard-deletes an administrator's writable order after releasing its physical stock allocations.
   *
   * @param id the order to delete
   * @throws NotFoundException when the order is unknown
   */
  @Transactional
  public void deleteJobOrder(UUID id) {
    jobOrderEditor.deletePlanned(id, this::deletionPlan);
  }

  private JobOrderEditPlan deletionPlan(@NotNull JobOrder order) {
    if (order.getType() == JobOrderType.ITEM) {
      itemFulfillment.lock(order.getId());
    }
    return new JobOrderEditPlan(
        Set.of(),
        Set.copyOf(stockReads.earmarkedRowIds(order.getId())),
        order.getPriority() != null,
        this::deletePrepared);
  }

  private JobOrderEdit deletePrepared(@NotNull JobOrder order) {
    final Integer priority = order.getPriority();
    final UUID deletedId = order.getId();
    final NoticeEvent deletedNotice =
        JobOrderLifecycleNotices.finished(order, "DELETED", currentMember(), null);
    final JobOrderClosedEvent closed = closedEvent(order, currentMember());
    stockCommands.releaseJobOrderEarmarks(deletedId);
    materialClaimService.deleteForOrderWithinTransaction(order);
    jobOrderRepository.delete(order);
    jobOrderRepository.flush();
    if (priority != null) {
      jobOrderPriorityService.normalizePriorities();
    }
    return JobOrderEdit.audited(
            order, AuditEventType.JOB_ORDER_DELETED, AuditDetails.of("priorityWas", priority))
        .notifying(List.of(closed, deletedNotice));
  }

  /**
   * Adds an assignee to a job order; re-adding the same user is a no-op.
   *
   * @param jobOrderId job order primary key
   * @param userId user to add
   * @return the persisted order with the refreshed assignee list
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when either id is
   *     unknown
   */
  @Transactional
  public JobOrderDto addAssignee(UUID jobOrderId, UUID userId) {
    return jobOrderAssigneeService.addAssignee(jobOrderId, userId);
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
    jobOrderEditor.editWithoutProjection(
        jobOrderId, JobOrderGate.EDIT, order -> inventoryUnlinkPlan(order, inventoryItemId));
  }

  private JobOrderEditPlan inventoryUnlinkPlan(JobOrder order, UUID inventoryItemId) {
    StockRow item =
        Entities.require(
            stockReads.findRow(inventoryItemId),
            () -> "InventoryItem not found: " + inventoryItemId);
    if (!item.isEarmarkedForJobOrder(order.getId())) {
      throw new NotFoundException("InventoryItem not linked to job order: " + inventoryItemId);
    }
    return new JobOrderEditPlan(
        Set.of(),
        Set.of(inventoryItemId),
        false,
        current -> {
          stockCommands.releaseJobOrderEarmark(inventoryItemId, current.getId());
          return JobOrderEdit.audited(
              current,
              AuditEventType.JOB_ORDER_INVENTORY_UNLINKED,
              AuditDetails.of("inventoryItem", inventoryItemId));
        });
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
    responsibilityGuard.assertWritable(jobOrder);
    boolean wasTerminal = terminal(jobOrder.getStatus());

    if (!wasTerminal && jobOrder.getPriority() != null) {
      jobOrder.setPriority(null);
    }

    changeLifecycle(jobOrder, JobOrderStatus.COMPLETED);

    if (!wasTerminal) {
      moneyLifecycle.close(jobOrder);
      jobOrderRepository.flush();
      jobOrderPriorityService.normalizePriorities();
      stockCommands.releaseJobOrderEarmarks(jobOrder.getId());
      auditRecorder.record(
          AuditEventType.JOB_ORDER_COMPLETED,
          jobOrder.getId(),
          JobOrderAuditLabel.of(jobOrder),
          null,
          "autoCompleted=true");
      publishJobOrderClosed(jobOrder);
      publishJobOrderFinished(jobOrder, "COMPLETED");
      publishLifecycleSections(jobOrder);
    }
  }

  /**
   * Transfers responsibility immediately to an eligible unit inside the verified Profit boundary.
   *
   * @param id the order to transfer
   * @param newResponsibleOrgUnitId the active Profit target
   * @param version the observed responsibility revision
   * @return the order with unchanged requester, facts and original provenance
   * @throws BadRequestException when the target is ineligible or outside the original boundary
   * @throws AccessDeniedException when the caller is not a current responsible-unit writer
   */
  @Transactional
  public JobOrderDto reassignResponsibleOrgUnit(
      UUID id, UUID newResponsibleOrgUnitId, Long version) {
    return jobOrderEditor.edit(
        id,
        JobOrderGate.TRANSFER,
        version,
        jobOrder -> reassign(jobOrder, newResponsibleOrgUnitId));
  }

  private JobOrderEdit reassign(@NotNull JobOrder jobOrder, @NotNull UUID newResponsibleOrgUnitId) {
    OrgUnit target =
        orgUnitRepository
            .findById(newResponsibleOrgUnitId)
            .orElseThrow(() ->
                new BadRequestException(
                    "responsibleOrgUnitId does not resolve to a known org unit: "
                        + newResponsibleOrgUnitId));
    UUID root = profitHierarchy.requireEligible(target.getId());
    if (jobOrder.getOriginalProfitOrgUnitId() != null
        && !root.equals(jobOrder.getOriginalProfitOrgUnitId())) {
      throw new BadRequestException("orders.responsibility.boundary.invalid");
    }

    final OrgUnit previous = jobOrder.getResponsibleOrgUnit();
    if (target.equals(previous)) {
      return JobOrderEdit.unchanged(jobOrder);
    }
    jobOrder.setResponsibleOrgUnit(target);
    jobOrder.setResponsibilityVersion(Math.incrementExact(jobOrder.getResponsibilityVersion()));
    participationCommands.becomeResponsible(jobOrder, target);
    moneyLifecycle.responsibilityLost(jobOrder, OrgUnit.idOf(previous));
    liveSyncPublisher.publishAfterCommit(
        LiveSyncTopic.of(LiveSyncTopicClass.ORDER, jobOrder.getId()),
        List.of(
            "header",
            "actions",
            "dialogs",
            "materials",
            "aggregated",
            "items",
            "assignees",
            "participations",
            "money",
            "blueprint-owners",
            "handovers",
            "item-handovers",
            "item-handover-lines"));
    liveSyncPublisher.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue", "demand"));
    jobOrder = jobOrderRepository.save(jobOrder);
    log.info(
        "Job order {} responsible org unit reassigned: {} ({}) → {} ({})",
        jobOrder.getId(),
        previous != null ? previous.getId() : null,
        previous != null ? previous.getKind() : null,
        target.getId(),
        target.getKind());

    NoticeEvent reassignedNotice =
        JobOrderLifecycleNotices.reassigned(
            jobOrder, previous, authHelperService.currentUserId().orElse(null));

    return JobOrderEdit.audited(
            jobOrder,
            AuditEventType.JOB_ORDER_REASSIGNED,
            JobOrderResponsibilityAudit.transferred(jobOrder, previous, root))
        .notifying(List.of(reassignedNotice));
  }
}

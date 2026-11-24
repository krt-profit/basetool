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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientNames;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates job orders of every kind on one path (ADR-0408): under the order locks it resolves both
 * units, rechecks the pair with the writer rule it is given, adds the kind's lines and records the
 * unit history, the priority, the created notice, the {@code orders/queue} frame and the audit
 * event. Nothing is filled in.
 *
 * <p>The web's MATERIAL and ITEM creates run under the contextual rule and the request's member;
 * {@link JobOrderAuecCreation} stores an aUEC order's target and a client's create key on top.
 */
@Service
@RequiredArgsConstructor
public class JobOrderCreation {

  private final JobOrderRepository jobOrderRepository;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderOrgUnitResolver orgUnitResolver;
  private final JobOrderWriters writers;
  private final JobOrderMaterialLines materialLines;
  private final JobOrderItemService itemService;
  private final JobOrderItemReconciliation itemReconciliation;
  private final JobOrderUnitHistory unitHistory;
  private final JobOrderPriorityService priorityService;
  private final JobOrderChangeEffects effects;
  private final JobOrderClientNames clientNames;
  private final JobOrderQueueFrame queueFrame;
  private final AuditRecorder auditRecorder;
  private final JobOrderStockProjectionService projection;
  private final AuthHelperService authHelper;

  /**
   * Persists a new MATERIAL order for the web caller in the next free priority slot (1 is highest),
   * taking each material's minimum quality verbatim from the DTO (650 or {@code null} for none).
   * After the commit the order overview and the material demand overview are refreshed
   * (REQ-ORDERS-056).
   *
   * @param createDto create payload
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when a referenced
   *     material or user id is unknown
   * @throws BadRequestException when a unit is unknown or the responsible unit is not an eligible
   *     Profit unit
   * @throws AccessDeniedException when the caller writes neither proposed unit
   */
  @Transactional
  public @NotNull JobOrderDto createMaterialOrder(@NotNull CreateJobOrderDto createDto) {
    JobOrder order =
        create(
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
            JobOrderActor.Web.current(authHelper));
    return projection.mapToDtoWithStock(order);
  }

  /**
   * Persists a new ITEM order for the web caller; unit stamping and priority match {@link
   * #createMaterialOrder(CreateJobOrderDto)}, and each line's materials are derived from its
   * blueprint by {@link JobOrderItemService}. After the commit the order overview and the material
   * demand overview are refreshed (REQ-ORDERS-056).
   *
   * @param createDto item-order create payload
   * @return the persisted order with derived materials and aggregation
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when a referenced game
   *     item or blueprint id is unknown
   * @throws BadRequestException when a blueprint does not produce its line's item, or unit stamping
   *     cannot be resolved
   * @throws AccessDeniedException when the caller writes neither proposed unit
   */
  @Transactional
  public @NotNull JobOrderDto createItemOrder(@NotNull CreateJobOrderItemRequestDto createDto) {
    JobOrder order =
        create(
            new NewOrder(
                createDto.responsibleOrgUnitId(),
                createDto.requestingOrgUnitId(),
                createDto.handle(),
                createDto.comment(),
                JobOrderType.ITEM,
                null),
            jobOrder ->
                itemReconciliation.apply(
                    jobOrder, itemService.prepareItemLines(createDto.items(), List.of())),
            JobOrderWriterRule.CONTEXTUAL,
            JobOrderActor.Web.current(authHelper));
    return projection.mapToDtoWithStock(order);
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
   * @throws BadRequestException when a unit is unknown or the responsible unit is not an eligible
   *     Profit unit
   * @throws AccessDeniedException when the rule does not let the actor write the pair
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @NotNull
  JobOrder createAuecOrder(
      @NotNull JobOrderCreateCommand.AuecOrder order,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderActor actor,
      @NotNull Consumer<JobOrder> origin) {
    return create(
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

  private @NotNull JobOrder create(
      @NotNull NewOrder draft,
      @NotNull Consumer<JobOrder> lines,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderActor actor) {
    profitHierarchy.lockForOrderWrite();
    jobOrderRepository.lockAllJobOrders();
    Integer newPriority = jobOrderRepository.findMaxPriority().orElse(0) + 1;

    OrgUnit responsible = orgUnitResolver.resolveResponsibleOrgUnit(draft.responsibleOrgUnitId());
    OrgUnit requesting = orgUnitResolver.resolveRequestingOrgUnit(draft.requestingOrgUnitId());
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
    priorityService.normalizePriorities();
    effects.publish(JobOrderLifecycleNotices.created(jobOrder, actor, clientNames.appOf(actor)));
    queueFrame.publish(jobOrder);
    auditRecorder.record(
        JobOrderResponsibilityAudit.createdType(draft.type(), actor),
        jobOrder.getId(),
        JobOrderAuditLabel.of(jobOrder),
        null,
        JobOrderResponsibilityAudit.created(jobOrder, actor, draft.target()));
    return jobOrder;
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
}

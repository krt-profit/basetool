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
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The edit module of the job-order aggregate (ADR-0287): every order edit runs through it, and it
 * alone decides the order load, access check on the loaded order, version check, mutation, audit
 * subject and label, notices and projection.
 *
 * <p>The access check runs for every caller, with or without an HTTP request (ADR-0236 §6). An edit
 * that carries a client version requires it: a missing or stale version is a 409 (REQ-ORDERS-046).
 * The mutation persists the rows it touches, because the order's writes differ in their flush and
 * re-fetch needs.
 */
@Service
@RequiredArgsConstructor
public class JobOrderEditor {

  private final JobOrderRepository jobOrderRepository;
  private final ProfitHierarchyService profitHierarchy;
  private final MaterialPromiseCoordinator materialPromises;
  private final StockCommands stockCommands;
  private final EntityManager entityManager;
  private final JobOrderResponsibilityGuard responsibilityGuard;
  private final JobOrderAccessPolicy jobOrderAccessPolicy;
  private final AuditRecorder auditRecorder;
  private final JobOrderChangeEffects effects;
  private final JobOrderStockProjectionService jobOrderStockProjectionService;
  private final JobOrderUnitHistory unitHistory;

  /** The checked order and first captured mutation after exact resource revalidation. */
  private record PreparedEdit(@NotNull JobOrder order, @NotNull JobOrderEditPlan plan) {}

  /** Scoped money mutation and the audit and notices that commit with it. */
  record MoneyEdit<T>(T result, JobOrderEdit edit) {}

  /** One financial change with an audit outcome in each affected order. */
  record MoneyPairEdit<T>(T result, JobOrderEdit source, JobOrderEdit destination) {}

  /** Both managed orders after all row locks and current authority checks. */
  record MoneyPairBasis(JobOrder source, JobOrder destination, boolean queueLocked) {}

  /**
   * Applies one monetary edit while responsibility, lifecycle and deletion are serialized.
   *
   * @param id the order to edit
   * @param mutation the financial action, including its applicable version checks
   * @param <T> the action result type
   * @return the action's durable result
   */
  @Transactional(propagation = Propagation.MANDATORY)
  <T> T editMoney(UUID id, Function<JobOrder, MoneyEdit<T>> mutation) {
    return editMoney(id, _ -> false, (order, _) -> mutation.apply(order));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  <T> T editMoney(
      UUID id,
      Predicate<JobOrder> needsQueue,
      BiFunction<JobOrder, Boolean, MoneyEdit<T>> mutation) {
    profitHierarchy.lockForOrderWrite();
    JobOrder order = requireMoneyOrder(id);
    boolean queueLocked = needsQueue.test(order);
    if (queueLocked) {
      jobOrderRepository.lockPriorityQueueRows();
    }
    Entities.require(jobOrderRepository.lockForScopedEdit(id), () -> "JobOrder not found: " + id);
    entityManager.refresh(order);
    authorizeMoney(order);
    MoneyEdit<T> result = mutation.apply(order, queueLocked);
    recordOutcome(result.edit());
    return result.result();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  <T> T editMoneyPair(
      UUID sourceId,
      UUID destinationId,
      BiFunction<JobOrder, JobOrder, MoneyPairEdit<T>> mutation) {
    return editMoneyPair(
        sourceId,
        destinationId,
        _ -> false,
        basis -> mutation.apply(basis.source(), basis.destination()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  <T> T editMoneyPair(
      UUID sourceId,
      UUID destinationId,
      Predicate<JobOrder> needsQueue,
      Function<MoneyPairBasis, MoneyPairEdit<T>> mutation) {
    profitHierarchy.lockForOrderWrite();
    JobOrder source = requireMoneyOrder(sourceId);
    final JobOrder destination = requireMoneyOrder(destinationId);
    boolean queueLocked = needsQueue.test(source);
    List<UUID> locked =
        queueLocked
            ? jobOrderRepository.lockPriorityQueueRows()
            : jobOrderRepository.lockForOrderEarmarks(List.of(sourceId, destinationId));
    if (!locked.contains(sourceId)) {
      throw new NotFoundException("JobOrder not found: " + sourceId);
    }
    if (!locked.contains(destinationId)) {
      throw new NotFoundException("JobOrder not found: " + destinationId);
    }
    entityManager.refresh(source);
    entityManager.refresh(destination);
    authorizeMoney(source);
    authorizeMoney(destination);
    MoneyPairEdit<T> result = mutation.apply(new MoneyPairBasis(source, destination, queueLocked));
    recordOutcome(result.source());
    recordOutcome(result.destination());
    return result.result();
  }

  private JobOrder requireMoneyOrder(UUID id) {
    JobOrder order =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    authorizeMoney(order);
    return order;
  }

  private void authorizeMoney(JobOrder order) {
    if (!jobOrderAccessPolicy.canSeeJobOrder(order)) {
      throw new AccessDeniedException("Caller may not read this order.");
    }
    JobOrderMoneyQueries.requireMoneyKind(order);
    responsibilityGuard.assertWritable(order);
  }

  /**
   * Runs a versioned edit and projects the result.
   *
   * @param id the order to edit
   * @param gate the access the edit demands
   * @param expectedVersion the order version the caller last saw; {@code null} is a conflict
   * @param mutation the change to apply to the managed order
   * @return the projected order after the edit
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown
   * @throws AccessDeniedException when the caller fails the gate
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedVersion} is missing or stale
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public JobOrderDto edit(
      @NotNull UUID id,
      @NotNull JobOrderGate gate,
      @Nullable Long expectedVersion,
      @NotNull Function<JobOrder, JobOrderEdit> mutation) {
    return jobOrderStockProjectionService.mapToDtoWithStock(
        run(id, gate, true, expectedVersion, _ -> JobOrderEditPlan.of(mutation)));
  }

  /**
   * Plans a versioned edit before taking its complete material and physical resource set.
   *
   * @param id the order to edit
   * @param gate the current authority required by the edit
   * @param expectedVersion the observed order version
   * @param planner immutable resource selection and mutation over the authorized order
   * @return the current projected order
   * @throws AccessDeniedException when current authority fails before or after resource locking
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the observed
   *     version is absent or stale
   * @throws BusinessConflictException when the complete resource plan changes while waiting
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public JobOrderDto editPlanned(
      @NotNull UUID id,
      @NotNull JobOrderGate gate,
      @Nullable Long expectedVersion,
      @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    return jobOrderStockProjectionService.mapToDtoWithStock(
        run(id, gate, true, expectedVersion, planner));
  }

  /**
   * Runs an edit whose request carries no version and projects the result.
   *
   * @param id the order to edit
   * @param gate the access the edit demands
   * @param mutation the change to apply to the managed order
   * @return the projected order after the edit
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown
   * @throws AccessDeniedException when the caller fails the gate
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public JobOrderDto editUnversioned(
      @NotNull UUID id,
      @NotNull JobOrderGate gate,
      @NotNull Function<JobOrder, JobOrderEdit> mutation) {
    return jobOrderStockProjectionService.mapToDtoWithStock(
        run(id, gate, false, null, _ -> JobOrderEditPlan.of(mutation)));
  }

  /**
   * Runs a resource-planned edit without a client version or response projection.
   *
   * @param id the order to edit
   * @param gate the access the edit demands
   * @param planner the complete resources and managed order mutation
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown
   * @throws AccessDeniedException when the caller fails the gate
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void editWithoutProjection(
      @NotNull UUID id,
      @NotNull JobOrderGate gate,
      @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    run(id, gate, false, null, planner);
  }

  /**
   * Deletes an authorized order after the same complete resource preparation as ordinary edits.
   *
   * @param id the order to delete
   * @param planner the complete release resources and deletion mutation
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when responsibility is
   *     ineligible
   * @throws AccessDeniedException when the caller is not an administrator
   * @throws BusinessConflictException when the complete resource plan changes while waiting
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void deletePlanned(
      @NotNull UUID id, @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    PreparedEdit prepared = prepare(id, JobOrderGate.ADMIN_DELETE, false, null, planner);
    if (prepared.order().getType() == JobOrderType.AUEC
        && jobOrderRepository.hasDurableMoneySubmission(id)) {
      throw new BusinessConflictException("orders.money.delete.submitted");
    }
    JobOrderEdit edit = prepared.plan().mutation().apply(prepared.order());
    recordOutcome(edit);
  }

  private JobOrder run(
      @NotNull UUID id,
      @NotNull JobOrderGate gate,
      boolean versioned,
      @Nullable Long expectedVersion,
      @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    PreparedEdit prepared = prepare(id, gate, versioned, expectedVersion, planner);
    JobOrder order = prepared.order();
    JobOrderEditPlan planned = prepared.plan();
    Set<UUID> previousParties = JobOrderUnitHistory.partyIds(order);
    JobOrderEdit edit = planned.mutation().apply(order);
    JobOrder result = edit.order();
    materialPromises.reconcileOrder(id);
    unitHistory.retainObservedParties(result, previousParties);
    recordOutcome(edit);
    return result;
  }

  private PreparedEdit prepare(
      @NotNull UUID id,
      @NotNull JobOrderGate gate,
      boolean versioned,
      @Nullable Long expectedVersion,
      @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    profitHierarchy.lockForOrderWrite();
    JobOrder order =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    authorize(order, gate, versioned, expectedVersion);
    JobOrderEditPlan planned = planner.apply(order);
    List<OrderMaterialScope> scopes = scopesOf(id, planned.materialIds());
    if (planned.stockRowIds().isEmpty()) {
      materialPromises.lock(scopes);
    } else {
      stockCommands.prepareWrites(planned.stockRowIds(), List.of(), scopes);
    }
    if (planned.prioritySequence()) {
      jobOrderRepository.lockAllJobOrders();
    } else {
      jobOrderRepository.lockForScopedEdit(id);
    }
    entityManager.refresh(order);
    authorize(order, gate, versioned, expectedVersion);
    JobOrderEditPlan current = planner.apply(order);
    if (!planned.sameResources(current)
        || !Set.copyOf(scopes).equals(Set.copyOf(scopesOf(id, current.materialIds())))) {
      throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
    }
    return new PreparedEdit(order, planned);
  }

  private void recordOutcome(@NotNull JobOrderEdit edit) {
    if (edit.type() != null) {
      JobOrder affected = edit.order();
      auditRecorder.record(
          edit.type(), affected.getId(), JobOrderAuditLabel.of(affected), null, edit.details());
    }
    edit.notices().forEach(effects::publish);
  }

  private List<OrderMaterialScope> scopesOf(@NotNull UUID id, @NotNull Set<UUID> materialIds) {
    List<OrderMaterialScope> scopes = new ArrayList<>(materialPromises.materialScopesOfOrder(id));
    materialIds.forEach(material -> scopes.add(new OrderMaterialScope(id, material)));
    return scopes.stream().distinct().toList();
  }

  private void authorize(
      @NotNull JobOrder order,
      @NotNull JobOrderGate gate,
      boolean versioned,
      @Nullable Long expectedVersion) {
    if (gate != JobOrderGate.TRANSFER) {
      responsibilityGuard.assertWritable(order);
    }
    if (!jobOrderAccessPolicy.canEdit(order, gate)) {
      throw new AccessDeniedException("Caller may not edit job order " + order.getId());
    }
    if (versioned) {
      Long currentVersion =
          switch (gate) {
            case CANCEL, SET_OPEN, STATUS -> order.getLifecycleVersion();
            case TRANSFER -> order.getResponsibilityVersion();
            default -> order.getVersion();
          };
      OptimisticLock.check(currentVersion, expectedVersion, JobOrder.class, order.getId());
    }
  }
}

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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderReadCheck;
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
import java.util.function.Consumer;
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
    return editMoney(id, JobOrderReadCheck.CONTEXTUAL, needsQueue, mutation);
  }

  /**
   * Applies one monetary edit under the read check its command's caller passed, while
   * responsibility, lifecycle and deletion are serialized (ADR-0408).
   *
   * <p>Before and after the order row lock the order must pass the read check, be an aUEC order and
   * have a valid responsibility; the mutation makes its own writer and version checks.
   *
   * @param id the order to edit
   * @param read the read check the loaded order must pass
   * @param needsQueue whether the edit may change the lifecycle and so needs the queue rows
   * @param mutation the financial action over the locked order and whether the queue is locked
   * @param <T> the action result type
   * @return the action's durable result
   */
  @Transactional(propagation = Propagation.MANDATORY)
  <T> T editMoney(
      UUID id,
      JobOrderReadCheck read,
      Predicate<JobOrder> needsQueue,
      BiFunction<JobOrder, Boolean, MoneyEdit<T>> mutation) {
    return editMoney(id, moneyChecks(read), needsQueue, mutation);
  }

  /**
   * Applies one monetary edit under the checks its command's caller decides, in the order and with
   * the refusals that caller answers (ADR-0408).
   *
   * <p>The checks run on the loaded order before the order row lock and again after it, so the
   * decision under the lock is the one that counts.
   *
   * @param id the order to edit
   * @param checks the checks of the loaded order; each refusal is an exception
   * @param needsQueue whether the edit may change the lifecycle and so needs the queue rows
   * @param mutation the financial action over the locked order and whether the queue is locked
   * @param <T> the action result type
   * @return the action's durable result
   * @throws NotFoundException when the order is unknown
   */
  @Transactional(propagation = Propagation.MANDATORY)
  <T> T editMoney(
      UUID id,
      Consumer<JobOrder> checks,
      Predicate<JobOrder> needsQueue,
      BiFunction<JobOrder, Boolean, MoneyEdit<T>> mutation) {
    profitHierarchy.lockForOrderWrite();
    JobOrder order =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    checks.accept(order);
    boolean queueLocked = needsQueue.test(order);
    if (queueLocked) {
      jobOrderRepository.lockPriorityQueueRows();
    }
    Entities.require(jobOrderRepository.lockForScopedEdit(id), () -> "JobOrder not found: " + id);
    entityManager.refresh(order);
    checks.accept(order);
    MoneyEdit<T> result = mutation.apply(order, queueLocked);
    recordOutcome(result.edit());
    return result.result();
  }

  /**
   * The checks a money edit under the read check runs on the loaded order before and after the
   * order row lock: the read check, an aUEC order and a valid responsibility.
   *
   * @param read the read check the loaded order must pass
   * @return the checks, refusing a failed read with {@link AccessDeniedException}
   */
  Consumer<JobOrder> moneyChecks(JobOrderReadCheck read) {
    return order -> authorizeMoney(order, read);
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
    JobOrder source = requireMoneyOrder(sourceId, JobOrderReadCheck.CONTEXTUAL);
    final JobOrder destination = requireMoneyOrder(destinationId, JobOrderReadCheck.CONTEXTUAL);
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
    authorizeMoney(source, JobOrderReadCheck.CONTEXTUAL);
    authorizeMoney(destination, JobOrderReadCheck.CONTEXTUAL);
    MoneyPairEdit<T> result = mutation.apply(new MoneyPairBasis(source, destination, queueLocked));
    recordOutcome(result.source());
    recordOutcome(result.destination());
    return result.result();
  }

  /**
   * Locks money orders for a patch change's currency wipe, which runs for every order whatever the
   * caller's access and the order's responsibility (REQ-PATCH-040, the exception to
   * REQ-ORDERS-055). The wipe audits once for all orders, so this checks and records nothing.
   *
   * @param ids the orders the wipe touches
   * @return the locked, refreshed orders in id order; a deleted order is absent
   */
  @Transactional(propagation = Propagation.MANDATORY)
  List<JobOrder> lockMoneyOrdersForPatchChange(List<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    profitHierarchy.lockForOrderWrite();
    List<JobOrder> locked = new ArrayList<>();
    for (UUID id : jobOrderRepository.lockForOrderEarmarks(ids)) {
      JobOrder order =
          Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
      entityManager.refresh(order);
      JobOrderMoneyQueries.requireMoneyKind(order);
      locked.add(order);
    }
    return locked;
  }

  private JobOrder requireMoneyOrder(UUID id, JobOrderReadCheck read) {
    JobOrder order =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    authorizeMoney(order, read);
    return order;
  }

  private void authorizeMoney(JobOrder order, JobOrderReadCheck read) {
    if (!jobOrderAccessPolicy.canRead(read, order)) {
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
        run(id, gated(gate, versioned(gate, expectedVersion)), _ -> JobOrderEditPlan.of(mutation)));
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
        run(id, gated(gate, versioned(gate, expectedVersion)), planner));
  }

  /**
   * Plans a lifecycle edit under the read check and writer decision its command's caller passed
   * (ADR-0408).
   *
   * <p>Before and after the resource locks the order must have a valid responsibility, then pass
   * the read check and the writer decision, then match the observed lifecycle version.
   *
   * @param id the order to edit
   * @param expectedLifecycleVersion the observed lifecycle version; {@code null} is a conflict
   * @param read the read check the loaded order must pass
   * @param writes the writer decision on the loaded order, resolved anew on each check
   * @param planner immutable resource selection and mutation over the authorized order
   * @return the edited order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the
   *     responsibility is invalid
   * @throws AccessDeniedException when the read check or the writer decision refuses
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the observed
   *     lifecycle version is absent or stale
   * @throws BusinessConflictException when the complete resource plan changes while waiting
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public JobOrder editLifecycle(
      @NotNull UUID id,
      @Nullable Long expectedLifecycleVersion,
      @NotNull JobOrderReadCheck read,
      @NotNull Predicate<JobOrder> writes,
      @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    return editLifecycle(
        id,
        order -> {
          responsibilityGuard.assertWritable(order);
          if (!jobOrderAccessPolicy.canRead(read, order) || !writes.test(order)) {
            throw denied(order);
          }
          OptimisticLock.check(
              order.getLifecycleVersion(), expectedLifecycleVersion, JobOrder.class, order.getId());
        },
        planner);
  }

  /**
   * Plans a lifecycle edit under the checks its command's caller decides, in the order and with the
   * refusals that caller answers (ADR-0408).
   *
   * <p>The checks run on the loaded order before the resource locks and again after them, so the
   * decision under the locks is the one that counts.
   *
   * @param id the order to edit
   * @param checks the checks of the loaded order; each refusal is an exception
   * @param planner immutable resource selection and mutation over the checked order
   * @return the edited order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown
   * @throws BusinessConflictException when the complete resource plan changes while waiting
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public JobOrder editLifecycle(
      @NotNull UUID id,
      @NotNull Consumer<JobOrder> checks,
      @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    return run(id, checks, planner);
  }

  /**
   * Plans a versioned edit that also writes the comment, which carries its own version beside the
   * order version (REQ-ORDERS-066).
   *
   * <p>Both versions are checked before and after the resource locks; the mutation writes the
   * comment through {@link JobOrderComment#replace}.
   *
   * @param id the order to edit
   * @param gate the current authority required by the edit
   * @param observed the observed order version and comment version
   * @param planner immutable resource selection and mutation over the authorized order
   * @return the current projected order
   * @throws AccessDeniedException when current authority fails before or after resource locking
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when either observed
   *     version is absent or stale
   * @throws BusinessConflictException when the complete resource plan changes while waiting
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public JobOrderDto editPlannedWithComment(
      @NotNull UUID id,
      @NotNull JobOrderGate gate,
      @NotNull JobOrderObservedVersions observed,
      @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    Consumer<JobOrder> versions =
        versioned(gate, observed.version())
            .andThen(order -> JobOrderComment.check(order, observed.commentVersion()));
    return jobOrderStockProjectionService.mapToDtoWithStock(
        run(id, gated(gate, versions), planner));
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
        run(id, gated(gate), _ -> JobOrderEditPlan.of(mutation)));
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
    run(id, gated(gate), planner);
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
    PreparedEdit prepared = prepare(id, gated(JobOrderGate.ADMIN_DELETE), planner);
    if (prepared.order().getType() == JobOrderType.AUEC
        && jobOrderRepository.hasDurableMoneySubmission(id)) {
      throw new BusinessConflictException("orders.money.delete.submitted");
    }
    JobOrderEdit edit = prepared.plan().mutation().apply(prepared.order());
    recordOutcome(edit);
  }

  private JobOrder run(
      @NotNull UUID id,
      @NotNull Consumer<JobOrder> authorization,
      @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    PreparedEdit prepared = prepare(id, authorization, planner);
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
      @NotNull Consumer<JobOrder> authorization,
      @NotNull Function<JobOrder, JobOrderEditPlan> planner) {
    profitHierarchy.lockForOrderWrite();
    JobOrder order =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    authorization.accept(order);
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
    authorization.accept(order);
    JobOrderEditPlan current = planner.apply(order);
    if (!planned.sameResources(current)
        || !Set.copyOf(scopes).equals(Set.copyOf(scopesOf(id, current.materialIds())))) {
      throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
    }
    return new PreparedEdit(order, planned);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  JobOrder prepareLinkedBankEffect(UUID id, Predicate<JobOrder> needsQueue) {
    profitHierarchy.lockForOrderWrite();
    JobOrder order =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    boolean queueLocked = needsQueue.test(order);
    if (queueLocked) {
      jobOrderRepository.lockPriorityQueueRows();
    }
    Entities.require(jobOrderRepository.lockForScopedEdit(id), () -> "JobOrder not found: " + id);
    entityManager.refresh(order);
    JobOrderMoneyQueries.requireMoneyKind(order);
    responsibilityGuard.assertWritable(order);
    if (!queueLocked && needsQueue.test(order)) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    return order;
  }

  void recordOutcome(@NotNull JobOrderEdit edit) {
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

  /**
   * Builds the authorization of a gated edit whose request carries no version: a valid
   * responsibility unless the gate is the transfer, then the gate on the loaded order.
   *
   * @param gate the access the edit demands
   * @return the check of the loaded order
   */
  private Consumer<JobOrder> gated(@NotNull JobOrderGate gate) {
    return order -> {
      if (gate != JobOrderGate.TRANSFER) {
        responsibilityGuard.assertWritable(order);
      }
      if (!jobOrderAccessPolicy.canEdit(order, gate)) {
        throw denied(order);
      }
    };
  }

  /**
   * Builds the authorization of a gated, versioned edit: {@link #gated(JobOrderGate)}, then the
   * versions.
   *
   * @param gate the access the edit demands
   * @param versions the version checks of the edited parts
   * @return the check of the loaded order
   */
  private Consumer<JobOrder> gated(
      @NotNull JobOrderGate gate, @NotNull Consumer<JobOrder> versions) {
    return gated(gate).andThen(versions);
  }

  private static AccessDeniedException denied(@NotNull JobOrder order) {
    return new AccessDeniedException("Caller may not edit job order " + order.getId());
  }

  /**
   * Checks the observed version of the part the gate edits.
   *
   * @param gate the access the edit demands, which names the part it edits
   * @param expectedVersion the version the caller last saw; {@code null} is a conflict
   * @return the check of the loaded order
   */
  private static Consumer<JobOrder> versioned(
      @NotNull JobOrderGate gate, @Nullable Long expectedVersion) {
    return order -> {
      Long currentVersion =
          switch (gate) {
            case TRANSFER -> order.getResponsibilityVersion();
            case EDIT, REQUESTER, SUPPORT, ADMIN_DELETE -> order.getVersion();
          };
      OptimisticLock.check(currentVersion, expectedVersion, JobOrder.class, order.getId());
    };
  }
}

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
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
   * Runs an edit whose request carries no version and whose caller needs no projection.
   *
   * @param id the order to edit
   * @param gate the access the edit demands
   * @param mutation the change to apply to the managed order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown
   * @throws AccessDeniedException when the caller fails the gate
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void editWithoutProjection(
      @NotNull UUID id,
      @NotNull JobOrderGate gate,
      @NotNull Function<JobOrder, JobOrderEdit> mutation) {
    run(id, gate, false, null, _ -> JobOrderEditPlan.of(mutation));
  }

  private JobOrder run(
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
    }
    entityManager.refresh(order);
    authorize(order, gate, versioned, expectedVersion);
    JobOrderEditPlan current = planner.apply(order);
    if (!planned.sameResources(current)
        || !Set.copyOf(scopes).equals(Set.copyOf(scopesOf(id, current.materialIds())))) {
      throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
    }
    Set<UUID> previousParties = JobOrderUnitHistory.partyIds(order);
    JobOrderEdit edit = current.mutation().apply(order);
    JobOrder result = edit.order();
    materialPromises.reconcileOrder(id);
    unitHistory.retainObservedParties(result, previousParties);
    if (edit.type() != null) {
      auditRecorder.record(
          edit.type(), result.getId(), JobOrderAuditLabel.of(result), null, edit.details());
    }
    edit.notices().forEach(effects::publish);
    return result;
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
      OptimisticLock.check(order.getVersion(), expectedVersion, JobOrder.class, order.getId());
    }
  }
}

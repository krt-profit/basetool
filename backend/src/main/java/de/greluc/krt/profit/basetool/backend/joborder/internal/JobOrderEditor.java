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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
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
  private final JobOrderAccessPolicy jobOrderAccessPolicy;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final JobOrderStockProjectionService jobOrderStockProjectionService;

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
        run(id, gate, true, expectedVersion, mutation));
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
    return jobOrderStockProjectionService.mapToDtoWithStock(run(id, gate, false, null, mutation));
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
    run(id, gate, false, null, mutation);
  }

  private JobOrder run(
      @NotNull UUID id,
      @NotNull JobOrderGate gate,
      boolean versioned,
      @Nullable Long expectedVersion,
      @NotNull Function<JobOrder, JobOrderEdit> mutation) {
    JobOrder order =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    if (!jobOrderAccessPolicy.canEdit(order, gate)) {
      throw new AccessDeniedException("Caller may not edit job order " + id);
    }
    if (versioned) {
      OptimisticLock.check(order.getVersion(), expectedVersion, JobOrder.class, id);
    }
    JobOrderEdit edit = mutation.apply(order);
    JobOrder result = edit.order();
    if (edit.type() != null) {
      auditRecorder.record(
          edit.type(), result.getId(), JobOrderAuditLabel.of(result), null, edit.details());
    }
    edit.notices().forEach(eventPublisher::publishEvent);
    return result;
  }
}

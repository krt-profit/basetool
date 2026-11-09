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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the job-order priority queue while preserving slots of orders with invalid responsibility.
 *
 * <p>Both operations take a pessimistic write lock over the whole sequence via {@link
 * JobOrderRepository#lockAllJobOrders}.
 */
@Service
@RequiredArgsConstructor
public class JobOrderPriorityService {

  /** Loads/locks the priority sequence and persists the reordered rows. */
  private final JobOrderRepository jobOrderRepository;

  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderResponsibilityGuard responsibilityGuard;
  private final JobOrderAccessPolicy jobOrderAccessPolicy;

  /** Records the priority-change audit event (REQ-AUDIT-001). */
  private final AuditRecorder auditRecorder;

  /** Projects the reordered order back to its stock/claim DTO. */
  private final JobOrderStockProjectionService jobOrderStockProjectionService;

  /**
   * Moves a job order to a new priority position, shifting adjacent orders, under a pessimistic
   * lock on the whole sequence.
   *
   * @param id job order primary key
   * @param newPriority target slot (1-based)
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
   */
  @Transactional
  public JobOrderDto updateJobOrderPriority(UUID id, Integer newPriority) {
    profitHierarchy.lockForOrderWrite();
    JobOrder targetOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    if (!jobOrderAccessPolicy.canEdit(targetOrder, JobOrderGate.EDIT)) {
      throw new AccessDeniedException("Caller may not reorder this job order");
    }
    responsibilityGuard.assertWritable(targetOrder);

    Integer oldPriority = targetOrder.getPriority();
    if (oldPriority == null) {
      throw new BadRequestException("Cannot update priority of a completed or rejected job order");
    }
    if (oldPriority.equals(newPriority)) {
      normalizePriorities();
      return jobOrderStockProjectionService.mapToDtoWithStock(targetOrder);
    }

    List<JobOrder> allOrders = jobOrderRepository.lockAllJobOrders();
    Set<UUID> validIds = responsibilityGuard.validOrderIds(allOrders);

    List<JobOrder> activeOrders =
        new ArrayList<>(
            allOrders.stream()
                .filter(o -> o.getPriority() != null && validIds.contains(o.getId()))
                .sorted(
                    Comparator.comparing(JobOrder::getPriority)
                        .thenComparing(JobOrder::getCreatedAt))
                .toList());

    activeOrders.remove(targetOrder);

    int clampedPriority = Math.clamp(newPriority, 1, activeOrders.size() + 1);
    int newIndex = clampedPriority - 1;

    activeOrders.add(newIndex, targetOrder);

    assignAvailablePriorities(activeOrders, allOrders, validIds);

    auditRecorder.record(
        AuditEventType.JOB_ORDER_PRIORITY_CHANGED,
        targetOrder.getId(),
        JobOrderAuditLabel.of(targetOrder),
        null,
        AuditDetails.of("fromPriority", oldPriority).with("toPriority", targetOrder.getPriority()));
    return jobOrderStockProjectionService.mapToDtoWithStock(targetOrder);
  }

  /**
   * Packs valid active orders around reserved invalid slots, stable by priority then creation time,
   * under a pessimistic lock on the whole sequence.
   *
   * <p>Runs in the caller's transaction; a caller holding a pending {@code @Version} bump must
   * flush it first.
   */
  public void normalizePriorities() {
    List<JobOrder> allOrders = jobOrderRepository.lockAllJobOrders();
    Set<UUID> validIds = responsibilityGuard.validOrderIds(allOrders);
    List<JobOrder> activeOrders =
        allOrders.stream()
            .filter(o -> o.getPriority() != null && validIds.contains(o.getId()))
            .sorted(
                Comparator.comparing(JobOrder::getPriority).thenComparing(JobOrder::getCreatedAt))
            .toList();
    assignAvailablePriorities(activeOrders, allOrders, validIds);
  }

  private static void assignAvailablePriorities(
      List<JobOrder> activeOrders, List<JobOrder> allOrders, Set<UUID> validIds) {
    Set<Integer> reserved =
        allOrders.stream()
            .filter(order -> !validIds.contains(order.getId()))
            .map(JobOrder::getPriority)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    int priority = 1;
    for (JobOrder order : activeOrders) {
      while (reserved.contains(priority)) {
        priority++;
      }
      if (!Objects.equals(order.getPriority(), priority)) {
        order.setPriority(priority);
      }
      priority++;
    }
  }
}

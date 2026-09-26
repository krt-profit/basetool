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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.JobOrderAuditLabel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the job-order priority queue: drag-and-drop reorder and normalisation to a contiguous {@code
 * 1..n} sequence.
 *
 * <p>Both operations take a pessimistic write lock over the whole sequence via {@link
 * JobOrderRepository#lockAllJobOrders}.
 */
@Service
@RequiredArgsConstructor
public class JobOrderPriorityService {

  /** Loads/locks the priority sequence and persists the reordered rows. */
  private final JobOrderRepository jobOrderRepository;

  /** Records the priority-change audit event (REQ-AUDIT-001). */
  private final AuditService auditService;

  /** Projects the reordered order back to its stock/claim DTO. */
  private final JobOrderStockProjectionService jobOrderStockProjectionService;

  /**
   * Moves a job order to a new priority position, shifting adjacent orders, under a pessimistic
   * lock on the whole sequence.
   *
   * @param id job order primary key
   * @param newPriority target slot (1-based)
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  @Transactional
  public JobOrderDto updateJobOrderPriority(UUID id, Integer newPriority) {
    JobOrder targetOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);

    Integer oldPriority = targetOrder.getPriority();
    if (oldPriority == null) {
      throw new BadRequestException("Cannot update priority of a completed or rejected job order");
    }
    if (oldPriority.equals(newPriority)) {
      normalizePriorities();
      return jobOrderStockProjectionService.mapToDtoWithStock(targetOrder);
    }

    List<JobOrder> allOrders = jobOrderRepository.lockAllJobOrders();

    List<JobOrder> activeOrders =
        new ArrayList<>(
            allOrders.stream()
                .filter(o -> o.getPriority() != null)
                .sorted(
                    Comparator.comparing(JobOrder::getPriority)
                        .thenComparing(JobOrder::getCreatedAt))
                .toList());

    activeOrders.remove(targetOrder);

    int clampedPriority = Math.max(1, Math.min(activeOrders.size() + 1, newPriority));
    int newIndex = clampedPriority - 1;

    activeOrders.add(newIndex, targetOrder);

    int currentPrio = 1;
    for (JobOrder o : activeOrders) {
      o.setPriority(currentPrio++);
    }

    auditService.record(
        AuditEventType.JOB_ORDER_PRIORITY_CHANGED,
        targetOrder.getId(),
        orderLabel(targetOrder),
        null,
        AuditDetails.of("fromPriority", oldPriority).with("toPriority", targetOrder.getPriority()));
    return jobOrderStockProjectionService.mapToDtoWithStock(targetOrder);
  }

  /**
   * Re-packs the active orders' priorities to a contiguous {@code 1..n} sequence, stable by current
   * priority then creation time, under a pessimistic lock on the whole sequence.
   *
   * <p>Runs in the caller's transaction; a caller holding a pending {@code @Version} bump must
   * flush it first.
   */
  public void normalizePriorities() {
    List<JobOrder> activeOrders =
        jobOrderRepository.lockAllJobOrders().stream()
            .filter(o -> o.getPriority() != null)
            .sorted(
                Comparator.comparing(JobOrder::getPriority).thenComparing(JobOrder::getCreatedAt))
            .toList();

    int currentPriority = 1;
    for (JobOrder order : activeOrders) {
      if (order.getPriority() == null || !order.getPriority().equals(currentPriority)) {
        order.setPriority(currentPriority);
      }
      currentPriority++;
    }
  }

  /**
   * Composes the audit subject label {@code #<displayId> '<handle>'} of a job order
   * (REQ-AUDIT-001). The handle names a person and is treated as personal data (REQ-SEC-058).
   *
   * @param jobOrder the order
   * @return the {@code #<displayId> '<handle>'} label
   */
  private static String orderLabel(@NotNull JobOrder jobOrder) {
    return JobOrderAuditLabel.of(jobOrder.getDisplayId());
  }
}

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
 * Owns the job-order priority queue: the drag-and-drop reorder and the contiguous-1..n
 * normalisation that the whole lifecycle (create / status change / delete / auto-complete) relies
 * on. Extracted from {@code JobOrderService} (L2, #921) so the priority-ordering concern lives on
 * its own, behind the same logic verbatim.
 *
 * <p>Concurrency (CLAUDE.md's "pessimistic locking for bulk reorders" rule) is preserved: both
 * methods take a {@code @Lock(PESSIMISTIC_WRITE)} lock over the whole priority sequence via {@link
 * JobOrderRepository#lockAllJobOrders} to serialise concurrent reorders. {@link
 * #normalizePriorities} carries no transaction annotation of its own — exactly as the
 * extracted-from private helper did — so it runs inside the caller's transaction; the callers that
 * need it (notably {@code completeJobOrderWithinTransaction}) still {@code flush()} their pending
 * {@code @Version} bump <em>before</em> invoking it, so the lock query never reads a stale row.
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
   * Reorders a job order to a new priority position.
   *
   * <p>Backend uses {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} on the whole priority sequence
   * (see {@code JobOrderRepository.lockAllJobOrders}) to serialize concurrent reorders — without
   * it, two simultaneous drag-and-drops would produce duplicate priorities. Adjacent orders shift
   * up or down to make room for the moved row.
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
   * Re-packs the active orders' priorities to a contiguous {@code 1..n} sequence (stable by current
   * priority then creation time), taking the whole-sequence {@code @Lock(PESSIMISTIC_WRITE)} lock
   * to serialise against concurrent reorders. Called from every lifecycle edge that can leave a gap
   * (create, status change to/from terminal, delete, auto-complete). Carries no
   * {@code @Transactional} of its own so it runs inside the caller's transaction — callers that
   * hold a pending {@code @Version} bump must {@code flush()} it before calling this (see {@code
   * JobOrderService.completeJobOrderWithinTransaction}).
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
   * Composes the audit subject label for a job order — {@code #<displayId> '<handle>'}, the
   * deletion-proof identity snapshot stored on each audit event (REQ-AUDIT-001).
   *
   * <p><b>The handle names a person.</b> It is the order's contact — {@code orders.create.handle}
   * renders it as "Handle des Ansprechpartners" — and is frequently somebody outside the
   * organisation with no account at all. The snapshot itself stays, because the trail has to remain
   * readable once the order is gone; but it is why {@code audit_event.subject_label} is registered
   * as a person-name surface in {@code PersonSearchTargets} and why the Art. 15 export does not
   * select it (REQ-SEC-058).
   *
   * <p>Corrected 2026-09-16: this said the handle was "a non-personal order title and is safe to
   * snapshot". It is neither non-personal nor safe to disclose, and that claim is why the export
   * shipped without a guard on the column.
   *
   * @param jobOrder the order
   * @return the {@code #<displayId> '<handle>'} label
   */
  private static String orderLabel(@NotNull JobOrder jobOrder) {
    return JobOrderAuditLabel.of(jobOrder.getDisplayId());
  }
}

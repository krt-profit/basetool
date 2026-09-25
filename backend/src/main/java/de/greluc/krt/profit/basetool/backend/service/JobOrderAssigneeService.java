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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages a job order's assignees and their per-assignee notes.
 *
 * <p>Writes flush so returned DTOs carry fresh versions; note edits are locked on the assignee
 * edge's own {@code @Version} via {@link OptimisticLock}, never bumping the order's version.
 */
@Service
@RequiredArgsConstructor
public class JobOrderAssigneeService {

  /** Loads + persists the parent order aggregate. */
  private final JobOrderRepository jobOrderRepository;

  /** Resolves the user being added as an assignee. */
  private final UserRepository userRepository;

  /** Records the state-mutating assignee activities into the audit log (REQ-AUDIT-001). */
  private final AuditService auditService;

  /** Projects the updated order back to its stock/claim DTO. */
  private final JobOrderStockProjectionService jobOrderStockProjectionService;

  /**
   * Adds a user as an assignee of a job order; idempotent.
   *
   * @param jobOrderId job order id
   * @param userId user to add
   * @return the order with its refreshed assignee list
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when either id is
   *     unknown
   */
  @Transactional
  public JobOrderDto addAssignee(UUID jobOrderId, UUID userId) {
    JobOrder jobOrder =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);
    boolean alreadyAssigned =
        jobOrder.getAssignees().stream()
            .anyMatch(a -> a.getUser() != null && a.getUser().getId().equals(userId));
    if (alreadyAssigned) {
      return jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
    }
    User user =
        Entities.require(userRepository.findById(userId), () -> "User not found: " + userId);
    jobOrder.addAssignee(JobOrderAssignee.builder().user(user).build());
    JobOrder saved = jobOrderRepository.saveAndFlush(jobOrder);
    auditService.record(
        AuditEventType.JOB_ORDER_ASSIGNEE_ADDED,
        saved.getId(),
        orderLabel(saved),
        userId,
        AuditDetails.of("assignee", userId));
    return jobOrderStockProjectionService.mapToDtoWithStock(saved);
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
    JobOrder jobOrder =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);
    boolean removed =
        jobOrder
            .getAssignees()
            .removeIf(a -> a.getUser() != null && a.getUser().getId().equals(userId));
    JobOrder saved = jobOrderRepository.saveAndFlush(jobOrder);
    if (removed) {
      auditService.record(
          AuditEventType.JOB_ORDER_ASSIGNEE_REMOVED,
          saved.getId(),
          orderLabel(saved),
          userId,
          AuditDetails.of("assignee", userId));
    }
    return jobOrderStockProjectionService.mapToDtoWithStock(saved);
  }

  /**
   * Sets the note on a user's assignee entry, locked on the assignee edge's own version.
   *
   * @param jobOrderId job order id
   * @param userId the assignee whose note is changed
   * @param note the new note text, already length-validated
   * @param version the edge version the client last saw, or {@code null} to skip the check
   * @return the order with its refreshed assignee list
   * @throws NotFoundException when the order or the assignee entry is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code version} is
   *     stale
   */
  @Transactional
  public JobOrderDto updateAssigneeNote(UUID jobOrderId, UUID userId, String note, Long version) {
    return setAssigneeNote(jobOrderId, userId, note, version);
  }

  /**
   * Clears the note on a user's assignee entry, locked like {@link #updateAssigneeNote}.
   *
   * @param jobOrderId job order id
   * @param userId the assignee whose note is cleared
   * @param version the edge version the client last saw, or {@code null} to skip the check
   * @return the order with its refreshed assignee list
   * @throws NotFoundException when the order or the assignee entry is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code version} is
   *     stale
   */
  @Transactional
  public JobOrderDto deleteAssigneeNote(UUID jobOrderId, UUID userId, Long version) {
    return setAssigneeNote(jobOrderId, userId, null, version);
  }

  /**
   * Sets or clears an assignee note after checking the edge's version, then flushes so the returned
   * DTO carries the new edge version.
   *
   * @param jobOrderId job order id
   * @param userId the assignee whose note is changed
   * @param note the new note, or {@code null} to clear it
   * @param version the edge version the client last saw, or {@code null} to skip the check
   * @return the order with its refreshed assignee list
   */
  private JobOrderDto setAssigneeNote(UUID jobOrderId, UUID userId, String note, Long version) {
    JobOrder jobOrder =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);
    JobOrderAssignee assignee =
        Entities.require(
            jobOrder.getAssignees().stream()
                .filter(a -> a.getUser() != null && a.getUser().getId().equals(userId))
                .findFirst(),
            () -> "Assignee not found on job order " + jobOrderId + ": " + userId);

    OptimisticLock.checkOptionalClient(
        assignee.getVersion(), version, JobOrderAssignee.class, assignee.getId());

    String trimmed = StringNormalization.trimToNull(note);
    assignee.setNote(trimmed);
    JobOrder saved = jobOrderRepository.saveAndFlush(jobOrder);
    if (trimmed != null) {
      auditService.record(
          AuditEventType.JOB_ORDER_ASSIGNEE_NOTE_SET,
          saved.getId(),
          orderLabel(saved),
          userId,
          AuditDetails.of("assignee", userId).with("noteLength", trimmed.length()));
    } else {
      auditService.record(
          AuditEventType.JOB_ORDER_ASSIGNEE_NOTE_CLEARED,
          saved.getId(),
          orderLabel(saved),
          userId,
          AuditDetails.of("assignee", userId));
    }
    return jobOrderStockProjectionService.mapToDtoWithStock(saved);
  }

  /**
   * Composes the audit subject label {@code #<displayId> '<handle>'} for a job order
   * (REQ-AUDIT-001).
   *
   * <p>The handle names a person (the order's contact), so the label is treated as personal data
   * and excluded from the data-subject export (REQ-SEC-058).
   *
   * @param jobOrder the order
   * @return the {@code #<displayId> '<handle>'} label
   */
  private static String orderLabel(@NotNull JobOrder jobOrder) {
    return JobOrderAuditLabel.of(jobOrder.getDisplayId());
  }
}

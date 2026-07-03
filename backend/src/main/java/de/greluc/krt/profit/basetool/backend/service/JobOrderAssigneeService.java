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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages a job order's assignees — the members who have signed up to work the order — and their
 * per-assignee free-text notes. Extracted from {@code JobOrderService} (L2, #921) so the assignee
 * concern lives on its own, behind the same logic verbatim.
 *
 * <p>Each write flushes explicitly so the returned DTO carries fresh versions; the note edit is
 * optimistic-locked on the assignee edge's own {@code @Version} (via {@link OptimisticLock}), so a
 * stale note edit surfaces as HTTP 409 without ever bumping the parent order's version. The order
 * is projected back to a DTO through the shared {@link JobOrderStockProjectionService}. {@code
 * JobOrderService} keeps its public assignee methods as thin delegations, so the controller and
 * transaction boundaries are unchanged.
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
   * Adds a user as an assignee of a job order (idempotent: a repeated add returns the current
   * state).
   *
   * @param jobOrderId job order primary key
   * @param userId user to add
   * @return the persisted order with refreshed assignee list
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when either id is
   *     unknown
   */
  @Transactional
  public JobOrderDto addAssignee(UUID jobOrderId, UUID userId) {
    JobOrder jobOrder =
        jobOrderRepository
            .findById(jobOrderId)
            .orElseThrow(() -> new NotFoundException("JobOrder not found: " + jobOrderId));
    boolean alreadyAssigned =
        jobOrder.getAssignees().stream()
            .anyMatch(a -> a.getUser() != null && a.getUser().getId().equals(userId));
    if (alreadyAssigned) {
      return jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
    }
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found: " + userId));
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
        jobOrderRepository
            .findById(jobOrderId)
            .orElseThrow(() -> new NotFoundException("JobOrder not found: " + jobOrderId));
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
   * Sets (creates or replaces) the note on a user's assignee entry. The note is the assignee's own
   * free-text context — when they work on the order, which part they take. Optimistic-locked on the
   * assignee edge's own version, so a stale client edit surfaces as HTTP 409 without ever bumping
   * the parent order's version.
   *
   * @param jobOrderId job order primary key
   * @param userId the assignee whose note is changed
   * @param note the new note text (already length-validated at the controller boundary)
   * @param version the assignee edge version the client last saw, or {@code null} to skip the check
   * @return the persisted order with the refreshed assignee list
   * @throws NotFoundException when the order or the assignee entry is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code version} is
   *     stale
   */
  @Transactional
  public JobOrderDto updateAssigneeNote(UUID jobOrderId, UUID userId, String note, Long version) {
    return setAssigneeNote(jobOrderId, userId, note, version);
  }

  /**
   * Clears the note on a user's assignee entry. Same optimistic-locking semantics as {@link
   * #updateAssigneeNote}.
   *
   * @param jobOrderId job order primary key
   * @param userId the assignee whose note is cleared
   * @param version the assignee edge version the client last saw, or {@code null} to skip the check
   * @return the persisted order with the refreshed assignee list
   * @throws NotFoundException when the order or the assignee entry is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code version} is
   *     stale
   */
  @Transactional
  public JobOrderDto deleteAssigneeNote(UUID jobOrderId, UUID userId, Long version) {
    return setAssigneeNote(jobOrderId, userId, null, version);
  }

  /**
   * Shared implementation for the note set/clear endpoints: locates the assignee edge, enforces the
   * supplied version against the edge's own {@code @Version}, mutates the note via dirty-checking
   * and flushes so the returned DTO carries the freshly incremented edge version.
   *
   * @param jobOrderId job order primary key
   * @param userId the assignee whose note is changed
   * @param note the new note value, or {@code null} to clear it
   * @param version the assignee edge version the client last saw, or {@code null} to skip the check
   * @return the persisted order with the refreshed assignee list
   */
  private JobOrderDto setAssigneeNote(UUID jobOrderId, UUID userId, String note, Long version) {
    JobOrder jobOrder =
        jobOrderRepository
            .findById(jobOrderId)
            .orElseThrow(() -> new NotFoundException("JobOrder not found: " + jobOrderId));
    JobOrderAssignee assignee =
        jobOrder.getAssignees().stream()
            .filter(a -> a.getUser() != null && a.getUser().getId().equals(userId))
            .findFirst()
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Assignee not found on job order " + jobOrderId + ": " + userId));

    OptimisticLock.checkOptionalClient(
        assignee.getVersion(), version, JobOrderAssignee.class, assignee.getId());

    String trimmed = StringNormalization.trimToNull(note);
    assignee.setNote(trimmed);
    JobOrder saved = jobOrderRepository.saveAndFlush(jobOrder);
    // PII: the note body is user free text — record only its presence/length, never the content.
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
   * Composes the audit subject label for a job order — {@code #<displayId> '<handle>'}, the
   * deletion-proof identity snapshot stored on each audit event (REQ-AUDIT-001). The handle is a
   * non-personal order title and is safe to snapshot.
   *
   * @param jobOrder the order
   * @return the {@code #<displayId> '<handle>'} label
   */
  private static String orderLabel(JobOrder jobOrder) {
    return "#" + jobOrder.getDisplayId() + " '" + jobOrder.getHandle() + "'";
  }
}

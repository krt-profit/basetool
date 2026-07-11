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
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderItemHandoverMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandoverEntry;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverEntryCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fulfils {@code ITEM} job orders by recording item handovers and auto-completing the order once
 * every ordered line is fully delivered.
 *
 * <p><b>Concurrency.</b> Unlike the material handover ({@link JobOrderHandoverService}), this flow
 * issues no {@code @Modifying(clearAutomatically = true)} bulk update, so the persistence context
 * is never detached mid-operation: per-line {@code deliveredAmount} updates rely on Hibernate dirty
 * checking (no explicit {@code save}), and the completion check runs against the same managed
 * {@link JobOrder}. Completion is delegated to {@link
 * JobOrderService#completeJobOrderWithinTransaction(JobOrder)} (the {@code MANDATORY}-propagation
 * {@code *WithinTransaction} method) so the order's {@code @Version} is bumped exactly once and a
 * clean caller never sees a 409. The audit trail (executing user + squadron snapshot) mirrors the
 * material handover for cross-staffel transparency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderItemHandoverService {

  private final JobOrderRepository jobOrderRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final JobOrderItemHandoverMapper jobOrderItemHandoverMapper;
  private final JobOrderService jobOrderService;
  private final UserService userService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final SquadronRepository squadronRepository;
  private final AuditService auditService;

  /**
   * Records an item handover against an item order: increments each referenced line's {@code
   * deliveredAmount} (rejecting over-delivery), persists the handover with its entries and the
   * executing-user audit snapshot, and completes the order once every line is fully delivered.
   *
   * @param jobOrderId the item order to fulfil
   * @param dto the handover payload (delivered item-line quantities)
   * @return the persisted handover as a DTO
   * @throws NotFoundException when the order does not exist
   * @throws BadRequestException when the order is not an item order, an entry references a line not
   *     on the order, or an entry exceeds the line's outstanding quantity
   */
  @Transactional
  public JobOrderItemHandoverDto createItemHandover(
      UUID jobOrderId, JobOrderItemHandoverCreateDto dto) {
    JobOrder jobOrder =
        jobOrderRepository
            .findById(jobOrderId)
            .orElseThrow(() -> new NotFoundException("JobOrder not found: " + jobOrderId));

    if (jobOrder.getType() != JobOrderType.ITEM) {
      throw new BadRequestException("Job order " + jobOrderId + " is not an item order");
    }

    JobOrderItemHandover handover = new JobOrderItemHandover();
    handover.setJobOrder(jobOrder);
    handover.setHandoverTime(dto.handoverTime());
    handover.setRecipientHandle(dto.recipientHandle());
    stampAuditTrail(handover);

    for (JobOrderItemHandoverEntryCreateDto entryDto : dto.entries()) {
      JobOrderItem line =
          jobOrder.getItems().stream()
              .filter(i -> i.getId().equals(entryDto.jobOrderItemId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new BadRequestException(
                          "Item line "
                              + entryDto.jobOrderItemId()
                              + " does not belong to job order "
                              + jobOrderId));

      int outstanding = line.getAmount() - line.getDeliveredAmount();
      if (entryDto.amount() > outstanding) {
        throw new BadRequestException(
            "Cannot hand over more than the outstanding amount for item line " + line.getId());
      }
      // Dirty-checked mutation only — no explicit save(), so no version double-bump.
      line.setDeliveredAmount(line.getDeliveredAmount() + entryDto.amount());

      JobOrderItemHandoverEntry entry = new JobOrderItemHandoverEntry();
      entry.setJobOrderItem(line);
      entry.setAmount(entryDto.amount());
      handover.addEntry(entry);
    }

    JobOrderItemHandover saved = jobOrderItemHandoverRepository.save(handover);
    JobOrderItemHandoverDto resultDto = jobOrderItemHandoverMapper.toDto(saved);

    boolean allDelivered =
        jobOrder.getItems().stream()
            .allMatch(line -> line.getDeliveredAmount() >= line.getAmount());
    if (allDelivered) {
      jobOrderService.completeJobOrderWithinTransaction(jobOrder);
    }

    // The recipientHandle is user free text and is never written to the audit details.
    // completeJobOrderWithinTransaction already recorded JOB_ORDER_COMPLETED when the order was
    // fulfilled, so this method records only the handover.
    auditService.record(
        AuditEventType.JOB_ORDER_ITEM_HANDOVER_CREATED,
        jobOrderId,
        "#" + jobOrder.getDisplayId() + " '" + jobOrder.getHandle() + "'",
        null,
        AuditDetails.of("handover", saved.getId())
            .with("entries", dto.entries().size())
            .with("autoCompleted", allDelivered));

    return resultDto;
  }

  /**
   * Stamps the executing user and their squadron snapshot onto the handover for the cross-staffel
   * audit trail, mirroring {@link JobOrderHandoverService}. REQ-ORG-017: the executor may hold up
   * to two Staffeln, so the snapshot is order-aligned — the executor's Staffel that matches the
   * order's responsible org unit, else their deterministic primary. No-op for an unresolved
   * principal.
   *
   * @param handover the handover being created
   */
  private void stampAuditTrail(JobOrderItemHandover handover) {
    UUID responsibleOrgUnitId =
        handover.getJobOrder() != null && handover.getJobOrder().getResponsibleOrgUnit() != null
            ? handover.getJobOrder().getResponsibleOrgUnit().getId()
            : null;
    userService
        .getCurrentUser()
        .ifPresent(
            current -> {
              handover.setExecutingUser(current);
              orgUnitMembershipQueryService
                  .findExecutingStaffelForOrder(current.getId(), responsibleOrgUnitId)
                  .flatMap(squadronRepository::findById)
                  .ifPresent(handover::setExecutingSquadron);
            });
  }
}

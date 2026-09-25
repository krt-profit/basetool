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
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderItemHandoverMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandoverEntry;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverEntryCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.support.InventoryAuditLabels;
import de.greluc.krt.profit.basetool.backend.support.JobOrderAuditLabel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fulfils {@code ITEM} job orders by recording item handovers and completing the order once every
 * line is fully delivered.
 *
 * <p>A handover of {@code N} units also consumes up to {@code N} units of the order's earmarked
 * item stock, oldest-first under a row lock (REQ-ORDERS-030); a stock shortfall never blocks the
 * delivery. Completion goes through {@link
 * JobOrderService#completeJobOrderWithinTransaction(JobOrder)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderItemHandoverService {

  /**
   * Tolerance for comparing item stock amounts stored as {@code double}. Item rows hold whole
   * units, so a residual below this is floating-point noise rather than a real remainder; a row at
   * or below it is treated as depleted and removed. Mirrors {@link JobOrderHandoverService}.
   */
  private static final double QUANTITY_EPSILON = 1e-4;

  private final JobOrderRepository jobOrderRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final JobOrderItemHandoverMapper jobOrderItemHandoverMapper;
  private final InventoryItemRepository inventoryItemRepository;
  private final MaterialExchangeOfferRepository materialExchangeOfferRepository;
  private final JobOrderService jobOrderService;
  private final UserService userService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final OrgUnitRepository orgUnitRepository;
  private final AuditService auditService;

  /**
   * Snapshot of one consumed game-item row, captured while managed so the {@code
   * INVENTORY_HANDED_OVER} audit events can be emitted after all writes.
   *
   * @param itemId the source inventory row id
   * @param label the {@code gameItem @ location} audit label
   * @param gameItem the game-item name
   * @param amount the consumed whole units
   * @param remaining the amount left afterwards (0 when depleted)
   * @param depleted whether the source row was removed
   */
  private record ConsumedItem(
      UUID itemId,
      String label,
      String gameItem,
      double amount,
      double remaining,
      boolean depleted) {}

  /**
   * Records an item handover: increments each referenced line's {@code deliveredAmount}, consumes
   * the order's earmarked item stock best-effort (REQ-ORDERS-030), persists the handover with its
   * audit snapshot and completes the order once every line is fully delivered.
   *
   * @param jobOrderId the item order to fulfil
   * @param dto the delivered item-line quantities
   * @return the persisted handover
   * @throws NotFoundException when the order does not exist
   * @throws BadRequestException when the order is not an item order, an entry references a foreign
   *     line, or an entry exceeds the manufactured-but-undelivered quantity
   */
  @Transactional
  public JobOrderItemHandoverDto createItemHandover(
      UUID jobOrderId, JobOrderItemHandoverCreateDto dto) {
    JobOrder jobOrder =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);

    if (jobOrder.getType() != JobOrderType.ITEM) {
      throw new BadRequestException("Job order " + jobOrderId + " is not an item order");
    }

    JobOrderItemHandover handover = new JobOrderItemHandover();
    handover.setJobOrder(jobOrder);
    handover.setHandoverTime(dto.handoverTime());
    handover.setRecipientHandle(dto.recipientHandle());
    stampAuditTrail(handover);

    final Map<UUID, Integer> handedByGameItem = new LinkedHashMap<>();

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

      int outstanding = line.getManufacturedAmount() - line.getDeliveredAmount();
      if (entryDto.amount() > outstanding) {
        throw new BadRequestException(
            "Cannot hand over more than the manufactured-but-undelivered amount for item line "
                + line.getId());
      }
      line.setDeliveredAmount(line.getDeliveredAmount() + entryDto.amount());

      if (line.getGameItem() != null) {
        handedByGameItem.merge(line.getGameItem().getId(), entryDto.amount(), Integer::sum);
      }

      JobOrderItemHandoverEntry entry = new JobOrderItemHandoverEntry();
      entry.setJobOrderItem(line);
      entry.setAmount(entryDto.amount());
      handover.addEntry(entry);
    }

    final List<ConsumedItem> consumedItems =
        consumeEarmarkedItemStock(jobOrderId, handedByGameItem);

    JobOrderItemHandover saved = jobOrderItemHandoverRepository.save(handover);

    boolean allDelivered =
        jobOrder.getItems().stream()
            .allMatch(line -> line.getDeliveredAmount() >= line.getAmount());
    if (allDelivered) {
      jobOrderService.completeJobOrderWithinTransaction(jobOrder);
    }

    final Integer orderDisplayId = jobOrder.getDisplayId();
    for (ConsumedItem consumed : consumedItems) {
      if (!consumed.depleted()) {
        materialExchangeOfferRepository.clampItemQuantityToStock(
            consumed.itemId(), (int) Math.floor(consumed.remaining()));
      }
      auditService.record(
          AuditEventType.INVENTORY_HANDED_OVER,
          consumed.itemId(),
          consumed.label(),
          null,
          AuditDetails.of("source", "ITEM_HANDOVER")
              .with("jobOrder", "#" + orderDisplayId)
              .with("gameItem", consumed.gameItem())
              .with("amount", consumed.amount())
              .with("remaining", consumed.remaining())
              .with("depleted", consumed.depleted()));
    }

    auditService.record(
        AuditEventType.JOB_ORDER_ITEM_HANDOVER_CREATED,
        jobOrderId,
        JobOrderAuditLabel.of(jobOrder.getDisplayId()),
        null,
        AuditDetails.of("handover", saved.getId())
            .with("entries", dto.entries().size())
            .with("autoCompleted", allDelivered));

    return jobOrderItemHandoverMapper.toDto(saved);
  }

  /**
   * Consumes the order's earmarked item stock for the delivered game items, best-effort
   * (REQ-ORDERS-030).
   *
   * <p>Rows are locked oldest-first; each draws at most this order's own earmark slice, and a
   * depleted row is deleted. Any shortfall is left unconsumed.
   *
   * @param jobOrderId the order whose earmark to draw down
   * @param handedByGameItem the whole units handed over per game item
   * @return one snapshot per consumed row for the audit trail; never {@code null}
   */
  @NotNull
  private List<ConsumedItem> consumeEarmarkedItemStock(
      UUID jobOrderId, Map<UUID, Integer> handedByGameItem) {
    final List<ConsumedItem> consumed = new ArrayList<>();
    for (Map.Entry<UUID, Integer> handed : handedByGameItem.entrySet()) {
      UUID gameItemId = handed.getKey();
      double remainingToConsume = handed.getValue();
      if (remainingToConsume <= QUANTITY_EPSILON) {
        continue;
      }
      for (InventoryItem row :
          inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
              jobOrderId, gameItemId)) {
        if (remainingToConsume <= QUANTITY_EPSILON) {
          break;
        }
        var slice = InventoryAllocations.jobOrderSlice(row, jobOrderId);
        double sliceAmount = slice != null && slice.getAmount() != null ? slice.getAmount() : 0.0;
        if (sliceAmount <= QUANTITY_EPSILON) {
          continue;
        }
        double take = Math.min(remainingToConsume, sliceAmount);
        double rowAmount = row.getAmount() != null ? row.getAmount() : 0.0;
        double rowRemaining = InventoryItem.roundToScuScale(rowAmount - take);
        boolean depleted = rowRemaining <= QUANTITY_EPSILON;
        String gameItemName = row.getGameItem() != null ? row.getGameItem().getName() : "—";
        consumed.add(
            new ConsumedItem(
                row.getId(),
                InventoryAuditLabels.label(row),
                gameItemName,
                take,
                depleted ? 0.0 : rowRemaining,
                depleted));

        InventoryAllocations.reduceJobOrder(row, jobOrderId, take);
        if (depleted) {
          inventoryItemRepository.delete(row);
        } else {
          row.setAmount(rowRemaining);
          inventoryItemRepository.save(row);
        }
        remainingToConsume -= take;
      }
    }
    return consumed;
  }

  /**
   * Stamps the executing user and their Staffel snapshot onto the handover, preferring the Staffel
   * that matches the order's responsible org unit (REQ-ORG-017). No-op for an unresolved principal.
   *
   * @param handover the handover being created
   */
  private void stampAuditTrail(@NotNull JobOrderItemHandover handover) {
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
                  .flatMap(orgUnitRepository::findById)
                  .map(ou -> Hibernate.unproxy(ou, OrgUnit.class))
                  .filter(Squadron.class::isInstance)
                  .map(Squadron.class::cast)
                  .ifPresent(handover::setExecutingSquadron);
            });
  }
}

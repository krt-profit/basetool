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
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandoverEntry;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverEntryCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.support.InventoryAuditLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fulfils {@code ITEM} job orders by recording item handovers and auto-completing the order once
 * every ordered line is fully delivered.
 *
 * <p><b>Delivery consumes earmarked item stock (REQ-ORDERS-030, best-effort).</b> A handover of
 * {@code N} units of a line additionally draws {@code min(N, the order's earmarked item stock for
 * that game item)} out of the Lager, oldest-first, so the phantom stock a delivery would otherwise
 * leave behind disappears. It is deliberately <b>never blocking</b>: a legacy line manufactured
 * before item stock existed ({@code manufacturedAmount > 0} with no earmark), or a line whose
 * earmark covers only part of the handed amount, consumes whatever stock is there and still
 * delivers the rest — a stock shortfall is a silent no-op, never a 400. The {@code deliveredAmount}
 * ceiling stays gated by {@code manufacturedAmount} (REQ-ORDERS-025), independent of the
 * consumption.
 *
 * <p><b>Concurrency.</b> Unlike the material handover ({@link JobOrderHandoverService}), this flow
 * issues no {@code @Modifying(clearAutomatically = true)} bulk update, so the persistence context
 * is never detached mid-operation: per-line {@code deliveredAmount} updates rely on Hibernate dirty
 * checking (no explicit {@code save}), and the consumption only mutates the loaded game-item rows
 * (shrinking this order's earmark slice and the row amount, deleting a depleted row) — none of them
 * part of the {@link JobOrder} aggregate — so the completion check still runs against the same
 * managed {@link JobOrder}. Completion is delegated to {@link
 * JobOrderService#completeJobOrderWithinTransaction(JobOrder)} (the {@code MANDATORY}-propagation
 * {@code *WithinTransaction} method) so the order's {@code @Version} is bumped exactly once and a
 * clean caller never sees a 409. The consumed game-item rows are locked {@code FOR UPDATE}
 * oldest-first, so two racing handovers against the same earmark pool serialise. The audit trail
 * (executing user + squadron snapshot) mirrors the material handover for cross-staffel
 * transparency, and each consumed row emits the shared {@code INVENTORY_HANDED_OVER} cross-domain
 * event.
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
  private final JobOrderService jobOrderService;
  private final UserService userService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final SquadronRepository squadronRepository;
  private final AuditService auditService;

  /**
   * One consumed game-item row's scalar snapshot, captured while the row is still managed so the
   * {@code INVENTORY_HANDED_OVER} audit events can be emitted after all writes from stable data
   * (mirrors {@link JobOrderHandoverService}'s {@code HandedItem}).
   *
   * @param itemId the source inventory row id
   * @param label the {@code gameItem @ location} audit subject label snapshot
   * @param gameItem the game-item name snapshot
   * @param amount the consumed whole units
   * @param remaining the post-decrement amount (0 when depleted)
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
   * Records an item handover against an item order: increments each referenced line's {@code
   * deliveredAmount} (rejecting over-delivery), consumes the order's earmarked item stock for the
   * delivered game items best-effort (REQ-ORDERS-030), persists the handover with its entries and
   * the executing-user audit snapshot, and completes the order once every line is fully delivered.
   *
   * @param jobOrderId the item order to fulfil
   * @param dto the handover payload (delivered item-line quantities)
   * @return the persisted handover as a DTO
   * @throws NotFoundException when the order does not exist
   * @throws BadRequestException when the order is not an item order, an entry references a line not
   *     on the order, or an entry exceeds the line's manufactured-but-undelivered quantity
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

    // The whole units handed over per game item in this handover — the amount of item stock the
    // best-effort consumption (REQ-ORDERS-030) tries to draw from the order's earmark below.
    // Aggregated per game item (not per line) because the earmark slices are keyed on (row, order),
    // so two lines requesting the same game item share one earmark pool.
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

      // A unit can only be delivered once it has been manufactured (REQ-ORDERS-025), so the
      // deliverable ceiling is the manufactured-but-not-yet-delivered quantity, not amount −
      // delivered. Legacy rows were backfilled with manufactured := delivered, so already-delivered
      // lines stay valid; further delivery requires booking production first.
      int outstanding = line.getManufacturedAmount() - line.getDeliveredAmount();
      if (entryDto.amount() > outstanding) {
        throw new BadRequestException(
            "Cannot hand over more than the manufactured-but-undelivered amount for item line "
                + line.getId());
      }
      // Dirty-checked mutation only — no explicit save(), so no version double-bump.
      line.setDeliveredAmount(line.getDeliveredAmount() + entryDto.amount());

      if (line.getGameItem() != null) {
        handedByGameItem.merge(line.getGameItem().getId(), entryDto.amount(), Integer::sum);
      }

      JobOrderItemHandoverEntry entry = new JobOrderItemHandoverEntry();
      entry.setJobOrderItem(line);
      entry.setAmount(entryDto.amount());
      handover.addEntry(entry);
    }

    // Best-effort delivery-consumes-stock (REQ-ORDERS-030): draw the delivered units out of the
    // order's earmarked item stock, oldest-first. Reduces only game-item rows (never the JobOrder
    // aggregate) with no context-clearing bulk update, so the still-managed jobOrder drives the
    // completion check below with a single @Version bump.
    final List<ConsumedItem> consumedItems =
        consumeEarmarkedItemStock(jobOrderId, handedByGameItem);

    // One INVENTORY_HANDED_OVER per consumed game-item row (cross-domain inventory effect), emitted
    // from the loop-captured snapshots so a deleted row is never re-read. Reuses the shared
    // handover
    // event (same lifecycle as the material handover), so no viewer filter / label change is needed
    // —
    // and, like the material handover, the INVENTORY events precede the JOB_ORDER handover event.
    // Post-#1342 (Materialbörse stock-backed item offers): wire the item-offer stock ratchet here —
    // one clampItemQuantityToStock(itemId, remaining) per non-depleted snapshot — mirroring the
    // material handover's clampOfferedAmountToStock. No such offer exists on this branch, so
    // nothing
    // is clamped yet; the collect-then-run-after-the-loop structure keeps the rebase point local.
    final Integer orderDisplayId = jobOrder.getDisplayId();
    for (ConsumedItem consumed : consumedItems) {
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
   * Consumes the order's earmarked item stock for the delivered game items, best-effort
   * (REQ-ORDERS-030). For each game item it loads the order's game-item rows oldest-first under a
   * {@code FOR UPDATE} lock and, per row, draws {@code min(remaining-to-consume, this order's
   * earmark slice)} — capping at the order's own slice (Variante C, REQ-INV-027), never a sibling
   * order's — shrinking that slice and the row amount, and deleting a depleted row (book-out
   * depletion convention). Items carry no mission dimension (REQ-INV-031), so there is no mission
   * slice to clamp. When the earmarked stock is smaller than the delivered amount (a legacy line
   * manufactured before item stock existed, or a partially stocked line) the shortfall is left
   * un-consumed — the delivery already advanced and is never rolled back or blocked.
   *
   * <p>Concurrency: no {@code @Modifying(clearAutomatically = true)} query runs here, so the
   * persistence context is never detached — every mutated row stays managed, {@code
   * reduceJobOrder}/{@code setAmount} flush via dirty checking (the explicit {@code save} of a
   * managed row is a no-op merge, no second {@code @Version} bump), and the caller's {@link
   * JobOrder} aggregate is untouched.
   *
   * @param jobOrderId the order whose earmark to draw down
   * @param handedByGameItem the whole units handed over per game item in this handover
   * @return one snapshot per consumed row, for the post-write audit trail; never {@code null}
   */
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
        // Draw only this order's own earmark on the row (R5 keeps the slice ≤ the row amount, so
        // the
        // physical remainder never goes negative), capped at what is still to consume.
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
      // remainingToConsume may still be > 0 here — best-effort: the surplus delivers without stock
      // backing (legacy manufactured-without-stock lines, or partial earmark). Never throw.
    }
    return consumed;
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

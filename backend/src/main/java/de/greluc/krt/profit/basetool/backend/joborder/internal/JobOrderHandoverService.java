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
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandoverItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandoverMapper;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records material handovers using a stabilized complete physical and demand plan. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderHandoverService {

  private final JobOrderRepository jobOrderRepository;
  private final MaterialPromiseCoordinator materialPromises;
  private final JobOrderResponsibilityGuard responsibilityGuard;
  private final MaterialHandoverPlanner handoverPlanner;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final StockCommands stockCommands;
  private final JobOrderHandoverMapper jobOrderHandoverMapper;
  private final JobOrderService jobOrderService;
  private final UserService userService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final OrgUnitRepository orgUnitRepository;
  private final AuditRecorder auditRecorder;

  /**
   * Snapshot of one handed-over inventory row, taken before it is decremented or deleted, for
   * emitting {@code INVENTORY_HANDED_OVER} audit events afterwards.
   *
   * @param itemId the source inventory row id
   * @param label the {@code material @ location} label
   * @param material the material name
   * @param amount the handed-over amount
   * @param remaining the amount left after the decrement (0 when depleted)
   * @param depleted whether the source row was removed
   * @param quality the code of the quality tier the amount was booked against, or {@code null}
   */
  private record HandedItem(
      UUID itemId,
      String label,
      String material,
      double amount,
      double remaining,
      boolean depleted,
      @Nullable String quality) {

    /**
     * Returns this snapshot with the tier it was booked against.
     *
     * @param tierCode the tier's code
     * @return the completed snapshot
     */
    HandedItem withQuality(@Nullable String tierCode) {
      return new HandedItem(itemId, label, material, amount, remaining, depleted, tierCode);
    }
  }

  /**
   * Creates a job-order handover and atomically applies its effects.
   *
   * <ul>
   *   <li>reduces inventory amounts, deleting fully consumed rows,
   *   <li>reduces the open amount per {@link
   *       de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial},
   *   <li>persists the handover and its items,
   *   <li>unlinks remaining inventory of fully fulfilled materials,
   *   <li>completes the order once every material is fulfilled.
   * </ul>
   *
   * <p>The complete consumption, fulfillment-release and terminal queue resources are stabilized
   * before the first mutation. Bulk unlinks follow persistence of every handover item.
   */
  @Transactional
  public JobOrderHandoverDto createHandover(UUID jobOrderId, JobOrderHandoverCreateDto dto) {
    final List<JobOrderHandoverItemCreateDto> items =
        dto.items().stream()
            .map(item ->
                new JobOrderHandoverItemCreateDto(
                    item.inventoryItemId(),
                    item.amount(),
                    item.missionReductions() == null ? null : List.copyOf(item.missionReductions()),
                    item.qualityRequirement()))
            .toList();
    var scopes = materialPromises.materialScopesOfOrder(jobOrderId);
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(jobOrderId), "JobOrder not found");
    responsibilityGuard.assertWritable(jobOrder);

    if (jobOrder.getType() == JobOrderType.ITEM) {
      throw new BadRequestException(JobOrderStockErrors.MATERIAL_ORDER_REQUIRED);
    }
    final MaterialHandoverCalculation.Booked booked =
        handoverPlanner.prepare(jobOrder, items, scopes);

    JobOrderHandover handover = new JobOrderHandover();
    handover.setJobOrder(jobOrder);
    handover.setHandoverTime(dto.handoverTime());
    handover.setRecipientHandle(dto.recipientHandle());
    handover.setRecipientSquadron(dto.recipientSquadron());

    UUID responsibleOrgUnitId =
        jobOrder.getResponsibleOrgUnit() != null ? jobOrder.getResponsibleOrgUnit().getId() : null;
    userService
        .getCurrentUser()
        .ifPresent(current -> {
          handover.setExecutingUser(current);
          orgUnitMembershipQueryService
              .findExecutingStaffelForOrder(current.getId(), responsibleOrgUnitId)
              .flatMap(orgUnitRepository::findById)
              .map(ou -> Hibernate.unproxy(ou, OrgUnit.class))
              .filter(Squadron.class::isInstance)
              .map(Squadron.class::cast)
              .ifPresent(handover::setExecutingSquadron);
        });

    final Integer orderDisplayId = jobOrder.getDisplayId();
    final List<HandedItem> handedItems = new ArrayList<>();

    int bookingIndex = 0;
    for (JobOrderHandoverItemCreateDto itemDto : items) {
      StockRow inventoryItem =
          Entities.require(
              stockCommands.lockRow(itemDto.inventoryItemId()), "Inventory item not found");

      final double remainingAmount = inventoryItem.getAmount() - itemDto.amount();

      JobOrderHandoverItem handoverItem = new JobOrderHandoverItem();
      handoverItem.setMaterial(Objects.requireNonNull(inventoryItem.getMaterial()));
      handoverItem.setQuality(inventoryItem.getQuality());
      handoverItem.setStolen(inventoryItem.getStolen());
      handoverItem.setAmount(itemDto.amount());
      handoverItem.setLocationName(
          inventoryItem.getLocation() != null ? inventoryItem.getLocation().getName() : null);

      handover.addItem(handoverItem);

      boolean itemDepleted = remainingAmount <= MaterialHandoverCalculation.EPSILON;
      String materialName =
          inventoryItem.getMaterial() != null ? inventoryItem.getMaterial().getName() : "—";
      String locationName =
          inventoryItem.getLocation() != null ? inventoryItem.getLocation().getName() : "—";
      handedItems.add(
          new HandedItem(
              inventoryItem.getId(),
              materialName + " @ " + locationName,
              materialName,
              itemDto.amount(),
              itemDepleted ? 0.0 : remainingAmount,
              itemDepleted,
              null));

      stockCommands.takeFromEarmarkedRow(
          inventoryItem.getId(),
          jobOrderId,
          itemDto.amount(),
          itemDto.missionReductions(),
          StockChangeReason.HANDOVER);

      String tierCode = booked.bookings().get(bookingIndex++).tierCode();
      handedItems.set(handedItems.size() - 1, handedItems.getLast().withQuality(tierCode));
    }
    jobOrder
        .getMaterials()
        .forEach(
            line -> line.setAmount(Objects.requireNonNull(booked.remaining().get(line.getId()))));

    JobOrderHandover savedHandover = jobOrderHandoverRepository.save(handover);
    final JobOrderHandoverDto resultDto = jobOrderHandoverMapper.toDto(savedHandover);

    for (UUID materialId : booked.fulfilledMaterials()) {
      stockCommands.releaseJobOrderMaterialEarmarks(jobOrderId, materialId);
    }

    JobOrder managedJobOrder =
        Entities.require(jobOrderRepository.findById(jobOrderId), "JobOrder not found");
    responsibilityGuard.assertWritable(managedJobOrder);

    boolean allFulfilled = booked.allFulfilled();

    if (allFulfilled) {
      jobOrderService.completeJobOrderWithinTransaction(managedJobOrder);
    }

    recordHandedStock(handedItems, orderDisplayId);
    auditRecorder.record(
        AuditEventType.JOB_ORDER_HANDOVER_CREATED,
        jobOrderId,
        JobOrderAuditLabel.of(managedJobOrder.getDisplayId()),
        null,
        AuditDetails.of("handover", savedHandover.getId())
            .with("items", handedItems.size())
            .with("autoCompleted", allFulfilled));

    return resultDto;
  }

  /**
   * Reports surviving stock reductions and audits each handed row from its pre-consumption
   * snapshot.
   *
   * @param handedItems the consumed stock snapshots in handover order
   * @param orderDisplayId the order's display number, or null when unassigned
   */
  private void recordHandedStock(
      @NotNull List<HandedItem> handedItems, @Nullable Integer orderDisplayId) {
    for (HandedItem h : handedItems) {
      if (!h.depleted()) {
        stockCommands.lowered(h.itemId(), h.remaining(), StockChangeReason.HANDOVER);
      }
      auditRecorder.record(
          AuditEventType.INVENTORY_HANDED_OVER,
          h.itemId(),
          h.label(),
          null,
          AuditDetails.of("source", "HANDOVER")
              .with("jobOrder", "#" + orderDisplayId)
              .with("material", h.material())
              .with("amount", h.amount())
              .with("remaining", h.remaining())
              .with("depleted", h.depleted())
              .with("quality", h.quality()));
    }
  }
}

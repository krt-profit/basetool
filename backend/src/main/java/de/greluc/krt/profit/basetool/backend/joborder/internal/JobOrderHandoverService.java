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
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.kernel.Quality;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderHandoverMapper;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandoverItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service handling JobOrderHandoverService operations. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderHandoverService {

  /**
   * Tolerance for comparing {@code double} handover and inventory quantities; residuals below it
   * are floating-point noise, as quantities are rounded to three decimals.
   */
  private static final double QUANTITY_EPSILON = 1e-4;

  /**
   * I18n key of the 400 detail for a payload naming an inventory entry that carries no slice for
   * the order it is booked against (a stale client payload or a concurrent unlink). Shared with
   * {@link JobOrderItemProductionService}, which applies the same guard to consumed entries.
   */
  static final String ERROR_ITEM_NOT_LINKED_TO_ORDER = "error.job_order.inventory_item_not_linked";

  /**
   * I18n key of the 400 detail for stock whose quality is below the floor of the tier it is booked
   * or consumed against (REQ-ORDERS-038, REQ-ORDERS-039). Shared with {@link
   * JobOrderItemProductionService}.
   */
  static final String ERROR_QUALITY_BELOW_FLOOR = "error.job_order.quality_below_floor";

  /** I18n key of the 400 detail for a tier code that names no line of the handed material. */
  static final String ERROR_QUALITY_TIER_NOT_ON_ORDER = "error.job_order.quality_tier_not_on_order";

  private final JobOrderRepository jobOrderRepository;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final StockCommands stockCommands;
  private final JobOrderHandoverMapper jobOrderHandoverMapper;
  private final JobOrderMaterialRepository jobOrderMaterialRepository;
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
   * Books a handed amount against the order's lines of the entry's material (REQ-ORDERS-038).
   *
   * <p>With a tier code the amount goes to that line first; without one to the line with the
   * highest floor the entry's quality meets. What the first line cannot take spills over to the
   * other lines the quality meets, highest floor first; anything left beyond every line is dropped,
   * as an over-delivery always was.
   *
   * @param jobOrder the managed order
   * @param inventoryItem the handed entry
   * @param amount the handed amount
   * @param tierCode the chosen tier's code, or {@code null}
   * @return the code of the tier the amount was booked against first, or {@code null} when the
   *     order has no line of the material
   * @throws BadRequestException when the code names no line of the material, or the entry's quality
   *     is below the chosen tier's floor
   */
  @Nullable
  private static String bookAgainstLines(
      @NotNull JobOrder jobOrder,
      @NotNull InventoryItem inventoryItem,
      double amount,
      @Nullable String tierCode) {
    UUID materialId = inventoryItem.getMaterial().getId();
    int quality = Quality.orMin(inventoryItem.getQuality());
    List<JobOrderMaterial> eligible =
        jobOrder.getMaterials().stream()
            .filter(mat -> mat.getMaterial().getId().equals(materialId))
            .filter(mat -> mat.getQualityTier().isSatisfiedBy(quality))
            .sorted(
                Comparator.comparingInt(
                        (JobOrderMaterial mat) -> mat.getQualityTier().getMinQuality())
                    .reversed())
            .collect(Collectors.toCollection(ArrayList::new));
    if (tierCode != null && !tierCode.isBlank()) {
      String code = tierCode.trim().toUpperCase(Locale.ROOT);
      boolean lineExists =
          jobOrder.getMaterials().stream()
              .anyMatch(
                  mat ->
                      mat.getMaterial().getId().equals(materialId)
                          && mat.getQualityTier().getCode().equals(code));
      if (!lineExists) {
        throw new BadRequestException(ERROR_QUALITY_TIER_NOT_ON_ORDER);
      }
      JobOrderMaterial chosen =
          eligible.stream()
              .filter(mat -> mat.getQualityTier().getCode().equals(code))
              .findFirst()
              .orElseThrow(() -> new BadRequestException(ERROR_QUALITY_BELOW_FLOOR));
      eligible.remove(chosen);
      eligible.addFirst(chosen);
    }
    if (eligible.isEmpty()) {
      boolean materialOnOrder =
          jobOrder.getMaterials().stream()
              .anyMatch(mat -> mat.getMaterial().getId().equals(materialId));
      if (materialOnOrder) {
        throw new BadRequestException(ERROR_QUALITY_BELOW_FLOOR);
      }
      return null;
    }
    double left = amount;
    for (JobOrderMaterial mat : eligible) {
      if (left <= QUANTITY_EPSILON) {
        break;
      }
      double take = Math.min(left, mat.getAmount());
      mat.setAmount(Math.max(0.0, mat.getAmount() - take));
      left -= take;
    }
    return eligible.getFirst().getQualityTier().getCode();
  }

  /**
   * Creates a job-order handover and atomically applies its effects.
   *
   * <ul>
   *   <li>reduces inventory amounts, deleting fully consumed rows,
   *   <li>reduces the open amount per {@link
   *       de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial},
   *   <li>persists the handover and its items,
   *   <li>unlinks remaining inventory of fully fulfilled materials,
   *   <li>completes the order once every material is fulfilled.
   * </ul>
   *
   * <p>The loop mutates only managed entities; bulk unlinks run after it, and the {@link JobOrder}
   * is re-fetched once before the completion check to avoid spurious optimistic-lock conflicts.
   */
  @Transactional
  public JobOrderHandoverDto createHandover(UUID jobOrderId, JobOrderHandoverCreateDto dto) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(jobOrderId), "JobOrder not found");

    if (jobOrder.getType() == JobOrderType.ITEM) {
      throw new BadRequestException("Material handover is not available for item orders");
    }

    JobOrderHandover handover = new JobOrderHandover();
    handover.setJobOrder(jobOrder);
    handover.setHandoverTime(dto.handoverTime());
    handover.setRecipientHandle(dto.recipientHandle());
    handover.setRecipientSquadron(dto.recipientSquadron());

    UUID responsibleOrgUnitId =
        jobOrder.getResponsibleOrgUnit() != null ? jobOrder.getResponsibleOrgUnit().getId() : null;
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

    Set<UUID> materialsToUnlink = new HashSet<>();
    Set<UUID> touchedMaterials = new LinkedHashSet<>();

    final Integer orderDisplayId = jobOrder.getDisplayId();
    final List<HandedItem> handedItems = new ArrayList<>();

    for (JobOrderHandoverItemCreateDto itemDto : dto.items()) {
      InventoryItem inventoryItem =
          Entities.require(
              inventoryItemRepository.findByIdForUpdate(itemDto.inventoryItemId()),
              "Inventory item not found");

      if (inventoryItem.getJobOrderAllocations().stream()
          .noneMatch(a -> a.getJobOrder() != null && a.getJobOrder().getId().equals(jobOrderId))) {
        throw new BadRequestException(ERROR_ITEM_NOT_LINKED_TO_ORDER);
      }

      if (inventoryItem.getMaterial() == null) {
        throw new BadRequestException("Handed-over inventory entry does not hold a material");
      }

      if (itemDto.amount() == null || itemDto.amount() <= 0) {
        throw new BadRequestException("Handover amount must be positive");
      }
      if (itemDto.amount() > inventoryItem.getAmount() + QUANTITY_EPSILON) {
        throw new BadRequestException("Cannot hand over more than the available amount");
      }
      var orderSlice = InventoryAllocations.jobOrderSlice(inventoryItem, jobOrderId);
      double orderSliceAmount =
          orderSlice != null && orderSlice.getAmount() != null ? orderSlice.getAmount() : 0.0;
      if (itemDto.amount() > orderSliceAmount + QUANTITY_EPSILON) {
        throw new BadRequestException(
            "Cannot hand over more than the amount earmarked to this job order");
      }
      QuantityType quantityType =
          inventoryItem.getMaterial() != null
              ? inventoryItem.getMaterial().getQuantityType()
              : null;
      if (quantityType == QuantityType.PIECE && itemDto.amount() % 1 != 0) {
        throw new BadRequestException("Amount must be a whole number for PIECE materials");
      }

      final double remainingAmount = inventoryItem.getAmount() - itemDto.amount();

      JobOrderHandoverItem handoverItem = new JobOrderHandoverItem();
      handoverItem.setMaterial(inventoryItem.getMaterial());
      handoverItem.setQuality(inventoryItem.getQuality());
      handoverItem.setStolen(inventoryItem.getStolen());
      handoverItem.setAmount(itemDto.amount());
      handoverItem.setLocationName(
          inventoryItem.getLocation() != null ? inventoryItem.getLocation().getName() : null);

      handover.addItem(handoverItem);

      boolean itemDepleted = remainingAmount <= QUANTITY_EPSILON;
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
          inventoryItem,
          jobOrderId,
          itemDto.amount(),
          itemDto.missionReductions(),
          StockChangeReason.HANDOVER);

      String tierCode =
          bookAgainstLines(jobOrder, inventoryItem, itemDto.amount(), itemDto.qualityRequirement());
      handedItems.set(handedItems.size() - 1, handedItems.getLast().withQuality(tierCode));
      touchedMaterials.add(inventoryItem.getMaterial().getId());
    }

    for (UUID materialId : touchedMaterials) {
      boolean materialFulfilled =
          jobOrder.getMaterials().stream()
              .filter(mat -> mat.getMaterial().getId().equals(materialId))
              .allMatch(mat -> mat.getAmount() <= QUANTITY_EPSILON);
      if (materialFulfilled) {
        materialsToUnlink.add(materialId);
      }
    }

    JobOrderHandover savedHandover = jobOrderHandoverRepository.save(handover);
    final JobOrderHandoverDto resultDto = jobOrderHandoverMapper.toDto(savedHandover);

    for (UUID materialId : materialsToUnlink) {
      stockCommands.releaseJobOrderMaterialEarmarks(jobOrderId, materialId);
    }

    JobOrder managedJobOrder =
        Entities.require(jobOrderRepository.findById(jobOrderId), "JobOrder not found");

    boolean allFulfilled =
        managedJobOrder.getMaterials().stream()
            .allMatch(mat -> mat.getAmount() <= QUANTITY_EPSILON);

    if (allFulfilled) {
      jobOrderService.completeJobOrderWithinTransaction(managedJobOrder);
    }

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
}

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
import de.greluc.krt.profit.basetool.backend.exception.ProductionAllocationException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionConsumptionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.QuantityTypeRounding;
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
 * Books production ("Herstellung", REQ-ORDERS-025) against an ordered item line: records how many
 * whole units have been manufactured and atomically reduces the linked inventory the manufacture
 * consumed.
 *
 * <p>The flow is a hybrid of the two handover services. Like {@link JobOrderItemHandoverService} it
 * bumps a per-line integer counter ({@code manufacturedAmount}) via Hibernate dirty checking (no
 * explicit {@code save}) and never issues a {@code @Modifying(clearAutomatically = true)} bulk
 * update, so the persistence context is never detached mid-operation. Like {@link
 * JobOrderHandoverService} it reduces each consumed inventory entry — deleting the row when
 * depleted or decrementing it (and shrinking this order's earmark slice plus, epsilon-safe, any
 * mission earmark) otherwise — under a pessimistic write lock, and emits the same cross-domain
 * audit trail.
 *
 * <p>Unlike a handover, a production booking does <b>not</b> complete the order (only full delivery
 * does) and, for item orders, touches no {@code JobOrderMaterial} requirement row (item orders have
 * none — their requirements are the per-line snapshots), so there is no fulfilled-material bulk
 * unlink to defer. The material demand for {@code N} units of a line is the line's snapshotted
 * per-unit recipe scaled to {@code N} ({@code requiredQuantity × N / lineAmount}, per-material,
 * rounded for the material's quantity type) — the same snapshot that feeds the aggregated-materials
 * view. The consumption plan must cover that demand for every required material exactly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderItemProductionService {

  /**
   * Tolerance for comparing SCU quantities stored as {@code double}. Amounts are SCU-rounded to
   * three decimals at the persistence boundary, so a residual below this is floating-point noise
   * rather than a real over/under-coverage. Mirrors {@link JobOrderHandoverService}.
   */
  private static final double QUANTITY_EPSILON = 1e-4;

  private final JobOrderRepository jobOrderRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final MaterialExchangeOfferRepository materialExchangeOfferRepository;
  private final JobOrderItemService jobOrderItemService;
  private final AuditService auditService;

  /**
   * One consumed inventory row's scalar snapshot, captured before the row is decremented/deleted so
   * the {@code INVENTORY_CONSUMED_BY_PRODUCTION} audit events can be emitted from stable data
   * (mirrors {@link JobOrderHandoverService}'s {@code HandedItem}).
   *
   * @param itemId the source inventory row id
   * @param label the {@code material @ location} label snapshot
   * @param material the material name snapshot
   * @param amount the consumed amount
   * @param remaining the post-decrement amount (0 when depleted)
   * @param depleted whether the source row was removed
   */
  private record ConsumedItem(
      UUID itemId,
      String label,
      String material,
      double amount,
      double remaining,
      boolean depleted) {}

  /**
   * Books a production run against one item line: validates the amount against the line's
   * remaining-to-manufacture and the consumption against the required per-material demand, reduces
   * the consumed linked inventory, increments {@code manufacturedAmount}, and audits the JOB_ORDER
   * booking plus each cross-domain INVENTORY reduction.
   *
   * @param jobOrderId the item order that owns the line
   * @param jobOrderItemId the ordered item line being produced
   * @param dto the production payload (amount, line version, per-entry consumption)
   * @return the refreshed ordered-item line DTO (with the advanced {@code manufacturedAmount} and
   *     version)
   * @throws NotFoundException when the order, the line, or a consumed inventory entry is unknown
   * @throws BadRequestException when the order is not an item order, or a consumed entry is not
   *     linked to the order / does not hold the claimed material / breaks the PIECE whole-number
   *     rule
   * @throws ProductionAllocationException when the amount exceeds the line's
   *     remaining-to-manufacture, or the consumption does not exactly cover every required
   *     material's demand (a well-formed 422, distinct from a stale-version 409)
   */
  @Transactional
  public JobOrderItemDto bookProduction(
      UUID jobOrderId, UUID jobOrderItemId, JobOrderItemProductionCreateDto dto) {
    JobOrder jobOrder =
        jobOrderRepository
            .findById(jobOrderId)
            .orElseThrow(() -> new NotFoundException("JobOrder not found: " + jobOrderId));

    if (jobOrder.getType() != JobOrderType.ITEM) {
      throw new BadRequestException("Job order " + jobOrderId + " is not an item order");
    }

    JobOrderItem line =
        jobOrder.getItems().stream()
            .filter(i -> i.getId().equals(jobOrderItemId))
            .findFirst()
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Item line "
                            + jobOrderItemId
                            + " does not belong to job order "
                            + jobOrderId));

    // Optimistic lock: a production booking must carry the line's current version (checkRequired
    // treats an unversioned line as a conflict too). GlobalExceptionHandler maps the resulting
    // ObjectOptimisticLockingFailureException to a 409 (code OPTIMISTIC_LOCK).
    OptimisticLock.checkRequired(
        line.getVersion(), dto.version(), JobOrderItem.class, jobOrderItemId);

    final int amount = dto.amount();
    final int remainingToManufacture = line.getAmount() - line.getManufacturedAmount();
    if (amount > remainingToManufacture) {
      // Producing more than the line still needs — a 422 quantity-invariant violation.
      throw new ProductionAllocationException();
    }

    // Required per-material demand for `amount` units, scaled from the line's snapshot (the same
    // snapshot that feeds the aggregated-materials view). requiredQuantity holds the demand for the
    // whole ordered amount, so scale it to `amount` and round for the material's quantity type.
    Map<UUID, Double> demandByMaterial = new LinkedHashMap<>();
    for (JobOrderItemMaterial req : line.getMaterials()) {
      Material material = req.getMaterial();
      if (material == null) {
        continue;
      }
      double reqTotal = req.getRequiredQuantity() == null ? 0.0 : req.getRequiredQuantity();
      double demand =
          QuantityTypeRounding.roundForQuantityType(reqTotal * amount / line.getAmount(), material);
      demandByMaterial.merge(material.getId(), demand, Double::sum);
    }

    // The consumption must exactly cover every required material's demand, and may not name a
    // material the line does not require.
    Map<UUID, Double> consumedByMaterial = new LinkedHashMap<>();
    for (JobOrderItemProductionConsumptionDto c : dto.consumption()) {
      consumedByMaterial.merge(c.materialId(), c.amount() == null ? 0.0 : c.amount(), Double::sum);
    }
    for (Map.Entry<UUID, Double> demand : demandByMaterial.entrySet()) {
      double consumed = consumedByMaterial.getOrDefault(demand.getKey(), 0.0);
      if (Math.abs(consumed - demand.getValue()) > QUANTITY_EPSILON) {
        throw new ProductionAllocationException();
      }
    }
    for (UUID consumedMaterialId : consumedByMaterial.keySet()) {
      if (!demandByMaterial.containsKey(consumedMaterialId)) {
        throw new ProductionAllocationException();
      }
    }

    final Integer orderDisplayId = jobOrder.getDisplayId();
    final List<ConsumedItem> consumedItems = new ArrayList<>();

    for (JobOrderItemProductionConsumptionDto c : dto.consumption()) {
      InventoryItem inventoryItem =
          inventoryItemRepository
              .findByIdForUpdate(c.inventoryItemId())
              .orElseThrow(
                  () -> new NotFoundException("Inventory item not found: " + c.inventoryItemId()));

      // Optimistic lock on the entry — a concurrent stock change surfaces as a 409.
      OptimisticLock.check(
          inventoryItem.getVersion(), c.version(), InventoryItem.class, c.inventoryItemId());

      var orderSlice = InventoryAllocations.jobOrderSlice(inventoryItem, jobOrderId);
      if (orderSlice == null) {
        // The entry is not earmarked to this order — a stale payload or a concurrent unlink.
        // GlobalExceptionHandler maps IllegalStateException to 400.
        throw new IllegalStateException("Inventory item does not belong to this JobOrder");
      }
      if (inventoryItem.getMaterial() == null
          || !inventoryItem.getMaterial().getId().equals(c.materialId())) {
        throw new BadRequestException(
            "Consumed inventory entry does not hold the claimed material");
      }

      double consumed = c.amount() == null ? 0.0 : c.amount();
      if (consumed <= 0) {
        throw new BadRequestException("Consumption amount must be positive");
      }
      double orderSliceAmount = orderSlice.getAmount() != null ? orderSlice.getAmount() : 0.0;
      if (consumed > orderSliceAmount + QUANTITY_EPSILON) {
        // May only draw from this order's own earmark on the entry, never a sibling's slice or the
        // free rest — mirrors the handover cap (Variante C, REQ-INV-027).
        throw new ProductionAllocationException();
      }
      if (consumed > inventoryItem.getAmount() + QUANTITY_EPSILON) {
        throw new ProductionAllocationException();
      }
      QuantityType quantityType =
          inventoryItem.getMaterial() != null
              ? inventoryItem.getMaterial().getQuantityType()
              : null;
      if (quantityType == QuantityType.PIECE && consumed % 1 != 0) {
        throw new BadRequestException("Amount must be a whole number for PIECE materials");
      }

      final double remainingAmount = inventoryItem.getAmount() - consumed;
      boolean depleted = remainingAmount <= QUANTITY_EPSILON;
      String materialName =
          inventoryItem.getMaterial() != null ? inventoryItem.getMaterial().getName() : "—";
      String locationName =
          inventoryItem.getLocation() != null ? inventoryItem.getLocation().getName() : "—";
      consumedItems.add(
          new ConsumedItem(
              inventoryItem.getId(),
              materialName + " @ " + locationName,
              materialName,
              consumed,
              depleted ? 0.0 : remainingAmount,
              depleted));

      if (depleted) {
        inventoryItemRepository.delete(inventoryItem);
      } else {
        // The consumed SCU physically leave inventory AND this order's earmark; the same SCU also
        // leave any mission earmark, so clamp the mission dimension by the same amount (auto plan:
        // rest-first then proportional) to keep R5 without a 422.
        Map<UUID, Double> missionPlan =
            AllocationReductions.resolveReductionPlan(inventoryItem, null, consumed, false);
        InventoryAllocations.reduceJobOrder(inventoryItem, jobOrderId, consumed);
        AllocationReductions.applyPlan(inventoryItem, missionPlan, false);
        inventoryItem.setAmount(remainingAmount);
        inventoryItemRepository.save(inventoryItem);
      }
    }

    // Bump the manufactured counter via dirty checking (no explicit save → single @Version bump).
    line.setManufacturedAmount(line.getManufacturedAmount() + amount);
    // Flush so the returned DTO carries the advanced line @Version (and the inventory writes commit
    // before the audit snapshot reads).
    jobOrderRepository.flush();

    // Audit AFTER the writes, from the loop-captured snapshots (never re-reading a deleted entity).
    // One cross-domain INVENTORY_CONSUMED_BY_PRODUCTION per consumed entry plus one
    // JOB_ORDER_PRODUCTION_BOOKED. No user free text goes into the details payload.
    for (ConsumedItem ci : consumedItems) {
      if (!ci.depleted()) {
        materialExchangeOfferRepository.clampOfferedAmountToStock(ci.itemId(), ci.remaining());
      }
      auditService.record(
          AuditEventType.INVENTORY_CONSUMED_BY_PRODUCTION,
          ci.itemId(),
          ci.label(),
          null,
          AuditDetails.of("source", "PRODUCTION")
              .with("jobOrder", "#" + orderDisplayId)
              .with("material", ci.material())
              .with("amount", ci.amount())
              .with("remaining", ci.remaining())
              .with("depleted", ci.depleted()));
    }
    auditService.record(
        AuditEventType.JOB_ORDER_PRODUCTION_BOOKED,
        jobOrderId,
        "#" + jobOrder.getDisplayId() + " '" + jobOrder.getHandle() + "'",
        null,
        AuditDetails.of("item", jobOrderItemId)
            .with("amount", amount)
            .with("consumed", dto.consumption().size()));

    return jobOrderItemService.toItemDtos(jobOrder).stream()
        .filter(d -> d.id().equals(jobOrderItemId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Item line not found after production booking"));
  }
}

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
import de.greluc.krt.profit.basetool.backend.exception.ProductionAllocationException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionConsumptionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.support.InventoryAuditLabels;
import de.greluc.krt.profit.basetool.backend.support.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.QuantityTypeRounding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Books production ("Herstellung", REQ-ORDERS-025) against an ordered item line: records the
 * manufactured units, reduces the consumed linked inventory under a pessimistic lock and books the
 * produced units into the Lager (REQ-INV-032).
 *
 * <p>The consumption must exactly cover the line's snapshotted per-unit recipe scaled to the
 * amount, except for materials listed in {@code skippedMaterialIds}. A production booking never
 * completes the order.
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
  private final UserService userService;
  private final UserRepository userRepository;
  private final LocationRepository locationRepository;
  private final OwnerScopeService ownerScopeService;
  private final InventoryCheckoutService inventoryCheckoutService;
  private final AuthHelperService authHelperService;

  /**
   * Snapshot of one consumed inventory row, captured before it is decremented or deleted so the
   * {@code INVENTORY_CONSUMED_BY_PRODUCTION} audit events can be emitted afterwards.
   *
   * @param itemId the source inventory row id
   * @param label the {@code material @ location} label
   * @param material the material name
   * @param amount the consumed amount
   * @param remaining the amount left afterwards (0 when depleted)
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
   * Books a production run against one item line: validates amount and consumption, reduces the
   * consumed inventory, increments {@code manufacturedAmount}, audits the booking and books the
   * produced units in at the {@code bookIn} target (REQ-INV-032). Materials in {@code
   * dto.skippedMaterialIds} are neither required nor consumed.
   *
   * @param jobOrderId the item order that owns the line
   * @param jobOrderItemId the item line being produced
   * @param dto the production payload
   * @return the refreshed item line with its new {@code manufacturedAmount} and version
   * @throws NotFoundException when the order, line, a consumed entry or the book-in owner or
   *     location is unknown
   * @throws BadRequestException when the order is not an item order, a consumed entry is invalid,
   *     or the book-in target is invalid
   * @throws ProductionAllocationException when the amount exceeds the remaining quantity or the
   *     consumption does not exactly cover the demand (422)
   * @throws AccessDeniedException when the caller may not book into the named member's inventory
   */
  @Transactional
  public JobOrderItemDto bookProduction(
      UUID jobOrderId, UUID jobOrderItemId, JobOrderItemProductionCreateDto dto) {
    assertMayBookInFor(dto.bookIn());

    JobOrder jobOrder =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);

    if (jobOrder.getType() != JobOrderType.ITEM) {
      throw new BadRequestException("Job order " + jobOrderId + " is not an item order");
    }

    JobOrderItem line =
        Entities.require(
            jobOrder.getItems().stream().filter(i -> i.getId().equals(jobOrderItemId)).findFirst(),
            () -> "Item line " + jobOrderItemId + " does not belong to job order " + jobOrderId);

    OptimisticLock.checkRequired(
        line.getVersion(), dto.version(), JobOrderItem.class, jobOrderItemId);

    final int amount = dto.amount();
    final int remainingToManufacture = line.getAmount() - line.getManufacturedAmount();
    if (amount > remainingToManufacture) {
      throw new ProductionAllocationException();
    }

    final Set<UUID> skippedMaterials =
        dto.skippedMaterialIds() == null ? Set.of() : new HashSet<>(dto.skippedMaterialIds());

    Map<UUID, Double> demandByMaterial = new LinkedHashMap<>();
    final Set<UUID> skippedRequiredMaterials = new LinkedHashSet<>();
    for (JobOrderItemMaterial req : line.getMaterials()) {
      Material material = req.getMaterial();
      if (material == null) {
        continue;
      }
      if (skippedMaterials.contains(material.getId())) {
        skippedRequiredMaterials.add(material.getId());
        continue;
      }
      double reqTotal = req.getRequiredQuantity() == null ? 0.0 : req.getRequiredQuantity();
      double demand =
          QuantityTypeRounding.roundForQuantityType(reqTotal * amount / line.getAmount(), material);
      demandByMaterial.merge(material.getId(), demand, Double::sum);
    }

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
          Entities.require(
              inventoryItemRepository.findByIdForUpdate(c.inventoryItemId()),
              () -> "Inventory item not found: " + c.inventoryItemId());

      OptimisticLock.check(
          inventoryItem.getVersion(), c.version(), InventoryItem.class, c.inventoryItemId());

      var orderSlice = InventoryAllocations.jobOrderSlice(inventoryItem, jobOrderId);
      if (orderSlice == null) {
        throw new BadRequestException(JobOrderHandoverService.ERROR_ITEM_NOT_LINKED_TO_ORDER);
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
        Map<UUID, Double> missionPlan =
            AllocationReductions.resolveReductionPlan(inventoryItem, null, consumed, false);
        InventoryAllocations.reduceJobOrder(inventoryItem, jobOrderId, consumed);
        AllocationReductions.applyPlan(inventoryItem, missionPlan, false);
        inventoryItem.setAmount(remainingAmount);
        inventoryItemRepository.save(inventoryItem);
      }
    }

    line.setManufacturedAmount(line.getManufacturedAmount() + amount);
    jobOrderRepository.flush();

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
        JobOrderAuditLabel.of(jobOrder.getDisplayId()),
        null,
        AuditDetails.of("item", jobOrderItemId)
            .with("amount", amount)
            .with("consumed", dto.consumption().size())
            .with("skipped", skippedRequiredMaterials.size()));

    bookProducedStockIn(jobOrder, line, amount, dto.bookIn());

    return Entities.require(
        jobOrderItemService.toItemDtos(jobOrder).stream()
            .filter(d -> d.id().equals(jobOrderItemId))
            .findFirst(),
        "Item line not found after production booking");
  }

  /**
   * Refuses a book-in into another member's inventory the caller may not write (REQ-INV-032,
   * REQ-SEC-005): a foreign {@code ownerUserId} requires {@link
   * OwnerScopeService#canManageUserInventory(UUID)}, and a {@code personal} book-in for someone
   * else is always refused.
   *
   * <p>Checked on the requested id before any lookup; an absent {@code ownerUserId} means the
   * caller.
   *
   * @param bookIn the book-in target; never {@code null}
   * @throws AccessDeniedException when the caller may not write the named owner's inventory, or
   *     asks for a personal book-in on behalf of another member
   */
  private void assertMayBookInFor(@NotNull JobOrderItemProductionCreateDto.BookInDto bookIn) {
    final UUID ownerUserId = bookIn.ownerUserId();
    if (ownerUserId == null
        || authHelperService.currentUserId().map(ownerUserId::equals).orElse(false)) {
      return;
    }
    if (!ownerScopeService.canManageUserInventory(ownerUserId)) {
      throw new AccessDeniedException(
          "You are not allowed to book produced stock in for this user");
    }
    if (Boolean.TRUE.equals(bookIn.personal())) {
      throw new AccessDeniedException(
          "You are not allowed to book produced stock into another user's personal inventory");
    }
  }

  /**
   * Books the produced units into the Lager as one fresh game-item stock row (REQ-INV-032).
   *
   * <p>The owner defaults to the acting user and the owning org unit is resolved through {@link
   * OwnerScopeService#resolveOrgUnitForPickerOutputNullable}. Unless {@code personal}, the row is
   * earmarked to the producing order; it is then merged via {@link
   * InventoryCheckoutService#mergeStockIfRequested} and audited as {@code
   * INVENTORY_RECEIVED_FROM_PRODUCTION}.
   *
   * @param jobOrder the producing order, managed in the current transaction
   * @param line the produced item line supplying the stock row's game item
   * @param amount the produced whole units
   * @param bookIn the book-in target; never {@code null}
   * @throws NotFoundException when the book-in owner or location is unknown
   * @throws BadRequestException when {@code personal} is combined with the order earmark, the
   *     acting user cannot be resolved, the line has no game item, or the org-unit picker output is
   *     invalid
   */
  private void bookProducedStockIn(
      JobOrder jobOrder,
      JobOrderItem line,
      int amount,
      @NotNull JobOrderItemProductionCreateDto.BookInDto bookIn) {
    final boolean personal = Boolean.TRUE.equals(bookIn.personal());
    final boolean allocateToOrder = !Boolean.FALSE.equals(bookIn.allocateToOrder());
    if (personal && allocateToOrder) {
      throw new BadRequestException("Personal items cannot be assigned to a mission or job order");
    }
    if (line.getGameItem() == null) {
      throw new BadRequestException("Produced item line carries no game item to book in");
    }

    final User owner =
        bookIn.ownerUserId() != null
            ? Entities.require(userRepository.findById(bookIn.ownerUserId()), "User not found")
            : userService
                .getCurrentUser()
                .orElseThrow(
                    () ->
                        new BadRequestException(
                            "No acting user to book the produced stock in for"));
    final Location location =
        Entities.require(locationRepository.findById(bookIn.locationId()), "Location not found");
    final OrgUnit owningOrgUnit =
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(owner, bookIn.owningOrgUnitId());

    InventoryItem stockRow = new InventoryItem();
    stockRow.setUser(owner);
    stockRow.setOwningOrgUnit(owningOrgUnit);
    stockRow.setGameItem(line.getGameItem());
    stockRow.setLocation(location);
    stockRow.setAmount((double) amount);
    stockRow.setPersonal(personal);
    if (allocateToOrder) {
      InventoryAllocations.addJobOrder(stockRow, jobOrder, (double) amount, false);
    }
    InventoryItem saved = inventoryItemRepository.save(stockRow);
    InventoryItem merged = inventoryCheckoutService.mergeStockIfRequested(saved, false);
    auditService.record(
        AuditEventType.INVENTORY_RECEIVED_FROM_PRODUCTION,
        merged.getId(),
        InventoryAuditLabels.label(merged),
        owner.getId(),
        AuditDetails.of("jobOrder", "#" + jobOrder.getDisplayId())
            .with("gameItemId", line.getGameItem().getId())
            .with("amount", amount)
            .with("locationId", location.getId()));
  }
}

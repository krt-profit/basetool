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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInRule;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderVisibility;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completion of a refinery order: books its output into inventory rows (store).
 *
 * <p>Only the owner or a logistician may store an order, checked per resource.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefineryOrderStoreService {

  private final RefineryOrderRepository refineryOrderRepository;
  private final UserRepository userRepository;
  private final LocationRepository locationRepository;
  private final MaterialRepository materialRepository;
  private final JobOrderRepository jobOrderRepository;
  private final JobOrderItemService jobOrderItemService;

  /**
   * Limits a line's earmark to the job orders the active-order lookup offers (REQ-REFINERY-022).
   */
  private final JobOrderVisibility jobOrderVisibility;

  private final OrgUnitStampingService orgUnitStampingService;
  private final StockCommands stockCommands;

  /** Books the checked lines and completes the order. */
  private final RefineryOrderStoreBooking storeBooking;

  /**
   * Completes a refinery order by booking each output material as an inventory row.
   *
   * <p>Rows marked {@code personal} get no earmark (REQ-INV-035); a non-logistician may only book
   * onto themselves (REQ-SEC-039). Every line is checked before the first row is booked.
   *
   * @throws AccessDeniedException when the caller is neither owner nor logistician, or when a
   *     non-logistician names another user as an item's receiving stock owner
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order or any
   *     referenced id is unknown, or an item names a job order outside the caller's read scope
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the order is
   *     already stored, an item's material is not an output of the order's goods, an item combines
   *     {@code personal} with a job order, or an item names a job order that does not require its
   *     material
   */
  @Transactional
  public void storeRefineryOrder(
      @NotNull UUID userId,
      @NotNull UUID orderId,
      @NotNull RefineryOrderStoreDto dto,
      boolean isLogistician) {
    RefineryOrder order = RefineryOrders.requireLocked(refineryOrderRepository, orderId);

    if (order.getStoredAt() != null || order.getStatus() == RefineryOrderStatus.COMPLETED) {
      throw new BadRequestException("error.refinery_order.already_stored");
    }

    if (!isLogistician
        && (order.getOwner() == null
            || order.getOwner().getId() == null
            || !order.getOwner().getId().equals(userId))) {
      throw new AccessDeniedException("Access denied: You do not own this refinery order");
    }

    dto.items()
        .forEach(item ->
            RefineryOrderStoreBooking.requireOutputGood(order, item.materialId(), item.quality()));
    List<RefineryOrderStoreBooking.Line> lines = new ArrayList<>(dto.items().size());
    for (RefineryOrderStoreItemDto itemDto : dto.items()) {
      lines.add(line(order, userId, isLogistician, itemDto));
    }
    storeBooking.store(order, userId, lines, null);
  }

  /**
   * Checks one item of the web's store dialog and loads what it names.
   *
   * @param order the locked order
   * @param userId the caller
   * @param isLogistician whether the caller is a Logistician of the order
   * @param itemDto the item
   * @return the checked line
   * @throws AccessDeniedException when the caller may not book onto the item's receiver
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when an id is unknown or
   *     the job order lies outside the caller's read scope
   * @throws BadRequestException when the item combines {@code personal} with a job order, its job
   *     order does not need its material, or its unit is not allowed
   */
  private @NotNull RefineryOrderStoreBooking.Line line(
      @NotNull RefineryOrder order,
      @NotNull UUID userId,
      boolean isLogistician,
      @NotNull RefineryOrderStoreItemDto itemDto) {
    final Material mat =
        Entities.require(
            materialRepository.findById(itemDto.materialId()),
            () -> "Material not found: " + itemDto.materialId());

    final Location loc =
        OfferedLocations.requireOffered(
            Entities.require(
                locationRepository.findById(itemDto.locationId()),
                () -> "Location not found: " + itemDto.locationId()));

    final UUID targetUserId =
        itemDto.userId() != null
            ? itemDto.userId()
            : (order.getOwner() != null ? order.getOwner().getId() : null);
    stockCommands.requireBookIn(
        targetUserId,
        userId,
        isLogistician,
        Boolean.TRUE.equals(itemDto.personal()),
        BookInRule.refineryStore());

    User assignee;
    if (itemDto.userId() != null) {
      assignee =
          Entities.require(
              userRepository.findById(itemDto.userId()),
              () -> "User not found: " + itemDto.userId());
    } else {
      assignee = order.getOwner();
    }

    final boolean personal = Boolean.TRUE.equals(itemDto.personal());
    if (personal && itemDto.jobOrderId() != null) {
      throw new BadRequestException("Personal items cannot be assigned to a mission or job order");
    }

    JobOrder jobOrder = null;
    if (itemDto.jobOrderId() != null) {
      jobOrder =
          Entities.require(
              jobOrderRepository
                  .findById(itemDto.jobOrderId())
                  .filter(jobOrderVisibility::canSeeJobOrderInScope),
              () -> "JobOrder not found: " + itemDto.jobOrderId());
      assertMaterialRequiredByJobOrder(mat, jobOrder);
    }

    final OrgUnit owningOrgUnit =
        orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(
            assignee, itemDto.owningOrgUnitId());

    return new RefineryOrderStoreBooking.Line(
        mat,
        loc,
        itemDto.quality(),
        itemDto.amount(),
        assignee,
        owningOrgUnit,
        personal,
        jobOrder,
        StringNormalization.trimToNull(itemDto.note()));
  }

  /**
   * Rejects earmarking refinery output for a job order that does not require its material, as the
   * inventory create path does (REQ-ORDERS-018).
   *
   * @param material the output material being booked
   * @param jobOrder the order the new inventory row is earmarked for
   * @throws BadRequestException when the order does not require the material
   */
  private void assertMaterialRequiredByJobOrder(
      @NotNull Material material, @NotNull JobOrder jobOrder) {
    if (!jobOrderItemService.requiredMaterialIds(jobOrder).contains(material.getId())) {
      throw new BadRequestException(
          "Material "
              + material.getId()
              + " is not required by job order "
              + jobOrder.getId()
              + "; an inventory item can only be linked to an order that needs its material.");
    }
  }
}

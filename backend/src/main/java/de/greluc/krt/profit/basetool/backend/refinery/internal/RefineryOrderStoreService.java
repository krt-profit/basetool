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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInRule;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderVisibility;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
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
  private final AuditRecorder auditRecorder;

  /** Publishes the refinery notices after the commit (REQ-REFINERY-024). */
  private final ApplicationEventPublisher eventPublisher;

  private final UserService userService;

  /**
   * Completes a refinery order by booking each output material as an inventory row.
   *
   * <p>Rows marked {@code personal} get no earmark (REQ-INV-035); a non-logistician may only book
   * onto themselves (REQ-SEC-039).
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

    final RefineryOrderStatus previousStatus = order.getStatus();
    final Set<UUID> bookedOnto = new LinkedHashSet<>();
    final List<RefineryGood> goods =
        dto.items().stream()
            .map(item -> requireOutputGood(order, item.materialId(), item.quality()))
            .toList();

    for (int index = 0; index < dto.items().size(); index++) {
      final RefineryOrderStoreItemDto itemDto = dto.items().get(index);
      final RefineryGood good = goods.get(index);
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
        throw new BadRequestException(
            "Personal items cannot be assigned to a mission or job order");
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

      String incomingNote = StringNormalization.trimToNull(itemDto.note());

      stockCommands.bookInFromRefinery(
          orderId,
          assignee,
          owningOrgUnit,
          mat,
          loc,
          itemDto.quality(),
          itemDto.amount(),
          incomingNote,
          personal,
          jobOrder != null ? jobOrder.getId() : null,
          jobOrder != null ? jobOrder.getDisplayId() : null,
          order.getMissionId());
      bookedOnto.add(assignee.getId());

      writeBackOutputQuantity(good, itemDto.amount());
    }

    order.setStatus(RefineryOrderStatus.COMPLETED);
    order.setStoredAt(Instant.now());
    RefineryOrderSection.GAME_DATA.advance(order);
    refineryOrderRepository.save(order);
    eventPublisher.publishEvent(RefineryNotices.readyCleared(order.getId()));
    announceStored(order, userId, bookedOnto);
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_STORED,
        order.getId(),
        RefineryOrders.label(order),
        order.getOwner() != null ? order.getOwner().getId() : null,
        AuditDetails.of("items", dto.items().size())
            .with("status", previousStatus + "->COMPLETED"));
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

  /**
   * Tells the owner that somebody else stored their order, and every other member whose stock
   * received a yield the acting member booked onto them (REQ-REFINERY-024).
   *
   * @param order the stored order
   * @param actorId the acting member
   * @param bookedOnto the members whose stock received a row
   */
  private void announceStored(
      @NotNull RefineryOrder order, @NotNull UUID actorId, @NotNull Set<UUID> bookedOnto) {
    UUID ownerId = order.getOwner() == null ? null : order.getOwner().getId();
    String location = order.getLocation() == null ? null : order.getLocation().getName();
    ActorRef actor = null;
    if (ownerId != null && !actorId.equals(ownerId)) {
      actor = userService.currentActor();
      eventPublisher.publishEvent(
          RefineryNotices.changedByOther(
              order.getId(),
              ownerId,
              location,
              bookedOnto.contains(ownerId) ? "STORED_TO_YOU" : "STORED",
              actor));
    }
    for (UUID memberId : bookedOnto) {
      if (memberId.equals(actorId) || memberId.equals(ownerId)) {
        continue;
      }
      if (actor == null) {
        actor = userService.currentActor();
      }
      eventPublisher.publishEvent(
          RefineryNotices.changedByOther(
              order.getId(), memberId, location, "STORED_TO_YOU", actor));
    }
  }

  /**
   * Resolves the good a store item books, refusing a material that is not an output of the order
   * (REQ-REFINERY-022).
   *
   * @param order the order being stored
   * @param materialId the material the item books
   * @param quality the grade the item carries; a good of another grade matches when none carries it
   * @return the good of that material and grade, or of that material at another grade
   * @throws BadRequestException when no good of the order outputs the material
   */
  @NotNull
  private RefineryGood requireOutputGood(
      @NotNull RefineryOrder order, @NotNull UUID materialId, @Nullable Integer quality) {
    RefineryGood good = findGood(order, materialId, quality);
    if (good == null) {
      good = findGood(order, materialId, null);
    }
    if (good == null) {
      throw new BadRequestException("error.refinery_order.material_not_an_output");
    }
    return good;
  }

  /**
   * Writes the stored amount back into the good's output quantity, converting SCU to units (×100)
   * and clamping to at least one unit.
   *
   * @param good the good the item belongs to
   * @param amount the stored amount in the material's quantity type
   */
  private void writeBackOutputQuantity(@NotNull RefineryGood good, double amount) {
    long units =
        good.getOutputMaterial().getQuantityType() == QuantityType.SCU
            ? Math.round(amount * RefineryOrders.UNITS_PER_SCU)
            : Math.round(amount);
    good.setOutputQuantity(Math.clamp(units, 1, Integer.MAX_VALUE));
  }

  /**
   * Finds the good a store item belongs to.
   *
   * @param order the order whose goods to search
   * @param materialId the output material the item books
   * @param quality the grade the item carries, or {@code null} to match on the material alone
   * @return the good, or {@code null} when none matches or the order has no goods
   */
  @Nullable
  private RefineryGood findGood(
      @NotNull RefineryOrder order, @NotNull UUID materialId, @Nullable Integer quality) {
    if (order.getGoods() == null) {
      return null;
    }
    for (RefineryGood good : order.getGoods()) {
      if (good.getOutputMaterial() == null || good.getOutputMaterial().getId() == null) {
        continue;
      }
      if (!good.getOutputMaterial().getId().equals(materialId)) {
        continue;
      }
      if (quality != null && !quality.equals(good.getQuality())) {
        continue;
      }
      return good;
    }
    return null;
  }
}

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
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Stores a refinery order: books each checked line as an inventory row, writes the stored amount
 * back into its good, completes the order with {@code storedAt}, notifies the members booked onto
 * and audits {@code REFINERY_ORDER_STORED} (REQ-REFINERY-022, REQ-REFINERY-024). The web's store
 * and a client's pickup with store lines share it; each checks its lines before, and both run in
 * the caller's transaction.
 */
@Component
@RequiredArgsConstructor
class RefineryOrderStoreBooking {

  private final RefineryOrderRepository refineryOrderRepository;
  private final StockCommands stockCommands;
  private final AuditRecorder auditRecorder;

  /** Publishes the refinery notices after the commit (REQ-REFINERY-024). */
  private final ApplicationEventPublisher eventPublisher;

  private final UserService userService;

  /**
   * Books the lines and completes the order.
   *
   * @param order the locked order, neither stored nor completed
   * @param actorId the member who stores
   * @param lines the checked lines, each of an output material of the order
   * @param clientId the client that reports the pickup, or {@code null} for the web's store
   * @throws BadRequestException when a line's material is not an output of the order's goods
   */
  void store(
      @NotNull RefineryOrder order,
      @NotNull UUID actorId,
      @NotNull List<Line> lines,
      @Nullable String clientId) {
    final RefineryOrderStatus previousStatus = order.getStatus();
    final List<RefineryGood> goods =
        lines.stream()
            .map(line -> requireOutputGood(order, line.material().getId(), line.quality()))
            .toList();
    final Set<UUID> bookedOnto = new LinkedHashSet<>();
    for (int index = 0; index < lines.size(); index++) {
      Line line = lines.get(index);
      JobOrder jobOrder = line.jobOrder();
      stockCommands.bookInFromRefinery(
          order.getId(),
          line.receiver(),
          line.orgUnit(),
          line.material(),
          line.location(),
          line.quality(),
          line.amount(),
          line.note(),
          line.personal(),
          jobOrder != null ? jobOrder.getId() : null,
          jobOrder != null ? jobOrder.getDisplayId() : null,
          order.getMissionId());
      bookedOnto.add(line.receiver().getId());
      writeBackOutputQuantity(goods.get(index), line.amount());
    }
    Instant storedAt = Instant.now();
    order.setStatus(RefineryOrderStatus.COMPLETED);
    order.setStoredAt(storedAt);
    RefineryOrderSection.GAME_DATA.advance(order);
    if (clientId == null) {
      order.getProvenance().recordWebChange(storedAt);
    } else {
      order.getProvenance().recordPickup(clientId, storedAt);
    }
    refineryOrderRepository.save(order);
    eventPublisher.publishEvent(RefineryNotices.readyCleared(order.getId()));
    announceStored(order, actorId, bookedOnto);
    AuditDetails details =
        AuditDetails.of("items", lines.size())
            .with("status", previousStatus + "->" + RefineryOrderStatus.COMPLETED);
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_STORED,
        order.getId(),
        RefineryOrders.label(order),
        order.getOwner() != null ? order.getOwner().getId() : null,
        clientId == null ? details : details.with("pickup", true));
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
   * Resolves the good a line books, refusing a material that is not an output of the order
   * (REQ-REFINERY-022).
   *
   * @param order the order being stored
   * @param materialId the material the line books
   * @param quality the grade the line carries; a good of another grade matches when none carries it
   * @return the good of that material and grade, or of that material at another grade
   * @throws BadRequestException when no good of the order outputs the material
   */
  @NotNull
  static RefineryGood requireOutputGood(
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
   * @param good the good the line belongs to
   * @param amount the stored amount in the material's quantity type
   */
  private static void writeBackOutputQuantity(@NotNull RefineryGood good, double amount) {
    long units =
        good.getOutputMaterial().getQuantityType() == QuantityType.SCU
            ? Math.round(amount * RefineryOrders.UNITS_PER_SCU)
            : Math.round(amount);
    good.setOutputQuantity(Math.clamp(units, 1, Integer.MAX_VALUE));
  }

  /**
   * Finds the good a line belongs to.
   *
   * @param order the order whose goods to search
   * @param materialId the output material the line books
   * @param quality the grade the line carries, or {@code null} to match on the material alone
   * @return the good, or {@code null} when none matches or the order has no goods
   */
  @Nullable
  private static RefineryGood findGood(
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

  /**
   * One checked line of a store.
   *
   * @param material the output material
   * @param location the offered location
   * @param quality the quality
   * @param amount the amount in the material's own unit
   * @param receiver the member whose stock receives the line
   * @param orgUnit the owning unit of the booked stock, or {@code null}
   * @param personal whether the stock is the receiver's private stock
   * @param jobOrder the job order the stock is earmarked to, or {@code null}
   * @param note the trimmed note, or {@code null}
   */
  record Line(
      @NotNull Material material,
      @NotNull Location location,
      int quality,
      double amount,
      @NotNull User receiver,
      @Nullable OrgUnit orgUnit,
      boolean personal,
      @Nullable JobOrder jobOrder,
      @Nullable String note) {}
}

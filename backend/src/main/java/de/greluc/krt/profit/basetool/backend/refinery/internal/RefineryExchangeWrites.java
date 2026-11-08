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
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeEdit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrigin;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRestore;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeWriter;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a member's own refinery orders for the exchange (REQ-XCH-052, REQ-XCH-056) with the web's
 * rules: an offered refinery as the location, raw inputs, the stored status lock, the ready notice
 * re-armed on a moved run, the section counters and the provenance (REQ-REFINERY-020, -022, -023,
 * -027, -029), audited like a web write.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RefineryExchangeWrites implements RefineryExchangeWriter {

  private final RefineryOrderRepository refineryOrderRepository;
  private final UserRepository userRepository;
  private final LocationRepository locationRepository;
  private final RefiningMethodRepository refiningMethodRepository;
  private final MaterialRepository materialRepository;
  private final MissionRepository missionRepository;
  private final OrgUnitStampingService orgUnitStampingService;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final EntityManager entityManager;

  @Override
  public @NotNull Optional<RefineryExchangeOrder> lockOwn(
      @NotNull UUID owner, @NotNull UUID orderId) {
    return refineryOrderRepository
        .lockById(orderId)
        .filter(order -> owns(order, owner))
        .map(RefineryExchangeReads::toRow);
  }

  @Override
  public @NotNull RefineryExchangeOrder create(
      @NotNull UUID owner,
      @NotNull String clientId,
      @NotNull UUID orgUnitId,
      @NotNull RefineryExchangeEdit edit) {
    User user = Entities.require(userRepository.findById(owner), "error.user.not_found");
    RefineryOrder order = new RefineryOrder();
    order.setOwner(user);
    order.setOwningOrgUnit(
        orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(user, orgUnitId));
    order.setLocation(
        refinery(
            OfferedLocations.require(
                locationRepository.findById(
                    Objects.requireNonNull(edit.locationId(), "locationId")),
                "error.location.not_found")));
    order.setStatus(RefineryOrderStatus.valueOf(Objects.requireNonNull(edit.status(), "status")));
    order.setGameDataVersion(0L);
    order.setFinancesVersion(0L);
    order.setProvenance(RefineryOrderProvenance.createdByClient(clientId, Instant.now()));
    if (edit.method() != null) {
      setMethod(order, edit.method());
    }
    RefineryExchangeEdit.Timer timer = edit.timer();
    order.setStartedAt(
        timer != null && timer.startedAt() != null ? timer.startedAt() : Instant.now());
    order.setDurationMinutes(timer == null ? null : timer.durationMinutes());
    if (edit.finances() != null) {
      setFinances(order, edit.finances());
    }
    List<RefineryExchangeEdit.Good> reinserted =
        edit.goods() == null ? List.of() : setGoods(order, edit.goods());
    if (!reinserted.isEmpty()) {
      throw new BadRequestException("error.refinery_order.good_unknown");
    }
    RefineryOrder saved = refineryOrderRepository.save(order);
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_CREATED,
        saved.getId(),
        RefineryOrders.label(saved),
        owner,
        AuditDetails.of("location", RefineryOrders.locationName(saved))
            .with("method", RefineryOrders.methodName(saved))
            .with("goods", saved.getGoods().size())
            .with("status", saved.getStatus()));
    return RefineryExchangeReads.toRow(saved);
  }

  @Override
  public @NotNull RefineryExchangeOrder edit(
      @NotNull UUID owner,
      @Nullable String clientId,
      @NotNull UUID orderId,
      @NotNull RefineryExchangeEdit edit) {
    RefineryOrder order = requireOwn(owner, orderId);
    final RefineryOrderStatus previousStatus = order.getStatus();
    boolean runTimeChanged = false;
    List<RefineryExchangeEdit.Good> reinserted = List.of();
    if (edit.touchesGameData()) {
      runTimeChanged = applyGameData(order, edit, clientId != null);
      if (edit.goods() != null) {
        reinserted = setGoods(order, edit.goods());
      }
      RefineryOrderSection.GAME_DATA.advance(order);
    }
    if (edit.finances() != null) {
      setFinances(order, edit.finances());
      RefineryOrderSection.FINANCES.advance(order);
    }
    if (clientId != null) {
      order.getProvenance().recordClientChange(clientId, Instant.now());
    } else {
      order.getProvenance().recordWebChange(Instant.now());
    }
    RefineryOrder saved = refineryOrderRepository.saveAndFlush(order);
    if (!reinserted.isEmpty()) {
      reinsert(saved.getId(), reinserted);
      entityManager.refresh(saved);
    }
    if (runTimeChanged
        || saved.getStatus() == RefineryOrderStatus.COMPLETED
        || saved.getStatus() == RefineryOrderStatus.CANCELED) {
      eventPublisher.publishEvent(RefineryNotices.readyCleared(saved.getId()));
    }
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_UPDATED,
        saved.getId(),
        RefineryOrders.label(saved),
        owner,
        AuditDetails.of("location", RefineryOrders.locationName(saved))
            .with("method", RefineryOrders.methodName(saved))
            .with("goods", saved.getGoods().size())
            .with("status", previousStatus + "->" + saved.getStatus()));
    return RefineryExchangeReads.toRow(saved);
  }

  @Override
  public @NotNull RefineryExchangeOrder cancel(
      @NotNull UUID owner, @NotNull String clientId, @NotNull UUID orderId) {
    RefineryOrder order = requireOwn(owner, orderId);
    final RefineryOrderStatus previousStatus = order.getStatus();
    order.setStatus(RefineryOrderStatus.CANCELED);
    RefineryOrderSection.GAME_DATA.advance(order);
    order.getProvenance().recordClientChange(clientId, Instant.now());
    RefineryOrder saved = refineryOrderRepository.saveAndFlush(order);
    eventPublisher.publishEvent(RefineryNotices.readyCleared(saved.getId()));
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_CANCELED,
        saved.getId(),
        RefineryOrders.label(saved),
        owner,
        AuditDetails.of("previousStatus", previousStatus));
    return RefineryExchangeReads.toRow(saved);
  }

  @Override
  public @NotNull RefineryExchangeOrigin delete(@NotNull UUID owner, @NotNull UUID orderId) {
    RefineryOrder order = requireOwn(owner, orderId);
    if (order.getStoredAt() != null) {
      throw new BadRequestException("error.refinery_order.stored");
    }
    final RefineryExchangeOrigin origin = originOf(order);
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_DELETED,
        order.getId(),
        RefineryOrders.label(order),
        owner,
        AuditDetails.of("status", order.getStatus())
            .with("location", RefineryOrders.locationName(order))
            .with("goods", order.getGoods().size()));
    refineryOrderRepository.delete(order);
    eventPublisher.publishEvent(RefineryNotices.readyCleared(orderId));
    return origin;
  }

  @Override
  public @NotNull RefineryExchangeOrder restore(
      @NotNull UUID owner, @NotNull RefineryExchangeRestore restore) {
    User user = Entities.require(userRepository.findById(owner), "error.user.not_found");
    RefineryExchangeEdit edit = restore.edit();
    RefineryExchangeOrigin origin = restore.origin();
    UUID location =
        Entities.require(
                locationRepository.findById(Objects.requireNonNull(edit.locationId(), "location")),
                "error.location.not_found")
            .getId();
    UUID methodId = edit.method() == null ? null : edit.method().id();
    UUID method =
        methodId == null
            ? null
            : Entities.require(
                    refiningMethodRepository.findById(methodId), "error.refining_method.not_found")
                .getId();
    OrgUnit unit = orgUnitStampingService.resolveOwnerMembershipTarget(user, origin.orgUnitId());
    UUID linked = origin.missionId();
    UUID mission = linked != null && missionRepository.existsById(linked) ? linked : null;
    RefineryExchangeEdit.Timer timer = edit.timer();
    RefineryExchangeEdit.Finances finances = edit.finances();
    refineryOrderRepository.insertOrder(
        new RefineryOrderRepository.RestoredRow(
            restore.id(),
            origin.createdAt(),
            owner,
            location,
            mission,
            timer == null ? null : timer.startedAt(),
            timer == null ? null : timer.durationMinutes(),
            method,
            finances == null ? null : RefineryOrderService.zeroToNull(finances.expenses()),
            finances == null ? null : RefineryOrderService.zeroToNull(finances.otherExpenses()),
            finances == null ? null : RefineryOrderService.zeroToNull(finances.oreSales()),
            RefineryOrderStatus.valueOf(Objects.requireNonNull(edit.status(), "status")).name(),
            origin.readyNotifiedAt(),
            unit == null ? null : unit.getId(),
            restore.gameDataVersion(),
            restore.financesVersion(),
            origin.createdClient(),
            origin.pickedUpAt(),
            origin.pickedUpClient()));
    if (edit.goods() != null) {
      reinsert(restore.id(), edit.goods());
    }
    RefineryOrder saved = requireOwn(owner, restore.id());
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_RESTORED,
        saved.getId(),
        RefineryOrders.label(saved),
        owner,
        AuditDetails.of("goods", saved.getGoods().size()));
    return RefineryExchangeReads.toRow(saved);
  }

  /**
   * Reads what an order holds beyond the fields a client writes.
   *
   * @param order the order
   * @return its creation, unit, mission, pickup and notice markers
   */
  private static @NotNull RefineryExchangeOrigin originOf(@NotNull RefineryOrder order) {
    RefineryOrderProvenance provenance = order.getProvenance();
    return new RefineryExchangeOrigin(
        order.getCreatedAt(),
        provenance.getCreatedClient(),
        order.getOwningOrgUnit() == null ? null : order.getOwningOrgUnit().getId(),
        order.getMissionId(),
        provenance.getPickedUpAt(),
        provenance.getPickedUpClient(),
        order.getReadyNotifiedAt());
  }

  /**
   * Writes the location, method, timer and status an edit carries.
   *
   * @param order the locked, managed order
   * @param edit the edit
   * @param byClient whether a client writes, which may not change a stored order's status
   * @return {@code true} when the start or the length of the run changed
   * @throws BadRequestException when a client would change the status of a stored order
   */
  private boolean applyGameData(
      @NotNull RefineryOrder order, @NotNull RefineryExchangeEdit edit, boolean byClient) {
    RefineryOrderStatus status =
        edit.status() == null ? null : RefineryOrderStatus.valueOf(edit.status());
    if (byClient && status != null && order.getStoredAt() != null && status != order.getStatus()) {
      throw new BadRequestException("error.refinery_order.stored_status_locked");
    }
    if (edit.locationId() != null) {
      order.setLocation(
          refinery(
              OfferedLocations.requireOfferedOrCurrent(
                  Entities.require(
                      locationRepository.findById(edit.locationId()), "error.location.not_found"),
                  order.getLocation())));
    }
    if (edit.method() != null) {
      setMethod(order, edit.method());
    }
    boolean runTimeChanged = false;
    RefineryExchangeEdit.Timer timer = edit.timer();
    if (timer != null) {
      runTimeChanged =
          !Objects.equals(order.getStartedAt(), timer.startedAt())
              || !Objects.equals(order.getDurationMinutes(), timer.durationMinutes());
      order.setStartedAt(timer.startedAt());
      order.setDurationMinutes(timer.durationMinutes());
      if (runTimeChanged) {
        order.setReadyNotifiedAt(null);
      }
    }
    if (status != null) {
      order.setStatus(status);
    }
    return runTimeChanged;
  }

  /**
   * Sets the order's refining method.
   *
   * @param order the order
   * @param method the method to set
   */
  private void setMethod(
      @NotNull RefineryOrder order, @NotNull RefineryExchangeEdit.Method method) {
    order.setRefiningMethod(
        method.id() == null
            ? null
            : Entities.require(
                refiningMethodRepository.findById(method.id()), "error.refining_method.not_found"));
  }

  /**
   * Sets the order's money fields, 0 stored as none.
   *
   * @param order the order
   * @param finances the values
   */
  private static void setFinances(
      @NotNull RefineryOrder order, @NotNull RefineryExchangeEdit.Finances finances) {
    order.setExpenses(RefineryOrderService.zeroToNull(finances.expenses()));
    order.setOtherExpenses(RefineryOrderService.zeroToNull(finances.otherExpenses()));
    order.setOreSales(RefineryOrderService.zeroToNull(finances.oreSales()));
  }

  /**
   * Makes the order hold exactly the given goods: known rows are changed in place, rows without an
   * id are added, and every other row is removed.
   *
   * @param order the managed order
   * @param goods every good the order holds afterwards
   * @return the goods whose id the order does not hold, to insert under that id after the flush
   * @throws BadRequestException when an input material is unknown or may not be refined
   */
  private @NotNull List<RefineryExchangeEdit.Good> setGoods(
      @NotNull RefineryOrder order, @NotNull List<RefineryExchangeEdit.Good> goods) {
    Map<UUID, RefineryGood> held = new HashMap<>();
    order.getGoods().forEach(good -> held.put(good.getId(), good));
    Set<UUID> kept = new HashSet<>();
    List<RefineryGood> added = new ArrayList<>();
    List<RefineryExchangeEdit.Good> reinserted = new ArrayList<>();
    for (RefineryExchangeEdit.Good good : goods) {
      Material input = input(good.inputMaterialId());
      if (good.id() != null) {
        kept.add(good.id());
        RefineryGood row = held.get(good.id());
        if (row == null) {
          reinserted.add(good);
          continue;
        }
        fill(row, good, input);
      } else {
        RefineryGood row = new RefineryGood();
        row.setRefineryOrder(order);
        fill(row, good, input);
        added.add(row);
      }
    }
    order.getGoods().removeIf(good -> !kept.contains(good.getId()));
    order.getGoods().addAll(added);
    return reinserted;
  }

  /**
   * Inserts the goods an undo brings back under their former ids.
   *
   * @param orderId the flushed or inserted order
   * @param goods the goods
   */
  private void reinsert(@NotNull UUID orderId, @NotNull List<RefineryExchangeEdit.Good> goods) {
    for (RefineryExchangeEdit.Good good : goods) {
      Material input = input(good.inputMaterialId());
      refineryOrderRepository.insertGood(
          Objects.requireNonNull(good.id()),
          orderId,
          input.getId(),
          good.inputQuantity(),
          RefineryExchangeRules.output(input).getId(),
          good.outputQuantity(),
          good.quality());
    }
  }

  /**
   * Copies a good's values onto its row.
   *
   * @param row the row
   * @param good the values
   * @param input the resolved input material
   */
  private static void fill(
      @NotNull RefineryGood row, @NotNull RefineryExchangeEdit.Good good, @NotNull Material input) {
    row.setInputMaterial(input);
    row.setOutputMaterial(RefineryExchangeRules.output(input));
    row.setInputQuantity(good.inputQuantity());
    row.setOutputQuantity(good.outputQuantity());
    row.setQuality(good.quality());
  }

  /**
   * Loads a good's input material.
   *
   * @param id the material
   * @return the material
   * @throws BadRequestException when it may not be refined
   */
  private @NotNull Material input(@NotNull UUID id) {
    Material material =
        Entities.require(materialRepository.findById(id), "error.material.input.not_found");
    if (!RefineryExchangeRules.refinable(material)) {
      throw new BadRequestException("error.refinery_order.input_material_not_raw");
    }
    return material;
  }

  /**
   * Refuses a location without a refinery.
   *
   * @param location the location
   * @return the same location
   * @throws BadRequestException when it hosts no refinery
   */
  private static @NotNull Location refinery(@NotNull Location location) {
    if (!RefineryExchangeRules.hostsRefinery(location)) {
      throw new BadRequestException("error.refinery_order.location_without_refinery");
    }
    return location;
  }

  /**
   * Loads one of the member's orders locked.
   *
   * @param owner the member
   * @param orderId the order
   * @return the locked order
   * @throws NotFoundException when the member owns no such order
   */
  private @NotNull RefineryOrder requireOwn(@NotNull UUID owner, @NotNull UUID orderId) {
    return Entities.require(
        refineryOrderRepository.lockById(orderId).filter(order -> owns(order, owner)),
        "error.refinery_order.not_found");
  }

  /**
   * Whether the member owns an order.
   *
   * @param order the order
   * @param owner the member
   * @return {@code true} when the order's owner is the member
   */
  private static boolean owns(@NotNull RefineryOrder order, @NotNull UUID owner) {
    return order.getOwner() != null && owner.equals(order.getOwner().getId());
  }
}

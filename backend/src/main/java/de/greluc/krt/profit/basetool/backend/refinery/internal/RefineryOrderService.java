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
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.refinery.api.MissionParticipantRequiredException;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeRules;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create, update and cancel for refinery orders; reads live in {@link RefineryOrderQueryService},
 * completion (store) in {@link RefineryOrderStoreService}.
 *
 * <p>Only the owner or a logistician may edit an order, checked per resource. Orders may only
 * target locations that host a refinery.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefineryOrderService {

  private final RefineryOrderRepository refineryOrderRepository;
  private final UserRepository userRepository;
  private final LocationRepository locationRepository;
  private final MissionRepository missionRepository;
  private final MissionParticipantRepository missionParticipantRepository;
  private final RefiningMethodRepository refiningMethodRepository;
  private final MaterialRepository materialRepository;
  private final OwnerScopeService ownerScopeService;
  private final OrgUnitStampingService orgUnitStampingService;
  private final AuditRecorder auditRecorder;

  /** Publishes the refinery notices after the commit (REQ-REFINERY-024). */
  private final ApplicationEventPublisher eventPublisher;

  private final UserService userService;

  /**
   * Persists a new refinery order owned by the given user, validating every referenced id and that
   * the location hosts a refinery. The order is always created {@code OPEN} and unstored; a
   * client-supplied status is ignored.
   *
   * @param userId owner id
   * @param order transient entity with shallow id-only references
   * @param owningOrgUnitId the org unit to stamp, which must be one of the owner's memberships;
   *     when {@code null}, the owner's single membership or none
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when any referenced id
   *     is unknown
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the location
   *     hosts no refinery or the org unit is not a membership of the owner
   * @throws MissionParticipantRequiredException when the owner does not take part in the given
   *     mission (REQ-SEC-042)
   */
  @Transactional
  public RefineryOrder createRefineryOrder(
      @NotNull UUID userId, @NotNull RefineryOrder order, UUID owningOrgUnitId) {
    return create(userId, order, owningOrgUnitId, false);
  }

  /**
   * Like {@link #createRefineryOrder}, for another member's order: the caller must be Logistician
   * of the stamped unit, an order without a unit is admin-only (REQ-SEC-005).
   *
   * @param userId the member who will own the order
   * @param order transient entity with shallow id-only references
   * @param owningOrgUnitId the org unit to stamp, as for {@link #createRefineryOrder}
   * @return the persisted order
   */
  @Transactional
  public RefineryOrder createRefineryOrderOnBehalf(
      @NotNull UUID userId, @NotNull RefineryOrder order, UUID owningOrgUnitId) {
    return create(userId, order, owningOrgUnitId, true);
  }

  private RefineryOrder create(
      @NotNull UUID userId, @NotNull RefineryOrder order, UUID owningOrgUnitId, boolean onBehalf) {
    order.setId(null);
    order.setVersion(null);
    order.setGameDataVersion(0L);
    order.setFinancesVersion(0L);
    order.setStatus(RefineryOrderStatus.OPEN);
    order.setStoredAt(null);
    order.setProvenance(RefineryOrderProvenance.createdInWeb(Instant.now()));

    User user = Entities.require(userRepository.findById(userId), "error.user.not_found");

    order.setOwner(user);
    order.setOwningOrgUnit(
        orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(user, owningOrgUnitId));
    if (onBehalf) {
      ownerScopeService.requireLogisticianInOwner(order.getOwningOrgUnit());
    }
    if (order.getLocation() != null && order.getLocation().getId() != null) {
      order.setLocation(
          OfferedLocations.require(
              locationRepository.findById(order.getLocation().getId()),
              "error.location.not_found"));
      validateLocationHasRefinery(order.getLocation());
    } else {
      throw new BadRequestException("error.refinery_order.location_required");
    }

    if (order.getMissionId() != null) {
      order.setMissionId(resolveMissionForOwner(order.getMissionId(), user));
    } else {
      order.setMissionId(null);
    }

    if (order.getRefiningMethod() != null && order.getRefiningMethod().getId() != null) {
      order.setRefiningMethod(
          Entities.require(
              refiningMethodRepository.findById(order.getRefiningMethod().getId()),
              "error.refining_method.not_found"));
    } else {
      order.setRefiningMethod(null);
    }

    if (order.getGoods() != null) {
      order.getGoods().forEach(good -> resolveGood(good, order));
    }

    if (order.getStartedAt() == null) {
      order.setStartedAt(Instant.now());
    }

    RefineryOrderEdits.setFinances(
        order, order.getExpenses(), order.getOtherExpenses(), order.getOreSales());

    RefineryOrder saved = refineryOrderRepository.save(order);
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_CREATED,
        saved.getId(),
        RefineryOrders.label(saved),
        saved.getOwner() != null ? saved.getOwner().getId() : null,
        RefineryOrders.summary(saved, saved.getStatus()));
    return saved;
  }

  /**
   * Loads the mission a refinery order is linked to and checks that the order's owner is a
   * participant of it (REQ-SEC-042).
   *
   * @param missionId the requested mission
   * @param owner the order's owner; {@code null} can never be linked
   * @return the id of the existing mission
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no mission has that
   *     id
   * @throws MissionParticipantRequiredException when the owner is not a participant of the mission
   */
  private UUID resolveMissionForOwner(@NotNull UUID missionId, @Nullable User owner) {
    Mission mission =
        Entities.require(missionRepository.findById(missionId), "error.mission.not_found");
    if (owner == null
        || owner.getId() == null
        || missionParticipantRepository
            .findByMissionIdAndUserId(missionId, owner.getId())
            .isEmpty()) {
      throw new MissionParticipantRequiredException();
    }
    return mission.getId();
  }

  /**
   * Row-locks a refinery order in the caller's transaction. A web write takes this lock before it
   * reads the order at all, so a writer that committed in the meantime is seen rather than reported
   * as a stale managed instance (REQ-REFINERY-029).
   *
   * @param orderId the order
   * @return the locked, managed order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no order has that
   *     id
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RefineryOrder lockRefineryOrder(@NotNull UUID orderId) {
    return RefineryOrders.requireLocked(refineryOrderRepository, orderId);
  }

  /**
   * Updates the named parts of a refinery order and leaves the others as they are
   * (REQ-REFINERY-029). Each section among the parts is checked against the counter the caller
   * echoed on {@code details} and raised by one; the mission link is not checked.
   *
   * @param userId the acting member, or the owner when a logistician acts
   * @param orderId the order
   * @param details the transient order holding the requested values and the echoed counters
   * @param parts the parts to write; an empty set writes nothing
   * @param isLogistician whether the caller is a logistician of the order's unit
   * @return the saved order
   * @throws AccessDeniedException when the caller is neither owner nor logistician
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the counter of a
   *     written section is absent or stale
   * @throws BadRequestException when the order is already stored and the status would change
   * @throws MissionParticipantRequiredException when the mission is changed to one the owner does
   *     not take part in (REQ-SEC-042)
   */
  @Transactional
  public RefineryOrder updateRefineryOrderParts(
      @NotNull UUID userId,
      @NotNull UUID orderId,
      @NotNull RefineryOrder details,
      @NotNull Set<RefineryOrderPart> parts,
      boolean isLogistician) {
    RefineryOrder order = RefineryOrders.requireLocked(refineryOrderRepository, orderId);
    for (RefineryOrderPart part : parts) {
      RefineryOrderSection section = part.section();
      if (section != null) {
        section.check(order, section.read(details));
      }
    }
    return update(userId, order, details, parts, isLogistician);
  }

  /**
   * Applies the named parts of {@code details} to the locked order and raises the counter of each
   * section written.
   *
   * @param userId the acting member, or the owner when a logistician acts
   * @param order the locked, managed order
   * @param details the transient order holding the requested values
   * @param parts the parts to write
   * @param isLogistician whether the caller is a logistician of the order's unit
   * @return the saved order
   */
  private RefineryOrder update(
      @NotNull UUID userId,
      @NotNull RefineryOrder order,
      @NotNull RefineryOrder details,
      @NotNull Set<RefineryOrderPart> parts,
      boolean isLogistician) {
    if (!isLogistician
        && (order.getOwner() == null
            || order.getOwner().getId() == null
            || !order.getOwner().getId().equals(userId))) {
      throw new AccessDeniedException("Access denied: You do not own this refinery order");
    }
    if (parts.isEmpty()) {
      return order;
    }

    final RefineryOrderStatus previousStatus = order.getStatus();
    final boolean runTimeChanged =
        parts.contains(RefineryOrderPart.GAME_DATA) && applyGameData(order, details);
    if (parts.contains(RefineryOrderPart.MISSION_LINK)) {
      applyMissionLink(order, details);
    }
    if (parts.contains(RefineryOrderPart.FINANCES)) {
      RefineryOrderEdits.setFinances(
          order, details.getExpenses(), details.getOtherExpenses(), details.getOreSales());
    }
    for (RefineryOrderPart part : parts) {
      RefineryOrderSection section = part.section();
      if (section != null) {
        section.advance(order);
      }
    }
    order.getProvenance().recordWebChange(Instant.now());

    RefineryOrder saved = refineryOrderRepository.save(order);
    RefineryOrderEdits.clearReadyNoticeIfDue(eventPublisher, saved, runTimeChanged);
    announceByOther(
        saved, userId, saved.getStatus() == RefineryOrderStatus.CANCELED ? "CANCELED" : "UPDATED");
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_UPDATED,
        saved.getId(),
        RefineryOrders.label(saved),
        saved.getOwner() != null ? saved.getOwner().getId() : null,
        RefineryOrders.summary(saved, previousStatus + "->" + saved.getStatus()));
    return saved;
  }

  /**
   * Links, re-links or unlinks the order's mission: a reference with an id links that mission, a
   * reference without an id leaves the link as it is, no reference unlinks it.
   *
   * @param order the locked, managed order
   * @param details the transient order holding the requested link
   * @throws MissionParticipantRequiredException when the owner does not take part in the mission
   */
  private void applyMissionLink(@NotNull RefineryOrder order, @NotNull RefineryOrder details) {
    UUID requestedMissionId = details.getMissionId();
    if (requestedMissionId != null) {
      if (!requestedMissionId.equals(order.getMissionId())) {
        order.setMissionId(resolveMissionForOwner(requestedMissionId, order.getOwner()));
      }
    } else if (!details.isMissionReferenceWithoutId()) {
      order.setMissionId(null);
    }
  }

  /**
   * Writes the game-data section: location, method, timer, status and goods. A changed timer clears
   * the ready marker so the run is announced again (REQ-REFINERY-023).
   *
   * @param order the locked, managed order
   * @param details the transient order holding the requested values
   * @return {@code true} when the start or the duration changed
   * @throws BadRequestException when the order is already stored and the status would change
   */
  private boolean applyGameData(@NotNull RefineryOrder order, @NotNull RefineryOrder details) {
    RefineryOrderEdits.requireStatusUnlocked(order, details.getStatus());
    if (details.getLocation() != null && details.getLocation().getId() != null) {
      order.setLocation(
          RefineryOrderEdits.offeredOrCurrent(
              locationRepository, order, details.getLocation().getId()));
      validateLocationHasRefinery(order.getLocation());
    }

    if (details.getRefiningMethod() != null && details.getRefiningMethod().getId() != null) {
      order.setRefiningMethod(
          Entities.require(
              refiningMethodRepository.findById(details.getRefiningMethod().getId()),
              "error.refining_method.not_found"));
    } else if (details.getRefiningMethod() == null) {
      order.setRefiningMethod(null);
    }

    final boolean runTimeChanged =
        RefineryOrderEdits.setRunTime(
            order,
            details.getStartedAt() != null ? details.getStartedAt() : Instant.now(),
            details.getDurationMinutes());
    if (details.getStatus() != null) {
      order.setStatus(details.getStatus());
    }

    if (details.getGoods() != null) {
      setGoods(order, details.getGoods());
    }
    return runTimeChanged;
  }

  /**
   * Makes the order hold exactly the given goods: a good with the id of a held row changes that row
   * in place and keeps its id, a good without an id is added, and every other row is removed.
   *
   * @param order the locked, managed order
   * @param goods every good the order holds afterwards, transient with id-only material references
   * @throws BadRequestException when a good names an id the order does not hold, or names one twice
   */
  private void setGoods(@NotNull RefineryOrder order, @NotNull Set<RefineryGood> goods) {
    Map<UUID, RefineryGood> held = new HashMap<>();
    order.getGoods().forEach(good -> held.put(good.getId(), good));
    Set<UUID> kept = new HashSet<>();
    List<RefineryGood> added = new ArrayList<>();
    for (RefineryGood good : goods) {
      resolveGood(good, order);
      if (good.getId() == null) {
        added.add(good);
        continue;
      }
      RefineryGood row = held.get(good.getId());
      if (row == null || !kept.add(good.getId())) {
        throw new BadRequestException("error.refinery_order.good_unknown");
      }
      row.setInputMaterial(good.getInputMaterial());
      row.setInputQuantity(good.getInputQuantity());
      row.setOutputMaterial(good.getOutputMaterial());
      row.setOutputQuantity(good.getOutputQuantity());
      row.setQuality(good.getQuality());
    }
    order.getGoods().removeIf(good -> !kept.contains(good.getId()));
    order.getGoods().addAll(added);
  }

  /**
   * Resolves and validates a good's input and output materials and sets its order back-reference.
   *
   * <p>The input material must be {@code RAW} or a manual raw material; the output must match or
   * derive from its refined material.
   *
   * @param good the transient good with id-only material references; mutated in place
   * @param order the owning order wired as the good's back-reference
   */
  private void resolveGood(RefineryGood good, RefineryOrder order) {
    if (good.getInputMaterial() != null && good.getInputMaterial().getId() != null) {
      Material inMat =
          Entities.require(
              materialRepository.findById(good.getInputMaterial().getId()),
              "error.material.input.not_found");

      if (!RefineryExchangeRules.refinable(inMat)) {
        throw new IllegalArgumentException(
            "Refinery goods input must be of type RAW. Material '"
                + inMat.getName()
                + "' is "
                + inMat.getType());
      }
      good.setInputMaterial(inMat);

      if (good.getOutputMaterial() != null && good.getOutputMaterial().getId() != null) {
        Material outMat =
            Entities.require(
                materialRepository.findById(good.getOutputMaterial().getId()),
                "error.material.output.not_found");

        if (inMat.getRefinedMaterial() != null
            && !outMat.getId().equals(inMat.getRefinedMaterial().getId())) {
          throw new IllegalArgumentException(
              "Output material must match the refined material of the input material.");
        }

        good.setOutputMaterial(outMat);
      } else {
        good.setOutputMaterial(RefineryExchangeRules.output(inMat));
      }
    } else {
      throw new BadRequestException("error.refinery_order.input_material_required");
    }
    good.setRefineryOrder(order);
  }

  /**
   * Cancels (soft-deletes) a refinery order. Same owner-vs-logistician gate as {@link
   * #updateRefineryOrderParts}.
   *
   * @throws AccessDeniedException when the caller is not allowed to delete this order
   */
  @Transactional
  public void deleteRefineryOrder(
      @NotNull UUID userId, @NotNull UUID orderId, boolean isLogistician) {
    RefineryOrder order = RefineryOrders.requireLocked(refineryOrderRepository, orderId);

    if (!isLogistician
        && (order.getOwner() == null
            || order.getOwner().getId() == null
            || !order.getOwner().getId().equals(userId))) {
      throw new AccessDeniedException("Access denied: You do not own this refinery order");
    }

    final RefineryOrderStatus previousStatus = order.getStatus();
    order.setStatus(RefineryOrderStatus.CANCELED);
    RefineryOrderSection.GAME_DATA.advance(order);
    order.getProvenance().recordWebChange(Instant.now());
    refineryOrderRepository.save(order);
    eventPublisher.publishEvent(RefineryNotices.readyCleared(order.getId()));
    announceByOther(order, userId, "CANCELED");
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_CANCELED,
        order.getId(),
        RefineryOrders.label(order),
        order.getOwner() != null ? order.getOwner().getId() : null,
        AuditDetails.of("previousStatus", previousStatus));
  }

  /**
   * Tells the owner that somebody else changed or cancelled their order (REQ-REFINERY-024). Nothing
   * happens when the owner acted.
   *
   * @param order the order
   * @param actorId the acting member
   * @param changeCode {@code UPDATED} or {@code CANCELED}
   */
  private void announceByOther(
      @NotNull RefineryOrder order, @NotNull UUID actorId, @NotNull String changeCode) {
    if (order.getOwner() == null || actorId.equals(order.getOwner().getId())) {
      return;
    }
    eventPublisher.publishEvent(
        RefineryNotices.changedByOther(
            order.getId(),
            order.getOwner().getId(),
            order.getLocation() == null ? null : order.getLocation().getName(),
            changeCode,
            userService.currentActor()));
  }

  /**
   * Rejects a location whose derived {@code hasRefineryTerminal} flag is false, the same signal the
   * picker uses (REQ-REFINERY-020).
   *
   * <p>Reads the parent through the association, never a query, so it cannot trigger an auto-flush
   * mid-update.
   *
   * @param location the order's chosen location
   * @throws IllegalArgumentException when the location hosts no live refinery terminal
   */
  private static void validateLocationHasRefinery(Location location) {
    if (!RefineryExchangeRules.hostsRefinery(location)) {
      throw new IllegalArgumentException("Selected location does not have a refinery.");
    }
  }
}

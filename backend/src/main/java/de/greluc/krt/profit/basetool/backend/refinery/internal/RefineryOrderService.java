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
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.refinery.api.MissionParticipantRequiredException;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
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
    order.setStatus(RefineryOrderStatus.OPEN);
    order.setStoredAt(null);

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

    order.setExpenses(zeroToNull(order.getExpenses()));
    order.setOtherExpenses(zeroToNull(order.getOtherExpenses()));
    order.setOreSales(zeroToNull(order.getOreSales()));

    RefineryOrder saved = refineryOrderRepository.save(order);
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_CREATED,
        saved.getId(),
        RefineryOrders.label(saved),
        saved.getOwner() != null ? saved.getOwner().getId() : null,
        AuditDetails.of("location", RefineryOrders.locationName(saved))
            .with("method", RefineryOrders.methodName(saved))
            .with("goods", saved.getGoods() != null ? saved.getGoods().size() : 0)
            .with("status", saved.getStatus()));
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

  @Contract("null -> null")
  @Nullable
  private static Double zeroToNull(@Nullable Double value) {
    if (value == null) {
      return null;
    }
    return value == 0.0 ? null : value;
  }

  /**
   * Updates a refinery order, replacing its goods; only the owner or a logistician may do so.
   *
   * @throws AccessDeniedException when the caller is neither owner nor logistician
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   * @throws BadRequestException when the order is already stored and the status would change
   * @throws MissionParticipantRequiredException when the mission is changed to one the owner does
   *     not take part in (REQ-SEC-042)
   */
  @Transactional
  public RefineryOrder updateRefineryOrder(
      @NotNull UUID userId,
      @NotNull UUID orderId,
      @NotNull RefineryOrder details,
      boolean isLogistician) {
    RefineryOrder order = RefineryOrders.require(refineryOrderRepository, orderId);

    OptimisticLock.checkOptionalClient(
        order.getVersion(), details.getVersion(), RefineryOrder.class, orderId);

    if (!isLogistician
        && (order.getOwner() == null
            || order.getOwner().getId() == null
            || !order.getOwner().getId().equals(userId))) {
      throw new AccessDeniedException("Access denied: You do not own this refinery order");
    }

    if (order.getStoredAt() != null
        && details.getStatus() != null
        && details.getStatus() != order.getStatus()) {
      throw new BadRequestException("error.refinery_order.stored_status_locked");
    }

    if (details.getLocation() != null && details.getLocation().getId() != null) {
      order.setLocation(
          OfferedLocations.requireOfferedOrCurrent(
              Entities.require(
                  locationRepository.findById(details.getLocation().getId()),
                  "error.location.not_found"),
              order.getLocation()));
      validateLocationHasRefinery(order.getLocation());
    }

    UUID requestedMissionId = details.getMissionId();
    if (requestedMissionId != null) {
      if (!requestedMissionId.equals(order.getMissionId())) {
        order.setMissionId(resolveMissionForOwner(requestedMissionId, order.getOwner()));
      }
    } else if (!details.isMissionReferenceWithoutId()) {
      order.setMissionId(null);
    }

    if (details.getRefiningMethod() != null && details.getRefiningMethod().getId() != null) {
      order.setRefiningMethod(
          Entities.require(
              refiningMethodRepository.findById(details.getRefiningMethod().getId()),
              "error.refining_method.not_found"));
    } else if (details.getRefiningMethod() == null) {
      order.setRefiningMethod(null);
    }

    final Instant previousStart = order.getStartedAt();
    final Long previousDuration = order.getDurationMinutes();
    order.setStartedAt(details.getStartedAt() != null ? details.getStartedAt() : Instant.now());
    order.setDurationMinutes(details.getDurationMinutes());
    final boolean runTimeChanged =
        !Objects.equals(previousStart, order.getStartedAt())
            || !Objects.equals(previousDuration, order.getDurationMinutes());
    if (runTimeChanged) {
      order.setReadyNotifiedAt(null);
    }
    order.setExpenses(zeroToNull(details.getExpenses()));
    order.setOtherExpenses(zeroToNull(details.getOtherExpenses()));
    order.setOreSales(zeroToNull(details.getOreSales()));
    final RefineryOrderStatus previousStatus = order.getStatus();
    if (details.getStatus() != null) {
      order.setStatus(details.getStatus());
    }

    if (details.getGoods() != null) {
      order.getGoods().clear();
      details
          .getGoods()
          .forEach(good -> {
            resolveGood(good, order);
            order.getGoods().add(good);
          });
    }

    RefineryOrder saved = refineryOrderRepository.save(order);
    if (runTimeChanged
        || saved.getStatus() == RefineryOrderStatus.COMPLETED
        || saved.getStatus() == RefineryOrderStatus.CANCELED) {
      eventPublisher.publishEvent(RefineryNotices.readyCleared(saved.getId()));
    }
    announceByOther(
        saved, userId, saved.getStatus() == RefineryOrderStatus.CANCELED ? "CANCELED" : "UPDATED");
    auditRecorder.record(
        AuditEventType.REFINERY_ORDER_UPDATED,
        saved.getId(),
        RefineryOrders.label(saved),
        saved.getOwner() != null ? saved.getOwner().getId() : null,
        AuditDetails.of("location", RefineryOrders.locationName(saved))
            .with("method", RefineryOrders.methodName(saved))
            .with("goods", saved.getGoods() != null ? saved.getGoods().size() : 0)
            .with("status", previousStatus + "->" + saved.getStatus()));
    return saved;
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

      if (inMat.getType() != MaterialType.RAW
          && !Boolean.TRUE.equals(inMat.getIsManualRawMaterial())) {
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
        if (inMat.getRefinedMaterial() != null) {
          good.setOutputMaterial(inMat.getRefinedMaterial());
        } else {
          good.setOutputMaterial(inMat);
        }
      }
    } else {
      throw new BadRequestException("error.refinery_order.input_material_required");
    }
    good.setRefineryOrder(order);
  }

  /**
   * Cancels (soft-deletes) a refinery order. Same owner-vs-logistician gate as {@link
   * #updateRefineryOrder}.
   *
   * @throws AccessDeniedException when the caller is not allowed to delete this order
   */
  @Transactional
  public void deleteRefineryOrder(
      @NotNull UUID userId, @NotNull UUID orderId, boolean isLogistician) {
    RefineryOrder order = RefineryOrders.require(refineryOrderRepository, orderId);

    if (!isLogistician
        && (order.getOwner() == null
            || order.getOwner().getId() == null
            || !order.getOwner().getId().equals(userId))) {
      throw new AccessDeniedException("Access denied: You do not own this refinery order");
    }

    final RefineryOrderStatus previousStatus = order.getStatus();
    order.setStatus(RefineryOrderStatus.CANCELED);
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
  private void validateLocationHasRefinery(Location location) {
    boolean hasRefinery = false;
    if (location.getCity() != null
        && Boolean.TRUE.equals(location.getCity().getHasRefineryTerminal())) {
      hasRefinery = true;
    } else if (location.getSpaceStation() != null
        && Boolean.TRUE.equals(location.getSpaceStation().getHasRefineryTerminal())) {
      hasRefinery = true;
    }
    if (!hasRefinery) {
      throw new IllegalArgumentException("Selected location does not have a refinery.");
    }
  }
}

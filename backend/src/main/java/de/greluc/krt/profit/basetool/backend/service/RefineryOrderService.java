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
import de.greluc.krt.profit.basetool.backend.exception.MissionParticipantRequiredException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.RefineryGood;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.RefineryYield;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreItemDto;
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryYieldRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD and completion (store) for refinery orders.
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
  private final InventoryItemRepository inventoryItemRepository;
  private final JobOrderRepository jobOrderRepository;
  private final RefineryYieldRepository refineryYieldRepository;
  private final OwnerScopeService ownerScopeService;
  private final AuditService auditService;

  /**
   * Owner-scoped paged list with optional status filter.
   *
   * @param userId owner id
   * @param statuses optional status filter; null/empty means "all statuses"
   * @param pageable page request
   * @return paged orders owned by the user
   */
  public Page<RefineryOrder> getMyRefineryOrders(
      @NotNull UUID userId, List<RefineryOrderStatus> statuses, @NotNull Pageable pageable) {
    if (statuses != null && !statuses.isEmpty()) {
      return refineryOrderRepository.findByOwnerIdAndStatusIn(userId, statuses, pageable);
    }
    return refineryOrderRepository.findByOwnerId(userId, pageable);
  }

  /**
   * Lists a target user's refinery orders within the caller's org-unit scope, for the cross-user
   * oversight endpoint.
   *
   * <p>The {@code @PreAuthorize} gate only checks a shared org unit; the per-row scope filter here
   * hides orders stamped to units the caller does not share.
   *
   * @param targetUserId the user whose orders to list; never {@code null}
   * @param pageable page request
   * @return the in-scope page of the target user's orders
   */
  public Page<RefineryOrder> getUserRefineryOrdersScoped(
      @NotNull UUID targetUserId, @NotNull Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return refineryOrderRepository.findByOwnerIdScoped(
        targetUserId,
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds(),
        pageable);
  }

  /**
   * Sums the yield of the user's {@code OPEN} and {@code IN_PROGRESS} refinery orders into one SCU
   * total per (output material, quality), for the craftability calculation.
   *
   * @param userId the owning user; never {@code null}
   * @return one slice per (output material, quality), with the summed SCU yield; never {@code null}
   */
  @NotNull
  public List<OwnedStockSlice> getOwnedOpenRefineryYieldSlices(@NotNull UUID userId) {
    List<RefineryOrder> orders =
        refineryOrderRepository.findOwnedWithGoodsByStatusIn(
            userId, List.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS));
    Map<UUID, Map<Integer, Double>> pooled = new HashMap<>();
    for (RefineryOrder order : orders) {
      if (order.getGoods() == null) {
        continue;
      }
      for (RefineryGood good : order.getGoods()) {
        Material material = good.getOutputMaterial();
        if (material == null
            || material.getId() == null
            || good.getOutputQuantity() == null
            || good.getQuality() == null) {
          continue;
        }
        double scu = toScu(good.getOutputQuantity(), material.getQuantityType());
        if (scu <= 0.0d) {
          continue;
        }
        pooled
            .computeIfAbsent(material.getId(), k -> new HashMap<>())
            .merge(good.getQuality(), scu, Double::sum);
      }
    }
    List<OwnedStockSlice> slices = new ArrayList<>();
    pooled.forEach(
        (materialId, byQuality) ->
            byQuality.forEach(
                (quality, scu) -> slices.add(new OwnedStockSlice(materialId, quality, scu))));
    return slices;
  }

  /**
   * Converts a refinery good's output quantity from units to SCU (100 units per SCU for SCU
   * commodities; others unchanged).
   *
   * @param outputQuantityUnits the good's output quantity in units
   * @param quantityType the output material's quantity type (may be {@code null})
   * @return the equivalent amount in SCU
   */
  private static double toScu(int outputQuantityUnits, QuantityType quantityType) {
    if (quantityType == QuantityType.SCU) {
      return outputQuantityUnits / 100.0d;
    }
    return outputQuantityUnits;
  }

  /**
   * Lists the refinery orders linked to a mission within the caller's org-unit scope.
   *
   * @param missionId mission id
   * @return the in-scope orders linked to the mission
   */
  public List<RefineryOrder> getMissionRefineryOrdersScoped(@NotNull UUID missionId) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return refineryOrderRepository.findByMissionIdScoped(
        missionId, scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds());
  }

  /**
   * Lists the orders of the given mission that are owned by the given user.
   *
   * @param missionId mission id
   * @param userId owner id
   * @return matching orders
   */
  public List<RefineryOrder> getMissionRefineryOrders(
      @NotNull UUID missionId, @NotNull UUID userId) {
    return refineryOrderRepository.findByMissionIdAndOwnerId(missionId, userId);
  }

  /**
   * Squadron-wide paged list with optional status filter (admin/logistician view).
   *
   * @param statuses optional status filter
   * @param pageable page request
   * @return paged orders across all users
   */
  public Page<RefineryOrder> getAllRefineryOrders(
      List<RefineryOrderStatus> statuses, @NotNull Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    if (statuses != null && !statuses.isEmpty()) {
      return refineryOrderRepository.findByStatusInScoped(
          statuses,
          scope.adminAllScope(),
          scope.activeOrgUnitId(),
          scope.memberOrgUnitIds(),
          pageable);
    }
    return refineryOrderRepository.findAllScoped(
        scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds(), pageable);
  }

  /**
   * Convenience overload without status filter.
   *
   * @param pageable page request
   * @return paged orders across all users
   */
  public Page<RefineryOrder> getAllRefineryOrders(@NotNull Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return refineryOrderRepository.findAllScoped(
        scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds(), pageable);
  }

  /**
   * Returns the order.
   *
   * @param id refinery order primary key
   * @return the order
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  public RefineryOrder getRefineryOrder(@NotNull UUID id) {
    return Entities.require(refineryOrderRepository.findById(id), "error.refinery_order.not_found");
  }

  /**
   * Persists a new refinery order owned by the given user, validating every referenced id and that
   * the location hosts a refinery.
   *
   * @param userId owner id
   * @param order transient entity with shallow id-only references
   * @param owningOrgUnitId the org unit to stamp, which must be one of the owner's memberships;
   *     when {@code null}, the owner's single membership or none
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when any referenced
   *     id is unknown
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the location
   *     hosts no refinery or the org unit is not a membership of the owner
   * @throws MissionParticipantRequiredException when the owner does not take part in the given
   *     mission (REQ-SEC-042)
   */
  @Transactional
  public RefineryOrder createRefineryOrder(
      @NotNull UUID userId, @NotNull RefineryOrder order, UUID owningOrgUnitId) {
    order.setId(null);
    order.setVersion(null);

    User user = Entities.require(userRepository.findById(userId), "error.user.not_found");

    order.setOwner(user);
    order.setOwningOrgUnit(
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, owningOrgUnitId));

    if (order.getLocation() != null && order.getLocation().getId() != null) {
      order.setLocation(
          Entities.require(
              locationRepository.findById(order.getLocation().getId()),
              "error.location.not_found"));
      validateLocationHasRefinery(order.getLocation());
    } else {
      throw new BadRequestException("error.refinery_order.location_required");
    }

    if (order.getMission() != null && order.getMission().getId() != null) {
      order.setMission(resolveMissionForOwner(order.getMission().getId(), user));
    } else {
      order.setMission(null);
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
    auditService.record(
        AuditEventType.REFINERY_ORDER_CREATED,
        saved.getId(),
        refineryLabel(saved),
        saved.getOwner() != null ? saved.getOwner().getId() : null,
        AuditDetails.of("location", locationName(saved))
            .with("method", methodName(saved))
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
   * @return the managed mission
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no mission has
   *     that id
   * @throws MissionParticipantRequiredException when the owner is not a participant of the mission
   */
  private Mission resolveMissionForOwner(@NotNull UUID missionId, @Nullable User owner) {
    Mission mission =
        Entities.require(missionRepository.findById(missionId), "error.mission.not_found");
    if (owner == null
        || owner.getId() == null
        || missionParticipantRepository
            .findByMissionIdAndUserId(missionId, owner.getId())
            .isEmpty()) {
      throw new MissionParticipantRequiredException();
    }
    return mission;
  }

  @Contract("null -> null")
  @Nullable
  private static Double zeroToNull(Double value) {
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
   * @throws MissionParticipantRequiredException when the mission is changed to one the owner does
   *     not take part in (REQ-SEC-042)
   */
  @Transactional
  public RefineryOrder updateRefineryOrder(
      @NotNull UUID userId,
      @NotNull UUID orderId,
      @NotNull RefineryOrder details,
      boolean isLogistician) {
    RefineryOrder order = getRefineryOrder(orderId);

    OptimisticLock.checkOptionalClient(
        order.getVersion(), details.getVersion(), RefineryOrder.class, orderId);

    if (!isLogistician
        && (order.getOwner() == null
            || order.getOwner().getId() == null
            || !order.getOwner().getId().equals(userId))) {
      throw new AccessDeniedException("Access denied: You do not own this refinery order");
    }

    if (details.getLocation() != null && details.getLocation().getId() != null) {
      order.setLocation(
          Entities.require(
              locationRepository.findById(details.getLocation().getId()),
              "error.location.not_found"));
      validateLocationHasRefinery(order.getLocation());
    }

    Mission requestedMission = details.getMission();
    UUID requestedMissionId = requestedMission != null ? requestedMission.getId() : null;
    if (requestedMissionId != null) {
      Mission currentMission = order.getMission();
      if (currentMission == null || !requestedMissionId.equals(currentMission.getId())) {
        order.setMission(resolveMissionForOwner(requestedMissionId, order.getOwner()));
      }
    } else if (requestedMission == null) {
      order.setMission(null);
    }

    if (details.getRefiningMethod() != null && details.getRefiningMethod().getId() != null) {
      order.setRefiningMethod(
          Entities.require(
              refiningMethodRepository.findById(details.getRefiningMethod().getId()),
              "error.refining_method.not_found"));
    } else if (details.getRefiningMethod() == null) {
      order.setRefiningMethod(null);
    }

    order.setStartedAt(details.getStartedAt() != null ? details.getStartedAt() : Instant.now());
    order.setDurationMinutes(details.getDurationMinutes());
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
          .forEach(
              good -> {
                resolveGood(good, order);
                order.getGoods().add(good);
              });
    }

    RefineryOrder saved = refineryOrderRepository.save(order);
    auditService.record(
        AuditEventType.REFINERY_ORDER_UPDATED,
        saved.getId(),
        refineryLabel(saved),
        saved.getOwner() != null ? saved.getOwner().getId() : null,
        AuditDetails.of("location", locationName(saved))
            .with("method", methodName(saved))
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
    RefineryOrder order = getRefineryOrder(orderId);

    if (!isLogistician
        && (order.getOwner() == null
            || order.getOwner().getId() == null
            || !order.getOwner().getId().equals(userId))) {
      throw new AccessDeniedException("Access denied: You do not own this refinery order");
    }

    final RefineryOrderStatus previousStatus = order.getStatus();
    order.setStatus(RefineryOrderStatus.CANCELED);
    refineryOrderRepository.save(order);
    auditService.record(
        AuditEventType.REFINERY_ORDER_CANCELED,
        order.getId(),
        refineryLabel(order),
        order.getOwner() != null ? order.getOwner().getId() : null,
        AuditDetails.of("previousStatus", previousStatus));
  }

  /**
   * Completes a refinery order by booking each output material as an inventory row.
   *
   * <p>Rows marked {@code personal} get no earmark (REQ-INV-035); a non-logistician may only book
   * onto themselves (REQ-SEC-039).
   *
   * @throws AccessDeniedException when the caller is neither owner nor logistician, or when a
   *     non-logistician names another user as an item's receiving stock owner
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the order or any
   *     referenced id is unknown
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the order is
   *     already stored, has no output goods, or an item combines {@code personal} with a job order
   */
  @Transactional
  public void storeRefineryOrder(
      @NotNull UUID userId,
      @NotNull UUID orderId,
      @NotNull RefineryOrderStoreDto dto,
      boolean isLogistician) {
    RefineryOrder order = getRefineryOrder(orderId);

    if (order.getStatus() == RefineryOrderStatus.COMPLETED) {
      throw new BadRequestException("error.refinery_order.already_stored");
    }

    if (!isLogistician
        && (order.getOwner() == null
            || order.getOwner().getId() == null
            || !order.getOwner().getId().equals(userId))) {
      throw new AccessDeniedException("Access denied: You do not own this refinery order");
    }

    final RefineryOrderStatus previousStatus = order.getStatus();

    for (RefineryOrderStoreItemDto itemDto : dto.items()) {
      final Material mat =
          Entities.require(
              materialRepository.findById(itemDto.materialId()),
              () -> "Material not found: " + itemDto.materialId());

      final Location loc =
          Entities.require(
              locationRepository.findById(itemDto.locationId()),
              () -> "Location not found: " + itemDto.locationId());

      final UUID targetUserId =
          itemDto.userId() != null
              ? itemDto.userId()
              : (order.getOwner() != null ? order.getOwner().getId() : null);
      if (targetUserId != null
          && !userId.equals(targetUserId)
          && !ownerScopeService.canManageUserInventory(targetUserId)) {
        throw new AccessDeniedException(
            "Access denied: You are not allowed to store refinery output for other users");
      }
      if (targetUserId == null && !isLogistician) {
        throw new AccessDeniedException(
            "Access denied: You are not allowed to store refinery output for other users");
      }

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
                jobOrderRepository.findById(itemDto.jobOrderId()),
                () -> "JobOrder not found: " + itemDto.jobOrderId());
      }

      final OrgUnit owningOrgUnit =
          ownerScopeService.resolveOrgUnitForPickerOutputNullable(
              assignee, itemDto.owningOrgUnitId());

      String incomingNote = StringNormalization.trimToNull(itemDto.note());

      InventoryItem item = new InventoryItem();
      item.setUser(assignee);
      item.setOwningOrgUnit(owningOrgUnit);
      item.setMaterial(mat);
      item.setLocation(loc);
      item.setQuality(itemDto.quality());
      item.setAmount(InventoryItem.roundToScuScale(itemDto.amount()));
      item.setNote(incomingNote);
      item.setPersonal(personal);
      if (jobOrder != null) {
        InventoryAllocations.addJobOrder(item, jobOrder, item.getAmount(), false);
      }
      if (!personal && order.getMission() != null) {
        InventoryAllocations.addMission(item, order.getMission(), item.getAmount());
      }

      inventoryItemRepository.save(item);
      auditService.record(
          AuditEventType.INVENTORY_RECEIVED_FROM_REFINERY,
          item.getId(),
          mat.getName() + " @ " + loc.getName(),
          assignee.getId(),
          AuditDetails.of("source", "REFINERY")
              .with("refineryOrder", orderId)
              .with("material", mat.getName())
              .with("amount", item.getAmount())
              .with("q", itemDto.quality())
              .with("personal", personal)
              .with("jobOrder", jobOrder != null ? "#" + jobOrder.getDisplayId() : "-"));

      updateGoodOutputQuantity(order, itemDto);
    }

    order.setStatus(RefineryOrderStatus.COMPLETED);
    refineryOrderRepository.save(order);
    auditService.record(
        AuditEventType.REFINERY_ORDER_STORED,
        order.getId(),
        refineryLabel(order),
        order.getOwner() != null ? order.getOwner().getId() : null,
        AuditDetails.of("items", dto.items().size())
            .with("status", previousStatus + "->COMPLETED"));
  }

  /**
   * Builds the non-personal audit subject label {@code <method> · <location>} for a refinery order
   * (REQ-AUDIT-001).
   *
   * @param order the refinery order
   * @return the {@code <method> · <location>} label
   */
  @NotNull
  private static String refineryLabel(RefineryOrder order) {
    return methodName(order) + " · " + locationName(order);
  }

  /**
   * The order's location name for an audit payload, or {@code -} when unset.
   *
   * @param order the refinery order
   * @return the location name or {@code -}
   */
  private static String locationName(@NotNull RefineryOrder order) {
    return order.getLocation() != null ? order.getLocation().getName() : "-";
  }

  /**
   * The order's refining-method name for an audit payload, or {@code none} when unset.
   *
   * @param order the refinery order
   * @return the method name or {@code none}
   */
  private static String methodName(@NotNull RefineryOrder order) {
    return order.getRefiningMethod() != null ? order.getRefiningMethod().getName() : "none";
  }

  /**
   * Writes the user's final stored amount back into the matching {@link
   * de.greluc.krt.profit.basetool.backend.model.RefineryGood}, converting SCU back to units (×100).
   */
  private void updateGoodOutputQuantity(RefineryOrder order, RefineryOrderStoreItemDto itemDto) {
    if (order.getGoods() == null
        || itemDto == null
        || itemDto.materialId() == null
        || itemDto.amount() == null) {
      return;
    }
    RefineryGood target = findGood(order, itemDto.materialId(), itemDto.quality());
    if (target == null) {
      target = findGood(order, itemDto.materialId(), null);
    }
    if (target != null) {
      RefineryGood good = target;
      double amount = itemDto.amount();
      String quantityTypeName =
          good.getOutputMaterial().getQuantityType() != null
              ? good.getOutputMaterial().getQuantityType().name()
              : null;
      long rawNew;
      if ("SCU".equals(quantityTypeName)) {
        rawNew = Math.round(amount * 100.0d);
      } else {
        rawNew = Math.round(amount);
      }
      int clamped = (int) Math.max(1L, Math.min(rawNew, Integer.MAX_VALUE));
      good.setOutputQuantity(clamped);
    }
  }

  /**
   * Finds the good a store item belongs to.
   *
   * @param order the order whose goods to search
   * @param materialId the output material the item books
   * @param quality the grade the item carries, or {@code null} to match on the material alone
   * @return the good, or {@code null} when none matches
   */
  @Nullable
  private RefineryGood findGood(RefineryOrder order, UUID materialId, Integer quality) {
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
   * Returns the UEX yield bonus in percent per material for the refinery at {@code location};
   * negative values are a malus, and an empty map means no known yield data.
   *
   * @param location the order's chosen location, may be {@code null}
   * @return map keyed by material UUID, never {@code null}
   */
  @NotNull
  public Map<UUID, Integer> getYieldBonusByMaterialForLocation(Location location) {
    if (location == null) {
      return Map.of();
    }
    String cityName = location.getCity() != null ? location.getCity().getName() : null;
    String stationName =
        location.getSpaceStation() != null ? location.getSpaceStation().getName() : null;
    if (cityName == null && stationName == null) {
      return Map.of();
    }
    Map<UUID, Integer> result = new HashMap<>();
    for (RefineryYield yield : refineryYieldRepository.findAllForLocation(cityName, stationName)) {
      if (yield.getMaterial() == null || yield.getMaterial().getId() == null) {
        continue;
      }
      result.putIfAbsent(yield.getMaterial().getId(), yield.getYieldBonus());
    }
    return result;
  }

  /**
   * Resolves {@code locationId} and delegates to {@link
   * #getYieldBonusByMaterialForLocation(Location)}; a {@code null} or unknown id yields an empty
   * map.
   *
   * @param locationId target location id; may be {@code null}
   * @return per-material yield bonus map for the location, never {@code null}
   */
  public Map<UUID, Integer> getYieldBonusByMaterialForLocationId(UUID locationId) {
    if (locationId == null) {
      return Map.of();
    }
    return locationRepository
        .findById(locationId)
        .map(this::getYieldBonusByMaterialForLocation)
        .orElseGet(Map::of);
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

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

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
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
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryYieldRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD plus completion (store) for refinery orders.
 *
 * <p>A refinery order tracks a player's ore-to-refined-good run at a specific terminal: input
 * materials and quantities, refining method, expected output, expenses and sale proceeds. The
 * service enforces "owner can edit / logistician can edit anyone" at the method boundary because
 * the rule is per-resource (the role-only check in {@code @PreAuthorize} can't see the order's
 * owner). The {@code store} operation finalizes a completed order by creating inventory items for
 * each output material and clearing the order's open status.
 *
 * <p>Location validation: refinery orders can only target locations that actually host a refinery.
 * The check runs at create + update time so a stale location pick surfaces as a 400 with a
 * localized message instead of silently producing an unreachable order.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefineryOrderService {

  private final RefineryOrderRepository refineryOrderRepository;
  private final UserRepository userRepository;
  private final LocationRepository locationRepository;
  private final MissionRepository missionRepository;
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
      @NotNull UUID userId,
      List<de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus> statuses,
      @NotNull Pageable pageable) {
    if (statuses != null && !statuses.isEmpty()) {
      return refineryOrderRepository.findByOwnerIdAndStatusIn(userId, statuses, pageable);
    }
    return refineryOrderRepository.findByOwnerId(userId, pageable);
  }

  /**
   * Lists a target user's refinery orders for the cross-user oversight endpoint {@code GET
   * /api/v1/refinery-orders/users/{userId}}, filtered to the caller's effective org-unit scope.
   *
   * <p>Security (finding SEC-01): the per-user {@code @PreAuthorize} gate {@link
   * OwnerScopeService#canViewUserRefineryOrders(UUID)} is only a coarse user-level pre-check — it
   * passes when the caller shares <em>any one</em> of the (up to two, REQ-ORG-017) org units the
   * target belongs to. The precise per-row filtering happens here via {@link
   * RefineryOrderRepository#findByOwnerIdScoped} and the caller's {@link ScopePredicate}, so a
   * logistician who shares only one of a multi-Staffel target's units never sees the target's
   * orders stamped to the other, foreign unit. Refinery is a strict-staffel aggregate with no
   * cross-squadron escape, mirroring {@link #getMissionRefineryOrdersScoped} (BAC-004). The
   * self-service "my orders" list ({@code GET /my-orders}) stays owner-scoped and unfiltered via
   * {@link #getMyRefineryOrders(UUID, List, Pageable)} — a user always sees all of their own orders
   * (REQ-ORG-011 owner escape).
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
   * Pools the yield of the caller's not-yet-completed refinery orders into one SCU total per
   * (output material, quality) pair, for the optional refinery fold-in of the blueprint
   * craftability calculation (#781). "Not yet completed or cancelled" maps to status {@code OPEN} +
   * {@code IN_PROGRESS}; strictly owner-scoped to {@code userId}. The {@link
   * RefineryGood#getOutputQuantity() outputQuantity} is tracked in units, so SCU commodities are
   * converted (100 units = 1 SCU, see {@link #updateGoodOutputQuantity}) before pooling so the
   * slices merge with the inventory slices.
   *
   * @param userId the owning user; never {@code null}
   * @return one slice per (output material, quality), with the summed SCU yield; never {@code null}
   */
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
   * Converts a refinery good's {@code outputQuantity} (tracked in units) into SCU. For SCU
   * commodities 100 units make 1 SCU (the inverse of the ×100 in {@link
   * #updateGoodOutputQuantity}); non-SCU materials are returned unchanged. Mirrors the refinery
   * store path so the craftability fold-in measures the same SCU the user would receive on
   * completion.
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
   * Lists the refinery orders linked to a mission that fall within the caller's org-unit scope.
   * Used by the mission detail page's refinery roll-up for logistician+ viewers. Refinery is a
   * strict-staffel aggregate, so the result is filtered through the caller's {@link ScopePredicate}
   * (admin all-scope sees every order; an admin pinned to a squadron and a non-admin logistician
   * see only their own org units' orders) by {@link RefineryOrderRepository#findByMissionIdScoped}.
   *
   * <p>Without this scope filter (finding BAC-004) a logistician of one squadron could read another
   * squadron's refinery financials by enumerating that squadron's public missions: Mission's
   * cross-staffel visibility escape ({@code is_internal = false}) exposes the mission id, but it
   * does NOT extend to the refinery orders attached to it - those stay private to their owning
   * squadron.
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
   * Lists the orders that BOTH belong to the given mission AND are owned by the given user. Used by
   * the participant-scoped mission detail view so participants only see their own refinery lines.
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
      List<de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus> statuses,
      @NotNull Pageable pageable) {
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
    return refineryOrderRepository
        .findById(id)
        .orElseThrow(
            () ->
                new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                    "error.refinery_order.not_found"));
  }

  /**
   * Persists a new refinery order owned by the given user. Resolves and validates every shallow
   * reference in the payload (location, mission, refining method, materials in goods) and rejects
   * with 404 / 400 if any id is missing or unknown. The location must host a refinery — picking a
   * regular city is rejected explicitly.
   *
   * @param userId owner id
   * @param order transient entity with shallow id-only references
   * @param owningOrgUnitId optional R5.d picker output: the {@link
   *     de.greluc.krt.profit.basetool.backend.model.OrgUnit} on whose stock the new order should
   *     land. When {@code null}, the service auto-stamps the owner's single org-unit membership, or
   *     — if the owner has no membership at all — leaves the order ownerless ({@code owningOrgUnit
   *     == null}, visible only to the owner). When non-null, must point at an org unit the order
   *     owner is a member of — {@link OwnerScopeService#resolveOrgUnitForPickerOutputNullable}
   *     performs the validation and rejects unknown / foreign selections with {@link
   *     de.greluc.krt.profit.basetool.backend.exception.BadRequestException}.
   * @return the persisted order
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when any referenced
   *     id is unknown
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the chosen
   *     location does not host a refinery, or the picker output is not a valid membership of the
   *     order owner
   */
  @Transactional
  public RefineryOrder createRefineryOrder(
      @NotNull UUID userId, @NotNull RefineryOrder order, UUID owningOrgUnitId) {
    // Mass-assignment guard (audit H-2): the create path must never honour a client-supplied id or
    // version. RefineryOrderDto is shared with the update path and carries both fields, and
    // RefineryOrderMapper.toEntity copies them onto the transient entity (it ignores only owner).
    // Because AbstractEntity.isNew() is id == null, a non-null id would route
    // JpaRepository.save() through EntityManager.merge() (UPSERT) and let a caller overwrite and
    // re-own an existing order, bypassing the per-resource canEditRefineryOrder gate. Resetting
    // both forces a clean INSERT — the LocationMapper.stripServerManaged contract applied at the
    // single create choke point so every create endpoint (my-orders + users/{userId}) is covered.
    order.setId(null);
    order.setVersion(null);

    User user =
        userRepository
            .findById(userId)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "error.user.not_found"));

    order.setOwner(user);
    order.setOwningOrgUnit(
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, owningOrgUnitId));

    if (order.getLocation() != null && order.getLocation().getId() != null) {
      order.setLocation(
          locationRepository
              .findById(order.getLocation().getId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "error.location.not_found")));
      validateLocationHasRefinery(order.getLocation());
    } else {
      throw new de.greluc.krt.profit.basetool.backend.exception.BadRequestException(
          "error.refinery_order.location_required");
    }

    if (order.getMission() != null && order.getMission().getId() != null) {
      order.setMission(
          missionRepository
              .findById(order.getMission().getId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "error.mission.not_found")));
    } else {
      order.setMission(null);
    }

    if (order.getRefiningMethod() != null && order.getRefiningMethod().getId() != null) {
      order.setRefiningMethod(
          refiningMethodRepository
              .findById(order.getRefiningMethod().getId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "error.refining_method.not_found")));
    } else {
      order.setRefiningMethod(null);
    }

    // Handle Goods relationships
    if (order.getGoods() != null) {
      order.getGoods().forEach(good -> resolveGood(good, order));
    }

    if (order.getStartedAt() == null) {
      order.setStartedAt(java.time.Instant.now());
    }

    // Monetary fields (expenses, otherExpenses, oreSales) are optional. Both null and 0
    // are treated semantically as "not set" and persisted as null, so the frontend never
    // has to distinguish between "empty" and "0" and the columns stay cleanly empty in
    // the DB when the user has not entered a value. The profit calculation
    // (see RefineryOrder#getProfit) already treats null as 0.
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

  private static Double zeroToNull(Double value) {
    if (value == null) {
      return null;
    }
    return value == 0.0 ? null : value;
  }

  /**
   * Updates an existing refinery order. Enforces "must be owner OR logistician" explicitly (the
   * role-only {@code @PreAuthorize} cannot see per-resource ownership). Validates the
   * optimistic-lock version and the same shallow-reference resolution as {@link
   * #createRefineryOrder}. The goods list is replaced wholesale; the old rows are orphan-removed.
   *
   * @throws AccessDeniedException when the caller is neither owner nor logistician
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     version is stale
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
          locationRepository
              .findById(details.getLocation().getId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "error.location.not_found")));
      validateLocationHasRefinery(order.getLocation());
    }

    if (details.getMission() != null && details.getMission().getId() != null) {
      order.setMission(
          missionRepository
              .findById(details.getMission().getId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "error.mission.not_found")));
    } else if (details.getMission() == null) {
      order.setMission(null);
    }

    if (details.getRefiningMethod() != null && details.getRefiningMethod().getId() != null) {
      order.setRefiningMethod(
          refiningMethodRepository
              .findById(details.getRefiningMethod().getId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "error.refining_method.not_found")));
    } else if (details.getRefiningMethod() == null) {
      order.setRefiningMethod(null);
    }

    order.setStartedAt(
        details.getStartedAt() != null ? details.getStartedAt() : java.time.Instant.now());
    order.setDurationMinutes(details.getDurationMinutes());
    // Money fields: 0 is treated as "not set" and persisted as null (see createRefineryOrder).
    order.setExpenses(zeroToNull(details.getExpenses()));
    order.setOtherExpenses(zeroToNull(details.getOtherExpenses()));
    order.setOreSales(zeroToNull(details.getOreSales()));
    final RefineryOrderStatus previousStatus = order.getStatus();
    if (details.getStatus() != null) {
      order.setStatus(details.getStatus());
    }

    // Update goods
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
   * Resolves and validates one refinery good's material references against the catalog and wires
   * its back-reference to the owning order, leaving collection membership to the caller. Shared
   * verbatim by the create and update paths, which differ only in whether the good is already in
   * the order's set (create) or the set is cleared and each good re-added (update). The input
   * material must resolve and be {@code RAW} (or a manual raw material); the output material either
   * matches the input's refined material or is derived from it.
   *
   * @param good the transient good carrying id-only input/output material references; resolved and
   *     mutated in place
   * @param order the owning order wired as the good's back-reference
   */
  private void resolveGood(RefineryGood good, RefineryOrder order) {
    if (good.getInputMaterial() != null && good.getInputMaterial().getId() != null) {
      de.greluc.krt.profit.basetool.backend.model.Material inMat =
          materialRepository
              .findById(good.getInputMaterial().getId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "error.material.input.not_found"));

      if (inMat.getType() != de.greluc.krt.profit.basetool.backend.model.MaterialType.RAW
          && !Boolean.TRUE.equals(inMat.getIsManualRawMaterial())) {
        throw new IllegalArgumentException(
            "Refinery goods input must be of type RAW. Material '"
                + inMat.getName()
                + "' is "
                + inMat.getType());
      }
      good.setInputMaterial(inMat);

      if (good.getOutputMaterial() != null && good.getOutputMaterial().getId() != null) {
        de.greluc.krt.profit.basetool.backend.model.Material outMat =
            materialRepository
                .findById(good.getOutputMaterial().getId())
                .orElseThrow(
                    () ->
                        new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                            "error.material.output.not_found"));

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
      throw new de.greluc.krt.profit.basetool.backend.exception.BadRequestException(
          "error.refinery_order.input_material_required");
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
    order.setStatus(de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus.CANCELED);
    refineryOrderRepository.save(order);
    auditService.record(
        AuditEventType.REFINERY_ORDER_CANCELED,
        order.getId(),
        refineryLabel(order),
        order.getOwner() != null ? order.getOwner().getId() : null,
        AuditDetails.of("previousStatus", previousStatus));
  }

  /**
   * Completes a refinery order by creating inventory items for each output material.
   *
   * <p>The refinery rounding mode setting controls how fractional output quantities are rounded
   * (see {@code SystemSettingService}). Each output material becomes one inventory row owned by the
   * configured recipient (typically the order's owner, optionally redirected to a different user /
   * job order from the store form).
   *
   * <p>A row the store form marks {@code personal} (REQ-INV-035) lands as the receiver's private
   * stock and, per the standing "personal stock carries no allocation" invariant ({@code
   * InventoryItemService#assertNotPersonal}), receives <strong>no</strong> earmark: the per-item
   * job order is a contradictory user choice and is rejected with a {@code BadRequestException},
   * while the refinery order's automatic mission earmark is simply not applied (marking the batch
   * personal is exactly the act of taking it out of the mission pool).
   *
   * <p>A non-logistician may only book the output onto <strong>themselves</strong>: the per-item
   * {@code userId} chooses the receiving stock owner, so it is gated separately from the
   * order-ownership check (REQ-SEC-039), mirroring {@code
   * InventoryItemService#createInventoryItem}.
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

    if (order.getStatus()
        == de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus.COMPLETED) {
      throw new IllegalStateException("Refinery order is already completed and stored.");
    }

    if (!isLogistician
        && (order.getOwner() == null
            || order.getOwner().getId() == null
            || !order.getOwner().getId().equals(userId))) {
      throw new AccessDeniedException("Access denied: You do not own this refinery order");
    }

    final RefineryOrderStatus previousStatus = order.getStatus();

    for (RefineryOrderStoreItemDto itemDto : dto.items()) {
      final de.greluc.krt.profit.basetool.backend.model.Material mat =
          materialRepository
              .findById(itemDto.materialId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "Material not found: " + itemDto.materialId()));

      final de.greluc.krt.profit.basetool.backend.model.Location loc =
          locationRepository
              .findById(itemDto.locationId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "Location not found: " + itemDto.locationId()));

      // REQ-SEC-039: the per-item userId names the RECEIVING inventory owner, so it decides whose
      // ledger the output lands in. The order-ownership check above does not cover that — it
      // constrains which order may be stored, not who the stock is booked for — so without this
      // guard a member storing their OWN order could redirect arbitrary amounts of any material
      // into any other member's stock (shared, or with `personal` into their private stock) and
      // leave INVENTORY_RECEIVED_FROM_REFINERY audit rows attributed to that member. Only a
      // logistician books on behalf of someone else, mirroring the Einbuchen path's guard verbatim
      // (InventoryItemService#createInventoryItem): the same "create an InventoryItem for another
      // user" operation must not be privileged in one entry point and open in the other.
      //
      // Checked on the REQUESTED id and BEFORE the lookup, for the same reason createInventoryItem
      // does it in that order: resolving first would answer "user not found" vs "access denied" to
      // an unauthorised caller, turning the endpoint into a user-existence oracle.
      final UUID targetUserId =
          itemDto.userId() != null
              ? itemDto.userId()
              : (order.getOwner() != null ? order.getOwner().getId() : null);
      // The flat ROLE_LOGISTICIAN that used to stand here is org-unit-less (the OR-union over ALL
      // of the caller's memberships), so it authorised booking into ANY member of ANY Staffel -
      // shared or, with `personal`, private. REQ-SEC-039 closed the caller-vs-owner axis and never
      // looked at the org-unit axis. canManageUserInventory is the same gate the Einbuchen path now
      // uses for the same operation, evaluated per item because the receiver is per item.
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
            userRepository
                .findById(itemDto.userId())
                .orElseThrow(
                    () ->
                        new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                            "User not found: " + itemDto.userId()));
      } else {
        assignee = order.getOwner();
      }

      // REQ-INV-035: the store dialog may mark an output row as the receiver's private stock. A
      // personal row never carries an allocation (the standing assertNotPersonal invariant), so the
      // contradictory per-item job-order pick is rejected rather than silently dropped — mirroring
      // the Einbuchen (InventoryItemService#createInventoryItem) and item-production book-in
      // guards.
      final boolean personal = Boolean.TRUE.equals(itemDto.personal());
      if (personal && itemDto.jobOrderId() != null) {
        throw new de.greluc.krt.profit.basetool.backend.exception.BadRequestException(
            "Personal items cannot be assigned to a mission or job order");
      }

      de.greluc.krt.profit.basetool.backend.model.JobOrder jobOrder = null;
      if (itemDto.jobOrderId() != null) {
        jobOrder =
            jobOrderRepository
                .findById(itemDto.jobOrderId())
                .orElseThrow(
                    () ->
                        new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                            "JobOrder not found: " + itemDto.jobOrderId()));
      }

      // Resolve the assignee's owning org-unit pool up front — the eighth identity dimension — so
      // the freshly created row is stamped with that pool. The store dialog carries a per-item
      // owning-org-unit picker (#596 follow-up to the SK §5.5 stamping wave), pre-filled with the
      // order's own org unit: the resolver auto-stamps for a single-membership assignee, yields an
      // ownerless personal row (owningOrgUnit == null, V132) for a membershipless one, and honours
      // an
      // explicit pick that is one of the assignee's memberships or — epic #692 Phase 4 /
      // REQ-ORG-016,
      // when the current caller differs from the assignee — a unit the caller may edit (create-on-
      // behalf). It 400s a multi-membership assignee with no pick, or a pick foreign to BOTH the
      // assignee's memberships and the caller's editable scope. See InventoryItemService and
      // OwnerScopeService.resolveStampedOrgUnit.
      final de.greluc.krt.profit.basetool.backend.model.OrgUnit owningOrgUnit =
          ownerScopeService.resolveOrgUnitForPickerOutputNullable(
              assignee, itemDto.owningOrgUnitId());

      // Why: The amount the user enters in the store dialog is the authoritative
      // amount (it overrides the originally calculated output amount of the refinery
      // order). The note is propagated to the resulting InventoryItem so the user can
      // attach storage remarks directly to the inventory item.
      String incomingNote = StringNormalization.trimToNull(itemDto.note());

      // Append-only: every stored refinery output becomes its own row and is never folded into an
      // existing identical stack. Rows that share a stack identity are grouped only for display
      // (group-on-read in aggregateInventoryItems), so no read-add-write race exists and each
      // entry keeps its own note and provenance.
      InventoryItem item = new InventoryItem();
      item.setUser(assignee);
      item.setOwningOrgUnit(owningOrgUnit);
      item.setMaterial(mat);
      item.setLocation(loc);
      item.setQuality(itemDto.quality());
      item.setAmount(InventoryItem.roundToScuScale(itemDto.amount()));
      item.setNote(incomingNote);
      item.setPersonal(personal);
      // Variante C (REQ-INV-027): the deposited row earmarks its full amount to the order it was
      // refined for and to the refinery order's mission (each as one allocation); an unset
      // dimension
      // stays empty. A personal row (REQ-INV-035) takes neither: the job-order pick was already
      // rejected above, and the order's mission earmark is dropped because marking the batch
      // personal is precisely the act of taking it out of the mission pool.
      if (jobOrder != null) {
        InventoryAllocations.addJobOrder(item, jobOrder, item.getAmount(), false);
      }
      if (!personal && order.getMission() != null) {
        InventoryAllocations.addMission(item, order.getMission(), item.getAmount());
      }

      inventoryItemRepository.save(item);
      // Cross-domain inventory effect: each stored refinery output creates one warehouse row. No
      // @Modifying(clearAutomatically) runs in this loop, so the audit insert is loop-safe. The id
      // is generated onto the managed entity by save() (GenerationType.UUID), so read it off item.
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

      // Write the adjusted amount back into the refinery order so that the
      // actually stored output amount is documented there as well.
      // Match by output material; if multiple identical goods exist, the
      // first not-yet-updated entry is taken.
      updateGoodOutputQuantity(order, itemDto);
    }

    order.setStatus(de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus.COMPLETED);
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
   * Composes the audit subject label for a refinery order. The order has no name/number field, so
   * the deletion-proof identity snapshot is the <strong>non-personal</strong> composite {@code
   * <method> · <location>}. REQ-AUDIT-001 limits {@code subjectLabel} to a non-personal display
   * label (no person names) — the order owner is captured separately as the audit row's target user
   * ({@code targetUserId}), so embedding the owner's handle here would be redundant and would
   * durably retain a third party's name in the append-only trail. Started-at and other facts go
   * into the event details.
   *
   * @param order the refinery order
   * @return the {@code <method> · <location>} label
   */
  private static String refineryLabel(RefineryOrder order) {
    return methodName(order) + " · " + locationName(order);
  }

  /**
   * The order's location name for an audit payload, or {@code -} when unset.
   *
   * @param order the refinery order
   * @return the location name or {@code -}
   */
  private static String locationName(RefineryOrder order) {
    return order.getLocation() != null ? order.getLocation().getName() : "-";
  }

  /**
   * The order's refining-method name for an audit payload, or {@code none} when unset.
   *
   * @param order the refinery order
   * @return the method name or {@code none}
   */
  private static String methodName(RefineryOrder order) {
    return order.getRefiningMethod() != null ? order.getRefiningMethod().getName() : "none";
  }

  /**
   * Writes the user's final storage amount back into the associated {@link
   * de.greluc.krt.profit.basetool.backend.model.RefineryGood}. This persists the manual correction
   * of the output amount in the refinery order itself (e.g. when the actual refinery output
   * deviates from the forecast). For SCU materials the SCU input is converted back into units
   * (x100) so the {@code outputQuantity} field stays uniformly tracked in units.
   */
  private void updateGoodOutputQuantity(RefineryOrder order, RefineryOrderStoreItemDto itemDto) {
    if (order.getGoods() == null
        || itemDto == null
        || itemDto.materialId() == null
        || itemDto.amount() == null) {
      return;
    }
    // Material AND grade. One run can yield the same material at two qualities, and matching on the
    // material alone put every item of that material on the first of them: the later item overwrote
    // the earlier one and the second good was never updated. The grade-less fallback keeps callers
    // that send no quality working exactly as before.
    de.greluc.krt.profit.basetool.backend.model.RefineryGood target =
        findGood(order, itemDto.materialId(), itemDto.quality());
    if (target == null) {
      target = findGood(order, itemDto.materialId(), null);
    }
    if (target != null) {
      de.greluc.krt.profit.basetool.backend.model.RefineryGood good = target;
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
      // Respect @Min(1) on outputQuantity: 0 would be an invalid value.
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
  private de.greluc.krt.profit.basetool.backend.model.RefineryGood findGood(
      RefineryOrder order, java.util.UUID materialId, Integer quality) {
    for (de.greluc.krt.profit.basetool.backend.model.RefineryGood good : order.getGoods()) {
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
   * Returns a {@code materialId → yieldBonusPercent} map for the refinery sitting at {@code
   * location}. The value semantics come straight from UEX: a positive integer is a bonus, a
   * negative integer is a malus, both expressed in percent (5 = +5%, -3 = -3%). An empty map means
   * "no UEX yield data is known for this location" (either the location was never picked, or the
   * UEX universe sync has not yet matched the location's city/space-station name to a terminal that
   * has yield rows).
   *
   * <p>Used by the controller to enrich {@code RefineryGoodDto.yieldBonusPercent} on outbound
   * payloads and to feed the detail page's reactive bonus display in the form. Same lookup runs for
   * every order detail render and every order write, so the underlying query is bounded by the
   * number of yield rows at one terminal (small).
   *
   * @param location the order's chosen location, may be {@code null}
   * @return map keyed by material UUID, never {@code null}
   */
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
   * Convenience overload that resolves {@code locationId} via {@link LocationRepository} and
   * delegates to {@link #getYieldBonusByMaterialForLocation(Location)}. Returns an empty map when
   * the id is {@code null} or unknown — the caller (typically the AJAX endpoint that refreshes the
   * detail page's yield badges after the user picks a new refinery) treats "unknown location" the
   * same as "no yield data", so a 404 would only force redundant error handling on the client.
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
   * Rejects a chosen location that hosts no refinery, keyed on the derived {@code
   * hasRefineryTerminal} flag — exactly the signal {@link
   * LocationRepository#findLocationsWithRefinery()} builds the picker from, so the gate accepts
   * precisely what the form offered (REQ-REFINERY-020).
   *
   * <p>Deliberately NOT keyed on UEX's parent-level {@code hasRefinery} claim, which this check
   * used to read: that claim both misses real refineries (MIC-L5, ARC-L4, Patch City) and invents
   * ones that do not exist (four People's Service Stations). The flag is recomputed from the live
   * {@code type = 'refinery'} terminals by {@code
   * UexUniverseSyncService.reconcileRefineryTerminalFlags()}.
   *
   * <p>Reads the already-loaded parent in memory rather than issuing a query, and that is load
   * bearing: a query here would auto-flush a transaction that is midway through rewriting the order
   * and its goods, so the goods {@code clear()} + re-add would race its own freshly written rows
   * and fail with {@code ObjectOptimisticLockingFailureException}.
   *
   * @param location the order's chosen location
   * @throws IllegalArgumentException when the location hosts no live refinery terminal
   */
  private void validateLocationHasRefinery(
      de.greluc.krt.profit.basetool.backend.model.Location location) {
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

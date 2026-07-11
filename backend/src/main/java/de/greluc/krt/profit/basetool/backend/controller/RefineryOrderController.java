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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.mapper.RefineryOrderMapper;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreDto;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.RefineryOrderService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for refinery orders. Two endpoint families: user endpoints under {@code /} that
 * derive the caller from the JWT, and admin/officer endpoints under {@code /all} and {@code
 * /users/{userId}} that take the target user from the URL. Logistician role lifts the owner
 * constraint on the user endpoints (a logistician can edit anyone's order via the user endpoint as
 * well — convenient for the in-app order screen).
 */
@RestController
@RequestMapping("/api/v1/refinery-orders")
@RequiredArgsConstructor
@Transactional
public class RefineryOrderController {
  private final RefineryOrderService refineryOrderService;
  private final UserService userService;
  private final RefineryOrderMapper mapper;
  private final AuthHelperService authHelperService;

  /**
   * Lists the calling user's own refinery orders. Optional status filter.
   *
   * @return paged refinery-order list DTOs
   */
  @GetMapping("/my-orders")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public PageResponse<RefineryOrderListDto> getMyRefineryOrders(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false)
          List<de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus> status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            Set.of("startedAt", "durationMinutes", "expenses", "id"),
            "startedAt");
    Page<RefineryOrder> p =
        refineryOrderService.getMyRefineryOrders(
            userService.getUserIdFromJwt(jwt), status, pageable);
    return PageResponse.of(p.map(mapper::toListDto));
  }

  /**
   * Fetches a single refinery order. Read access is gated entirely by the {@code @PreAuthorize}
   * SpEL ({@code @ownerScopeService.canSeeRefineryOrder(#id)}); the body just maps the resolved
   * order to its DTO, enriched with the per-material yield bonus for the order's location.
   *
   * @return the refinery-order DTO
   */
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeRefineryOrder(#id)")
  @Transactional(readOnly = true)
  public RefineryOrderDto getRefineryOrder(@PathVariable @NotNull UUID id) {
    RefineryOrder order = refineryOrderService.getRefineryOrder(id);
    return mapper.toDto(
        order, refineryOrderService.getYieldBonusByMaterialForLocation(order.getLocation()));
  }

  /**
   * Returns the UEX-derived refinery bonus/malus per input material for the refinery at {@code
   * locationId}, as a {@code materialId → percent} map (positive = bonus, negative = malus, {@code
   * 0} = explicit baseline). Used by the refinery-order detail page to refresh the yield badge
   * client-side when the user changes the input material or the picked location. An unknown or null
   * location id returns an empty map (200, not 404) because the client treats "unknown location"
   * identically to "no yield data" — see {@link
   * RefineryOrderService#getYieldBonusByMaterialForLocationId(UUID)}.
   *
   * @param locationId the chosen refinery location
   * @return per-material yield bonus map
   */
  @GetMapping("/locations/{locationId}/yields")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public Map<UUID, Integer> getYieldsForLocation(@PathVariable @NotNull UUID locationId) {
    return refineryOrderService.getYieldBonusByMaterialForLocationId(locationId);
  }

  /**
   * Lists refinery orders linked to a mission. Logisticians see every order within their own
   * org-unit scope (an admin without an active pin sees all; an admin pinned to a squadron and a
   * non-admin logistician see only their scope); regular users see only their own orders. The
   * mission detail page is rendered for both roles.
   *
   * <p>Security (finding BAC-004): the logistician branch is org-unit-scoped via {@link
   * RefineryOrderService#getMissionRefineryOrdersScoped}. Refinery is a strict-staffel aggregate
   * with no cross-squadron escape, so although a non-internal mission is visible to other
   * squadrons, the refinery financials attached to it are not - a logistician cannot read a foreign
   * squadron's refinery orders by enumerating that squadron's public missions.
   *
   * @return list of refinery-order list DTOs
   */
  @GetMapping("/mission/{missionId}")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<RefineryOrderListDto> getMissionRefineryOrders(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @NotNull UUID missionId) {
    boolean isLogistician = authHelperService.isLogisticianOrAbove();
    if (isLogistician) {
      return refineryOrderService.getMissionRefineryOrdersScoped(missionId).stream()
          .map(mapper::toListDto)
          .toList();
    }
    return refineryOrderService
        .getMissionRefineryOrders(missionId, userService.getUserIdFromJwt(jwt))
        .stream()
        .map(mapper::toListDto)
        .toList();
  }

  /**
   * Creates a refinery order. Logisticians can specify any owner via {@code orderDto.owner()};
   * regular users always own their orders themselves (any owner override is ignored).
   *
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  public RefineryOrderDto createMyRefineryOrder(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid @NotNull RefineryOrderDto orderDto) {
    UUID userId = userService.getUserIdFromJwt(jwt);
    if (orderDto.owner() != null && orderDto.owner().id() != null) {
      if (authHelperService.isLogisticianOrAbove()) {
        userId = orderDto.owner().id();
      }
    }
    RefineryOrder saved =
        refineryOrderService.createRefineryOrder(
            userId, mapper.toEntity(orderDto), orderDto.owningOrgUnitId());
    return mapper.toDto(
        saved, refineryOrderService.getYieldBonusByMaterialForLocation(saved.getLocation()));
  }

  /**
   * Updates a refinery order. Non-logistician callers must be the owner (HTTP-boundary check
   * because the rule is per-resource and {@code @PreAuthorize} can't see the order's owner).
   * Logisticians may edit any order; in that case the {@code owner} can also be reassigned via the
   * DTO.
   *
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditRefineryOrder(#id)")
  public RefineryOrderDto updateMyRefineryOrder(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull RefineryOrderDto orderDto) {
    UUID callerId = userService.getUserIdFromJwt(jwt);
    RefineryOrder existing = refineryOrderService.getRefineryOrder(id);

    boolean isLogistician = authHelperService.isLogisticianOrAbove();

    UUID targetUserId = callerId;
    if (isLogistician) {
      if (orderDto.owner() != null && orderDto.owner().id() != null) {
        targetUserId = orderDto.owner().id();
      } else if (existing.getOwner() != null) {
        targetUserId = existing.getOwner().getId();
      }
    } else {
      // Normal user: must be the owner
      if (existing.getOwner() == null || !existing.getOwner().getId().equals(callerId)) {
        throw new AccessDeniedException("Access denied: You do not own this refinery order");
      }
    }

    RefineryOrder saved =
        refineryOrderService.updateRefineryOrder(
            targetUserId, id, mapper.toEntity(orderDto), isLogistician);
    return mapper.toDto(
        saved, refineryOrderService.getYieldBonusByMaterialForLocation(saved.getLocation()));
  }

  /**
   * Cancels (soft-deletes) a refinery order. Same owner-vs-logistician rule as {@link
   * #updateMyRefineryOrder}.
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditRefineryOrder(#id)")
  public void deleteMyRefineryOrder(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @NotNull UUID id) {
    refineryOrderService.deleteRefineryOrder(
        userService.getUserIdFromJwt(jwt), id, authHelperService.isLogisticianOrAbove());
  }

  /**
   * Completes a refinery order by storing the refined output as inventory items at the chosen
   * target location.
   */
  @PostMapping("/{id}/store")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditRefineryOrder(#id)")
  public void storeMyRefineryOrder(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull RefineryOrderStoreDto dto) {
    refineryOrderService.storeRefineryOrder(
        userService.getUserIdFromJwt(jwt), id, dto, authHelperService.isLogisticianOrAbove());
  }

  // Admin/Officer endpoints

  /**
   * Squadron-wide refinery-order list. Open to all authenticated callers (read-only).
   *
   * @return paged refinery-order list DTOs
   */
  @GetMapping("/all")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public PageResponse<RefineryOrderListDto> getAllRefineryOrders(
      @RequestParam(required = false)
          List<de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus> status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            Set.of("startedAt", "durationMinutes", "expenses", "id"),
            "startedAt");
    Page<RefineryOrder> p = refineryOrderService.getAllRefineryOrders(status, pageable);
    return PageResponse.of(p.map(mapper::toListDto));
  }

  /**
   * Lists a specific user's refinery orders. Two-stage org-unit scoping (epic #800 / PR #808 +
   * finding SEC-01): the {@code @PreAuthorize} gate {@code canViewUserRefineryOrders} is a coarse
   * user-level pre-check (admin, the target user themselves, or a logistician whose strict org-unit
   * scope covers <em>any one</em> unit the target belongs to), and the returned page is then
   * filtered per-row to the caller's effective scope by {@link
   * RefineryOrderService#getUserRefineryOrdersScoped}. Because a member may belong to up to two
   * Staffeln (REQ-ORG-017), the gate alone is not enough — without the scoped query a logistician
   * sharing only one of the target's Staffeln would read the target's orders stamped to the other,
   * foreign Staffel. Refinery is a strict-staffel aggregate, so the list never returns a row the
   * per-order {@code canSeeRefineryOrder} gate would individually deny.
   *
   * @param userId target user id
   * @return paged refinery-order list DTOs
   */
  @GetMapping("/users/{userId}")
  @PreAuthorize(
      "hasRole('"
          + Roles.LOGISTICIAN
          + "') and @ownerScopeService.canViewUserRefineryOrders(#userId)")
  @Transactional(readOnly = true)
  public PageResponse<RefineryOrderListDto> getUserRefineryOrders(
      @PathVariable @NotNull UUID userId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            Set.of("startedAt", "durationMinutes", "expenses", "id"),
            "startedAt");
    Page<RefineryOrder> p = refineryOrderService.getUserRefineryOrdersScoped(userId, pageable);
    return PageResponse.of(p.map(mapper::toListDto));
  }

  /**
   * Creates a refinery order on behalf of a target user. Logistician scoped to the caller's org
   * units: an admin, the target user themselves, or a logistician whose strict org-unit scope
   * covers a unit the target user belongs to (epic #800 / PR #808 security review — no longer
   * org-wide).
   *
   * @return the persisted DTO
   */
  @PostMapping("/users/{userId}")
  @PreAuthorize(
      "hasRole('"
          + Roles.LOGISTICIAN
          + "') and @ownerScopeService.canManageUserRefineryOrders(#userId)")
  public RefineryOrderDto createUserRefineryOrder(
      @PathVariable @NotNull UUID userId, @RequestBody @Valid @NotNull RefineryOrderDto orderDto) {
    RefineryOrder saved =
        refineryOrderService.createRefineryOrder(
            userId, mapper.toEntity(orderDto), orderDto.owningOrgUnitId());
    return mapper.toDto(
        saved, refineryOrderService.getYieldBonusByMaterialForLocation(saved.getLocation()));
  }

  /**
   * Logistician-only: updates a target user's refinery order.
   *
   * @return the persisted DTO
   */
  @PutMapping("/users/{userId}/{orderId}")
  @PreAuthorize(
      "hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canEditRefineryOrder(#orderId)")
  public RefineryOrderDto updateUserRefineryOrder(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID orderId,
      @RequestBody @Valid @NotNull RefineryOrderDto orderDto) {
    RefineryOrder saved =
        refineryOrderService.updateRefineryOrder(userId, orderId, mapper.toEntity(orderDto), true);
    return mapper.toDto(
        saved, refineryOrderService.getYieldBonusByMaterialForLocation(saved.getLocation()));
  }

  /** Logistician-only: cancels a target user's refinery order. */
  @DeleteMapping("/users/{userId}/{orderId}")
  @PreAuthorize(
      "hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canEditRefineryOrder(#orderId)")
  public void deleteUserRefineryOrder(
      @PathVariable @NotNull UUID userId, @PathVariable @NotNull UUID orderId) {
    refineryOrderService.deleteRefineryOrder(userId, orderId, true);
  }
}

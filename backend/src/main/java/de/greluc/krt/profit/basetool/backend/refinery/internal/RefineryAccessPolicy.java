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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.UserAccessPolicy;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The refinery module's access policy: the read and edit gates of a refinery order and the coarse
 * on-behalf pre-checks, evaluated on the scope kernel (plan §5.4, ADR-0236).
 *
 * <p>Invoked from SpEL as {@code @refineryAccessPolicy}. Unknown ids are refused. Read-only
 * transactional.
 */
@Service(RefineryAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefineryAccessPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "refineryAccessPolicy";

  /**
   * The org-unit scope predicate of the order queries (alias {@code r}, REQ-ORG-003): the plain
   * triple, since the refinery is strict-staffel and has no visibility escape.
   */
  static final String REFINERY_ORDER_SCOPE_TRIPLE =
      """
      (
        :isAdminAllScope = true
        OR (:activeOrgUnitId IS NOT NULL AND r.owningOrgUnit.id = :activeOrgUnitId)
        OR (:activeOrgUnitId IS NULL AND r.owningOrgUnit.id IN :memberOrgUnitIds)
       )
      """;

  private final AccessGateService accessGateService;
  private final AuthHelperService authHelper;
  private final RefineryOrderRepository refineryOrderRepository;
  private final UserAccessPolicy userAccessPolicy;

  /**
   * Checks whether the caller may read refinery order {@code orderId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then the org-unit read scope.
   *
   * @param orderId refinery order to inspect; never {@code null}
   * @return {@code true} iff the caller may read the order
   */
  public boolean canSeeRefineryOrder(@NotNull UUID orderId) {
    return refineryOrderRepository.findById(orderId).map(this::canSeeRefineryOrder).orElse(false);
  }

  /**
   * Entity overload of {@link #canSeeRefineryOrder(UUID)} for callers that already hold the order.
   *
   * @param order the refinery order to inspect; never {@code null}
   * @return {@code true} iff the caller may read the order
   */
  public boolean canSeeRefineryOrder(@NotNull RefineryOrder order) {
    return accessGateService.permitsOwnedRow(
        order.getOwner(), order.getOwningOrgUnit(), false, Roles.LOGISTICIAN);
  }

  /**
   * Checks whether the caller may edit, cancel or store refinery order {@code orderId}: the row
   * scope admits them (owner escape of REQ-ORG-011, ownerless rule, org-unit edit scope or covering
   * Logistician grant), and they own the order or act as Logistician of its owning unit
   * (REQ-SEC-005). A plain member of the order's unit reads it but does not write it.
   *
   * @param orderId refinery order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the order; {@code false} for an unknown order
   */
  public boolean canEditRefineryOrder(@NotNull UUID orderId) {
    return refineryOrderRepository.findById(orderId).map(this::canEditRefineryOrder).orElse(false);
  }

  /**
   * Entity overload of {@link #canEditRefineryOrder(UUID)} for callers that already hold the order,
   * such as the list rows' {@code canEdit} flag (REQ-REFINERY-019).
   *
   * @param order the refinery order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the order
   */
  public boolean canEditRefineryOrder(@NotNull RefineryOrder order) {
    return accessGateService.permitsOwnedRow(
            order.getOwner(), order.getOwningOrgUnit(), true, Roles.LOGISTICIAN)
        && (isOwnedByCaller(order) || isLogisticianOf(order));
  }

  /**
   * Checks whether the caller handles refinery order {@code orderId} as Logistician: a Logistician
   * grant in the order's owning unit inside the active scope, or an admin within their own scope.
   * An order without a unit is reserved to an admin without a pin (REQ-SEC-005, REQ-ORG-032).
   *
   * @param orderId refinery order to inspect; never {@code null}
   * @return {@code true} iff the caller may act on the order as Logistician; {@code false} for an
   *     unknown order
   */
  public boolean isLogisticianOfOrder(@NotNull UUID orderId) {
    return refineryOrderRepository.findById(orderId).map(this::isLogisticianOf).orElse(false);
  }

  /**
   * Whether the caller acts as Logistician in the unit that owns {@code order}, inside the active
   * context; an admin passes within their own scope, and an order without a unit is reserved to an
   * admin without a pin.
   *
   * @param order the refinery order; never {@code null}
   * @return {@code true} iff the caller is Logistician of the order's unit in the active context,
   *     or an admin whose scope covers it; for an order without a unit, iff the caller is an admin
   *     without a pin
   */
  private boolean isLogisticianOf(@NotNull RefineryOrder order) {
    return accessGateService.canActAsLogisticianInOwner(OrgUnit.idOf(order.getOwningOrgUnit()));
  }

  /**
   * Whether the caller owns {@code order}.
   *
   * @param order the refinery order; never {@code null}
   * @return {@code true} iff the caller is authenticated and is the order's owner
   */
  private boolean isOwnedByCaller(@NotNull RefineryOrder order) {
    User owner = order.getOwner();
    UUID ownerId = owner == null ? null : owner.getId();
    return ownerId != null && authHelper.currentUserId().filter(ownerId::equals).isPresent();
  }

  /**
   * Coarse pre-check for reading a member's refinery orders: admin, the member themselves, or a
   * caller whose read scope or Logistician grant covers any of the member's memberships.
   *
   * @param targetUserId the member whose orders the caller wants to read; never {@code null}
   * @return {@code true} iff the caller may read the member's in-scope refinery orders
   */
  public boolean canViewUserRefineryOrders(@NotNull UUID targetUserId) {
    return userAccessPolicy.canActOnTargetUserAsGrantee(targetUserId, Roles.LOGISTICIAN);
  }

  /**
   * Coarse pre-check for creating a refinery order on a member's behalf, like {@link
   * #canViewUserRefineryOrders(UUID)}.
   *
   * @param targetUserId the member the caller wants to create an order for; never {@code null}
   * @return {@code true} iff the caller may create a refinery order on the member's behalf
   */
  public boolean canManageUserRefineryOrders(@NotNull UUID targetUserId) {
    return userAccessPolicy.canActOnTargetUserAsGrantee(targetUserId, Roles.LOGISTICIAN);
  }
}

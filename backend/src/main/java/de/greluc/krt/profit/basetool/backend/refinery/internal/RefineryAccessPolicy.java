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

import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
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

  private final OwnerScopeService ownerScopeService;
  private final RefineryOrderRepository refineryOrderRepository;

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
    return ownerScopeService.permitsOwnedRow(order.getOwner(), order.getOwningOrgUnit(), false);
  }

  /**
   * Checks whether the caller may edit refinery order {@code orderId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then the org-unit edit scope.
   *
   * @param orderId refinery order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the order
   */
  public boolean canEditRefineryOrder(@NotNull UUID orderId) {
    return refineryOrderRepository
        .findById(orderId)
        .map(o -> ownerScopeService.permitsOwnedRow(o.getOwner(), o.getOwningOrgUnit(), true))
        .orElse(false);
  }

  /**
   * Coarse pre-check for reading a member's refinery orders: admin, the member themselves, or a
   * caller whose read scope covers any of the member's memberships.
   *
   * @param targetUserId the member whose orders the caller wants to read; never {@code null}
   * @return {@code true} iff the caller may read the member's in-scope refinery orders
   */
  public boolean canViewUserRefineryOrders(@NotNull UUID targetUserId) {
    return ownerScopeService.canActOnTargetUser(targetUserId, false);
  }

  /**
   * Coarse pre-check for creating a refinery order on a member's behalf, like {@link
   * #canViewUserRefineryOrders(UUID)} with the edit scope.
   *
   * @param targetUserId the member the caller wants to create an order for; never {@code null}
   * @return {@code true} iff the caller may create a refinery order on the member's behalf
   */
  public boolean canManageUserRefineryOrders(@NotNull UUID targetUserId) {
    return ownerScopeService.canActOnTargetUser(targetUserId, true);
  }
}

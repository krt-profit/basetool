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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Offers the shared order workspace and unit-work views without conferring command authority. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderWorkspaceAccess {

  private final RequestScopeResolver scopeResolver;
  private final AuthHelperService authHelper;
  private final JobOrderRepository orders;
  private final OrgUnitRepository orgUnits;

  /**
   * Tests whether the current caller has an order-reading context.
   *
   * @return whether the shared workspace may be offered, including an empty permitted result
   */
  public boolean canViewWorkspace() {
    return scopeResolver.canViewJobOrders()
        || scopeResolver.canViewOwnJobOrders()
        || authHelper.currentUserId().map(orders::existsByAssigneesUserId).orElse(false);
  }

  /**
   * Tests whether current units have a responsible or supporting work context.
   *
   * @return whether the unit-work view may be offered, independently of write authority
   */
  public boolean canViewUnitWork() {
    ScopePredicate scope = scopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN);
    if (scope.adminAllScope()) {
      return true;
    }
    Set<UUID> unitIds =
        scope.activeOrgUnitId() == null
            ? scope.memberOrgUnitIds()
            : Set.of(scope.activeOrgUnitId());
    return !unitIds.isEmpty()
        && (orgUnits.countInActiveProfitHierarchyByIdIn(unitIds) > 0
            || orders.existsByResponsibleOrgUnitIdIn(unitIds));
  }
}

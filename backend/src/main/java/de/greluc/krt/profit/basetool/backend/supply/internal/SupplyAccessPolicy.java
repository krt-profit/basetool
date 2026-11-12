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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriceAudience;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supply module's access policy (ADR-0236): who manages a unit's needs and donations, who leads
 * the governing Bereich and who sees the purchase fields of a need (REQ-SUP-006). Needs are visible
 * to every member and not tenant scoped, so the policy asks for roles, not for scopes.
 *
 * <p>Invoked from SpEL as {@code @supplyAccessPolicy}.
 */
@Service(SupplyAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplyAccessPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "supplyAccessPolicy";

  /** The contextual role that stands for the lead or a Logistician of a unit. */
  static final String UNIT_MANAGER_ROLE = Roles.LOGISTICIAN;

  private final RequestScopeResolver requestScopeResolver;
  private final AccessGateService accessGateService;
  private final AuthHelperService authHelperService;
  private final SupplySettingsService settingsService;
  private final ObjectProvider<NeedPriceAudience> priceAudiences;

  /**
   * Whether the caller is an admin.
   *
   * @return {@code true} for an admin
   */
  public boolean isAdmin() {
    return authHelperService.isAdmin();
  }

  /**
   * Whether the caller manages the unit's needs and donations inside the active context
   * (REQ-ORG-032): as its lead or a Logistician, or as an admin without a pin or pinned to the
   * unit. The write gate of every manager action; {@link #managedUnits()} holds the same rule for
   * the flags and lists.
   *
   * @param orgUnitId the unit
   * @return {@code true} when the caller manages the unit's needs and donations
   */
  public boolean canManageUnit(@NotNull UUID orgUnitId) {
    return accessGateService.hasRoleInOrgUnit(orgUnitId, UNIT_MANAGER_ROLE);
  }

  /**
   * Whether the caller may withdraw a pledge: its donor or an admin.
   *
   * @param donorUserId the donor of the pledge, {@code null} once detached
   * @param caller the calling user
   * @return {@code true} when the caller is the donor or an admin
   */
  public boolean canWithdrawPledge(UUID donorUserId, @NotNull UUID caller) {
    return caller.equals(donorUserId) || isAdmin();
  }

  /**
   * Returns the units the caller manages inside the active context (REQ-ORG-032), by the rule of
   * {@link #canManageUnit(UUID)}: every unit for an admin without a pin, only the pinned unit for a
   * pinned admin, otherwise the units the caller leads or is a Logistician of in the active
   * context. Every manage flag and manager list of the module reads it.
   *
   * @return the caller's managed units
   */
  public @NotNull SupplyManagedUnits managedUnits() {
    return SupplyManagedUnits.of(accessGateService.currentGrantScope(UNIT_MANAGER_ROLE));
  }

  /**
   * Returns the units the caller manages in any context, whatever unit is pinned: every unit for an
   * admin, otherwise the units they lead or are a Logistician of. Only the tab decisions read it,
   * because a tab flag must not depend on the pinned unit (ADR-0329); every manage flag, list and
   * write reads {@link #managedUnits()}.
   *
   * @return the caller's managed units in any context
   */
  public @NotNull SupplyManagedUnits managedUnitsInAnyContext() {
    return isAdmin()
        ? new SupplyManagedUnits(true, Set.of())
        : new SupplyManagedUnits(
            false, requestScopeResolver.currentGrantedOrgUnitIds(UNIT_MANAGER_ROLE));
  }

  /**
   * Whether the caller leads the governing Bereich, belongs to the Organisationsleitung or is an
   * admin: the circle that reads every donation with its donor and the purchase fields.
   *
   * @return {@code true} for the governing leadership
   */
  public boolean isGoverningLeadership() {
    if (authHelperService.isAdmin() || requestScopeResolver.currentUserIsOlMember()) {
      return true;
    }
    Optional<UUID> area = settingsService.governingAreaId();
    return area.isPresent()
        && requestScopeResolver.currentUserHoldsRoleOnOrgUnit(
            area.get(), MembershipRole.BEREICHSLEITER);
  }

  /**
   * Whether the caller reads the donation statistics, whatever unit is pinned: the governing
   * leadership and the lead or a Logistician of at least one unit, so the „Statistik" tab does not
   * depend on the pinned unit (ADR-0329). Whose figures they read follows the active context
   * ({@link #managedUnits()}).
   *
   * @return {@code true} when the statistics answer the caller
   */
  public boolean canReadStats() {
    return isGoverningLeadership() || !managedUnitsInAnyContext().isEmpty();
  }

  /**
   * Whether the caller reads the purchase fields of every need as a trade delegate, as the
   * purchasing module's {@link NeedPriceAudience} says.
   *
   * @return {@code true} for an active trade delegate
   */
  public boolean isTradeDelegate() {
    return authHelperService
        .currentUserId()
        .map(user -> priceAudiences.orderedStream().anyMatch(a -> a.mayReadPurchaseFields(user)))
        .orElse(false);
  }
}

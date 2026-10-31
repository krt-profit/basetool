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

import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriceAudience;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;
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
  static final String UNIT_MANAGER_ROLE = "LOGISTICIAN";

  private final OwnerScopeService ownerScopeService;
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
   * Whether the caller leads or is a Logistician of the unit, or is an admin.
   *
   * @param orgUnitId the unit
   * @return {@code true} when the caller manages the unit's needs and donations
   */
  public boolean canManageUnit(@NotNull UUID orgUnitId) {
    return ownerScopeService.hasRoleInOrgUnit(orgUnitId, UNIT_MANAGER_ROLE);
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
   * Returns the units the caller manages explicitly; an admin manages every unit, which this set
   * does not list.
   *
   * @return the ids of the units the caller leads or is a Logistician of
   */
  public @NotNull Set<UUID> managedUnitIds() {
    Set<UUID> ids = new HashSet<>();
    authHelperService
        .currentAuthentication()
        .ifPresent(authentication -> {
          for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority instanceof OrgUnitContextualAuthority contextual
                && UNIT_MANAGER_ROLE.equals(contextual.roleName())) {
              ids.add(contextual.orgUnitId());
            }
          }
        });
    return ids;
  }

  /**
   * Whether the caller leads the governing Bereich, belongs to the Organisationsleitung or is an
   * admin: the circle that reads every donation with its donor and the purchase fields.
   *
   * @return {@code true} for the governing leadership
   */
  public boolean isGoverningLeadership() {
    if (authHelperService.isAdmin() || ownerScopeService.currentUserIsOlMember()) {
      return true;
    }
    Optional<UUID> area = settingsService.governingAreaId();
    return area.isPresent()
        && ownerScopeService.currentUserHoldsRoleOnOrgUnit(
            area.get(), MembershipRole.BEREICHSLEITER);
  }

  /**
   * Whether the caller may read the maximum price and the purchase progress of the needs of a unit:
   * the unit's lead or Logistician, the governing leadership and a trade delegate.
   *
   * @param orgUnitId the raising unit
   * @return {@code true} when the purchase fields may be returned
   */
  public boolean seesPurchaseFields(@NotNull UUID orgUnitId) {
    if (canManageUnit(orgUnitId) || isGoverningLeadership()) {
      return true;
    }
    return isTradeDelegate();
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

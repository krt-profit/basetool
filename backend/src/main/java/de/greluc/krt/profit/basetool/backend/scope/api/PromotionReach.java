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

package de.greluc.krt.profit.basetool.backend.scope.api;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's reach narrowed to the org units that have the promotion subsystem switched on: the
 * one place where promotion reads, actions and the promotion navigation entries ask which units
 * count (REQ-PROMO-004, REQ-SEC-015).
 *
 * <p>A unit counts when its {@code is_promotion_enabled} flag is set, which only a Staffel can
 * carry. Admins are not narrowed. Read-only transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionReach {

  private final RequestScopeResolver requestScopeResolver;
  private final AuthHelperService authHelper;
  private final OrgUnitRepository orgUnitRepository;

  /**
   * The org units whose promotion data the caller may read as an officer: the officer read scope,
   * pin-aware, reduced to units with promotion enabled.
   *
   * @return the readable unit ids, empty without such a unit; an admin's scope is not narrowed and
   *     is {@code null} when unrestricted
   */
  @Nullable
  public Set<UUID> currentManagementScope() {
    Set<UUID> readScope = requestScopeResolver.currentOfficerReadScope();
    return readScope == null || authHelper.isAdmin() ? readScope : promotionEnabledAmong(readScope);
  }

  /**
   * Whether the caller manages promotions in the active context: officer reach in the pinned unit,
   * or in any unit when none is pinned, counting only units with promotion enabled.
   *
   * @return {@code true} iff the caller is an admin or reaches a promotion-enabled unit there
   */
  public boolean canManageInActiveContext() {
    return authHelper.isAdmin()
        || !promotionEnabledAmong(
                requestScopeResolver.narrowToPin(requestScopeResolver.currentOfficerOrgUnitIds()))
            .isEmpty();
  }

  /**
   * Whether the caller manages promotions in at least one unit, whatever unit is pinned.
   *
   * @return {@code true} iff the caller is an admin or reaches a promotion-enabled unit as officer
   */
  public boolean canManageAnywhere() {
    return authHelper.isAdmin()
        || !promotionEnabledAmong(requestScopeResolver.currentOfficerOrgUnitIds()).isEmpty();
  }

  /**
   * Whether the caller has the promotion pages in at least one unit, whatever unit is pinned: a
   * promotion-enabled unit they belong to or reach as officer.
   *
   * @return {@code true} iff the caller is an admin or belongs to or reaches such a unit
   */
  public boolean canUseAnywhere() {
    if (authHelper.isAdmin()) {
      return true;
    }
    Set<UUID> units = new HashSet<>(requestScopeResolver.currentMemberOrgUnitIds());
    units.addAll(requestScopeResolver.currentOfficerOrgUnitIds());
    return !promotionEnabledAmong(units).isEmpty();
  }

  /**
   * Whether the unit has the promotion subsystem switched on.
   *
   * @param orgUnitId the unit to check; never {@code null}
   * @return {@code true} iff the unit exists and has promotion enabled
   */
  public boolean isEnabled(@NotNull UUID orgUnitId) {
    return !promotionEnabledAmong(Set.of(orgUnitId)).isEmpty();
  }

  @NotNull
  private Set<UUID> promotionEnabledAmong(@NotNull Set<UUID> orgUnitIds) {
    if (orgUnitIds.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(orgUnitRepository.findPromotionEnabledIdsAmong(orgUnitIds));
  }
}

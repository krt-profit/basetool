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

import de.greluc.krt.profit.basetool.backend.model.Squadron;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The promotion module's access policy (plan §5.4, ADR-0236): the per-Staffel feature flag and the
 * read gate every Beförderung read and write consults, built on the scope kernel.
 */
@Component("promotionAccessPolicy")
@RequiredArgsConstructor
public class PromotionAccessPolicy {

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelper;

  /**
   * Whether the per-squadron promotion feature flag is on for the caller's scope: the flag of the
   * effective (pinned or home) squadron, or {@code true} when there is none.
   *
   * @return {@code true} when the promotion menu may be exposed for the caller
   */
  public boolean isFeatureEnabledForCurrentScope() {
    return ownerScopeService.currentSquadron().map(Squadron::isPromotionEnabled).orElse(true);
  }

  /**
   * Whether the caller may read any promotion data: admins and non-admins with an effective home
   * squadron. List and eligibility reads return empty otherwise.
   *
   * @return {@code true} for admins and for non-admins with an effective squadron
   */
  public boolean hasReadAccess() {
    return authHelper.isAdmin() || ownerScopeService.currentSquadronId().isPresent();
  }

  /**
   * Refuses the call when the promotion feature flag is off for the caller's scope; called before
   * every promotion write and single-row read.
   *
   * @throws AccessDeniedException if the flag is disabled for the caller's scope
   */
  public void assertFeatureEnabled() {
    if (!isFeatureEnabledForCurrentScope()) {
      throw new AccessDeniedException(
          "Promotion feature is disabled for the caller's squadron; ask an administrator to"
              + " re-enable it.");
    }
  }
}

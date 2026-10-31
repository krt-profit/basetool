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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The promotion module's access policy (plan §5.4, ADR-0236): the per-Staffel feature flag and the
 * read gate every Beförderung read and write consults, and the officer-reach checks of its writes
 * (REQ-SEC-015), built on the scope kernel.
 */
@Component("promotionAccessPolicy")
@RequiredArgsConstructor
public class PromotionAccessPolicy {

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelper;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

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

  /**
   * Refuses the call unless the caller holds officer reach in the squadron that owns the promotion
   * data being written; admins pass.
   *
   * @param squadronId the owning squadron of the topic, category, level content or requirement
   * @throws AccessDeniedException if the caller holds no officer reach in {@code squadronId}
   */
  public void assertOfficerIn(@NotNull UUID squadronId) {
    if (!ownerScopeService.canActAsOfficerIn(squadronId)) {
      throw new AccessDeniedException("Caller holds no officer reach in the owning squadron");
    }
  }

  /**
   * Refuses the call unless the caller may act on the member as an officer: admins pass, others
   * need officer reach in at least one of the member's Staffeln (REQ-ORG-017).
   *
   * @param userId the {@code app_user.id} of the member being evaluated; never {@code null}
   * @throws AccessDeniedException if the caller holds officer reach in none of the member's
   *     Staffeln
   */
  public void assertOfficerOverMember(@NotNull UUID userId) {
    if (authHelper.isAdmin()) {
      return;
    }
    boolean reaches =
        orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(userId).stream()
            .anyMatch(ownerScopeService::canActAsOfficerIn);
    if (!reaches) {
      throw new AccessDeniedException("Caller holds no officer reach over this member");
    }
  }
}

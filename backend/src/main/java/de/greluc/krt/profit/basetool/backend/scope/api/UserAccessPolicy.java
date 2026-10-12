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

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The on-behalf gate of the scope kernel as a named policy (plan section 5.4): whether the caller
 * may act on another member's rows, judged by the org units the two share.
 *
 * <p>Read-only transactional, like the gate it builds on.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAccessPolicy {

  private final AccessGateService accessGateService;
  private final AuthHelperService authHelper;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;

  /**
   * Coarse pre-check for acting on another member's rows: admin, then self, then whether the read
   * scope or, with {@code edit}, the edit scope accepts any of the member's memberships. It does
   * not bound individual rows.
   *
   * @param targetUserId the member being acted upon; never {@code null}
   * @param edit {@code true} for the write check, {@code false} for the read check
   * @return {@code true} iff the caller shares at least one in-scope org unit with the member
   */
  public boolean canActOnTargetUser(@NotNull UUID targetUserId, boolean edit) {
    return canActOnTargetUserScoped(
        targetUserId,
        edit ? accessGateService::canEditSquadron : accessGateService::canSeeSquadron);
  }

  /**
   * Shared on-behalf check: admin, then self, then whether {@code unitScope} accepts any of the
   * target user's memberships.
   *
   * @param targetUserId the user being acted upon; never {@code null}
   * @param unitScope the per-unit scope check to apply; never {@code null}
   * @return {@code true} iff the caller shares at least one in-scope org unit with the target
   */
  private boolean canActOnTargetUserScoped(
      @NotNull UUID targetUserId, @NotNull Predicate<UUID> unitScope) {
    if (authHelper.isAdmin()) {
      return true;
    }
    if (authHelper.currentUserId().map(targetUserId::equals).orElse(false)) {
      return true;
    }
    return orgUnitMembershipRepository.findAllByIdUserId(targetUserId).stream()
        .map(m -> m.getId().getOrgUnitId())
        .anyMatch(unitScope);
  }
}

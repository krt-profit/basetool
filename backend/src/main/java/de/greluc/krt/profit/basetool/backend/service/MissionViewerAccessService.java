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

import de.greluc.krt.profit.basetool.backend.support.MissionViewerAccess;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Service-layer implementation of the {@link MissionViewerAccess} seam the {@code MissionMapper}
 * depends on. Lives here (not in {@code support}) so the dependency on {@link AuthHelperService}
 * and {@link MissionSecurityService} stays in the {@code service} layer and the mapper depends only
 * on the leaf interface — see {@link MissionViewerAccess} and ADR-0047 for why this breaks the
 * {@code mapper} &harr; {@code service} package cycle.
 *
 * <p>It is a thin adapter: it folds the {@code (missionId, Authentication)} call shape of {@link
 * MissionSecurityService} into the mapper-friendly {@code (missionId)} shape by sourcing the {@code
 * Authentication} from {@link AuthHelperService}, so the mapper never has to touch the raw
 * authentication object.
 */
@Service
@RequiredArgsConstructor
public class MissionViewerAccessService implements MissionViewerAccess {

  private final AuthHelperService authHelperService;
  private final MissionSecurityService missionSecurityService;

  /**
   * Delegates to {@link AuthHelperService#isAuthenticated()}.
   *
   * @return {@code true} iff the caller is authenticated.
   */
  @Override
  public boolean isAuthenticated() {
    return authHelperService.isAuthenticated();
  }

  /**
   * Delegates to {@link AuthHelperService#isMemberOrAbove()} — the REQ-SEC-009 outsider
   * discriminator, false for anonymous and role-less {@code GUEST} callers alike.
   *
   * @return {@code true} iff the caller holds a member or elevated role.
   */
  @Override
  public boolean isMemberOrAbove() {
    return authHelperService.isMemberOrAbove();
  }

  /**
   * Resolves the caller's {@code Authentication} via {@link AuthHelperService} and delegates to
   * {@link MissionSecurityService#canManageMission(UUID,
   * org.springframework.security.core.Authentication)}.
   *
   * @param missionId the mission to check; never {@code null}.
   * @return {@code true} iff the caller may manage the mission.
   */
  @Override
  public boolean canManageMission(UUID missionId) {
    return missionSecurityService.canManageMission(
        missionId, authHelperService.rawAuthentication());
  }

  /**
   * Resolves the caller's {@code Authentication} via {@link AuthHelperService} and delegates to
   * {@link MissionSecurityService#canManageManagers(UUID,
   * org.springframework.security.core.Authentication)}.
   *
   * @param missionId the mission to check; never {@code null}.
   * @return {@code true} iff the caller may manage the mission's managers.
   */
  @Override
  public boolean canManageManagers(UUID missionId) {
    return missionSecurityService.canManageManagers(
        missionId, authHelperService.rawAuthentication());
  }
}

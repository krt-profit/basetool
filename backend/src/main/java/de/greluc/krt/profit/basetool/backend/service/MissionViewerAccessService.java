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
 * Implements {@link MissionViewerAccess} for the mission mapper by resolving the caller's
 * authentication and delegating to {@link MissionSecurityService} (ADR-0047).
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
   * Delegates to {@link MissionSecurityService#canManageMission(UUID,
   * org.springframework.security.core.Authentication)} with the caller's authentication.
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
   * Delegates to {@link MissionSecurityService#canManageManagers(UUID,
   * org.springframework.security.core.Authentication)} with the caller's authentication.
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

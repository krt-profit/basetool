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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.UUID;

/**
 * Caller-aware seam the {@code MissionMapper} uses to fill the viewer-dependent fields of a mission
 * DTO ({@code description} redaction, {@code canEdit}, {@code canManageManagers}).
 *
 * <p>Implemented in the {@code service} layer, so the {@code mapper} package depends on neither the
 * service layer nor the security context (ADR-0047).
 */
public interface MissionViewerAccess {

  /**
   * Reports whether the current request is made by an authenticated caller.
   *
   * @return {@code true} iff the caller is authenticated.
   */
  boolean isAuthenticated();

  /**
   * Reports whether the current caller is a squadron member or above (REQ-SEC-009); gates the
   * mission {@code description} redaction (REQ-SEC-041).
   *
   * @return {@code true} iff the caller holds a member or elevated role
   */
  boolean isMemberOrAbove();

  /**
   * Reports whether the current caller may edit (manage) the given mission.
   *
   * @param missionId the mission to check; never {@code null}.
   * @return {@code true} iff the caller may manage the mission.
   */
  boolean canManageMission(UUID missionId);

  /**
   * Reports whether the current caller may add or remove the given mission's managers.
   *
   * @param missionId the mission to check; never {@code null}.
   * @return {@code true} iff the caller may manage the mission's managers.
   */
  boolean canManageManagers(UUID missionId);
}

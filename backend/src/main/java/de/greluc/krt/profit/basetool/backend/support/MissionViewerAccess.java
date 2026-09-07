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
 * Caller-aware access seam the {@code MissionMapper} uses to fill the viewer-dependent fields of a
 * mission DTO ({@code description} redaction for guests, {@code canEdit}, {@code
 * canManageManagers}) without the {@code mapper} package depending on the {@code service} layer.
 *
 * <p>This is a deliberate <b>dependency inversion</b> (ADR-0047, cycle cleanup): the {@code
 * service} layer already depends on the {@code mapper} layer (services map entities to DTOs through
 * the mappers), so a {@code mapper} &rarr; {@code service} edge — which the previous {@code
 * MissionMapper} had via {@code AuthHelperService} + {@code MissionSecurityService} — closed a
 * {@code mapper} &harr; {@code service} package cycle. By depending on this leaf interface instead,
 * implemented in the {@code service} layer ({@code MissionViewerAccessService}), the mapper stays
 * free of the service layer and the cycle is gone. It supersedes the older "mappers route auth
 * lookups through {@code AuthHelperService}" guidance (which is still honoured transitively — the
 * implementation is the only thing that touches {@code AuthHelperService}); mappers must reach
 * neither {@code SecurityContextHolder} (ArchUnit {@code
 * mapperLayerShouldNotReachIntoSecurityContext}) nor, now, the {@code service} layer directly.
 */
public interface MissionViewerAccess {

  /**
   * Reports whether the current request is made by an authenticated caller.
   *
   * @return {@code true} iff the caller is authenticated.
   */
  boolean isAuthenticated();

  /**
   * Reports whether the current caller is a squadron member or above (REQ-SEC-009).
   *
   * <p>Backs the mission {@code description} redaction (REQ-SEC-041). {@link #isAuthenticated()} is
   * deliberately <em>not</em> the gate for it: authentication and membership are different
   * questions, and a caller can hold the first without the second — a PENDING registration does,
   * and until ADR-0159 a role-less {@code GUEST} token did too. Gating on membership here makes the
   * list/search rows agree with the detail instead of leaking what the detail's redactor removes.
   *
   * @return {@code true} iff the caller holds a member or elevated role.
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

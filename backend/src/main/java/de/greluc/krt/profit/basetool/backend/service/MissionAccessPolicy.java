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

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mission module's access policy: the read and edit gates of a mission, evaluated on the scope
 * kernel (plan §5.4, ADR-0236). The finer write gates (manage, owner change, participant access)
 * stay with {@link MissionSecurityService}, which asks this policy for the edit scope.
 *
 * <p>Invoked from SpEL as {@code @missionAccessPolicy}. Unknown ids are refused. Read-only
 * transactional, so the parent chain loads lazily.
 */
@Service(MissionAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionAccessPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "missionAccessPolicy";

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelper;
  private final MissionRepository missionRepository;

  /**
   * Checks whether the caller may read mission {@code missionId}: the mission and every ancestor
   * must be readable, where a public mission is readable organisation-wide and an internal one only
   * within the owning org unit's read scope, or by a member-or-above when it has no owning unit
   * (REQ-ORG-009).
   *
   * @param missionId the mission to inspect; never {@code null}
   * @return {@code true} iff the caller may read the mission
   */
  public boolean canSeeMission(@NotNull UUID missionId) {
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(this::canSeeMissionChain)
        .orElse(false);
  }

  /**
   * Checks whether the caller may edit mission {@code missionId}: an ownerless mission passes and
   * leaves the decision to {@link MissionSecurityService}, any other needs the owning org unit's
   * edit scope, without the public escape.
   *
   * @param missionId the mission to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the mission
   */
  public boolean canEditMission(@NotNull UUID missionId) {
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(
            m ->
                m.getOwningOrgUnit() == null
                    || ownerScopeService.canEditSquadron(m.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  private boolean canSeeMissionChain(@NotNull Mission mission) {
    for (Mission ancestor = mission; ancestor != null; ancestor = ancestor.getParent()) {
      if (!canSeeMissionRow(ancestor)) {
        return false;
      }
    }
    return true;
  }

  private boolean canSeeMissionRow(@NotNull Mission mission) {
    boolean internal = Boolean.TRUE.equals(mission.getIsInternal());
    if (mission.getOwningOrgUnit() == null) {
      return !internal || authHelper.isMemberOrAbove();
    }
    return !internal || ownerScopeService.canSeeSquadron(mission.getOwningOrgUnit().getId());
  }
}

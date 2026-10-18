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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static de.greluc.krt.profit.basetool.backend.mission.internal.MissionSectionVersions.enforceSectionVersion;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionSectionVersions.MissionSection;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The edit module of the mission aggregate (ADR-0287): every timeline, roster and structure
 * mutation runs through it, and it alone decides the sequence of load, access check on the loaded
 * mission, section-version check, mutation, save and audit.
 *
 * <p>The access check runs for every caller, with or without an HTTP request; a caller without an
 * authentication is refused (ADR-0236 §6). The section check is the DB-enforced atomic bump of
 * {@link MissionSectionVersions#enforceSectionVersion} and never widens the lock.
 */
@Service
@RequiredArgsConstructor
public class MissionEditor {

  private final MissionRepository missionRepository;
  private final MissionAccessPolicy missionAccessPolicy;
  private final AuthHelperService authHelperService;
  private final AuditRecorder auditRecorder;

  /**
   * Edits one versioned section of a mission: load, gate, atomic section-version check, mutation,
   * save of the mission row, one audit event.
   *
   * @param missionId the mission to edit
   * @param gate the access the edit demands
   * @param section the section whose counter guards the edit
   * @param expectedVersion the section version the caller last saw
   * @param mutation the change to apply to the managed mission
   * @return the managed mission after the edit
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the mission is
   *     unknown
   * @throws AccessDeniedException when the caller fails the gate
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedVersion} is stale
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Mission editSection(
      @NotNull UUID missionId,
      @NotNull MissionGate gate,
      @NotNull MissionSection section,
      @NotNull Long expectedVersion,
      @NotNull Function<Mission, MissionEdit> mutation) {
    Mission mission = loadPermitted(missionId, gate);
    enforceSectionVersion(missionRepository, mission, section, expectedVersion, missionId);
    MissionEdit edit = mutation.apply(mission);
    missionRepository.save(mission);
    audit(mission, edit);
    return mission;
  }

  /**
   * Edits a part of a mission that carries its own row lock or none: load, gate, mutation, one
   * audit event. The mutation persists the rows it touches; the mission row is not saved.
   *
   * @param missionId the mission to edit
   * @param gate the access the edit demands
   * @param mutation the change to apply to the managed mission
   * @return the managed mission after the edit
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the mission is
   *     unknown
   * @throws AccessDeniedException when the caller fails the gate
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Mission edit(
      @NotNull UUID missionId,
      @NotNull MissionGate gate,
      @NotNull Function<Mission, MissionEdit> mutation) {
    Mission mission = loadPermitted(missionId, gate);
    MissionEdit edit = mutation.apply(mission);
    audit(mission, edit);
    return mission;
  }

  private Mission loadPermitted(@NotNull UUID missionId, @NotNull MissionGate gate) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    Authentication authentication = authHelperService.rawAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || !missionAccessPolicy.canEdit(mission, gate, authentication)) {
      throw new AccessDeniedException("Caller may not edit mission " + missionId);
    }
    return mission;
  }

  private void audit(@NotNull Mission mission, @NotNull MissionEdit edit) {
    if (edit.type() != null) {
      auditRecorder.record(
          edit.type(), mission.getId(), mission.getName(), edit.targetUserId(), edit.details());
    }
  }
}

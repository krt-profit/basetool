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

import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The access a mission edit demands of its caller, decided by {@link MissionAccessPolicy} on the
 * already-loaded mission (ADR-0236 §6, ADR-0287).
 */
public sealed interface MissionGate {

  /** The caller may manage the mission: an in-scope elevated role, the owner or a co-manager. */
  MissionGate MANAGE = new Manage();

  /** The caller may read the mission and every ancestor (REQ-ORG-009). */
  MissionGate SEE = new See();

  /**
   * The caller may act on one participant row: its own linked user, or a caller who may manage the
   * mission.
   *
   * @param participantId the participant the edit touches
   * @return the participant gate
   */
  static MissionGate participant(@NotNull UUID participantId) {
    return new Participant(participantId);
  }

  /** Demands {@link MissionAccessPolicy#canManageLoadedMission}. */
  record Manage() implements MissionGate {}

  /** Demands {@link MissionAccessPolicy#canSeeMission(Mission)}. */
  record See() implements MissionGate {}

  /**
   * Demands {@link MissionAccessPolicy#canAccessLoadedParticipant}.
   *
   * @param participantId the participant the edit touches
   */
  record Participant(@NotNull UUID participantId) implements MissionGate {}
}

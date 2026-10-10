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

import de.greluc.krt.profit.basetool.backend.notification.api.MissionRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The mission module's {@link MissionRecipientDirectory}, reading participants and managers. */
@Service
@RequiredArgsConstructor
public class MissionRecipientDirectoryImpl implements MissionRecipientDirectory {

  private final MissionParticipantRepository missionParticipantRepository;
  private final MissionRepository missionRepository;

  /**
   * The registered members signed up for a mission.
   *
   * @param missionId the mission
   * @param onlyNotCheckedIn {@code true} to keep only participants who have not checked in
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> participantsOf(@NotNull UUID missionId, boolean onlyNotCheckedIn) {
    return onlyNotCheckedIn
        ? missionParticipantRepository.findNotCheckedInUserIdsByMission(missionId)
        : missionParticipantRepository.findRegisteredUserIdsByMission(missionId);
  }

  /**
   * The mission's owner and its co-managers.
   *
   * @param missionId the mission
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> leadershipOf(@NotNull UUID missionId) {
    Set<UUID> leadership = new HashSet<>(missionRepository.findManagerUserIdsById(missionId));
    missionRepository.findOwnerUserIdById(missionId).ifPresent(leadership::add);
    return leadership;
  }
}

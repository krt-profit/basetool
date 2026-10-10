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

import de.greluc.krt.profit.basetool.backend.catalogue.api.JobTypeDesignationObserver;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Clears the participants' Einsatzleiter flag of a job type that lost the designation. */
@Component
@RequiredArgsConstructor
public class MissionLeadDesignationRelease implements JobTypeDesignationObserver {

  private final MissionParticipantRepository missionParticipantRepository;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int missionLeadRevoked(@NotNull UUID jobTypeId) {
    return missionParticipantRepository.clearMissionLeadFlagForJobType(jobTypeId);
  }
}

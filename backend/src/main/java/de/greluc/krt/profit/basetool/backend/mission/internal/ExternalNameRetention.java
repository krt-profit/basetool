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

import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes the names of external participants and external party leads of missions that ended before
 * the cutoff (REQ-DATA-047); on a settled operation the payout mark follows the name (V441).
 */
@Component
@RequiredArgsConstructor
public class ExternalNameRetention implements NameRetentionParticipant {

  private final MissionParticipantRepository participantRepository;
  private final MissionRepository missionRepository;

  @Override
  public @NotNull String area() {
    return "external_names";
  }

  @Override
  @Transactional
  public int removeNamesBefore(@NotNull Instant cutoff) {
    return participantRepository.clearExternalNamesEndedBefore(cutoff)
        + missionRepository.clearExternalPartyLeadsEndedBefore(cutoff);
  }
}

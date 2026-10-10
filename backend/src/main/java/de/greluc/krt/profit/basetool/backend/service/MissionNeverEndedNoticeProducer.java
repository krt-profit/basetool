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

import de.greluc.krt.profit.basetool.backend.mission.internal.MissionNoticeProperties;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.notification.api.TimedNoticeProducer;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Tells a mission's leadership when an active or completed mission is more than {@code
 * app.missions.notices.overdue-after} past its planned end and still has no actual end time
 * (REQ-MISSION-025), once per mission.
 */
@Component
@RequiredArgsConstructor
public class MissionNeverEndedNoticeProducer implements TimedNoticeProducer {

  private final MissionRepository missionRepository;
  private final MissionNotificationPublisher notificationPublisher;
  private final MissionNoticeProperties properties;

  @Override
  @NotNull
  public String kind() {
    return "mission_never_ended";
  }

  @Override
  public int produce(@NotNull Instant now) {
    int raised = 0;
    for (Mission mission :
        missionRepository.findOverdueWithoutEnd(now.minus(properties.overdueAfter()))) {
      notificationPublisher.markNeverEnded(mission);
      raised++;
    }
    return raised;
  }
}

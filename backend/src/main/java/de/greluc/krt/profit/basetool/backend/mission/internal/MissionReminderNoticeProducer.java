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

import de.greluc.krt.profit.basetool.backend.mission.api.events.MissionNotices;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.notification.api.TimedNoticeProducer;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Raises the reminders 24 hours and one hour before a planned mission (REQ-MISSION-022), once per
 * mission and lead: the mission's marker is set in the same transaction that publishes the events.
 *
 * <p>The 24-hour reminder is raised only in the hour after the 24-hour mark, so a mission planned
 * for the day itself gets no reminder that would lie about its lead.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class MissionReminderNoticeProducer implements TimedNoticeProducer {

  private static final Duration DAY = Duration.ofHours(24);
  private static final Duration HOUR = Duration.ofHours(1);

  private final MissionRepository missionRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @NotNull
  public String kind() {
    return "mission_reminder";
  }

  @Override
  public int produce(@NotNull Instant now, int limit) {
    int raised = 0;
    for (Mission mission :
        missionRepository.findDueForReminder24h(now.plus(DAY).minus(HOUR), now.plus(DAY))) {
      if (raised >= limit) {
        return raised;
      }
      mission.setReminder24hSentAt(now);
      raised += remind(mission, "24 h");
    }
    for (Mission mission : missionRepository.findDueForReminder1h(now, now.plus(HOUR))) {
      if (raised >= limit) {
        return raised;
      }
      mission.setReminder1hSentAt(now);
      raised += remind(mission, "1 h");
    }
    return raised;
  }

  private int remind(Mission mission, String lead) {
    int count = 0;
    for (MissionParticipant participant : mission.getParticipants()) {
      if (participant.getUser() == null) {
        continue;
      }
      JobType role = participant.getPlannedMissionJobType();
      eventPublisher.publishEvent(
          MissionNotices.reminder(
              mission.getId(),
              mission.getName(),
              participant.getUser().getId(),
              lead,
              mission.referenceTime(),
              mission.getMeetingPoint(),
              role == null ? null : role.getName()));
      count++;
    }
    return count;
  }
}

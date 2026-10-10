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

import de.greluc.krt.profit.basetool.backend.mission.api.events.MissionNotices;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Publishes the notifications of the mission services (REQ-MISSION-050…056). Every method is called
 * inside the transaction that changes the mission and publishes after the commit, never for a
 * change that rolls back.
 */
@Service
@RequiredArgsConstructor
public class MissionNotificationPublisher {

  /** How close to the start a participant leaving still counts as dropping out. */
  private static final Duration LATE_LEAVE_WINDOW = Duration.ofHours(24);

  private final ApplicationEventPublisher eventPublisher;
  private final UserService userService;

  /**
   * Announces a moved meeting time or planned start and resets the reminders for the new time.
   *
   * @param mission the managed mission, already carrying the new times
   * @param oldMeetingTime the meeting time before the change
   * @param oldPlannedStart the planned start before the change
   */
  public void scheduleChanged(
      @NotNull Mission mission,
      @Nullable Instant oldMeetingTime,
      @Nullable Instant oldPlannedStart) {
    boolean meetingMoved = !Objects.equals(oldMeetingTime, mission.getMeetingTime());
    boolean startMoved = !Objects.equals(oldPlannedStart, mission.getPlannedStartTime());
    if (!meetingMoved && !startMoved) {
      return;
    }
    mission.setReminder24hSentAt(null);
    mission.setReminder1hSentAt(null);
    Instant before = meetingMoved ? oldMeetingTime : oldPlannedStart;
    Instant after = meetingMoved ? mission.getMeetingTime() : mission.getPlannedStartTime();
    eventPublisher.publishEvent(
        MissionNotices.rescheduled(
            mission.getId(), mission.getName(), before, after, userService.currentActor()));
  }

  /**
   * Announces what a status change means: cancelled, started (check-in open) or completed without
   * an end time.
   *
   * @param mission the managed mission, already carrying the new status
   * @param oldStatus the status before the change
   */
  public void statusChanged(@NotNull Mission mission, @Nullable String oldStatus) {
    String status = mission.getStatus();
    if (status == null || status.equals(oldStatus)) {
      return;
    }
    ActorRef actor = userService.currentActor();
    switch (status) {
      case "CANCELLED" ->
          eventPublisher.publishEvent(
              MissionNotices.cancelled(mission.getId(), mission.getName(), actor));
      case "ACTIVE" ->
          eventPublisher.publishEvent(
              MissionNotices.started(
                  mission.getId(), mission.getName(), mission.getActualStartTime(), actor));
      case "COMPLETED" -> {
        if (mission.getActualEndTime() == null) {
          markNeverEnded(mission);
        }
      }
      default -> {}
    }
  }

  /**
   * Announces that a mission got its end time, and re-arms the never-ended notice when the planned
   * end moved.
   *
   * @param mission the managed mission, already carrying the new times
   * @param oldPlannedEnd the planned end before the change
   * @param oldActualEnd the actual end before the change
   */
  public void endChanged(
      @NotNull Mission mission, @Nullable Instant oldPlannedEnd, @Nullable Instant oldActualEnd) {
    if (!Objects.equals(oldPlannedEnd, mission.getPlannedEndTime())) {
      mission.setNeverEndedNotifiedAt(null);
    }
    if (oldActualEnd == null && mission.getActualEndTime() != null) {
      mission.setNeverEndedNotifiedAt(null);
      eventPublisher.publishEvent(MissionNotices.endRecorded(mission.getId()));
    }
  }

  /**
   * Tells the leadership that a mission is over but has no end time, once.
   *
   * @param mission the managed mission
   */
  public void markNeverEnded(@NotNull Mission mission) {
    if (mission.getNeverEndedNotifiedAt() != null) {
      return;
    }
    mission.setNeverEndedNotifiedAt(Instant.now());
    eventPublisher.publishEvent(
        MissionNotices.neverEnded(mission.getId(), mission.getName(), mission.getPlannedEndTime()));
  }

  /**
   * Announces a deleted mission to the participants captured before the delete.
   *
   * @param missionId the deleted mission
   * @param missionName its name
   * @param participantUserIds the registered participants captured before the delete
   */
  public void deleted(
      @NotNull UUID missionId, @NotNull String missionName, @NotNull Set<UUID> participantUserIds) {
    eventPublisher.publishEvent(
        MissionNotices.deleted(
            missionId, missionName, participantUserIds, userService.currentActor()));
  }

  /**
   * Announces that somebody else added a member to a mission.
   *
   * @param mission the managed mission
   * @param participant the added participant
   */
  public void participantAdded(@NotNull Mission mission, @NotNull MissionParticipant participant) {
    User user = participant.getUser();
    ActorRef actor = userService.currentActor();
    if (user == null || user.getId().equals(actor.id())) {
      return;
    }
    eventPublisher.publishEvent(
        MissionNotices.participantAdded(
            mission.getId(),
            mission.getName(),
            user.getId(),
            mission.referenceTime(),
            participant.getPayoutPreference().name(),
            actor));
  }

  /**
   * Announces a removed participant: to the member when somebody else removed them, to the
   * leadership when they left themselves with a slot, a role or a close start.
   *
   * @param mission the managed mission
   * @param participant the participant being removed, before the removal
   */
  public void participantRemoved(
      @NotNull Mission mission, @NotNull MissionParticipant participant) {
    User user = participant.getUser();
    if (user == null) {
      return;
    }
    ActorRef actor = userService.currentActor();
    if (!user.getId().equals(actor.id())) {
      eventPublisher.publishEvent(
          MissionNotices.participantRemoved(
              mission.getId(), mission.getName(), user.getId(), actor));
      return;
    }
    List<String> freed = freedSlots(mission, participant);
    Instant start = mission.referenceTime();
    boolean closeStart =
        start != null
            && start.isAfter(Instant.now())
            && Duration.between(Instant.now(), start).compareTo(LATE_LEAVE_WINDOW) < 0;
    if (freed.isEmpty() && !closeStart) {
      return;
    }
    eventPublisher.publishEvent(
        MissionNotices.participantLeft(
            mission.getId(),
            mission.getName(),
            user.getId(),
            actor.name(),
            freed.isEmpty() ? null : String.join(", ", freed)));
  }

  /**
   * Announces that a participant checked in, clearing their open check-in notice.
   *
   * @param mission the managed mission
   * @param participant the participant who checked in
   */
  public void checkedIn(@NotNull Mission mission, @NotNull MissionParticipant participant) {
    if (participant.getUser() != null) {
      eventPublisher.publishEvent(
          MissionNotices.checkedIn(mission.getId(), participant.getUser().getId()));
    }
  }

  /**
   * Tells a member that they became responsible for part of a mission; nothing happens when they
   * made the change themselves.
   *
   * @param mission the managed mission
   * @param recipientId the new responsible member
   * @param roleCode {@code OWNER}, {@code MANAGER}, {@code PARTY_LEAD} or {@code UNIT_RESPONSIBLE}
   */
  public void responsibilityAssigned(
      @NotNull Mission mission, @NotNull UUID recipientId, @NotNull String roleCode) {
    ActorRef actor = userService.currentActor();
    if (recipientId.equals(actor.id())) {
      return;
    }
    eventPublisher.publishEvent(
        MissionNotices.responsibilityAssigned(
            mission.getId(), mission.getName(), recipientId, roleCode, actor));
  }

  /**
   * The registered participants of a mission, for a delete that takes the rows with it.
   *
   * @param mission the managed mission
   * @return the participants' user ids; never {@code null}
   */
  @NotNull
  public static Set<UUID> participantUserIds(@NotNull Mission mission) {
    Set<UUID> ids = new HashSet<>();
    for (MissionParticipant participant : mission.getParticipants()) {
      if (participant.getUser() != null) {
        ids.add(participant.getUser().getId());
      }
    }
    return ids;
  }

  private static List<String> freedSlots(Mission mission, MissionParticipant participant) {
    List<String> freed = new ArrayList<>();
    JobType planned = participant.getPlannedMissionJobType();
    if (planned != null && planned.getName() != null) {
      freed.add(planned.getName());
    }
    for (MissionUnit unit : mission.getAssignedUnits()) {
      boolean crewed =
          unit.getCrew().stream()
              .anyMatch(
                  crew ->
                      crew.getParticipant() != null
                          && crew.getParticipant().getId().equals(participant.getId()));
      if (crewed && unit.getName() != null) {
        freed.add(unit.getName());
      }
    }
    return freed;
  }
}

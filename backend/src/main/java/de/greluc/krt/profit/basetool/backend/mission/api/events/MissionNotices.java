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

package de.greluc.krt.profit.basetool.backend.mission.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notification events of the mission module (REQ-MISSION-050…056), built from scalars so they
 * can be delivered after the commit. Each factory names the event, its recipients' context, its
 * render parameters and the notices it supersedes.
 */
public final class MissionNotices {

  /** Loose entity-type tag stored on every mission notification. */
  public static final String ENTITY_TYPE = "MISSION";

  /** The text for a time or name that is not known. */
  public static final String UNKNOWN = ActorRef.UNKNOWN_NAME;

  /** The notices that go stale when a mission is cancelled or deleted. */
  public static final Set<NotificationType> OPEN_MISSION_NOTICES =
      Set.of(
          NotificationType.MISSION_RESCHEDULED,
          NotificationType.MISSION_CANCELLED,
          NotificationType.MISSION_REMINDER,
          NotificationType.MISSION_CHECKIN_OPEN,
          NotificationType.MISSION_PARTICIPANT_ADDED_BY_OTHER,
          NotificationType.MISSION_NEVER_ENDED);

  /** What a participant's own notices about a mission are once they are no longer part of it. */
  private static final Set<NotificationType> PARTICIPANT_NOTICES =
      Set.of(
          NotificationType.MISSION_PARTICIPANT_ADDED_BY_OTHER,
          NotificationType.MISSION_REMINDER,
          NotificationType.MISSION_CHECKIN_OPEN);

  private static final DateTimeFormatter DISPLAY =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  private MissionNotices() {}

  /**
   * Formats an instant the way the inbox shows times.
   *
   * @param instant the time, possibly {@code null}
   * @return {@code yyyy-MM-dd HH:mm UTC}, or {@link #UNKNOWN} for {@code null}
   */
  @NotNull
  public static String time(@Nullable Instant instant) {
    return instant == null ? UNKNOWN : DISPLAY.format(instant);
  }

  /**
   * A name for a notice, never blank.
   *
   * @param name the name, possibly {@code null} or blank
   * @return the trimmed name, or {@link #UNKNOWN}
   */
  @NotNull
  public static String text(@Nullable String name) {
    return name == null || name.isBlank() ? UNKNOWN : name.trim();
  }

  private static NoticeEvent.NoticeEventBuilder base(
      NotificationEventType type, UUID missionId, ActorRef actor) {
    return NoticeEvent.builder()
        .eventType(type)
        .actorSub(actor == null ? null : actor.id())
        .entityType(ENTITY_TYPE)
        .entityId(missionId)
        .contextMissionId(missionId);
  }

  private static Map<String, String> params(String... keyValues) {
    Map<String, String> params = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      params.put(keyValues[i], keyValues[i + 1]);
    }
    return params;
  }

  /**
   * A mission's meeting time or planned start moved.
   *
   * @param missionId the mission
   * @param missionName its name
   * @param oldTime the earlier of the two times before the change, or {@code null}
   * @param newTime that time after the change, or {@code null}
   * @param actor who moved it
   * @return the event
   */
  @NotNull
  public static NoticeEvent rescheduled(
      @NotNull UUID missionId,
      @NotNull String missionName,
      @Nullable Instant oldTime,
      @Nullable Instant newTime,
      @NotNull ActorRef actor) {
    return base(NotificationEventType.MISSION_RESCHEDULED, missionId, actor)
        .renderParams(
            params(
                "mission", text(missionName),
                "old", time(oldTime),
                "new", time(newTime),
                "actor", text(actor.name())))
        .resolvesNotificationTypes(
            Set.of(NotificationType.MISSION_RESCHEDULED, NotificationType.MISSION_REMINDER))
        .build();
  }

  /**
   * A mission was cancelled.
   *
   * @param missionId the mission
   * @param missionName its name
   * @param actor who cancelled it
   * @return the event
   */
  @NotNull
  public static NoticeEvent cancelled(
      @NotNull UUID missionId, @NotNull String missionName, @NotNull ActorRef actor) {
    return base(NotificationEventType.MISSION_CANCELLED, missionId, actor)
        .renderParams(params("mission", text(missionName), "actor", text(actor.name())))
        .resolvesNotificationTypes(OPEN_MISSION_NOTICES)
        .build();
  }

  /**
   * A mission was deleted.
   *
   * @param missionId the deleted mission
   * @param missionName its name at deletion
   * @param participants the registered participants captured before the delete
   * @param actor who deleted it
   * @return the event
   */
  @NotNull
  public static NoticeEvent deleted(
      @NotNull UUID missionId,
      @NotNull String missionName,
      @NotNull Set<UUID> participants,
      @NotNull ActorRef actor) {
    return base(NotificationEventType.MISSION_DELETED, missionId, actor)
        .contextMissionId(null)
        .contextRecipientUserIds(participants)
        .renderParams(params("mission", text(missionName), "actor", text(actor.name())))
        .resolvesNotificationTypes(OPEN_MISSION_NOTICES)
        .build();
  }

  /**
   * A mission starts in 24 hours or in one hour, for one participant.
   *
   * @param missionId the mission
   * @param missionName its name
   * @param participantId the participant, the single recipient
   * @param lead {@code 24 h} or {@code 1 h}
   * @param when the meeting time, or the planned start without one
   * @param meetingPoint the meeting point, or {@code null}
   * @param role the participant's planned role, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent reminder(
      @NotNull UUID missionId,
      @NotNull String missionName,
      @NotNull UUID participantId,
      @NotNull String lead,
      @Nullable Instant when,
      @Nullable String meetingPoint,
      @Nullable String role) {
    return base(NotificationEventType.MISSION_REMINDER_DUE, missionId, ActorRef.system())
        .contextRecipientUserId(participantId)
        .renderParams(
            params(
                "mission", text(missionName),
                "lead", lead,
                "when", time(when),
                "where", text(meetingPoint),
                "role", text(role)))
        .build();
  }

  /**
   * A mission became active; the participants who have not checked in are told.
   *
   * @param missionId the mission
   * @param missionName its name
   * @param start the actual start
   * @param actor who started it
   * @return the event
   */
  @NotNull
  public static NoticeEvent started(
      @NotNull UUID missionId,
      @NotNull String missionName,
      @Nullable Instant start,
      @NotNull ActorRef actor) {
    return base(NotificationEventType.MISSION_STARTED, missionId, actor)
        .contextMissionOnlyNotCheckedIn(true)
        .renderParams(params("mission", text(missionName), "start", time(start)))
        .resolvesNotificationTypes(Set.of(NotificationType.MISSION_REMINDER))
        .build();
  }

  /**
   * A participant checked in; only their own open check-in notice goes.
   *
   * @param missionId the mission
   * @param participantId the participant who checked in
   * @return the event
   */
  @NotNull
  public static NoticeEvent checkedIn(@NotNull UUID missionId, @NotNull UUID participantId) {
    return base(NotificationEventType.MISSION_CHECKED_IN, missionId, ActorRef.system())
        .resolvesNotificationTypesForRecipients(Set.of(NotificationType.MISSION_CHECKIN_OPEN))
        .supersedeRecipients(Set.of(participantId))
        .build();
  }

  /**
   * Somebody else added a member to a mission.
   *
   * @param missionId the mission
   * @param missionName its name
   * @param participantId the added member, the single recipient
   * @param start the planned start (meeting time without one), or {@code null}
   * @param payoutCode the payout choice stamped on the member, {@code PAYOUT} or {@code DONATE}
   * @param actor who added them
   * @return the event
   */
  @NotNull
  public static NoticeEvent participantAdded(
      @NotNull UUID missionId,
      @NotNull String missionName,
      @NotNull UUID participantId,
      @Nullable Instant start,
      @NotNull String payoutCode,
      @NotNull ActorRef actor) {
    return base(NotificationEventType.MISSION_PARTICIPANT_ADDED, missionId, actor)
        .contextRecipientUserId(participantId)
        .renderParams(
            params(
                "mission", text(missionName),
                "start", time(start),
                "payoutCode", payoutCode,
                "actor", text(actor.name())))
        .resolvesNotificationTypesForRecipients(
            Set.of(NotificationType.MISSION_PARTICIPANT_REMOVED_BY_OTHER))
        .supersedeRecipients(Set.of(participantId))
        .build();
  }

  /**
   * Somebody else removed a member from a mission; their add, reminder and check-in notices go.
   *
   * @param missionId the mission
   * @param missionName its name
   * @param participantId the removed member, the single recipient
   * @param actor who removed them
   * @return the event
   */
  @NotNull
  public static NoticeEvent participantRemoved(
      @NotNull UUID missionId,
      @NotNull String missionName,
      @NotNull UUID participantId,
      @NotNull ActorRef actor) {
    return base(NotificationEventType.MISSION_PARTICIPANT_REMOVED, missionId, actor)
        .contextRecipientUserId(participantId)
        .renderParams(params("mission", text(missionName), "actor", text(actor.name())))
        .resolvesNotificationTypesForRecipients(PARTICIPANT_NOTICES)
        .supersedeRecipients(Set.of(participantId))
        .build();
  }

  /**
   * A participant left a mission themselves; their own notices go.
   *
   * @param missionId the mission
   * @param missionName its name
   * @param participantId the member who left
   * @param participantName their effective name
   * @param freed the crew slot or role that became free, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent participantLeft(
      @NotNull UUID missionId,
      @NotNull String missionName,
      @NotNull UUID participantId,
      @NotNull String participantName,
      @Nullable String freed) {
    return base(
            NotificationEventType.MISSION_PARTICIPANT_LEFT,
            missionId,
            new ActorRef(participantId, participantName))
        .renderParams(
            params(
                "mission", text(missionName),
                "participant", text(participantName),
                "freed", text(freed)))
        .resolvesNotificationTypesForRecipients(PARTICIPANT_NOTICES)
        .supersedeRecipients(Set.of(participantId))
        .build();
  }

  /**
   * A mission has no end time although it is over.
   *
   * @param missionId the mission
   * @param missionName its name
   * @param plannedEnd the planned end, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent neverEnded(
      @NotNull UUID missionId, @NotNull String missionName, @Nullable Instant plannedEnd) {
    return base(NotificationEventType.MISSION_NEVER_ENDED, missionId, ActorRef.system())
        .renderParams(params("mission", text(missionName), "plannedEnd", time(plannedEnd)))
        .resolvesNotificationTypes(Set.of(NotificationType.MISSION_NEVER_ENDED))
        .build();
  }

  /**
   * A mission got its actual end time; the never-ended and check-in notices go.
   *
   * @param missionId the mission
   * @return the event
   */
  @NotNull
  public static NoticeEvent endRecorded(@NotNull UUID missionId) {
    return base(NotificationEventType.MISSION_END_RECORDED, missionId, ActorRef.system())
        .resolvesNotificationTypes(
            Set.of(NotificationType.MISSION_NEVER_ENDED, NotificationType.MISSION_CHECKIN_OPEN))
        .build();
  }

  /**
   * A member became responsible for part of a mission.
   *
   * @param missionId the mission
   * @param missionName its name
   * @param recipientId the new responsible member, the single recipient
   * @param roleCode {@code OWNER}, {@code MANAGER}, {@code PARTY_LEAD} or {@code UNIT_RESPONSIBLE}
   * @param actor who made the change
   * @return the event
   */
  @NotNull
  public static NoticeEvent responsibilityAssigned(
      @NotNull UUID missionId,
      @NotNull String missionName,
      @NotNull UUID recipientId,
      @NotNull String roleCode,
      @NotNull ActorRef actor) {
    return base(NotificationEventType.MISSION_RESPONSIBILITY_ASSIGNED, missionId, actor)
        .contextRecipientUserId(recipientId)
        .renderParams(
            params(
                "mission", text(missionName),
                "roleCode", roleCode,
                "actor", text(actor.name())))
        .build();
  }
}

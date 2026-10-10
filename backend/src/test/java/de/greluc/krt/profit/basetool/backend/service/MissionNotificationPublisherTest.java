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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionCrew;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class MissionNotificationPublisherTest {

  private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final Instant T0 = Instant.parse("2026-10-12T19:00:00Z");

  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private UserService userService;

  private MissionNotificationPublisher publisher;
  private Mission mission;
  private ActorRef actor = new ActorRef(ACTOR, "Ada");

  @BeforeEach
  void setUp() {
    publisher = new MissionNotificationPublisher(eventPublisher, userService);
    lenient().when(userService.currentActor()).thenAnswer(invocation -> actor);
    mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setName("Nachtflug");
    mission.setStatus("PLANNED");
    mission.setMeetingTime(T0);
    mission.setPlannedStartTime(T0.plus(30, ChronoUnit.MINUTES));
  }

  private NotificationEvent published() {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(captor.capture());
    return (NotificationEvent) captor.getValue();
  }

  private static User user(UUID id) {
    User user = new User();
    user.setId(id);
    user.setUsername("u-" + id);
    return user;
  }

  private MissionParticipant participant(UUID userId) {
    MissionParticipant participant = new MissionParticipant();
    participant.setId(UUID.randomUUID());
    participant.setMission(mission);
    participant.setUser(userId == null ? null : user(userId));
    participant.setPayoutPreference(PayoutPreference.DONATE);
    return participant;
  }

  @Test
  void aMovedMeetingTimeIsAnnouncedWithOldAndNewAndResetsBothReminders() {
    mission.setReminder24hSentAt(T0);
    mission.setReminder1hSentAt(T0);
    Instant oldMeeting = mission.getMeetingTime();
    mission.setMeetingTime(T0.plus(1, ChronoUnit.DAYS));

    publisher.scheduleChanged(mission, oldMeeting, mission.getPlannedStartTime());

    NotificationEvent event = published();
    assertThat(event.eventType()).isEqualTo(NotificationEventType.MISSION_RESCHEDULED);
    assertThat(event.actorSub()).isEqualTo(ACTOR);
    assertThat(event.contextMissionId()).isEqualTo(mission.getId());
    assertThat(event.renderParams())
        .containsEntry("mission", "Nachtflug")
        .containsEntry("old", "2026-10-12 19:00 UTC")
        .containsEntry("new", "2026-10-13 19:00 UTC")
        .containsEntry("actor", "Ada");
    assertThat(mission.getReminder24hSentAt()).isNull();
    assertThat(mission.getReminder1hSentAt()).isNull();
  }

  @Test
  void aMovedPlannedStartAloneIsAnnouncedWithThePlannedStart() {
    Instant oldStart = mission.getPlannedStartTime();
    mission.setPlannedStartTime(oldStart.plus(2, ChronoUnit.HOURS));

    publisher.scheduleChanged(mission, mission.getMeetingTime(), oldStart);

    assertThat(published().renderParams())
        .containsEntry("old", "2026-10-12 19:30 UTC")
        .containsEntry("new", "2026-10-12 21:30 UTC");
  }

  @Test
  void anUnchangedScheduleAnnouncesNothingAndKeepsTheReminders() {
    mission.setReminder24hSentAt(T0);

    publisher.scheduleChanged(mission, mission.getMeetingTime(), mission.getPlannedStartTime());

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
    assertThat(mission.getReminder24hSentAt()).isEqualTo(T0);
  }

  @Test
  void cancellingAMissionIsAnnouncedOnce() {
    mission.setStatus("CANCELLED");

    publisher.statusChanged(mission, "PLANNED");

    assertThat(published().eventType()).isEqualTo(NotificationEventType.MISSION_CANCELLED);
  }

  @Test
  void aMissionBecomingActiveOpensTheCheckInForTheParticipantsWhoHaveNotCheckedIn() {
    mission.setStatus("ACTIVE");
    mission.setActualStartTime(T0);

    publisher.statusChanged(mission, "PLANNED");

    NotificationEvent event = published();
    assertThat(event.eventType()).isEqualTo(NotificationEventType.MISSION_STARTED);
    assertThat(event.contextMissionOnlyNotCheckedIn()).isTrue();
    assertThat(event.renderParams()).containsEntry("start", "2026-10-12 19:00 UTC");
  }

  @Test
  void aStatusThatDidNotChangeAnnouncesNothing() {
    mission.setStatus("ACTIVE");

    publisher.statusChanged(mission, "ACTIVE");

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
  }

  @Test
  void completingWithoutAnEndTimeReportsTheMissionAsNeverEndedOnce() {
    mission.setStatus("COMPLETED");

    publisher.statusChanged(mission, "ACTIVE");

    assertThat(published().eventType()).isEqualTo(NotificationEventType.MISSION_NEVER_ENDED);
    assertThat(mission.getNeverEndedNotifiedAt()).isNotNull();
  }

  @Test
  void completingWithAnEndTimeReportsNothing() {
    mission.setStatus("COMPLETED");
    mission.setActualEndTime(T0);

    publisher.statusChanged(mission, "ACTIVE");

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
    assertThat(mission.getNeverEndedNotifiedAt()).isNull();
  }

  @Test
  void aNeverEndedMissionAlreadyReportedIsNotReportedAgain() {
    mission.setNeverEndedNotifiedAt(T0);

    publisher.markNeverEnded(mission);

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
  }

  @Test
  void recordingTheEndTimeClearsTheNeverEndedNoticeAndRearmsIt() {
    mission.setNeverEndedNotifiedAt(T0);
    mission.setActualEndTime(T0);

    publisher.endChanged(mission, null, null);

    assertThat(published().eventType()).isEqualTo(NotificationEventType.MISSION_END_RECORDED);
    assertThat(mission.getNeverEndedNotifiedAt()).isNull();
  }

  @Test
  void aMovedPlannedEndRearmsTheNeverEndedNoticeWithoutAnEvent() {
    mission.setNeverEndedNotifiedAt(T0);
    mission.setPlannedEndTime(T0.plus(1, ChronoUnit.DAYS));

    publisher.endChanged(mission, T0, null);

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
    assertThat(mission.getNeverEndedNotifiedAt()).isNull();
  }

  @Test
  void aDeletedMissionListsItsRegisteredParticipants() {
    mission.setParticipants(new HashSet<>(Set.of(participant(MEMBER), participant(null))));

    publisher.deleted(
        mission.getId(),
        mission.getName(),
        MissionNotificationPublisher.participantUserIds(mission));

    NotificationEvent event = published();
    assertThat(event.eventType()).isEqualTo(NotificationEventType.MISSION_DELETED);
    assertThat(event.contextRecipientUserIds()).containsExactly(MEMBER);
  }

  @Test
  void addingAnotherMemberNotifiesThemWithTheStampedPayoutChoice() {
    publisher.participantAdded(mission, participant(MEMBER));

    NotificationEvent event = published();
    assertThat(event.eventType()).isEqualTo(NotificationEventType.MISSION_PARTICIPANT_ADDED);
    assertThat(event.contextRecipientUserId()).isEqualTo(MEMBER);
    assertThat(event.renderParams())
        .containsEntry("payoutCode", "DONATE")
        .containsEntry("actor", "Ada");
  }

  @Test
  void signingUpYourselfOrAddingAGuestNotifiesNobody() {
    publisher.participantAdded(mission, participant(ACTOR));
    publisher.participantAdded(mission, participant(null));

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
  }

  @Test
  void removingAnotherMemberNotifiesThemAndClearsTheirNotices() {
    publisher.participantRemoved(mission, participant(MEMBER));

    NotificationEvent event = published();
    assertThat(event.eventType()).isEqualTo(NotificationEventType.MISSION_PARTICIPANT_REMOVED);
    assertThat(event.contextRecipientUserId()).isEqualTo(MEMBER);
    assertThat(event.supersedeRecipients()).containsExactly(MEMBER);
  }

  @Test
  void leavingWithAPlannedRoleTellsTheLeadershipWhatBecameFree() {
    actor = new ActorRef(MEMBER, "Bob");
    MissionParticipant leaving = participant(MEMBER);
    JobType pilot = new JobType();
    pilot.setName("Pilot");
    leaving.setPlannedMissionJobType(pilot);
    mission.setMeetingTime(Instant.now().plus(5, ChronoUnit.DAYS));

    publisher.participantRemoved(mission, leaving);

    NotificationEvent event = published();
    assertThat(event.eventType()).isEqualTo(NotificationEventType.MISSION_PARTICIPANT_LEFT);
    assertThat(event.renderParams())
        .containsEntry("participant", "Bob")
        .containsEntry("freed", "Pilot");
  }

  @Test
  void leavingFromACrewSlotNamesTheUnit() {
    actor = new ActorRef(MEMBER, "Bob");
    MissionParticipant leaving = participant(MEMBER);
    MissionUnit unit = new MissionUnit();
    unit.setName("Constellation");
    MissionCrew crew = new MissionCrew();
    crew.setParticipant(leaving);
    unit.setCrew(new HashSet<>(Set.of(crew)));
    mission.setAssignedUnits(new java.util.LinkedHashSet<>(Set.of(unit)));
    mission.setMeetingTime(Instant.now().plus(5, ChronoUnit.DAYS));

    publisher.participantRemoved(mission, leaving);

    assertThat(published().renderParams()).containsEntry("freed", "Constellation");
  }

  @Test
  void leavingWithoutASlotFarBeforeTheStartIsNotWorthANotice() {
    actor = new ActorRef(MEMBER, "Bob");
    mission.setMeetingTime(Instant.now().plus(5, ChronoUnit.DAYS));

    publisher.participantRemoved(mission, participant(MEMBER));

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
  }

  @Test
  void leavingWithoutASlotShortlyBeforeTheStartStillIs() {
    actor = new ActorRef(MEMBER, "Bob");
    mission.setMeetingTime(Instant.now().plus(2, ChronoUnit.HOURS));

    publisher.participantRemoved(mission, participant(MEMBER));

    assertThat(published().eventType()).isEqualTo(NotificationEventType.MISSION_PARTICIPANT_LEFT);
  }

  @Test
  void checkingInClearsThatParticipantsOpenCheckInNoticeOnly() {
    publisher.checkedIn(mission, participant(MEMBER));

    NotificationEvent event = published();
    assertThat(event.eventType()).isEqualTo(NotificationEventType.MISSION_CHECKED_IN);
    assertThat(event.supersedeRecipients()).containsExactly(MEMBER);
    assertThat(event.resolvesNotificationTypes()).isEmpty();
  }

  @Test
  void aNewResponsibleIsToldUnlessTheyMadeTheChangeThemselves() {
    publisher.responsibilityAssigned(mission, MEMBER, "MANAGER");
    NotificationEvent event = published();
    assertThat(event.eventType()).isEqualTo(NotificationEventType.MISSION_RESPONSIBILITY_ASSIGNED);
    assertThat(event.contextRecipientUserId()).isEqualTo(MEMBER);
    assertThat(event.renderParams()).containsEntry("roleCode", "MANAGER");
  }

  @Test
  void takingOnResponsibilityYourselfNotifiesNobody() {
    publisher.responsibilityAssigned(mission, ACTOR, "OWNER");

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
  }
}

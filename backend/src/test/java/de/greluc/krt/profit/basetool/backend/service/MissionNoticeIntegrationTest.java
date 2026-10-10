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

import de.greluc.krt.profit.basetool.backend.mission.api.events.MissionNotices;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionNeverEndedNoticeProducer;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionReminderNoticeProducer;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mission notices end to end against Postgres (REQ-MISSION-021…029): the seeded rules resolve
 * the right members, the producers raise each reminder once, and a check-in clears one member's
 * notice only. The test runs in a rolled-back transaction, so nothing is delivered asynchronously.
 */
@SpringBootTest
@Transactional
class MissionNoticeIntegrationTest {

  private static final UUID OWNER = UUID.fromString("44444444-4444-4444-4444-4444444460c1");
  private static final UUID ALICE = UUID.fromString("44444444-4444-4444-4444-4444444460c2");
  private static final UUID BOB = UUID.fromString("44444444-4444-4444-4444-4444444460c3");
  private static final ActorRef ACTOR = new ActorRef(OWNER, "Owner");

  @Autowired private NotificationCreationService creationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private MissionReminderNoticeProducer reminderProducer;
  @Autowired private MissionNeverEndedNoticeProducer neverEndedProducer;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository participantRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;

  private Mission mission;

  @BeforeEach
  void seed() {
    User owner = user(OWNER, "mission-owner");
    User alice = user(ALICE, "mission-alice");
    User bob = user(BOB, "mission-bob");
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Nachtflug");
    mission.setStatus("PLANNED");
    mission.setOwner(owner);
    mission.setMeetingTime(Instant.now().plus(Duration23h30m()));
    mission = missionRepository.saveAndFlush(mission);
    participant(alice, null);
    participant(bob, null);
    missionRepository.flush();
  }

  private static java.time.Duration Duration23h30m() {
    return java.time.Duration.ofHours(23).plusMinutes(30);
  }

  private User user(UUID id, String name) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user);
  }

  private MissionParticipant participant(User user, Instant checkedIn) {
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(user);
    participant.setStartTime(checkedIn);
    mission.getParticipants().add(participant);
    return participantRepository.saveAndFlush(participant);
  }

  private List<NotificationType> inbox(UUID member) {
    return notificationRepository
        .findAllByRecipientUserId(member, PageRequest.of(0, 50))
        .map(Notification::getType)
        .getContent();
  }

  @Test
  void aRescheduleNotifiesTheParticipantsButNotTheActor() {
    creationService.createFromEvent(
        MissionNotices.rescheduled(
            mission.getId(),
            mission.getName(),
            Instant.now(),
            Instant.now().plus(1, ChronoUnit.DAYS),
            ACTOR));

    assertThat(inbox(ALICE)).containsExactly(NotificationType.MISSION_RESCHEDULED);
    assertThat(inbox(BOB)).containsExactly(NotificationType.MISSION_RESCHEDULED);
    assertThat(inbox(OWNER)).isEmpty();
  }

  @Test
  void aSecondRescheduleReplacesTheFirstSoOnlyTheLatestChangeStays() {
    for (int i = 0; i < 2; i++) {
      creationService.createFromEvent(
          MissionNotices.rescheduled(
              mission.getId(), mission.getName(), Instant.now(), Instant.now(), ACTOR));
    }

    assertThat(inbox(ALICE)).containsExactly(NotificationType.MISSION_RESCHEDULED);
  }

  @Test
  void aCancellationNotifiesTheParticipantsAndClearsTheirRescheduleNotice() {
    creationService.createFromEvent(
        MissionNotices.rescheduled(
            mission.getId(), mission.getName(), Instant.now(), Instant.now(), ACTOR));

    creationService.createFromEvent(
        MissionNotices.cancelled(mission.getId(), mission.getName(), ACTOR));

    assertThat(inbox(ALICE)).containsExactly(NotificationType.MISSION_CANCELLED);
  }

  @Test
  void aDeletionReachesTheParticipantsTheEventListsEvenThoughTheMissionIsGone() {
    creationService.createFromEvent(
        MissionNotices.deleted(mission.getId(), mission.getName(), Set.of(ALICE, BOB), ACTOR));

    assertThat(inbox(ALICE)).containsExactly(NotificationType.MISSION_DELETED);
    assertThat(inbox(BOB)).containsExactly(NotificationType.MISSION_DELETED);
  }

  @Test
  void startingAMissionAsksOnlyThoseWhoHaveNotCheckedInAndACheckInClearsOneNotice() {
    MissionParticipant alice =
        mission.getParticipants().stream()
            .filter(p -> ALICE.equals(p.getUser().getId()))
            .findFirst()
            .orElseThrow();
    alice.setStartTime(Instant.now());
    participantRepository.saveAndFlush(alice);

    creationService.createFromEvent(
        MissionNotices.started(mission.getId(), mission.getName(), Instant.now(), ACTOR));

    assertThat(inbox(ALICE)).isEmpty();
    assertThat(inbox(BOB)).containsExactly(NotificationType.MISSION_CHECKIN_OPEN);

    creationService.createFromEvent(MissionNotices.checkedIn(mission.getId(), BOB));

    assertThat(inbox(BOB)).isEmpty();
  }

  @Test
  void aReminderIsRaisedOncePerMissionAndAsksEachRegisteredParticipant() {
    int first = reminderProducer.produce(Instant.now());
    int second = reminderProducer.produce(Instant.now());

    assertThat(first).isEqualTo(2);
    assertThat(second).isZero();
    assertThat(missionRepository.findById(mission.getId()).orElseThrow().getReminder24hSentAt())
        .isNotNull();
  }

  @Test
  void aMissionBeyondTheWindowOrAlreadyStartedGetsNoReminder() {
    mission.setMeetingTime(Instant.now().plus(3, ChronoUnit.DAYS));
    missionRepository.saveAndFlush(mission);
    assertThat(reminderProducer.produce(Instant.now())).isZero();

    mission.setMeetingTime(Instant.now().minus(1, ChronoUnit.HOURS));
    missionRepository.saveAndFlush(mission);
    assertThat(reminderProducer.produce(Instant.now())).isZero();

    mission.setMeetingTime(Instant.now().plus(40, ChronoUnit.MINUTES));
    mission.setStatus("CANCELLED");
    missionRepository.saveAndFlush(mission);
    assertThat(reminderProducer.produce(Instant.now())).isZero();
  }

  @Test
  void theOneHourReminderIsRaisedInItsOwnWindow() {
    mission.setMeetingTime(Instant.now().plus(40, ChronoUnit.MINUTES));
    missionRepository.saveAndFlush(mission);

    assertThat(reminderProducer.produce(Instant.now())).isEqualTo(2);
    assertThat(reminderProducer.produce(Instant.now())).isZero();
    assertThat(missionRepository.findById(mission.getId()).orElseThrow().getReminder1hSentAt())
        .isNotNull();
  }

  @Test
  void aMissionPastItsPlannedEndWithoutAnEndTimeIsReportedOnce() {
    mission.setStatus("ACTIVE");
    mission.setPlannedEndTime(Instant.now().minus(7, ChronoUnit.HOURS));
    missionRepository.saveAndFlush(mission);

    assertThat(neverEndedProducer.produce(Instant.now())).isEqualTo(1);
    assertThat(neverEndedProducer.produce(Instant.now())).isZero();
  }

  @Test
  void aMissionThatEndedOrIsOnlySlightlyLateIsNotReported() {
    mission.setStatus("ACTIVE");
    mission.setPlannedEndTime(Instant.now().minus(1, ChronoUnit.HOURS));
    missionRepository.saveAndFlush(mission);
    assertThat(neverEndedProducer.produce(Instant.now())).isZero();

    mission.setPlannedEndTime(Instant.now().minus(9, ChronoUnit.HOURS));
    mission.setActualEndTime(Instant.now().minus(8, ChronoUnit.HOURS));
    missionRepository.saveAndFlush(mission);
    assertThat(neverEndedProducer.produce(Instant.now())).isZero();
  }

  @Test
  void theNeverEndedNoticeGoesToTheOwnerAndThePlainParticipantsAreLeftAlone() {
    creationService.createFromEvent(
        MissionNotices.neverEnded(mission.getId(), mission.getName(), Instant.now()));

    assertThat(inbox(OWNER)).containsExactly(NotificationType.MISSION_NEVER_ENDED);
    assertThat(inbox(ALICE)).isEmpty();
  }
}

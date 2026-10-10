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

import de.greluc.krt.profit.basetool.backend.admin.api.events.HangarNotices;
import de.greluc.krt.profit.basetool.backend.mission.api.events.MissionNotices;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hangar and blueprint notices end to end against Postgres (REQ-HANGAR-005…008): the seeded
 * rules reach the ship's owner, the unit's responsible member and a member whose data an admin
 * changed, never the actor, and unassigning a ship clears the owner's notice. Runs in a rolled-back
 * transaction.
 */
@SpringBootTest
@Transactional
class HangarNoticeIntegrationTest {

  private static final UUID OWNER = UUID.fromString("44444444-4444-4444-4444-4444444493c1");
  private static final UUID ACTOR = UUID.fromString("44444444-4444-4444-4444-4444444493c2");
  private static final UUID RESPONSIBLE = UUID.fromString("44444444-4444-4444-4444-4444444493c3");
  private static final ActorRef ACTING = new ActorRef(ACTOR, "Ada");

  @Autowired private NotificationCreationService creationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;

  private UUID unitId;
  private UUID missionId;

  @BeforeEach
  void seed() {
    unitId = UUID.randomUUID();
    missionId = UUID.randomUUID();
    user(OWNER, "hangar-owner");
    user(ACTOR, "hangar-actor");
    user(RESPONSIBLE, "hangar-responsible");
  }

  private void user(UUID id, String name) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    userRepository.saveAndFlush(user);
  }

  private List<NotificationType> inbox(UUID member) {
    return notificationRepository
        .findAllByRecipientUserId(member, PageRequest.of(0, 50))
        .map(Notification::getType)
        .getContent();
  }

  private void assign() {
    creationService.createFromEvent(
        MissionNotices.shipAssigned(
            unitId, OWNER, "Cutlass Black", "Op Aurora", "Alpha", Instant.now(), ACTING));
  }

  @Test
  void anAssignmentTellsTheShipsOwnerButNotTheActor() {
    assign();

    assertThat(inbox(OWNER)).containsExactly(NotificationType.HANGAR_SHIP_ASSIGNED);
    assertThat(inbox(ACTOR)).isEmpty();
  }

  @Test
  void aSecondAssignmentOfTheSameUnitReplacesTheFirst() {
    assign();
    assign();

    assertThat(inbox(OWNER)).containsExactly(NotificationType.HANGAR_SHIP_ASSIGNED);
  }

  @Test
  void unassigningTheShipClearsTheOwnersNotice() {
    assign();

    creationService.createFromEvent(MissionNotices.shipUnassigned(unitId, OWNER));

    assertThat(inbox(OWNER)).isEmpty();
  }

  @Test
  void aDeletedShipTellsTheUnitsResponsibleMemberButNotTheActor() {
    creationService.createFromEvent(
        HangarNotices.shipDeleted(
            missionId, RESPONSIBLE, "Cutlass Black", "Op Aurora", "Alpha", ACTING));

    assertThat(inbox(RESPONSIBLE)).containsExactly(NotificationType.HANGAR_SHIP_REMOVED_FROM_UNIT);
    assertThat(inbox(ACTOR)).isEmpty();
  }

  @Test
  void aFittedResetAndAnAdminsChangesReachTheMemberOnly() {
    creationService.createFromEvent(HangarNotices.fittedReset(OWNER, 3, ACTING));
    creationService.createFromEvent(HangarNotices.hangarChanged(OWNER, "ADDED", "Carrack", ACTING));
    creationService.createFromEvent(
        HangarNotices.blueprintsChanged(OWNER, "IMPORTED", "5×", ACTING));
    creationService.createFromEvent(HangarNotices.blueprintsPurged(OWNER, 12, ACTING));

    assertThat(inbox(OWNER))
        .containsExactlyInAnyOrder(
            NotificationType.HANGAR_FITTED_RESET,
            NotificationType.HANGAR_CHANGED_BY_ADMIN,
            NotificationType.BLUEPRINT_CHANGED_BY_ADMIN,
            NotificationType.BLUEPRINT_PURGED_BY_ADMIN);
    assertThat(inbox(ACTOR)).isEmpty();
  }
}

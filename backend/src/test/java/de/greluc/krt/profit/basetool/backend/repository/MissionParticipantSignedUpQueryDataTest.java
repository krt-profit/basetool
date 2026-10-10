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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies {@link MissionParticipantRepository#findMissionIdsSignedUpBy} against PostgreSQL: it
 * returns exactly the given missions the member holds a participant row on (REQ-MISSION-012). Each
 * test rolls back and is scoped to its own ids.
 */
@SpringBootTest
@Transactional
class MissionParticipantSignedUpQueryDataTest {

  @Autowired private MissionParticipantRepository participantRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;

  /**
   * Verifies that only the member's own rows count: another member's row, a guest row and a mission
   * outside the given ids are not reported.
   */
  @Test
  void findMissionIdsSignedUpBy_returnsOnlyTheMembersOwnMissionsAmongTheGivenIds() {
    User member = newUser();
    User peer = newUser();
    Mission joined = newMission();
    Mission peerOnly = newMission();
    Mission guestOnly = newMission();
    Mission outsidePage = newMission();
    newParticipant(joined, member);
    newParticipant(peerOnly, peer);
    newParticipant(guestOnly, null);
    newParticipant(outsidePage, member);

    List<UUID> result =
        participantRepository.findMissionIdsSignedUpBy(
            member.getId(), List.of(joined.getId(), peerOnly.getId(), guestOnly.getId()));

    assertThat(result).containsExactly(joined.getId());
  }

  /** Verifies that a member with no participant row on the page gets an empty result. */
  @Test
  void findMissionIdsSignedUpBy_isEmptyForAMemberWithoutRows() {
    User member = newUser();
    Mission mission = newMission();
    newParticipant(mission, newUser());

    assertThat(
            participantRepository.findMissionIdsSignedUpBy(
                member.getId(), List.of(mission.getId())))
        .isEmpty();
  }

  private User newUser() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("signed-up-" + UUID.randomUUID());
    return userRepository.save(user);
  }

  private Mission newMission() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("Signed-Up-" + tag);
    squadron.setShorthand("SU" + tag);
    OrgUnit owner = squadronRepository.save(squadron);

    Mission mission = new Mission();
    mission.setName("Signed-Up-Mission-" + tag);
    mission.setStatus("PLANNED");
    mission.setIsInternal(false);
    mission.setOwningOrgUnit(owner);
    return missionRepository.save(mission);
  }

  private void newParticipant(Mission mission, User user) {
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(user);
    if (user == null) {
      participant.setGuestName("Signed-up guest");
    }
    participantRepository.save(participant);
  }
}

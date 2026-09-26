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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies org-unit stamping of mission participants: a registered participant's affiliations come
 * from their memberships, while a guest's affiliation uses the submitted org units verbatim.
 */
@SpringBootTest
@Transactional
class MissionParticipantOrgUnitTest {

  @Autowired private MissionService missionService;
  @Autowired private MissionRepository missionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;

  private Mission mission;
  private Squadron testStaffel;
  private SpecialCommand testSk;

  @BeforeEach
  void setup() {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName("Test Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    testStaffel = new Squadron();
    testStaffel.setName("Test Staffel");
    testStaffel.setShorthand("TST");
    testStaffel = squadronRepository.save(testStaffel);

    testSk = new SpecialCommand();
    testSk.setName("Test Spezialkommando");
    testSk.setShorthand("TSK");
    testSk = specialCommandRepository.save(testSk);
  }

  private User newUser(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }

  private void addMembership(User user, OrgUnit orgUnit, OrgUnitKind kind) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(user.getId(), orgUnit.getId()));
    m.setUser(user);
    m.setKind(kind);
    m.setJoinedAt(Instant.now());
    membershipRepository.saveAndFlush(m);
  }

  private MissionParticipant onlyParticipant(Mission updated) {
    return updated.getParticipants().iterator().next();
  }

  @Test
  void registeredUserWithNoMembership_getsNoOrgUnit() {
    User user = newUser("nomember");

    Mission updated =
        missionService.addParticipant(mission.getId(), user.getId(), null, null, null, null, null);

    assertTrue(
        onlyParticipant(updated).getOrgUnits().isEmpty(),
        "a user with no membership must get no org-unit affiliation (no IRIDIUM fallback)");
  }

  @Test
  void registeredUserWithOnlyStaffel_getsThatStaffel() {
    User user = newUser("staffelonly");
    addMembership(user, testStaffel, OrgUnitKind.SQUADRON);

    Mission updated =
        missionService.addParticipant(mission.getId(), user.getId(), null, null, null, null, null);

    List<UUID> ids = onlyParticipant(updated).getOrgUnits().stream().map(OrgUnit::getId).toList();
    assertEquals(List.of(testStaffel.getId()), ids);
  }

  @Test
  void registeredUserWithOnlySpecialCommand_getsThatSk() {
    User user = newUser("skonly");
    addMembership(user, testSk, OrgUnitKind.SPECIAL_COMMAND);

    Mission updated =
        missionService.addParticipant(mission.getId(), user.getId(), null, null, null, null, null);

    List<UUID> ids = onlyParticipant(updated).getOrgUnits().stream().map(OrgUnit::getId).toList();
    assertEquals(List.of(testSk.getId()), ids);
  }

  @Test
  void registeredUserWithStaffelAndSk_getsBoth() {
    User user = newUser("both");
    addMembership(user, testStaffel, OrgUnitKind.SQUADRON);
    addMembership(user, testSk, OrgUnitKind.SPECIAL_COMMAND);

    Mission updated =
        missionService.addParticipant(mission.getId(), user.getId(), null, null, null, null, null);

    List<UUID> ids = onlyParticipant(updated).getOrgUnits().stream().map(OrgUnit::getId).toList();
    assertTrue(ids.contains(testStaffel.getId()), "Staffel affiliation must be present");
    assertTrue(ids.contains(testSk.getId()), "SK affiliation must be present");
    assertEquals(2, ids.size());
  }

  @Test
  void guest_anonymousCaller_submittedOrgUnitsAreHonored() {
    Mission updated =
        missionService.addParticipant(
            mission.getId(),
            null,
            "Guest",
            null,
            "Comment",
            List.of(testStaffel.getId(), testSk.getId()),
            null);

    MissionParticipant participant =
        updated.getParticipants().stream()
            .filter(p -> "Guest".equals(p.getGuestName()))
            .findFirst()
            .orElseThrow();
    List<UUID> ids = participant.getOrgUnits().stream().map(OrgUnit::getId).toList();
    assertTrue(ids.contains(testStaffel.getId()), "guest Staffel label must be honored");
    assertTrue(ids.contains(testSk.getId()), "guest SK label must be honored");
    assertEquals(2, ids.size());
  }
}

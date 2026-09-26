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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionCrew;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.MissionCrewRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the unique-index backstops behind the in-memory mission guards: at most one
 * Einsatzleiter per mission (REQ-MISSION-013) and one crew per participant, each surfacing as a
 * {@link DataIntegrityViolationException}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionUniqueIndexBackstopTest {

  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository missionParticipantRepository;
  @Autowired private MissionUnitRepository missionUnitRepository;
  @Autowired private MissionCrewRepository missionCrewRepository;

  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void twoEinsatzleiterInSameMission_secondViolatesPartialUniqueIndex() {
    Mission mission = persistPlannedMission("Lead Index Mission");

    MissionParticipant first = persistGuestParticipant(mission, "Lead A");
    first.setMissionLeadParticipant(true);
    missionParticipantRepository.saveAndFlush(first);

    MissionParticipant second = persistGuestParticipant(mission, "Lead B");
    second.setMissionLeadParticipant(true);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> missionParticipantRepository.saveAndFlush(second));
  }

  @Test
  void einsatzleiterInADifferentMission_isAllowed() {
    MissionParticipant a = persistGuestParticipant(persistPlannedMission("Lead M1"), "Lead A");
    a.setMissionLeadParticipant(true);
    missionParticipantRepository.saveAndFlush(a);

    MissionParticipant b = persistGuestParticipant(persistPlannedMission("Lead M2"), "Lead B");
    b.setMissionLeadParticipant(true);
    assertDoesNotThrow(() -> missionParticipantRepository.saveAndFlush(b));
  }

  @Test
  void participantInTwoCrews_secondViolatesUniqueIndex() {
    Mission mission = persistPlannedMission("Crew Index Mission");

    MissionUnit unit = new MissionUnit();
    unit.setMission(mission);
    unit.setName("Alpha");
    unit = missionUnitRepository.saveAndFlush(unit);

    MissionParticipant participant = persistGuestParticipant(mission, "Crewman");

    MissionCrew firstCrew = new MissionCrew();
    firstCrew.setMissionUnit(unit);
    firstCrew.setParticipant(participant);
    missionCrewRepository.saveAndFlush(firstCrew);

    MissionCrew secondCrew = new MissionCrew();
    secondCrew.setMissionUnit(unit);
    secondCrew.setParticipant(participant);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> missionCrewRepository.saveAndFlush(secondCrew));
  }

  private Mission persistPlannedMission(String name) {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName(name + " " + UUID.randomUUID());
    mission.setStatus("PLANNED");
    return missionRepository.saveAndFlush(mission);
  }

  private MissionParticipant persistGuestParticipant(Mission mission, String guestName) {
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setGuestName(guestName + " " + UUID.randomUUID());
    return missionParticipantRepository.saveAndFlush(participant);
  }
}

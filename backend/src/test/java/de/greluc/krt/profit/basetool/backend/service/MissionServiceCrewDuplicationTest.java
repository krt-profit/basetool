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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MissionServiceCrewDuplicationTest {

  @Mock private MissionRepository missionRepository;
  @Mock private UserRepository userRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private JobTypeRepository jobTypeRepository;
  @Mock private SquadronRepository squadronRepository;

  @Mock private AuditService auditService;
  @InjectMocks private MissionStructureService missionStructureService;

  @Test
  void addCrewToShip_shouldThrowIfParticipantAlreadyAssignedToSameUnit() {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    Mission mission = new Mission();
    mission.setId(missionId);

    MissionUnit unit = new MissionUnit();
    unit.setId(unitId);
    mission.getAssignedUnits().add(unit);

    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    mission.getParticipants().add(participant);

    MissionCrew existingCrew = new MissionCrew();
    existingCrew.setMissionUnit(unit);
    existingCrew.setParticipant(participant);
    unit.getCrew().add(existingCrew);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException.class,
        () ->
            missionStructureService.addCrewToShip(
                missionId, unitId, participantId, Collections.emptySet()));
  }

  @Test
  void addCrewToShip_shouldThrowIfParticipantAlreadyAssignedToOtherUnit() {
    UUID missionId = UUID.randomUUID();
    UUID unitId1 = UUID.randomUUID();
    UUID unitId2 = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();

    Mission mission = new Mission();
    mission.setId(missionId);

    MissionUnit unit1 = new MissionUnit();
    unit1.setId(unitId1);
    mission.getAssignedUnits().add(unit1);

    MissionUnit unit2 = new MissionUnit();
    unit2.setId(unitId2);
    mission.getAssignedUnits().add(unit2);

    MissionParticipant participant = new MissionParticipant();
    participant.setId(participantId);
    mission.getParticipants().add(participant);

    MissionCrew existingCrew = new MissionCrew();
    existingCrew.setMissionUnit(unit1);
    existingCrew.setParticipant(participant);
    unit1.getCrew().add(existingCrew);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException.class,
        () ->
            missionStructureService.addCrewToShip(
                missionId, unitId2, participantId, Collections.emptySet()));
  }
}

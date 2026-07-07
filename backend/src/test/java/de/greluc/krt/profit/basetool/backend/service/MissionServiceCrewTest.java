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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class MissionServiceCrewTest {

  @Mock private MissionRepository missionRepository;
  @Mock private UserRepository userRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MissionCrewRepository missionCrewRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private JobTypeRepository jobTypeRepository;
  @Mock private SquadronRepository squadronRepository;

  @Mock private AuditService auditService;
  @InjectMocks private MissionStructureService missionStructureService;

  @Test
  void updateCrewInShip_shouldUpdateJobTypes() {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID crewId = UUID.randomUUID();
    UUID jobTypeId = UUID.randomUUID();

    Mission mission = new Mission();
    mission.setId(missionId);

    MissionUnit unit = new MissionUnit();
    unit.setId(unitId);
    mission.getAssignedUnits().add(unit);

    MissionCrew crew = new MissionCrew();
    crew.setId(crewId);
    unit.getCrew().add(crew);

    JobType jobType = new JobType();
    jobType.setId(jobTypeId);
    jobType.setArchetype(JobTypeArchetype.CREW);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(jobTypeRepository.findById(jobTypeId)).thenReturn(Optional.of(jobType));

    Mission updatedMission =
        missionStructureService.updateCrewInShip(
            missionId, unitId, crewId, null, Set.of(jobTypeId));

    assertNotNull(updatedMission);
    MissionUnit updatedUnit = updatedMission.getAssignedUnits().iterator().next();
    MissionCrew updatedCrew = updatedUnit.getCrew().iterator().next();
    assertEquals(1, updatedCrew.getJobTypes().size());
    assertEquals(jobTypeId, updatedCrew.getJobTypes().iterator().next().getId());

    // Option A: parent Mission.version must NOT be bumped by a sub-section (crew) write.
    verify(missionRepository, never()).save(any(Mission.class));
    verify(missionCrewRepository).save(crew);
  }

  @Test
  void updateCrewInShip_shouldThrowIfMissionNotFound() {
    UUID missionId = UUID.randomUUID();
    when(missionRepository.findById(missionId)).thenReturn(Optional.empty());

    assertThrows(
        RuntimeException.class,
        () ->
            missionStructureService.updateCrewInShip(
                missionId, UUID.randomUUID(), UUID.randomUUID(), null, Collections.emptySet()));
  }

  @Test
  void updateCrewInShip_shouldThrowIfUnitNotFound() {
    UUID missionId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    assertThrows(
        RuntimeException.class,
        () ->
            missionStructureService.updateCrewInShip(
                missionId, UUID.randomUUID(), UUID.randomUUID(), null, Collections.emptySet()));
  }

  @Test
  void updateCrewInShip_shouldThrowIfCrewNotFound() {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();

    Mission mission = new Mission();
    mission.setId(missionId);

    MissionUnit unit = new MissionUnit();
    unit.setId(unitId);
    mission.getAssignedUnits().add(unit);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    assertThrows(
        RuntimeException.class,
        () ->
            missionStructureService.updateCrewInShip(
                missionId, unitId, UUID.randomUUID(), null, Collections.emptySet()));
  }

  @Test
  void updateCrewInShip_throwsConflict_whenClientVersionStale() {
    // #1131: a stale full-set crew save must 409 rather than silently revert a concurrent edit.
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID crewId = UUID.randomUUID();

    Mission mission = new Mission();
    mission.setId(missionId);
    MissionUnit unit = new MissionUnit();
    unit.setId(unitId);
    mission.getAssignedUnits().add(unit);
    MissionCrew crew = new MissionCrew();
    crew.setId(crewId);
    crew.setVersion(5L); // persisted version
    unit.getCrew().add(crew);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    // Client echoes a stale version (4 != 5) -> optimistic-lock conflict, before any job-type
    // fetch.
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () ->
            missionStructureService.updateCrewInShip(
                missionId, unitId, crewId, 4L, Set.of(UUID.randomUUID())));
    verify(missionCrewRepository, never()).save(any());
  }

  @Test
  void updateCrewInShip_passes_whenClientVersionMatches() {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID crewId = UUID.randomUUID();
    UUID jobTypeId = UUID.randomUUID();

    Mission mission = new Mission();
    mission.setId(missionId);
    MissionUnit unit = new MissionUnit();
    unit.setId(unitId);
    mission.getAssignedUnits().add(unit);
    MissionCrew crew = new MissionCrew();
    crew.setId(crewId);
    crew.setVersion(7L);
    unit.getCrew().add(crew);

    JobType jobType = new JobType();
    jobType.setId(jobTypeId);
    jobType.setArchetype(JobTypeArchetype.CREW);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(jobTypeRepository.findById(jobTypeId)).thenReturn(Optional.of(jobType));

    Mission result =
        missionStructureService.updateCrewInShip(missionId, unitId, crewId, 7L, Set.of(jobTypeId));

    assertNotNull(result);
    MissionCrew updated = result.getAssignedUnits().iterator().next().getCrew().iterator().next();
    assertEquals(1, updated.getJobTypes().size());
    verify(missionCrewRepository).save(crew);
  }

  @Test
  void updateMissionUnit_throwsConflict_whenClientVersionStale() {
    // #1131: the same guard on the unit full-form save — a stale snapshot must 409, not clobber.
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();

    Mission mission = new Mission();
    mission.setId(missionId);
    MissionUnit unit = new MissionUnit();
    unit.setId(unitId);
    unit.setVersion(9L);
    mission.getAssignedUnits().add(unit);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    // Stale client version (8 != 9) -> 409 at the check, before any ship / name resolution.
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () ->
            missionStructureService.updateMissionUnit(
                missionId, unitId, 8L, "Alpha", null, null, false, null, null, null));
  }

  @Test
  void removeCrewFromShip_shouldRemoveCrew() {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID crewId = UUID.randomUUID();

    Mission mission = new Mission();
    mission.setId(missionId);

    MissionUnit unit = new MissionUnit();
    unit.setId(unitId);
    mission.getAssignedUnits().add(unit);

    MissionCrew crew = new MissionCrew();
    crew.setId(crewId);
    unit.getCrew().add(crew);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    Mission updatedMission = missionStructureService.removeCrewFromShip(missionId, unitId, crewId);

    assertNotNull(updatedMission);
    MissionUnit updatedUnit = updatedMission.getAssignedUnits().iterator().next();
    assertTrue(updatedUnit.getCrew().isEmpty());

    // Option A: parent Mission.version must NOT be bumped by a sub-section (crew) write.
    verify(missionRepository, never()).save(any(Mission.class));
  }

  @Test
  void removeCrewFromShip_shouldThrowIfCrewNotFound() {
    UUID missionId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();

    Mission mission = new Mission();
    mission.setId(missionId);

    MissionUnit unit = new MissionUnit();
    unit.setId(unitId);
    mission.getAssignedUnits().add(unit);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    assertThrows(
        RuntimeException.class,
        () -> missionStructureService.removeCrewFromShip(missionId, unitId, UUID.randomUUID()));
  }
}

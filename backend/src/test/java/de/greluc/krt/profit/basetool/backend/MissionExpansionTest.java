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

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionExpansionTest {

  @MockitoBean private JwtDecoder jwtDecoder;

  @Autowired private MissionService missionService;

  @Autowired private MissionRepository missionRepository;

  @Autowired private ShipRepository shipRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private JobTypeRepository jobTypeRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

  @Test
  void testMissionExpansion() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("pilot1");
    user.setEmail("pilot@test.com");
    final User savedUser = userRepository.save(user);

    ShipType fighter = new ShipType();
    fighter.setName("Fighter");
    fighter = shipTypeRepository.save(fighter);

    Ship ship = new Ship();
    ship.setName("Test Ship");
    ship.setShipType(fighter);
    ship.setOwner(savedUser);
    ship = shipRepository.save(ship);

    Mission mission =
        missionService.createMission(
            new de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest(
                "Test Mission",
                null,
                null,
                "PLANNED",
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null));

    mission =
        missionService.addParticipant(
            mission.getId(), savedUser.getId(), null, null, null, null, null);
    MissionParticipant participant =
        mission.getParticipants().stream()
            .filter(p -> p.getUser().getId().equals(savedUser.getId()))
            .findFirst()
            .orElseThrow();

    mission =
        missionService.addUnitToMission(
            mission.getId(),
            "Expansion Unit",
            fighter.getId(),
            ship.getId(),
            false,
            null,
            null,
            null);

    assertNotNull(mission.getAssignedUnits());
    assertEquals(1, mission.getAssignedUnits().size());
    MissionUnit missionShip = mission.getAssignedUnits().iterator().next();
    assertEquals(ship.getId(), missionShip.getShip().getId());

    JobType pilot = new JobType();
    pilot.setName("Pilot");
    pilot.setArchetype(JobTypeArchetype.CREW);
    pilot = jobTypeRepository.save(pilot);

    JobType engineer = new JobType();
    engineer.setName("Engineer");
    engineer.setArchetype(JobTypeArchetype.CREW);
    engineer = jobTypeRepository.save(engineer);

    Set<UUID> jobTypeIds = Set.of(pilot.getId(), engineer.getId());
    mission =
        missionService.addCrewToShip(
            mission.getId(), missionShip.getId(), participant.getId(), jobTypeIds);

    Mission updatedMission = missionRepository.findById(mission.getId()).orElseThrow();
    assertEquals(1, updatedMission.getAssignedUnits().size());
    MissionUnit updatedMissionShip = updatedMission.getAssignedUnits().iterator().next();
    assertEquals(1, updatedMissionShip.getCrew().size());

    MissionCrew crew = updatedMissionShip.getCrew().iterator().next();
    assertEquals(savedUser.getId(), crew.getParticipant().getUser().getId());
    assertEquals(2, crew.getJobTypes().size());
    assertTrue(crew.getJobTypes().stream().anyMatch(jt -> jt.getName().equals("Pilot")));
  }
}

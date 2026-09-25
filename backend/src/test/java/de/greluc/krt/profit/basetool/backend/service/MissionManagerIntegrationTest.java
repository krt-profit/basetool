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

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MissionManagerIntegrationTest {

  @Autowired private MissionService missionService;

  @Autowired private MissionRepository missionRepository;

  @Autowired private UserRepository userRepository;

  @Test
  void addManager_ShouldSuccessfullyAddUserToManagers() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("testmanager");
    user.setDisplayName("Test Manager");
    user.setEmail("test@example.com");
    user.setRank(1);
    userRepository.save(user);

    Mission mission = new Mission();
    mission.setName("Test Mission");
    mission.setStatus("PLANNED");
    mission.setIsInternal(false);
    mission = missionRepository.save(mission);
    UUID missionId = mission.getId();
    UUID userId = user.getId();

    Mission updatedMission = missionService.addManager(missionId, userId);

    assertNotNull(updatedMission);
    assertEquals(1, updatedMission.getManagers().size());
    assertTrue(updatedMission.getManagers().stream().anyMatch(u -> u.getId().equals(userId)));

    Mission reloaded = missionRepository.findById(missionId).orElseThrow();
    assertEquals(1, reloaded.getManagers().size());
    assertTrue(reloaded.getManagers().stream().anyMatch(u -> u.getId().equals(userId)));
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ownership version against a real database: the {@code @Formula} that exposes {@code
 * mission_ownership.version} on {@link Mission}, and the versioned owner change that moves it.
 *
 * <p>A mocked repository cannot prove either half. The formula is SQL that only PostgreSQL
 * evaluates, and the counter's movement is Hibernate's {@code @Version} bump on a flush — which is
 * exactly what the service relies on to hand the response the counter it just produced. Each read
 * below goes through {@link EntityManager#clear()} first, so it is the database answering and not
 * the persistence context repeating what the service set.
 */
@SpringBootTest
@Transactional
class MissionOwnershipVersionIntegrationTest {

  @Autowired private MissionService missionService;
  @Autowired private MissionRepository missionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;

  private User user(String name) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(name);
    user.setDisplayName(name);
    user.setEmail(name + "@example.com");
    user.setRank(1);
    return userRepository.save(user);
  }

  private UUID mission(User owner) {
    Mission mission = new Mission();
    mission.setName("Ownership lock");
    mission.setStatus("PLANNED");
    mission.setIsInternal(false);
    mission.setOwner(owner);
    return missionRepository.save(mission).getId();
  }

  private long ownershipVersionFromTheDatabase(UUID missionId) {
    entityManager.flush();
    entityManager.clear();
    return missionRepository.findById(missionId).orElseThrow().getOwnershipVersion();
  }

  @Test
  void aMissionWhoseOwnerNeverChangedReadsAsVersionZero() {
    UUID missionId = mission(user("creator"));

    assertEquals(0L, ownershipVersionFromTheDatabase(missionId));
  }

  @Test
  void eachChangeMovesTheCounterAndTheResponseAlreadyCarriesIt() {
    User creator = user("creator2");
    User first = user("first");
    User second = user("second");
    UUID missionId = mission(creator);

    Mission afterFirst = missionService.updateMissionOwner(missionId, first.getId(), 0L);
    assertEquals(1L, afterFirst.getOwnershipVersion());
    assertEquals(1L, ownershipVersionFromTheDatabase(missionId));

    Mission afterSecond = missionService.updateMissionOwner(missionId, second.getId(), 1L);
    assertEquals(2L, afterSecond.getOwnershipVersion());
    assertEquals(2L, ownershipVersionFromTheDatabase(missionId));
    assertEquals(
        second.getId(), missionRepository.findById(missionId).orElseThrow().getOwner().getId());
  }

  @Test
  void anEchoReadBeforeTheFirstChangeIsStaleAfterIt() {
    // The lost update the counter exists for: two managers opened the page while it said 0. The
    // first one's change must leave the second one's 0 behind, or the second overwrites unasked.
    User creator = user("creator3");
    User first = user("first3");
    User second = user("second3");
    UUID missionId = mission(creator);

    missionService.updateMissionOwner(missionId, first.getId(), 0L);
    entityManager.flush();
    entityManager.clear();

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> missionService.updateMissionOwner(missionId, second.getId(), 0L));
    assertEquals(
        first.getId(), missionRepository.findById(missionId).orElseThrow().getOwner().getId());
  }
}

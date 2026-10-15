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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.internal.HangarService;
import de.greluc.krt.profit.basetool.backend.hangar.internal.ShipBulkOrgUnitResultDto;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two bulk org-unit changes over overlapping selections, sent in opposite orders, both finish on
 * PostgreSQL: the ships are locked in one id order (REQ-HANGAR-010).
 */
@SpringBootTest
class HangarShipBulkOrgUnitConcurrencyIntegrationTest {

  @Autowired private HangarService hangarService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ShipRepository shipRepository;

  private UUID owner;
  private UUID shipType;
  private final List<UUID> ships = new ArrayList<>();

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("bulk-lock-" + user.getId());
    User saved = userRepository.saveAndFlush(user);
    owner = saved.getId();
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(owner, Squadron.IRIDIUM_ID));
    membership.setUser(saved);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.saveAndFlush(membership);
    ShipType type = new ShipType();
    type.setName("bulk-lock-" + owner);
    ShipType savedType = shipTypeRepository.saveAndFlush(type);
    shipType = savedType.getId();
    for (int i = 0; i < 4; i++) {
      Ship ship = new Ship();
      ship.setShipType(savedType);
      ship.setOwner(saved);
      ship.setInsurance("LTI");
      ships.add(shipRepository.saveAndFlush(ship).getId());
    }
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM ship WHERE owner_id = ?", owner);
    shipTypeRepository.deleteById(shipType);
    jdbc.update("DELETE FROM audit_event WHERE actor_user_id = ?", owner);
    orgUnitMembershipRepository.deleteById(new OrgUnitMembershipId(owner, Squadron.IRIDIUM_ID));
    jdbc.update("DELETE FROM app_user WHERE id = ?", owner);
  }

  @Test
  @DisplayName("overlapping selections in opposite orders both finish once a held lock is released")
  void overlappingSelectionsInOppositeOrdersBothFinish() throws Exception {
    List<UUID> forward = List.copyOf(ships);
    List<UUID> backward = new ArrayList<>(ships).reversed();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      holdLocks(blocker);
      int waitingBefore = lockWaiters();

      Future<ShipBulkOrgUnitResultDto> first =
          pool.submit(() -> hangarService.bulkChangeShipOrgUnit(owner, forward, null));
      Future<ShipBulkOrgUnitResultDto> second =
          pool.submit(
              () -> hangarService.bulkChangeShipOrgUnit(owner, backward, Squadron.IRIDIUM_ID));
      awaitLockWaiters(waitingBefore + 2);
      blocker.rollback();

      ShipBulkOrgUnitResultDto a = first.get(60, TimeUnit.SECONDS);
      ShipBulkOrgUnitResultDto b = second.get(60, TimeUnit.SECONDS);
      assertThat(a.changed() + a.skipped()).isEqualTo(ships.size());
      assertThat(b.changed() + b.skipped()).isEqualTo(ships.size());
    } finally {
      pool.shutdownNow();
    }
  }

  private void holdLocks(@NotNull Connection blocker) throws Exception {
    try (PreparedStatement lock =
        blocker.prepareStatement("SELECT id FROM ship WHERE owner_id = ? FOR UPDATE")) {
      lock.setObject(1, owner);
      lock.executeQuery().close();
    }
  }

  private int lockWaiters() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                + " AND datname = current_database()",
            Integer.class);
    return count == null ? 0 : count;
  }

  private void awaitLockWaiters(int expected) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (lockWaiters() < expected && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    assertThat(lockWaiters()).isGreaterThanOrEqualTo(expected);
  }
}

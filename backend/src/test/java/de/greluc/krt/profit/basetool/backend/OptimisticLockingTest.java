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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.HangarService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Exercises {@link HangarService#updateShip} under real concurrent contention to verify that the
 * {@code @Version}-based optimistic-locking guard holds end to end (service-layer pre-check + JPA
 * UPDATE-WHERE-VERSION fallback).
 *
 * <p>The previous incarnation of this test fetched the first row from {@code
 * shipRepository.findAll()} and updated it, then asserted only that the result was non-null — but
 * the test database (Postgres Testcontainer with Flyway-managed schema only) contains no ships, so
 * the {@code findAll()} stream resolved to {@code null} and the entire body became dead code: the
 * test always passed without ever invoking the service.
 *
 * <p>This rewrite seeds a real Ship + owner + type, then launches several worker threads that each
 * request the same update with the same stale version. A {@link CountDownLatch} barrier holds them
 * until every thread has built its DTO so the simultaneous-update race is genuine. Exactly one
 * thread must win and every other one must observe an {@link
 * ObjectOptimisticLockingFailureException} — surfaced either by the explicit version check at the
 * top of {@code updateShip} or by Hibernate's UPDATE-rows-affected-zero fallback on commit,
 * depending on which thread got to the {@code findById} first.
 *
 * <p>Intentionally <em>not</em> {@code @Transactional}: a test-managed transaction would be
 * invisible to the worker threads (each running in its own session), and the seed Ship would never
 * be readable. Per-test row-leakage into the Testcontainer Postgres is acceptable because the
 * container lives only for the duration of the Gradle run.
 */
@SpringBootTest
@ActiveProfiles("test")
class OptimisticLockingTest {

  private static final int THREADS = 5;
  private static final int START_TIMEOUT_SECONDS = 5;
  private static final int FINISH_TIMEOUT_SECONDS = 30;

  @Autowired private SquadronRepository squadronRepository;

  private Squadron iridium;

  @Autowired private HangarService hangarService;

  @Autowired private ShipRepository shipRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @MockitoBean private JwtDecoder jwtDecoder;

  private UUID ownerId;
  private UUID shipTypeId;
  private UUID shipId;
  private Long initialVersion;

  @AfterEach
  void cleanupSeedRows() {
    if (shipId != null) {
      shipRepository.deleteById(shipId);
    }
    if (shipTypeId != null) {
      shipTypeRepository.deleteById(shipTypeId);
    }
    if (ownerId != null) {
      orgUnitMembershipRepository.deleteById(new OrgUnitMembershipId(ownerId, Squadron.IRIDIUM_ID));
      userRepository.deleteById(ownerId);
    }
  }

  @BeforeEach
  void seedShip() {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("oltest-" + owner.getId());
    userRepository.save(owner);
    ownerId = owner.getId();
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(owner.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(owner);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);

    ShipType type = new ShipType();
    type.setName("OL-Test Type " + UUID.randomUUID());
    shipTypeId = shipTypeRepository.save(type).getId();

    Ship ship = new Ship();

    ship.setOwningOrgUnit(iridium);
    ship.setName("Concurrent Ship " + UUID.randomUUID());
    ship.setInsurance("LTI");
    ship.setShipType(type);
    ship.setOwner(owner);
    ship.setFitted(true);
    Ship saved = shipRepository.save(ship);
    shipId = saved.getId();
    initialVersion = saved.getVersion();
    assertNotNull(initialVersion, "freshly persisted ship must carry a @Version value");
  }

  @Test
  void hangarServiceUpdateShip_underRealConcurrentContention_exactlyOneThreadWins()
      throws Exception {
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger conflictCount = new AtomicInteger();
    AtomicInteger otherErrorCount = new AtomicInteger();
    List<Throwable> unexpectedErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures = new ArrayList<>(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        final int idx = i;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    ShipRequestDto dto =
                        new ShipRequestDto(
                            "Updated by thread " + idx,
                            shipTypeId,
                            "LTI",
                            null,
                            true,
                            initialVersion,
                            null);
                    ready.countDown();
                    if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                      otherErrorCount.incrementAndGet();
                      return;
                    }
                    try {
                      hangarService.updateShip(ownerId, shipId, dto);
                      successCount.incrementAndGet();
                    } catch (ObjectOptimisticLockingFailureException expected) {
                      conflictCount.incrementAndGet();
                    }
                  } catch (Throwable t) {
                    otherErrorCount.incrementAndGet();
                    unexpectedErrors.add(t);
                  }
                }));
      }

      assertTrue(
          ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "all worker threads should have built their DTO within " + START_TIMEOUT_SECONDS + "s");
      go.countDown();

      for (Future<?> f : futures) {
        f.get(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    assertEquals(
        0,
        otherErrorCount.get(),
        () -> "no thread should have thrown an unexpected exception, got: " + unexpectedErrors);
    assertEquals(
        1, successCount.get(), "exactly one updateShip call should succeed under contention");
    assertEquals(
        THREADS - 1,
        conflictCount.get(),
        "every other thread should see ObjectOptimisticLockingFailureException");

    Ship persisted = shipRepository.findById(shipId).orElseThrow();
    assertTrue(
        persisted.getName().startsWith("Updated by thread "),
        "winning thread's update must be the one that landed in the DB, got: "
            + persisted.getName());
    assertEquals(
        initialVersion + 1,
        persisted.getVersion(),
        "version must be incremented exactly once across the whole race");
  }
}

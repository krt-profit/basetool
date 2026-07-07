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
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionRequest;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies the {@code Mission @Version} optimistic-locking guarantee under <em>real</em> concurrent
 * access. The previous incarnation of this test simulated two users sequentially (load → update →
 * save → load-stale → update-stale → save-stale-throws) which exercised the SQL semantics of the
 * version column but could not surface timing-dependent bugs (early commit, connection-pool
 * starvation, persistence-context cross-thread leakage, …).
 *
 * <p>This rewrite launches several worker threads, makes them all load the same entity, holds them
 * at a {@link CountDownLatch} until everyone has the same stale version, then releases them to
 * attempt a full-replace update ({@code MissionService.updateMission}) simultaneously. Since #1114
 * every mutable {@code Mission} scalar is {@code @OptimisticLock(excluded = true)} so a bare scalar
 * save no longer bumps the row {@code @Version} (that decoupling is what stops a core edit from
 * 409-ing a concurrent schedule edit); the whole-mission "exactly one wins" guarantee now rides on
 * {@code updateMission}, which force-increments {@code @Version} via {@code
 * OPTIMISTIC_FORCE_INCREMENT}. Postgres serialises the resulting {@code UPDATE … WHERE version = N}
 * via row-level locks; exactly one thread's returns rows-affected = 1 (success), and every other
 * thread sees rows-affected = 0 which Hibernate translates to {@link
 * ObjectOptimisticLockingFailureException}.
 *
 * <p>The test is deliberately <strong>not</strong> annotated with {@code @Transactional} — a
 * Spring-managed test transaction would wrap all setup in one rolled-back transaction that the
 * worker threads (each running in their own session) could not see. Each thread relies on Spring
 * Data JPA's per-method {@code @Transactional} on the repository calls.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyTest {

  private static final int THREADS = 5;
  private static final int START_TIMEOUT_SECONDS = 5;
  private static final int FINISH_TIMEOUT_SECONDS = 30;

  @Autowired private SquadronRepository squadronRepository;

  private Squadron iridium;

  @Autowired private MissionRepository missionRepository;

  @Autowired private MissionService missionService;

  @MockitoBean private JwtDecoder jwtDecoder;

  private UUID seedMissionId;

  @AfterEach
  void cleanupSeedRow() {
    // Without an outer @Transactional the seed mission survives the test;
    // remove it so adjacent tests that snapshot the table can rely on a
    // clean baseline.
    if (seedMissionId != null) {
      missionRepository.deleteById(seedMissionId);
      seedMissionId = null;
    }
  }

  @Test
  void missionUpdate_underRealConcurrentContention_exactlyOneThreadWins() throws Exception {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Mission seed = new Mission();
    seed.setOwningOrgUnit(iridium);
    seed.setName("Concurrency Mission " + UUID.randomUUID());
    seed.setStatus("PLANNED");
    seedMissionId = missionRepository.save(seed).getId();
    final UUID id = seedMissionId;

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
                    // Since #1114 every mutable Mission scalar is @OptimisticLock(excluded=true),
                    // so a
                    // bare save after mutating one scalar no longer bumps the row @Version (that is
                    // what stops a core edit from 409-ing a concurrent schedule edit). The
                    // row-level
                    // "exactly one wins" guarantee now lives on the legacy full-replace path
                    // (updateMission), which force-increments @Version via
                    // OPTIMISTIC_FORCE_INCREMENT
                    // — this test exercises that path.
                    Long staleVersion = missionRepository.findById(id).orElseThrow().getVersion();
                    ready.countDown();
                    if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                      otherErrorCount.incrementAndGet();
                      return;
                    }
                    UpdateMissionRequest request =
                        new UpdateMissionRequest(
                            "Updated by thread " + idx,
                            null,
                            null,
                            "PLANNED",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            staleVersion,
                            null);
                    try {
                      missionService.updateMission(id, request);
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
          "all worker threads should have loaded the mission within "
              + START_TIMEOUT_SECONDS
              + "s");
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
    assertEquals(1, successCount.get(), "exactly one thread should win the optimistic-lock race");
    assertEquals(
        THREADS - 1,
        conflictCount.get(),
        "every other thread should see ObjectOptimisticLockingFailureException");

    Mission persisted = missionRepository.findById(id).orElseThrow();
    assertTrue(
        persisted.getName().startsWith("Updated by thread "),
        "winning thread's update must be the one that landed in the DB, got: "
            + persisted.getName());
  }
}

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionStepRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Real-contention proof that the DB-enforced mission section counters (#1112/#1114/#1147) serialise
 * same-section writers into exactly one winner while leaving disjoint-section writers independent.
 * Each worker runs its own transaction (no outer {@code @Transactional}); a {@code go} latch fires
 * them in lockstep so the atomic conditional bumps genuinely race at the database.
 *
 * <ul>
 *   <li>Same-section: N threads append an Ablauf step / reassign the party lead against the same
 *       start version — the conditional {@code UPDATE … WHERE xVersion = ?} row-locks the mission,
 *       so exactly one commits and the rest get {@link ObjectOptimisticLockingFailureException}
 *       (HTTP 409). Before #1147/#1112 they all silently committed, duplicating the step
 *       order-index or losing a party-lead assignment.
 *   <li>Cross-section: a core edit and a schedule edit racing on the same mission BOTH succeed —
 *       the row lock briefly serialises them but neither touches the other's counter or the row
 *       {@code @Version}, so no spurious 409 (the #1114 cross-section-collision fix).
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class MissionSectionLockConcurrencyTest {

  private static final int THREADS = 5;
  private static final int START_TIMEOUT_SECONDS = 5;
  private static final int FINISH_TIMEOUT_SECONDS = 30;

  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionStepRepository missionStepRepository;
  @Autowired private MissionTimelineService missionTimelineService;
  @Autowired private MissionParticipantService missionParticipantService;
  @Autowired private MissionService missionService;

  @MockitoBean private JwtDecoder jwtDecoder;

  private UUID seedMissionId;

  /** Deletes the seed mission (cascade-removing its steps / party lead) after each race. */
  @AfterEach
  void cleanupSeedRows() {
    if (seedMissionId != null) {
      missionRepository.deleteById(seedMissionId);
      seedMissionId = null;
    }
  }

  @Test
  void concurrentAddStep_exactlyOneWins_restGet409() throws Exception {
    final UUID missionId = persistPlannedMission("Concurrent Ablauf");

    RaceResult result = race(i -> missionTimelineService.addStep(missionId, "Step " + i, null, 0L));

    assertEquals(
        1, result.successes(), "exactly one concurrent append may win the stepsVersion race");
    assertEquals(
        THREADS - 1,
        result.conflicts(),
        () -> "the losers must all 409 (not silently duplicate order_index=1): " + result.errors());
    assertEquals(0, result.otherErrors(), () -> "no unexpected errors: " + result.errors());

    assertEquals(
        1,
        missionStepRepository.findByMissionIdOrderByOrderIndexAsc(missionId).size(),
        "only the winning append persists a step (the losers 409 before their INSERT)");
    assertEquals(
        1L,
        missionRepository.findById(missionId).orElseThrow().getStepsVersion(),
        "stepsVersion advances exactly once");
  }

  @Test
  void concurrentPartyLeadReassignment_exactlyOneWins_restGet409() throws Exception {
    final UUID missionId = persistPlannedMission("Concurrent Party Lead");

    RaceResult result =
        race(i -> missionParticipantService.setPartyLead(missionId, null, "Lead " + i, 0L));

    assertEquals(
        1, result.successes(), "exactly one reassignment may win the partyLeadVersion race");
    assertEquals(
        THREADS - 1,
        result.conflicts(),
        () ->
            "the losers must 409 instead of silently overwriting each other (#1112): "
                + result.errors());
    assertEquals(0, result.otherErrors(), () -> "no unexpected errors: " + result.errors());

    Mission persisted = missionRepository.findById(missionId).orElseThrow();
    assertTrue(persisted.getPartyLeadGuestName() != null, "the winner's guest lead is stored");
    assertEquals(1L, persisted.getPartyLeadVersion(), "partyLeadVersion advances exactly once");
  }

  @Test
  void concurrentCoreAndScheduleEdits_bothSucceed_withoutCrossSection409() throws Exception {
    final UUID missionId = persistPlannedMission("Concurrent Cross Section");
    final Long rowVersionBefore = missionRepository.findById(missionId).orElseThrow().getVersion();
    // Truncate to millis: Postgres timestamptz keeps microsecond precision, so a raw nanosecond
    // Instant.now() would not equal the value read back after the round-trip.
    final Instant plannedStart =
        Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    final Instant plannedEnd = plannedStart.plus(2, ChronoUnit.HOURS);

    // Thread 0 edits the core section, thread 1 edits the (disjoint) schedule section. Both echo
    // version 0 for their OWN counter, so neither may 409 the other.
    RaceResult result =
        race(
            2,
            i -> {
              if (i == 0) {
                missionService.updateCoreSection(
                    missionId, "Renamed", "desc", null, "PLANNED", null, "ARC-L1", 0L);
              } else {
                missionService.updateScheduleSection(
                    missionId, null, plannedStart, plannedEnd, null, null, 0L);
              }
            });

    assertEquals(
        2, result.successes(), () -> "disjoint sections must not collide: " + result.errors());
    assertEquals(
        0,
        result.conflicts(),
        () -> "a core edit must never 409 a concurrent schedule edit (#1114): " + result.errors());
    assertEquals(0, result.otherErrors(), () -> "no unexpected errors: " + result.errors());

    Mission persisted = missionRepository.findById(missionId).orElseThrow();
    assertEquals("Renamed", persisted.getName(), "core edit applied");
    assertEquals(plannedStart, persisted.getPlannedStartTime(), "schedule edit applied");
    assertEquals(1L, persisted.getCoreVersion());
    assertEquals(1L, persisted.getScheduleVersion());
    assertEquals(
        rowVersionBefore,
        persisted.getVersion(),
        "neither section edit bumps the row @Version — that is what keeps the sections"
            + " independent");
  }

  /** Seeds a PLANNED mission owned by IRIDIUM and records its id for cleanup. */
  private UUID persistPlannedMission(String name) {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Mission mission = new Mission();
    mission.setOwningOrgUnit(iridium);
    mission.setName(name + " " + UUID.randomUUID());
    mission.setStatus("PLANNED");
    seedMissionId = missionRepository.save(mission).getId();
    return seedMissionId;
  }

  /** Runs {@code action} on {@value #THREADS} threads in lockstep and tallies the outcomes. */
  private RaceResult race(IntConsumer action) throws Exception {
    return race(THREADS, action);
  }

  /**
   * Runs {@code action} on {@code threads} workers released simultaneously by a shared latch,
   * counting successes, {@link ObjectOptimisticLockingFailureException} conflicts (409) and any
   * other error.
   *
   * @param threads the number of concurrent workers.
   * @param action the work each worker performs, given its 0-based index.
   * @return the tallied outcome of the race.
   * @throws Exception if the workers do not all reach the start line, or a worker hangs.
   */
  private RaceResult race(int threads, IntConsumer action) throws Exception {
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger conflicts = new AtomicInteger();
    AtomicInteger otherErrors = new AtomicInteger();
    List<Throwable> errors = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<?>> futures = new ArrayList<>(threads);
    try {
      for (int i = 0; i < threads; i++) {
        final int index = i;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    ready.countDown();
                    if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                      otherErrors.incrementAndGet();
                      return;
                    }
                    action.accept(index);
                    successes.incrementAndGet();
                  } catch (ObjectOptimisticLockingFailureException conflict) {
                    conflicts.incrementAndGet();
                  } catch (Throwable t) {
                    otherErrors.incrementAndGet();
                    errors.add(t);
                  }
                }));
      }

      assertTrue(
          ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "all workers should reach the start line within " + START_TIMEOUT_SECONDS + "s");
      go.countDown();
      for (Future<?> f : futures) {
        f.get(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
    return new RaceResult(successes.get(), conflicts.get(), otherErrors.get(), errors);
  }

  /**
   * Tally of a concurrent race.
   *
   * @param successes the number of workers whose action committed.
   * @param conflicts the number of workers that got a 409 ({@link
   *     ObjectOptimisticLockingFailureException}).
   * @param otherErrors the number of workers that failed for any other reason.
   * @param errors the collected unexpected throwables, for diagnostics.
   */
  private record RaceResult(
      int successes, int conflicts, int otherErrors, List<Throwable> errors) {}
}

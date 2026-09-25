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

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the multi-user concurrency guarantee for the participant signup flow: {@code N} users may
 * sign up to the same mission in parallel without any thread seeing an {@link
 * ObjectOptimisticLockingFailureException}, and {@link Mission#getVersion()} stays unchanged across
 * the whole batch.
 *
 * <p>The guarantee is the load-bearing reason why {@link
 * de.greluc.krt.profit.basetool.backend.model.Mission#getParticipants()} carries
 * {@code @OptimisticLock(excluded = true)} and why {@link MissionService#addParticipant}
 * deliberately does not call {@code missionRepository.save(mission)} (see the inline comment on the
 * service method). A future change that removes either of those by accident would re-introduce 409s
 * on every concurrent "Anmelden" click — this test catches the regression at build time.
 *
 * <p>Sibling guard {@code ArchitectureTest#missionParticipantsCollectionMustExcludeOptimisticLock}
 * and {@code ArchitectureTest#missionServiceAddParticipantMustNotSaveMission} encode the same
 * invariants as static bytecode checks; this {@code @SpringBootTest} additionally proves the
 * dynamic behaviour against a real Hibernate session.
 *
 * <p>Modelled after {@link ConcurrencyTest}: deliberately NOT {@code @Transactional} so each worker
 * thread runs in its own session, and the seed rows are cleaned up via {@code @AfterEach} so
 * adjacent tests inherit a clean baseline.
 */
@SpringBootTest
@ActiveProfiles("test")
class MissionParticipantConcurrencyTest {

  private static final int THREADS = 5;
  private static final int START_TIMEOUT_SECONDS = 5;
  private static final int FINISH_TIMEOUT_SECONDS = 30;

  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository missionParticipantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MissionService missionService;

  @MockitoBean private JwtDecoder jwtDecoder;

  private UUID seedMissionId;
  private final List<UUID> seedUserIds = new ArrayList<>();

  /**
   * Removes the seed mission, its participants and the seed users so the next test starts from a
   * clean state. Without an outer {@code @Transactional} the rows persist across tests.
   */
  @AfterEach
  void cleanupSeedRows() {
    if (seedMissionId != null) {
      missionParticipantRepository.deleteAll(
          missionParticipantRepository.findAll().stream()
              .filter(p -> seedMissionId.equals(p.getMission().getId()))
              .toList());
      missionRepository.deleteById(seedMissionId);
      seedMissionId = null;
    }
    for (UUID userId : seedUserIds) {
      userRepository.deleteById(userId);
    }
    seedUserIds.clear();
  }

  /**
   * {@value THREADS} threads add {@value THREADS} distinct users to the same mission in lockstep.
   * The {@code go} latch synchronises every worker so the {@code INSERT mission_participant}
   * statements race against the database, mirroring the realistic case of multiple users hitting
   * the "Anmelden" button within the same millisecond.
   *
   * <p>The expected outcome is {@value THREADS} successes and zero conflicts because the parent
   * {@code mission} row is never updated (only the inverse-side {@code mission_participant} child
   * rows are inserted), so Hibernate has no version column to race on. Any thread throwing {@link
   * ObjectOptimisticLockingFailureException} indicates a regression in the
   * {@code @OptimisticLock(excluded = true)} annotation on {@link
   * de.greluc.krt.profit.basetool.backend.model.Mission#getParticipants()} or in the {@link
   * MissionService#addParticipant} body (e.g. a stray {@code missionRepository.save(mission)} call
   * that dirties the parent row).
   */
  @Test
  void addParticipant_underRealConcurrentContention_allThreadsSucceed() throws Exception {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Mission seed = new Mission();
    seed.setOwningOrgUnit(iridium);
    seed.setName("Concurrency Signup Mission " + UUID.randomUUID());
    seed.setStatus("PLANNED");
    seedMissionId = missionRepository.save(seed).getId();
    final UUID missionId = seedMissionId;
    final Long versionBeforeAdds = missionRepository.findById(missionId).orElseThrow().getVersion();

    final List<UUID> userIds = new ArrayList<>(THREADS);
    for (int i = 0; i < THREADS; i++) {
      User user = new User();
      user.setId(UUID.randomUUID());
      user.setUsername("concurrent-signup-user-" + i + "-" + UUID.randomUUID());
      User saved = userRepository.save(user);
      userIds.add(saved.getId());
      seedUserIds.add(saved.getId());
    }

    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger conflictCount = new AtomicInteger();
    AtomicInteger otherErrorCount = new AtomicInteger();
    List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures = new ArrayList<>(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        final UUID userId = userIds.get(i);
        futures.add(
            pool.submit(
                () -> {
                  try {
                    ready.countDown();
                    if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                      otherErrorCount.incrementAndGet();
                      return;
                    }
                    missionService.addParticipant(missionId, userId, null, null, null, null, null);
                    successCount.incrementAndGet();
                  } catch (ObjectOptimisticLockingFailureException unexpected) {
                    conflictCount.incrementAndGet();
                    unexpectedErrors.add(unexpected);
                  } catch (Throwable t) {
                    otherErrorCount.incrementAndGet();
                    unexpectedErrors.add(t);
                  }
                }));
      }

      assertTrue(
          ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "all worker threads should have entered the race within " + START_TIMEOUT_SECONDS + "s");
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
        conflictCount.get(),
        () ->
            "no thread should see ObjectOptimisticLockingFailureException — Mission.participants is"
                + " annotated @OptimisticLock(excluded = true) and addParticipant must not bump the"
                + " parent version. Got conflicts: "
                + unexpectedErrors);
    assertEquals(
        0,
        otherErrorCount.get(),
        () -> "no thread should throw any other exception, got: " + unexpectedErrors);
    assertEquals(THREADS, successCount.get(), "every concurrent signup must succeed");

    Mission persisted = missionRepository.findById(missionId).orElseThrow();
    assertEquals(
        THREADS,
        persisted.getParticipants().size(),
        "every concurrent signup must result in a participant row");
    assertNotNull(versionBeforeAdds, "seed mission must have a version stamped after save");
    assertEquals(
        versionBeforeAdds,
        persisted.getVersion(),
        "Mission.version must not bump on participant adds (Option A: @OptimisticLock excluded on"
            + " the participants collection) — concurrent edits on other mission sections must stay"
            + " independent of signup activity");
  }

  /**
   * {@value THREADS} threads add the <em>same</em> user to the same mission in lockstep. The
   * in-memory duplicate check in {@link MissionService#addParticipant} uses each thread's own
   * snapshot of {@code mission.getParticipants()} and is therefore unable to see the participant
   * row the winning thread inserts in parallel — the check is best-effort against a stale view. The
   * Stufe-2 DB-level backstop is the partial unique index {@code uq_mission_participant_user}
   * (Flyway V96), which rejects the second {@code INSERT} at commit time; Spring wraps the
   * underlying SQL {@code unique_violation} as a {@link DataIntegrityViolationException} and the
   * {@code GlobalExceptionHandler} maps it to HTTP 409 — the same status the in-memory branch
   * produces via {@code DuplicateEntityException}, so the frontend toast (status-code-based) is the
   * same for both paths.
   *
   * <p>Thread interleaving is non-deterministic: a thread that loads the mission <em>before</em>
   * the winner commits passes the in-memory check and ends up at the DB index ({@code
   * DataIntegrityViolationException}); a thread that loads <em>after</em> the winner commits sees
   * the participant in its snapshot and is rejected by the in-memory check ({@code
   * DuplicateEntityException}). Both are correct outcomes — the test accepts either and asserts on
   * the load-bearing invariant: exactly one signup wins, the rest are rejected, and the DB ends up
   * with exactly one participant row for the (mission, user) pair.
   */
  @Test
  void addParticipant_sameUserParallelClicks_exactlyOneWinsRestRejectedByUniqueIndex()
      throws Exception {
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    Mission seed = new Mission();
    seed.setOwningOrgUnit(iridium);
    seed.setName("Concurrency Duplicate Mission " + UUID.randomUUID());
    seed.setStatus("PLANNED");
    seedMissionId = missionRepository.save(seed).getId();
    final UUID missionId = seedMissionId;

    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("double-click-user-" + UUID.randomUUID());
    final UUID userId = userRepository.save(user).getId();
    seedUserIds.add(userId);

    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger rejectedByInMemoryCheck = new AtomicInteger();
    AtomicInteger rejectedByDbIndex = new AtomicInteger();
    AtomicInteger otherErrorCount = new AtomicInteger();
    List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures = new ArrayList<>(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        futures.add(
            pool.submit(
                () -> {
                  try {
                    ready.countDown();
                    if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                      otherErrorCount.incrementAndGet();
                      return;
                    }
                    missionService.addParticipant(missionId, userId, null, null, null, null, null);
                    successCount.incrementAndGet();
                  } catch (DuplicateEntityException expected) {
                    rejectedByInMemoryCheck.incrementAndGet();
                  } catch (DataIntegrityViolationException expected) {
                    rejectedByDbIndex.incrementAndGet();
                  } catch (Throwable t) {
                    otherErrorCount.incrementAndGet();
                    unexpectedErrors.add(t);
                  }
                }));
      }

      assertTrue(
          ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "all worker threads should have entered the race within " + START_TIMEOUT_SECONDS + "s");
      go.countDown();

      for (Future<?> f : futures) {
        f.get(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    int totalRejected = rejectedByInMemoryCheck.get() + rejectedByDbIndex.get();
    assertEquals(
        0,
        otherErrorCount.get(),
        () -> "no thread should throw an unexpected exception, got: " + unexpectedErrors);
    assertEquals(
        1,
        successCount.get(),
        () ->
            "exactly one thread should win the duplicate-signup race — got "
                + successCount.get()
                + " successes (in-memory rejections: "
                + rejectedByInMemoryCheck.get()
                + ", DB-index rejections: "
                + rejectedByDbIndex.get()
                + ")");
    assertEquals(
        THREADS - 1,
        totalRejected,
        () ->
            "every losing thread must be rejected by either the in-memory check (DuplicateEntity)"
                + " or the V96 partial unique index (DataIntegrityViolation) — got "
                + rejectedByInMemoryCheck.get()
                + " in-memory + "
                + rejectedByDbIndex.get()
                + " DB rejections, expected "
                + (THREADS - 1)
                + " total");

    Mission persisted = missionRepository.findById(missionId).orElseThrow();
    assertEquals(
        1,
        persisted.getParticipants().size(),
        "regardless of which mechanism caught which thread, the (mission, user) pair must end up"
            + " with exactly one participant row — that is the load-bearing invariant of Stufe 2");
  }
}

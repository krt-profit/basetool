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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationPayoutStatus;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationPayoutStatusRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reproduces the concurrent <em>first-toggle</em> race on the operation payout-status flag against
 * the real Postgres test container and pins its last-writer-wins guarantee: two mission managers
 * ticking the "Bezahlt" box for the <em>same</em> {@code (operation, participant)} tuple at once
 * must <b>both</b> complete without any thread seeing a 500 / propagated conflict, and the DB must
 * end up with exactly one {@link OperationPayoutStatus} row for that tuple.
 *
 * <p>The row carries a JPA {@code @Version} (via {@code AbstractEntity}) <em>and</em> the unique
 * index {@code uk_operation_payout_status_operation_participant} (columns {@code operation_id},
 * {@code participant_key}), so the loser of the parallel INSERT hits a {@code
 * DataIntegrityViolationException} (or, on the repeat edit, an {@code
 * ObjectOptimisticLockingFailureException}). {@link OperationService#setPayoutStatus} is a
 * non-transactional orchestrator that retries each attempt in its own {@code REQUIRES_NEW}
 * transaction (#1111), so the loser reloads the winner's committed row and UPDATEs it in place
 * instead of surfacing the conflict as an HTTP 500. This test is the dynamic regression guard for
 * that behaviour: were the unique index or the {@code @Version} ever dropped, two concurrent "mark
 * paid" clicks would each INSERT a row (duplicate payout-status rows, double audit trail) with no
 * exception — and only the {@code hasSize(1)} assertion below would catch it. The deterministic
 * retry-count contract lives in {@code OperationServiceTest.SetPayoutStatusConcurrencyTests}, which
 * fakes the collision with a spy; this test proves the schema actually raises it.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: each worker runs in its own session
 * so the versions actually race, and the seed rows are removed via {@code @AfterEach}. The
 * {@code @WithMockUser} context is captured on the test thread and re-applied inside each worker
 * because {@code SecurityContextHolder}'s default strategy does not inherit into a thread pool (the
 * toggle reads it to stamp the acting user).
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN"})
class OperationPayoutStatusConcurrencyTest {

  private static final int THREADS = 2;
  private static final int START_TIMEOUT_SECONDS = 5;
  private static final int FINISH_TIMEOUT_SECONDS = 30;
  private static final Instant ACTUAL_START = Instant.parse("2026-03-01T10:00:00Z");
  private static final Instant ACTUAL_END = ACTUAL_START.plus(60, ChronoUnit.MINUTES);

  @Autowired private OperationService operationService;
  @Autowired private OperationRepository operationRepository;
  @Autowired private OperationPayoutStatusRepository payoutStatusRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository missionParticipantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  @MockitoBean private JwtDecoder jwtDecoder;

  /**
   * The seeded fixture ids for one test run — the operation, its single time-stamped mission, the
   * one user participant and that user — captured so {@code @AfterEach} can delete the rows.
   *
   * @param operationId the seeded operation both writers toggle
   * @param missionId the seeded, fully time-stamped mission carrying the participant
   * @param participantId the single mission participant row
   * @param userId the participant's user (its stringified id is the payout {@code participant_key})
   */
  private record Fixture(UUID operationId, UUID missionId, UUID participantId, UUID userId) {}

  private Fixture fixture;

  /** Removes the seeded rows so adjacent tests inherit a clean baseline (no outer transaction). */
  @AfterEach
  void cleanup() {
    if (fixture == null) {
      return;
    }
    transactionTemplate.executeWithoutResult(
        st -> {
          payoutStatusRepository.deleteAll(
              payoutStatusRepository.findByOperationId(fixture.operationId()));
          missionParticipantRepository.deleteById(fixture.participantId());
          missionRepository.deleteById(fixture.missionId());
          operationRepository.deleteById(fixture.operationId());
          userRepository.deleteById(fixture.userId());
        });
    fixture = null;
  }

  /**
   * Two threads flip the paid-out flag for the <em>same</em> participant of the same operation in
   * lockstep. The {@code go} latch releases both workers together so their find-or-create + INSERT
   * statements race against the unique index. The guarantee: no worker throws (the loser retries in
   * a fresh transaction instead of surfacing a 500), both toggles report success, and the operation
   * ends up with exactly one payout-status row carrying {@code paidOut = true} (last-writer-wins).
   *
   * @throws Exception if a worker future fails to complete within the finish timeout
   */
  @Test
  void firstToggleRace_sameParticipant_lastWriterWins_noServerError() throws Exception {
    fixture = seed();
    final String participantKey = fixture.userId().toString();

    // Capture the @WithMockUser admin context on the test thread so each worker can re-apply it —
    // SecurityContextHolder's default MODE_THREADLOCAL strategy does not propagate into a thread
    // pool, and the toggle reads the context to resolve the acting user.
    final SecurityContext adminContext = SecurityContextHolder.getContext();

    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger otherErrorCount = new AtomicInteger();
    List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures = new ArrayList<>(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        futures.add(
            pool.submit(
                () -> {
                  SecurityContextHolder.setContext(adminContext);
                  try {
                    ready.countDown();
                    if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                      otherErrorCount.incrementAndGet();
                      return;
                    }
                    operationService.setPayoutStatus(fixture.operationId(), participantKey, true);
                    successCount.incrementAndGet();
                  } catch (Throwable t) {
                    otherErrorCount.incrementAndGet();
                    unexpectedErrors.add(t);
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                }));
      }

      assertThat(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("both writers should have entered the race within %ds", START_TIMEOUT_SECONDS)
          .isTrue();
      go.countDown();

      for (Future<?> f : futures) {
        f.get(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    assertThat(otherErrorCount.get())
        .as(
            "the losing first-toggle writer must retry in a fresh transaction, never surface a"
                + " 500 / propagated conflict — got: %s",
            unexpectedErrors)
        .isZero();
    assertThat(successCount.get())
        .as("both concurrent same-participant toggles must complete (last-writer-wins)")
        .isEqualTo(THREADS);

    List<OperationPayoutStatus> rows =
        payoutStatusRepository.findByOperationId(fixture.operationId());
    assertThat(rows)
        .as(
            "the same-participant toggle must collapse to exactly one row for the"
                + " (operation, participant) tuple — a second row means the unique index or"
                + " @Version guard is gone")
        .hasSize(1);
    assertThat(rows.get(0).getParticipantKey()).isEqualTo(participantKey);
    assertThat(rows.get(0).isPaidOut())
        .as("the surviving row must record the paid-out flag both writers set")
        .isTrue();
  }

  /**
   * Seeds an operation with a single, fully time-stamped mission (so its participant contributes a
   * valid attendance window and therefore a resolvable {@code participant_key}) and one registered
   * user participant on it. Both racing writers toggle the payout status for exactly this
   * participant.
   *
   * @return the created fixture ids
   */
  private Fixture seed() {
    return transactionTemplate.execute(
        status -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);

          User user = new User();
          user.setId(UUID.randomUUID());
          user.setUsername("PayoutRace-" + tag);
          user = userRepository.save(user);

          Operation operation = new Operation();
          operation.setName("PayoutRace-Op-" + tag);
          operation.setStatus(OperationStatus.COMPLETED);
          operation = operationRepository.save(operation);

          Mission mission = new Mission();
          mission.setName("PayoutRace-Mission-" + tag);
          mission.setStatus("COMPLETED");
          mission.setOperation(operation);
          mission.setActualStartTime(ACTUAL_START);
          mission.setActualEndTime(ACTUAL_END);
          mission = missionRepository.save(mission);

          MissionParticipant participant = new MissionParticipant();
          participant.setMission(mission);
          participant.setUser(user);
          participant.setStartTime(ACTUAL_START);
          participant.setEndTime(ACTUAL_END);
          participant.setPayoutPreference(PayoutPreference.PAYOUT);
          participant = missionParticipantRepository.save(participant);

          return new Fixture(operation.getId(), mission.getId(), participant.getId(), user.getId());
        });
  }
}

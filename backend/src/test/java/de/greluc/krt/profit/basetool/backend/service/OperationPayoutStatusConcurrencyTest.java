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
 * Races two concurrent first toggles of the payout status for the same participant against
 * Postgres: both succeed and exactly one {@link OperationPayoutStatus} row remains.
 *
 * <p>Not {@code @Transactional}, so each worker runs its own session.
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

  @Autowired private OperationPayoutService operationPayoutService;
  @Autowired private OperationRepository operationRepository;
  @Autowired private OperationPayoutStatusRepository payoutStatusRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private MissionParticipantRepository missionParticipantRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  @MockitoBean private JwtDecoder jwtDecoder;

  /**
   * Seeded fixture ids of one test run, deleted in {@code @AfterEach}.
   *
   * @param operationId the seeded operation both writers toggle
   * @param missionId the time-stamped mission carrying the participant
   * @param participantId the single mission participant row
   * @param userId the participant's user; its id string is the {@code participant_key}
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
   * Two threads toggle the paid-out flag of the same participant in lockstep; neither throws, and
   * exactly one row with {@code paidOut = true} remains.
   *
   * @throws Exception if a worker future does not complete within the timeout
   */
  @Test
  void firstToggleRace_sameParticipant_lastWriterWins_noServerError() throws Exception {
    fixture = seed();
    final String participantKey = fixture.userId().toString();

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
                    operationPayoutService.setPayoutStatus(
                        fixture.operationId(), participantKey, true);
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
   * Seeds an operation with one time-stamped mission and one registered user participant.
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

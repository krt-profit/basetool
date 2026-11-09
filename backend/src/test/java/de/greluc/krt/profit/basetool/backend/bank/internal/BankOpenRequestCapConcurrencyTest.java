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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestWrites;
import de.greluc.krt.profit.basetool.backend.bank.api.BankProblemCode;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Races two exchange raises of one member at the open-request cap against the real Postgres
 * container (REQ-BXCH-004): the second raise counts only after the first one's transaction ended,
 * so the member never holds more than {@link BankExchangeProperties#openRequestCap()} pending
 * requests. Not {@code @Transactional}, so each raise runs its own session.
 */
@SpringBootTest
class BankOpenRequestCapConcurrencyTest {

  private static final String CLIENT = "cap-race";

  private static final int TIMEOUT_SECONDS = 30;

  /** How long the second raise may run before the first one commits. */
  private static final long OVERTAKE_MILLIS = 1_000;

  private static final BigDecimal AMOUNT = new BigDecimal("500");

  @Autowired private BankOwnRequestWrites writes;
  @Autowired private BankActors actors;
  @Autowired private BankExchangeProperties properties;
  @Autowired private UserRepository userRepository;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private UUID member;
  private UUID account;

  /** Removes the seeded member, account and requests, which the raises committed. */
  @AfterEach
  void cleanup() {
    if (account != null) {
      jdbc.update("DELETE FROM bank_booking_request WHERE account_id = ?", account);
      jdbc.update("DELETE FROM bank_account WHERE id = ?", account);
    }
    if (member != null) {
      jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    }
  }

  @Test
  void aRaiseThatRacesTheLastFreeSlotIsRefusedWithTheCap() throws Exception {
    seed();
    BankActor actor = actors.forExchange(member);
    for (int held = 1; held < properties.openRequestCap(); held++) {
      asMember(() -> writes.raise(actor, deposit(), CLIENT, null));
    }
    CountDownLatch firstRaised = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Object> first =
          pool.submit(() ->
              asMember(() ->
                  transactionTemplate.execute(_ -> {
                    writes.raise(actor, deposit(), CLIENT, null);
                    firstRaised.countDown();
                    return await(releaseFirst);
                  })));
      assertThat(firstRaised.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      Future<Throwable> second = pool.submit(() -> asMember(() -> refusal(actor)));
      try {
        second.get(OVERTAKE_MILLIS, TimeUnit.MILLISECONDS);
      } catch (TimeoutException waitsForTheFirst) {
        assertThat(waitsForTheFirst).isNotNull();
      }
      releaseFirst.countDown();
      first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      Throwable refused = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(refused)
          .as("the raise that lost the race")
          .isInstanceOf(DomainProblem.class)
          .extracting(problem -> ((DomainProblem) problem).code())
          .isEqualTo(BankProblemCode.BANK_OPEN_REQUEST_CAP.code());
    } finally {
      releaseFirst.countDown();
      pool.shutdownNow();
      pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bank_booking_request WHERE requested_by = ? AND status ="
                    + " 'PENDING'",
                Long.class,
                member))
        .isEqualTo(properties.openRequestCap());
  }

  /** Commits a member and an active account the member deposits onto. */
  private void seed() {
    String tag = "cap-" + UUID.randomUUID().toString().substring(0, 8);
    transactionTemplate.executeWithoutResult(_ -> {
      User user = new User();
      user.setId(UUID.randomUUID());
      user.setUsername(tag);
      user.setApprovalStatus(ApprovalStatus.ACTIVE);
      user.setInKeycloak(true);
      user.setEnabledInKeycloak(true);
      member = userRepository.save(user).getId();
      BankAccount deposits = new BankAccount();
      deposits.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
      deposits.setName(tag);
      deposits.setType(BankAccountType.SPECIAL);
      deposits.setStatus(BankAccountStatus.ACTIVE);
      account = accountRepository.save(deposits).getId();
    });
  }

  /**
   * A deposit onto the seeded account.
   *
   * @return the request
   */
  @NotNull
  private BankOwnRequestWrites.NewRequest deposit() {
    return new BankOwnRequestWrites.NewRequest(
        BankBookingRequestType.DEPOSIT, account, null, AMOUNT, null, null, false, null);
  }

  /**
   * Raises one more deposit and catches its refusal.
   *
   * @param actor the member's actor
   * @return the refusal, or {@code null} when the request was raised
   */
  @Nullable
  private Throwable refusal(@NotNull BankActor actor) {
    try {
      writes.raise(actor, deposit(), CLIENT, null);
      return null;
    } catch (RuntimeException refused) {
      return refused;
    }
  }

  /**
   * Runs a step with the member's token bound, as the gateway's relay binds it.
   *
   * @param step the step
   * @param <T> its result
   * @return the step's result
   * @throws Exception when the step fails
   */
  private <T> T asMember(@NotNull Callable<T> step) throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("cap-race")
                    .header("alg", "none")
                    .subject(member.toString())
                    .build(),
                List.of()));
    try {
      return step.call();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /**
   * Waits for a latch inside the first raise's transaction.
   *
   * @param latch the latch
   * @return whether it opened in time
   */
  private static boolean await(@NotNull CountDownLatch latch) {
    try {
      return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}

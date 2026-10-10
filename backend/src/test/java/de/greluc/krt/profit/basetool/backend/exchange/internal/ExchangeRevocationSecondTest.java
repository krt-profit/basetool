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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The whole-client disconnect answers only in a second after its revocation's, so a connection the
 * member starts afterwards carries a later token time than the revocation (REQ-XCH-008).
 */
class ExchangeRevocationSecondTest {

  private static final Instant REVOKED_AT = Instant.parse("2026-09-28T08:06:30.300Z");

  private final MutableClock clock = new MutableClock(REVOKED_AT);
  private final List<Duration> slept = new ArrayList<>();

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  void theAnswerWaitsUntilTheNextSecondSoANewTokenIsIssuedAfterTheRevocation() {
    ExchangeRevocationSecond wait = new ExchangeRevocationSecond(clock, advancing());

    wait.awaitSecondAfter(REVOKED_AT);

    assertThat(slept).containsExactly(Duration.ofMillis(700));
    long tokenIssuedAt = clock.instant().getEpochSecond();
    assertThat(connectedAfter(tokenIssuedAt, REVOKED_AT))
        .as("a token issued once the disconnect answered passes the <= comparison")
        .isTrue();
    assertThat(connectedAfter(REVOKED_AT.getEpochSecond(), REVOKED_AT))
        .as("a token of the revocation's own second stays refused")
        .isFalse();
  }

  @Test
  void aRevocationStampedOnAFullSecondWaitsTheWholeSecond() {
    Instant onTheSecond = Instant.parse("2026-09-28T08:06:30Z");
    clock.set(onTheSecond);
    ExchangeRevocationSecond wait = new ExchangeRevocationSecond(clock, advancing());

    wait.awaitSecondAfter(onTheSecond);

    assertThat(slept).containsExactly(Duration.ofSeconds(1));
    assertThat(clock.instant()).isEqualTo(Instant.parse("2026-09-28T08:06:31Z"));
  }

  @Test
  void aSleepThatEndsEarlyIsContinuedUntilTheNextSecond() {
    ExchangeRevocationSecond wait =
        new ExchangeRevocationSecond(
            clock,
            duration -> {
              slept.add(duration);
              clock.advance(Duration.ofMillis(400));
            });

    wait.awaitSecondAfter(REVOKED_AT);

    assertThat(slept).containsExactly(Duration.ofMillis(700), Duration.ofMillis(300));
    assertThat(clock.instant().getEpochSecond()).isGreaterThan(REVOKED_AT.getEpochSecond());
  }

  @Test
  void aClockAlreadyInALaterSecondDoesNotWait() {
    clock.set(REVOKED_AT.plusSeconds(2));
    ExchangeRevocationSecond wait = new ExchangeRevocationSecond(clock, advancing());

    wait.awaitSecondAfter(REVOKED_AT);

    assertThat(slept).isEmpty();
  }

  @Test
  void aClockThatDoesNotMoveEndsTheWaitInsteadOfSpinning() {
    ExchangeRevocationSecond wait = new ExchangeRevocationSecond(clock, slept::add);

    wait.awaitSecondAfter(REVOKED_AT);

    assertThat(slept).hasSize(1);
  }

  @Test
  void anInterruptEndsTheWaitAndKeepsTheFlag() {
    ExchangeRevocationSecond wait =
        new ExchangeRevocationSecond(
            clock,
            duration -> {
              throw new InterruptedException("stop");
            });

    wait.awaitSecondAfter(REVOKED_AT);

    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  /**
   * The gateway's and the backend's comparison: a connection counts as made after the revocation
   * only in a later second.
   *
   * @param connectedAt the token's time in epoch seconds
   * @param revokedAt the revocation
   * @return {@code true} when the token is not refused
   */
  private static boolean connectedAfter(long connectedAt, @NotNull Instant revokedAt) {
    return !(connectedAt <= revokedAt.getEpochSecond());
  }

  /**
   * A sleeper that records the duration and moves the clock by it.
   *
   * @return the sleeper
   */
  private @NotNull ExchangeRevocationSecond.Sleeper advancing() {
    return duration -> {
      slept.add(duration);
      clock.advance(duration);
    };
  }

  /** A clock the test moves. */
  private static final class MutableClock extends Clock {

    private Instant now;

    /**
     * Creates it.
     *
     * @param now the starting time
     */
    MutableClock(@NotNull Instant now) {
      this.now = now;
    }

    /**
     * Sets the time.
     *
     * @param time the new time
     */
    void set(@NotNull Instant time) {
      now = time;
    }

    /**
     * Moves the time forward.
     *
     * @param duration how far
     */
    void advance(@NotNull Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}

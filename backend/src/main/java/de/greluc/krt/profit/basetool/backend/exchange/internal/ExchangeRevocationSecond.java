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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Holds a member's whole-client disconnect until the clock has left the second its revocation was
 * stamped with (REQ-XCH-008, ADR-0217).
 *
 * <p>A revocation refuses every token connected at or before its epoch second, and token times are
 * whole seconds, so a connection the member starts once the disconnect has answered is issued in a
 * later second and passes. Called after the disconnect's transaction has committed; it holds no
 * transaction or lock and waits at most one second.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ExchangeRevocationSecond {

  /** The clock the revocation was stamped with. */
  private final @NotNull Clock clock;

  /** Waits for a duration. */
  private final @NotNull Sleeper sleeper;

  /** Creates the wait on the system clock and {@link Thread#sleep(Duration)}. */
  public ExchangeRevocationSecond() {
    this(Clock.systemUTC(), Thread::sleep);
  }

  /**
   * Returns once the clock has reached the second after the revocation's; an interrupt ends the
   * wait early and keeps the thread's interrupt flag.
   *
   * @param revokedAt the time the revocation was stamped with
   */
  public void awaitSecondAfter(@NotNull Instant revokedAt) {
    Instant next = Instant.ofEpochSecond(revokedAt.getEpochSecond() + 1);
    Instant now = clock.instant();
    while (now.isBefore(next)) {
      Duration left = Duration.between(now, next);
      try {
        sleeper.sleep(left.compareTo(Duration.ofSeconds(1)) > 0 ? Duration.ofSeconds(1) : left);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      Instant later = clock.instant();
      if (!later.isAfter(now)) {
        return;
      }
      now = later;
    }
  }

  /** Waits for a duration. */
  @FunctionalInterface
  interface Sleeper {

    /**
     * Waits.
     *
     * @param duration how long
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void sleep(@NotNull Duration duration) throws InterruptedException;
  }
}

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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;

/**
 * Pins the behaviour that keeps an unreachable Redis from deciding whether the backend exists.
 *
 * <p>The production line these tests answer to is {@code ApplicationContextException: Failed to
 * start bean 'liveSyncRedisMessageListenerContainer'} (2026-09-02, 07:07:09Z): a plain {@code
 * RedisMessageListenerContainer} whose first subscription fails throws out of {@code
 * SmartLifecycle#start()}, and Spring cancels the context refresh. Every assertion below is red
 * against the plain superclass.
 */
class ResilientRedisMessageListenerContainerTest {

  /** How long a bounded poll waits for the retry thread to act, in milliseconds. */
  private static final long POLL_TIMEOUT_MILLIS = 5_000L;

  /**
   * Builds a container wired to a connection factory that always refuses, counting the attempts.
   *
   * @param attempts incremented once per connection attempt, which is how a retry is observed
   *     without mocking a whole working subscription.
   * @param retryIntervalMillis the retry cadence for this container.
   * @return an initialised container that has not been started yet.
   */
  private static ResilientRedisMessageListenerContainer refusingContainer(
      AtomicInteger attempts, long retryIntervalMillis) {
    RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    when(factory.getConnection())
        .thenAnswer(
            invocation -> {
              attempts.incrementAndGet();
              throw new RedisConnectionFailureException("Connection refused");
            });
    ResilientRedisMessageListenerContainer container =
        new ResilientRedisMessageListenerContainer(retryIntervalMillis);
    container.setBeanName("testFanoutContainer");
    container.setConnectionFactory(factory);
    // Keep the failure fast: the default registration wait is two seconds per attempt.
    container.setMaxSubscriptionRegistrationWaitingTime(200L);
    container.addMessageListener((message, pattern) -> {}, new ChannelTopic("basetool:test"));
    container.afterPropertiesSet();
    return container;
  }

  /**
   * Waits until the attempt counter reaches {@code target}, or the poll times out.
   *
   * @param attempts the counter the connection factory increments.
   * @param target the value being waited for.
   * @return {@code true} if the target was reached in time.
   * @throws InterruptedException if the waiting thread is interrupted.
   */
  private static boolean awaitAttempts(AtomicInteger attempts, int target)
      throws InterruptedException {
    long deadline = System.nanoTime() + POLL_TIMEOUT_MILLIS * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (attempts.get() >= target) {
        return true;
      }
      Thread.sleep(20L);
    }
    return false;
  }

  @Test
  @DisplayName("A refused first subscription does not propagate out of start()")
  void refusedFirstSubscriptionDoesNotPropagate() {
    AtomicInteger attempts = new AtomicInteger();
    ResilientRedisMessageListenerContainer container =
        refusingContainer(
            attempts, ResilientRedisMessageListenerContainer.DEFAULT_RETRY_INTERVAL_MILLIS);
    try {
      // The whole point: the superclass throws here, and Spring turns that into a failed refresh.
      assertThatNoException().isThrownBy(container::start);
      assertThat(container.isListening())
          .as("nothing is subscribed, and the container must not pretend otherwise")
          .isFalse();
    } finally {
      container.stop();
    }
  }

  @Test
  @DisplayName("The failed attempt resets the started flag, so a retry is not a silent no-op")
  void failedAttemptResetsTheStartedFlag() {
    AtomicInteger attempts = new AtomicInteger();
    ResilientRedisMessageListenerContainer container =
        refusingContainer(
            attempts, ResilientRedisMessageListenerContainer.DEFAULT_RETRY_INTERVAL_MILLIS);
    try {
      container.start();

      // Upstream start() is `if (started.compareAndSet(false, true)) { lazyListen(); }`, so the
      // flag
      // is already true when lazyListen throws. Without the super.stop() in the catch, isRunning()
      // would report true for a container subscribed to nothing AND every later start() would be
      // skipped outright — the retry would run forever without ever attempting anything.
      assertThat(container.isRunning()).isFalse();
    } finally {
      container.stop();
    }
  }

  @Test
  @DisplayName("The subscription is retried after the configured interval")
  void subscriptionIsRetried() throws InterruptedException {
    AtomicInteger attempts = new AtomicInteger();
    ResilientRedisMessageListenerContainer container = refusingContainer(attempts, 50L);
    try {
      container.start();

      assertThat(awaitAttempts(attempts, 3))
          .as("the retry must keep attempting; observed %d attempt(s)", attempts.get())
          .isTrue();
    } finally {
      container.stop();
    }
  }

  @Test
  @DisplayName("stop() abandons the retry instead of leaving a thread attempting forever")
  void stopAbandonsTheRetry() throws InterruptedException {
    AtomicInteger attempts = new AtomicInteger();
    ResilientRedisMessageListenerContainer container = refusingContainer(attempts, 50L);
    container.start();
    assertThat(awaitAttempts(attempts, 2)).isTrue();

    container.stop();
    int afterStop = attempts.get();
    Thread.sleep(300L);

    assertThat(attempts.get())
        .as("a stopped container must not keep reconnecting: the retry thread is released")
        .isEqualTo(afterStop);
  }

  @Test
  @DisplayName("A non-positive retry interval is rejected rather than busy-looping")
  void nonPositiveRetryIntervalIsRejected() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new ResilientRedisMessageListenerContainer(0L));
  }
}

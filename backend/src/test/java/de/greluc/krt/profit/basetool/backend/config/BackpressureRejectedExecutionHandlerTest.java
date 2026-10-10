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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The notification pool's rejection handler waits for room and counts what it finally refuses. */
class BackpressureRejectedExecutionHandlerTest {

  private final Counter rejected = new SimpleMeterRegistry().counter("rejected");
  private ThreadPoolExecutor pool;

  private ThreadPoolExecutor poolWith(Duration maxWait) {
    pool =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1),
            new BackpressureRejectedExecutionHandler("test", maxWait, rejected));
    return pool;
  }

  @AfterEach
  void shutDown() {
    pool.shutdownNow();
  }

  @Test
  void aSubmitterWaitsForRoomAndEveryTaskRuns() throws InterruptedException {
    ThreadPoolExecutor executor = poolWith(Duration.ofSeconds(20));
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger ran = new AtomicInteger();
    Runnable blocked =
        () -> {
          try {
            release.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
          ran.incrementAndGet();
        };
    executor.execute(blocked);
    executor.execute(ran::incrementAndGet);

    Thread submitter = new Thread(() -> executor.execute(ran::incrementAndGet));
    submitter.start();
    submitter.join(300);
    assertThat(submitter.isAlive()).as("the third task waits for room").isTrue();

    release.countDown();
    submitter.join(10_000);
    executor.shutdown();
    assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

    assertThat(ran).hasValue(3);
    assertThat(rejected.count()).isZero();
  }

  @Test
  void aWaitThatOutlastsTheLimitIsRejectedAndCounted() {
    ThreadPoolExecutor executor = poolWith(Duration.ofMillis(200));
    CountDownLatch never = new CountDownLatch(1);
    Runnable blocked =
        () -> {
          try {
            never.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
        };
    executor.execute(blocked);
    executor.execute(blocked);

    assertThatThrownBy(() -> executor.execute(blocked))
        .isInstanceOf(RejectedExecutionException.class);

    assertThat(rejected.count()).isEqualTo(1);
  }

  @Test
  void aShutDownPoolRejectsAtOnce() {
    ThreadPoolExecutor executor = poolWith(Duration.ofSeconds(20));
    executor.shutdown();

    assertThatThrownBy(() -> executor.execute(() -> {}))
        .isInstanceOf(RejectedExecutionException.class);

    assertThat(rejected.count()).isEqualTo(1);
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Unit tests for {@link AsyncConfig}. The bean produced by {@link AsyncConfig#uexExecutor()} is the
 * sole bound on UEX-async thread growth; the assertions pin the sizing contract so a refactor that
 * "simplifies" the pool back to defaults breaks the build before it ships.
 */
class AsyncConfigTest {

  private AsyncConfig config;
  private ThreadPoolTaskExecutor created;

  @BeforeEach
  void setUp() {
    config = new AsyncConfig();
  }

  @AfterEach
  void tearDown() {
    if (created != null) {
      created.shutdown();
    }
  }

  @Test
  void uexExecutor_isThreadPoolTaskExecutorWithPinnedSizing() {
    Executor executor = config.uexExecutor();
    assertNotNull(executor);
    created = assertInstanceOf(ThreadPoolTaskExecutor.class, executor);

    assertEquals(2, created.getCorePoolSize(), "core pool size");
    assertEquals(4, created.getMaxPoolSize(), "max pool size");
    assertEquals(100, created.getQueueCapacity(), "queue capacity");
    assertEquals("uex-async-", created.getThreadNamePrefix(), "thread name prefix");
  }

  @Test
  void uexExecutor_usesAbortPolicyToFailLoudOnSaturation() {
    Executor executor = config.uexExecutor();
    created = (ThreadPoolTaskExecutor) executor;

    ThreadPoolExecutor underlying = created.getThreadPoolExecutor();
    assertInstanceOf(
        ThreadPoolExecutor.AbortPolicy.class,
        underlying.getRejectedExecutionHandler(),
        "saturation must surface as RejectedExecutionException, not silent backpressure");
  }

  @Test
  void uexExecutorBeanName_isStableConstant() {
    assertEquals("uexExecutor", AsyncConfig.UEX_EXECUTOR);
  }

  @Test
  void uexExecutor_propagatesMdcFromSubmittingThreadToWorker() throws InterruptedException {
    Executor executor = config.uexExecutor();
    created = (ThreadPoolTaskExecutor) executor;

    MDC.put("correlationId", "test-correlation-42");
    MDC.put("userId", "test-user-007");
    try {
      AtomicReference<String> observedCorrelation = new AtomicReference<>();
      AtomicReference<String> observedUser = new AtomicReference<>();
      CountDownLatch done = new CountDownLatch(1);

      executor.execute(
          () -> {
            observedCorrelation.set(MDC.get("correlationId"));
            observedUser.set(MDC.get("userId"));
            done.countDown();
          });

      assertEquals(
          true,
          done.await(5, TimeUnit.SECONDS),
          "worker did not finish — task decorator may have thrown");
      assertEquals(
          "test-correlation-42",
          observedCorrelation.get(),
          "correlationId must propagate to the worker thread via TaskDecorator");
      assertEquals(
          "test-user-007",
          observedUser.get(),
          "userId must propagate to the worker thread via TaskDecorator");
    } finally {
      MDC.clear();
    }
  }

  @Test
  void uexExecutor_clearsMdcAfterTask_noBleedAcrossSubmissions() throws InterruptedException {
    Executor executor = config.uexExecutor();
    created = (ThreadPoolTaskExecutor) executor;

    MDC.put("correlationId", "first-id");
    CountDownLatch firstDone = new CountDownLatch(1);
    executor.execute(
        () -> {
          firstDone.countDown();
        });
    assertEquals(true, firstDone.await(5, TimeUnit.SECONDS));
    MDC.clear();

    AtomicReference<String> leakedCorrelation = new AtomicReference<>("never-written");
    CountDownLatch secondDone = new CountDownLatch(1);
    executor.execute(
        () -> {
          leakedCorrelation.set(MDC.get("correlationId"));
          secondDone.countDown();
        });
    assertEquals(true, secondDone.await(5, TimeUnit.SECONDS));
    assertNull(
        leakedCorrelation.get(),
        "second task on the same worker must observe a clean MDC, not the previous task's id");
  }
}

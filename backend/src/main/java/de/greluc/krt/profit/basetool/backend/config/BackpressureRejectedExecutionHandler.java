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

import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Rejection handler of a pool whose tasks must not be dropped: the submitting thread waits for room
 * in the queue instead of failing at once, and only a wait that outlasts the limit is rejected,
 * counted and logged.
 *
 * <p>The submitter stays blocked, never runs the task itself, so a task submitted from an
 * after-commit callback does not join the finished transaction.
 */
@Slf4j
@RequiredArgsConstructor
final class BackpressureRejectedExecutionHandler implements RejectedExecutionHandler {

  private final String pool;
  private final Duration maxWait;
  private final Counter rejected;

  /**
   * Waits up to the configured limit for room in the pool's queue.
   *
   * @param task the task the pool refused
   * @param executor the saturated pool
   * @throws RejectedExecutionException when the pool is shut down or no room appeared within the
   *     limit; the task is lost and the rejection counter has been incremented
   */
  @Override
  public void rejectedExecution(@NotNull Runnable task, @NotNull ThreadPoolExecutor executor) {
    if (!executor.isShutdown()) {
      try {
        if (executor.getQueue().offer(task, maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
          return;
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
    rejected.increment();
    log.error(
        "Executor '{}' rejected a task after waiting up to {}; the task is lost", pool, maxWait);
    throw new RejectedExecutionException("Executor '" + pool + "' is saturated");
  }
}

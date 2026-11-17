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

package de.greluc.krt.profit.basetool.ingest.store;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Runs a read-check-write step on Redis as an optimistic transaction, so the gateway sends no
 * script (ADR-0389): the step's keys are watched, the step reads and decides, and its writes run
 * between {@code MULTI} and {@code EXEC}. When another client changes a watched key in between,
 * Redis discards the writes and the step runs again on the new state.
 */
final class OptimisticTransaction {

  /** The most attempts one step makes before it gives up. */
  static final int MAX_ATTEMPTS = 32;

  /** The longest pause between two attempts, in milliseconds; each pause is drawn up to it. */
  static final long MAX_PAUSE_MILLIS = 8L;

  /** Not instantiable. */
  private OptimisticTransaction() {}

  /**
   * Runs a step until its writes commit, or until an attempt plans none.
   *
   * @param template the Redis access the step reads and writes through
   * @param keys the keys the step reads, which must be unchanged when its writes run
   * @param step the step
   * @param <T> the step's result
   * @return the result of the attempt whose writes committed or that planned none
   * @throws OptimisticLockingFailureException if every attempt met a changed key, or the thread was
   *     interrupted between two attempts
   */
  static <T> @NotNull T run(
      @NotNull StringRedisTemplate template,
      @NotNull Collection<String> keys,
      @NotNull Step<T> step) {
    Attempt<T> attempt = new Attempt<>(template, keys, step);
    for (int count = 1; ; count++) {
      T result = template.execute(attempt);
      if (result != null) {
        return result;
      }
      if (count == MAX_ATTEMPTS) {
        throw new OptimisticLockingFailureException(
            "A Redis step met a changed key on every one of its " + MAX_ATTEMPTS + " attempts.");
      }
      pause();
    }
  }

  /**
   * Waits a random time up to {@link #MAX_PAUSE_MILLIS}, so steps that met each other do not meet
   * again at once.
   *
   * @throws OptimisticLockingFailureException if the thread is interrupted
   */
  private static void pause() {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(MAX_PAUSE_MILLIS + 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OptimisticLockingFailureException(
          "Interrupted between two attempts of a Redis step.", e);
    }
  }

  /**
   * A step that reads after its keys are watched and plans its writes.
   *
   * @param <T> the step's result
   */
  @FunctionalInterface
  interface Step<T> {

    /**
     * Reads what the step needs and decides.
     *
     * @return the result and the writes that make it true
     */
    @NotNull
    Plan<T> plan();
  }

  /**
   * What a step decided.
   *
   * @param result the step's result, which holds once the writes committed
   * @param writes the writes, queued through the same template between {@code MULTI} and {@code
   *     EXEC}, or {@code null} when the step writes nothing
   * @param <T> the step's result
   */
  record Plan<T>(@NotNull T result, @Nullable Runnable writes) {

    /**
     * Checks the result.
     *
     * @throws NullPointerException if the result is {@code null}
     */
    Plan {
      Objects.requireNonNull(result, "result");
    }

    /**
     * Plans a result that needs no write.
     *
     * @param result the result
     * @param <T> the result type
     * @return the plan
     */
    static <T> @NotNull Plan<T> read(@NotNull T result) {
      return new Plan<>(result, null);
    }

    /**
     * Plans a result and the writes that make it true.
     *
     * @param result the result
     * @param writes the writes
     * @param <T> the result type
     * @return the plan
     */
    static <T> @NotNull Plan<T> write(@NotNull T result, @NotNull Runnable writes) {
      return new Plan<>(result, writes);
    }
  }

  /**
   * One attempt of a step, run on the connection the session binds to the thread; the template's
   * calls inside it share that connection, so the watch, the reads, the queued writes and the
   * {@code EXEC} form one transaction.
   *
   * @param template the Redis access
   * @param keys the keys to watch
   * @param step the step
   * @param <T> the step's result
   */
  private record Attempt<T>(
      @NotNull StringRedisTemplate template,
      @NotNull Collection<String> keys,
      @NotNull Step<T> step)
      implements SessionCallback<T> {

    /**
     * Watches the keys, runs the step and commits its writes.
     *
     * @param operations the session's operations, the same template
     * @param <K> the key type
     * @param <V> the value type
     * @return the step's result, or {@code null} when Redis discarded the writes
     */
    @Override
    public <K, V> @Nullable T execute(RedisOperations<K, V> operations) {
      template.watch(keys);
      boolean queueing = false;
      try {
        Plan<T> plan = step.plan();
        Runnable writes = plan.writes();
        if (writes == null) {
          template.unwatch();
          return plan.result();
        }
        template.multi();
        queueing = true;
        writes.run();
        List<Object> replies = template.exec();
        return replies.isEmpty() ? null : plan.result();
      } catch (RuntimeException failure) {
        close(queueing, failure);
        throw failure;
      }
    }

    /**
     * Leaves the connection without a watch or a queued transaction before it goes back to the
     * pool.
     *
     * @param queueing whether a transaction was opened
     * @param failure the failure that ended the attempt, which keeps a failed clean-up as
     *     suppressed
     */
    private void close(boolean queueing, @NotNull RuntimeException failure) {
      try {
        if (queueing) {
          template.discard();
        } else {
          template.unwatch();
        }
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
    }
  }
}

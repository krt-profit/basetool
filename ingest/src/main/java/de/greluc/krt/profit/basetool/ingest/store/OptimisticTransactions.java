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

import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs read-check-write steps on Redis as optimistic transactions, so the gateway sends no script
 * (ADR-0389): a step's keys are watched, the step reads and decides, and its writes run between
 * {@code MULTI} and {@code EXEC}. When another client changes a watched key in between, Redis
 * discards the writes and the step runs again on the new state.
 *
 * <p>An attempt that fails for any other reason closes its connection before the connection goes
 * back to the pool, so no watch, open transaction or unanswered command reaches the next borrower;
 * {@link RedisClientConfig} makes every connection a {@link DiscardableConnection} and the pool
 * test each connection it hands out.
 */
@Component
@RequiredArgsConstructor
public class OptimisticTransactions {

  /** The most attempts one step makes before it gives up. */
  static final int MAX_ATTEMPTS = 32;

  /** How long one step may keep trying before it gives up, whatever its attempts. */
  static final Duration MAX_DURATION = Duration.ofSeconds(2);

  /** The longest pause between two attempts, in milliseconds. */
  static final long MAX_PAUSE_MILLIS = 32L;

  /** The {@code outcome} tag of an attempt whose {@code EXEC} Redis discarded. */
  static final String OUTCOME_ABORTED = "aborted";

  /** The {@code outcome} tag of a step that gave up. */
  static final String OUTCOME_EXHAUSTED = "exhausted";

  /** The shift that caps the doubling pause at {@link #MAX_PAUSE_MILLIS}. */
  private static final int MAX_PAUSE_SHIFT = 5;

  /** {@code EXEC} as the connection answers it: {@code null} when Redis discarded the writes. */
  private static final RedisCallback<List<Object>> EXEC = connection -> connection.exec();

  /** Closes the native connection the session borrowed, so the pool does not lend it again. */
  private static final RedisCallback<Void> DISCARD =
      connection -> {
        DiscardableConnection.behind(connection).discard();
        return null;
      };

  /** The Redis access the steps read and write through, on the gateway's pooled connections. */
  private final @NotNull StringRedisTemplate template;

  /** Receives the aborted attempts and exhausted steps. */
  private final @NotNull MeterRegistry meterRegistry;

  /**
   * Runs a step until its writes commit or an attempt plans none.
   *
   * @param name the step's name, the {@code step} tag of its conflict counter
   * @param keys the keys the step reads, which must be unchanged when its writes run
   * @param step the step
   * @param <T> the step's result
   * @return the result of the attempt whose writes committed or that planned none
   * @throws OptimisticLockingFailureException if every attempt met a changed key within {@link
   *     #MAX_ATTEMPTS} and {@link #MAX_DURATION}, or the thread was interrupted between two
   *     attempts
   */
  <T> @NotNull T run(
      @NotNull String name, @NotNull Collection<String> keys, @NotNull Step<T> step) {
    Attempt<T> attempt = new Attempt<>(template, keys, step);
    long deadline = System.nanoTime() + MAX_DURATION.toNanos();
    for (int count = 1; ; count++) {
      T result = template.execute(attempt);
      if (result != null) {
        return result;
      }
      conflicts(name, OUTCOME_ABORTED).increment();
      if (count == MAX_ATTEMPTS || System.nanoTime() - deadline >= 0) {
        conflicts(name, OUTCOME_EXHAUSTED).increment();
        throw new OptimisticLockingFailureException(
            "A Redis step met a changed key on each of its " + count + " attempts.");
      }
      pause(count);
    }
  }

  /**
   * Returns the conflict counter of a step and outcome.
   *
   * @param name the step's name
   * @param outcome {@link #OUTCOME_ABORTED} or {@link #OUTCOME_EXHAUSTED}
   * @return the counter
   */
  private @NotNull Counter conflicts(@NotNull String name, @NotNull String outcome) {
    return Counter.builder(MetricNames.EXCHANGE_STORE_CONFLICTS)
        .description("Exchange store steps whose Redis transaction met a changed key.")
        .tag(MetricNames.TAG_STEP, name)
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .register(meterRegistry);
  }

  /**
   * Waits a random time that doubles its bound with each attempt up to {@link #MAX_PAUSE_MILLIS},
   * so steps that met each other do not meet again at once.
   *
   * @param attempt the attempt that just ended, from 1
   * @throws OptimisticLockingFailureException if the thread is interrupted
   */
  private static void pause(int attempt) {
    long bound = Math.min(MAX_PAUSE_MILLIS, 1L << Math.min(attempt, MAX_PAUSE_SHIFT));
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(bound + 1));
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
   * calls inside it use that connection, so the watch, the reads, the queued writes and the {@code
   * EXEC} form one transaction.
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
     * Watches the keys, runs the step and commits its writes; any failure closes the connection.
     *
     * @param operations the session's operations, the same template
     * @param <K> the key type
     * @param <V> the value type
     * @return the step's result, or {@code null} when Redis discarded the writes
     */
    @Override
    public <K, V> @Nullable T execute(RedisOperations<K, V> operations) {
      try {
        template.watch(keys);
        Plan<T> plan = step.plan();
        Runnable writes = plan.writes();
        if (writes == null) {
          template.unwatch();
          return plan.result();
        }
        template.multi();
        writes.run();
        return template.execute(EXEC) == null ? null : plan.result();
      } catch (Throwable failure) {
        abandon(failure);
        throw failure;
      }
    }

    /**
     * Closes the attempt's connection, which ends any watch or transaction Redis holds for it and
     * keeps the pool from lending it again.
     *
     * @param failure the failure that ended the attempt, which keeps a failed close as suppressed
     */
    private void abandon(@NotNull Throwable failure) {
      try {
        template.execute(DISCARD);
      } catch (RuntimeException closing) {
        failure.addSuppressed(closing);
      }
    }
  }
}

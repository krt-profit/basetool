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

package de.greluc.krt.profit.basetool.backend.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Instrumentation wrapper for the backend {@code @Scheduled} batch jobs (REQ-OBS-011).
 *
 * <p>{@link #record(ScheduledJob, ThrowingRunnable)} publishes per job an executions counter tagged
 * by outcome, a duration timer and a last-success timestamp gauge, and swallows any exception after
 * recording and logging it. The last-success gauge is registered only on a job's first success,
 * never with a {@code 0} sentinel.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskMetrics {

  /**
   * MDC key the correlation filter and the logback patterns use. Restated as a literal rather than
   * injected from {@code LoggingProperties}: this class sits in the {@code metrics} leaf package on
   * purpose (ADR-0047) and must not grow a dependency on {@code config}. Same precedent as {@code
   * SecurityProblemResponseHandler} and {@code BasetoolErrorController}.
   */
  private static final String MDC_CORRELATION_ID = "correlationId";

  /** The meter registry the scrape endpoint exposes. */
  private final @NotNull MeterRegistry registry;

  private final Map<ScheduledJob, AtomicLong> lastSuccessHolders = new ConcurrentHashMap<>();

  /** One holder per job whose bean exists, so the enabled gauge is registered once. */
  private final Map<ScheduledJob, AtomicLong> enabledJobs = new ConcurrentHashMap<>();

  /**
   * Runs {@code work} while recording the executions counter, duration timer and last-success gauge
   * for {@code job}.
   *
   * <p>An exception is recorded as a {@code failure}, logged at {@code ERROR} and swallowed; the
   * last-success gauge advances only on a clean return.
   *
   * @param job the job being instrumented; its {@link ScheduledJob#label()} is the tag value
   * @param work the job body to execute and measure
   */
  public void record(@NotNull ScheduledJob job, @NotNull ThrowingRunnable work) {
    recordInternal(
        job,
        () -> {
          work.run();
          return null;
        });
  }

  /**
   * Item-counting variant of {@link #record(ScheduledJob, ThrowingRunnable)} that also adds the
   * returned count to {@code basetool_scheduled_job_items_total}.
   *
   * @param job the job being instrumented
   * @param work the job body; its return value is the item count added on a clean run
   */
  public void recordCounting(@NotNull ScheduledJob job, @NotNull ThrowingIntSupplier work) {
    recordInternal(job, work::getAsInt);
  }

  /**
   * Runs {@code work} and records the job meters, plus the items counter when a clean run returns a
   * non-null count. Any exception is recorded as a {@code failure}, logged and swallowed.
   *
   * @param job the job being instrumented
   * @param work the job body returning an optional item count ({@code null} = none)
   */
  private void recordInternal(@NotNull ScheduledJob job, @NotNull ThrowingItemWork work) {
    long startNanos = System.nanoTime();
    String outcome = MetricNames.OUTCOME_SUCCESS;
    Integer items = null;
    boolean mdcOwned = openRunContext(job);
    try {
      items = work.run();
      markSuccess(job);
    } catch (Exception e) {
      outcome = MetricNames.OUTCOME_FAILURE;
      log.error("Scheduled job '{}' failed", job.label(), e);
    } finally {
      emitJobMetrics(job, outcome, startNanos, items);
      if (mdcOwned) {
        MDC.remove(MDC_CORRELATION_ID);
      }
    }
  }

  /**
   * Installs a {@code <job-label>-<8 hex>} {@code correlationId} in the MDC for this run, unless
   * one is already present.
   *
   * @param job the job about to run
   * @return {@code true} when this call installed the id and must remove it again
   */
  private static boolean openRunContext(@NotNull ScheduledJob job) {
    String existing = MDC.get(MDC_CORRELATION_ID);
    if (existing != null && !existing.isBlank()) {
      return false;
    }
    String runId = Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 32);
    MDC.put(MDC_CORRELATION_ID, job.label() + "-" + runId);
    return true;
  }

  /**
   * Rethrowing counting variant for a synchronous, caller-facing run of a scheduled job, such as
   * the admin-triggered manual user sync. It records the same meters as {@link #recordCounting} but
   * returns the item count and rethrows the body's failure (a checked one wrapped unchecked).
   *
   * <p>Use only from a request-scoped caller that owns error handling.
   *
   * @param job the job being instrumented
   * @param work the job body; its return value is returned to the caller
   * @return the item count the body reported
   */
  public int recordCountingRethrow(@NotNull ScheduledJob job, @NotNull ThrowingIntSupplier work) {
    long startNanos = System.nanoTime();
    String outcome = MetricNames.OUTCOME_SUCCESS;
    Integer items = null;
    try {
      items = work.getAsInt();
      markSuccess(job);
      return items;
    } catch (RuntimeException e) {
      outcome = MetricNames.OUTCOME_FAILURE;
      throw e;
    } catch (Exception e) {
      outcome = MetricNames.OUTCOME_FAILURE;
      throw new IllegalStateException(
          "Manual run of scheduled job '" + job.label() + "' failed", e);
    } finally {
      emitJobMetrics(job, outcome, startNanos, items);
    }
  }

  /**
   * Emits the duration timer, executions counter and, when a count was reported, the items counter
   * for a finished run.
   *
   * @param job the job being instrumented
   * @param outcome {@code success} or {@code failure}
   * @param startNanos the {@link System#nanoTime()} reading taken before the body ran
   * @param items the reported item count, or {@code null} when none
   */
  private void emitJobMetrics(
      @NotNull ScheduledJob job, @NotNull String outcome, long startNanos, Integer items) {
    long elapsedNanos = System.nanoTime() - startNanos;
    registry
        .timer(MetricNames.SCHEDULED_JOB_DURATION, MetricNames.TAG_JOB, job.label())
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
    registry
        .counter(
            MetricNames.SCHEDULED_JOB_EXECUTIONS,
            MetricNames.TAG_JOB,
            job.label(),
            MetricNames.TAG_OUTCOME,
            outcome)
        .increment();
    if (items != null) {
      registry
          .counter(MetricNames.SCHEDULED_JOB_ITEMS, MetricNames.TAG_JOB, job.label())
          .increment(items.doubleValue());
    }
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task} = 1} for this job.
   *
   * <p>Called from the job bean's own {@code @PostConstruct} (or, for {@code ScWikiScheduler}, only
   * while its runtime switch is on). Idempotent.
   *
   * @param job the job whose bean has just been created
   */
  public void markEnabled(@NotNull ScheduledJob job) {
    enabledJobs.computeIfAbsent(
        job,
        registered -> {
          AtomicLong holder = new AtomicLong(1L);
          Gauge.builder(MetricNames.SCHEDULED_JOB_ENABLED, holder, AtomicLong::doubleValue)
              .tag(MetricNames.TAG_JOB, registered.label())
              .description(
                  "1 while this scheduled job's bean exists; absent when it is switched off.")
              .register(registry);
          return holder;
        });
  }

  /**
   * Stamps the current epoch second into {@code job}'s last-success gauge, registering the gauge on
   * the job's first success.
   *
   * <p>Called only on the success path; the timestamp is taken before registration so the gauge
   * never publishes a {@code 0}.
   *
   * @param job the job whose run just completed cleanly
   */
  private void markSuccess(@NotNull ScheduledJob job) {
    long epochSeconds = Instant.now().getEpochSecond();
    lastSuccessHolders
        .computeIfAbsent(job, first -> registerLastSuccessGauge(first, epochSeconds))
        .set(epochSeconds);
  }

  /**
   * Registers the last-success timestamp gauge for {@code job} and returns its backing holder.
   *
   * @param job the job to register the gauge for
   * @param epochSeconds the first successful run's completion time, seeded into the holder
   * @return the holder referenced by the gauge, pre-set to {@code epochSeconds}
   */
  private @NotNull AtomicLong registerLastSuccessGauge(
      @NotNull ScheduledJob job, long epochSeconds) {
    AtomicLong holder = new AtomicLong(epochSeconds);
    Gauge.builder(MetricNames.SCHEDULED_JOB_LAST_SUCCESS, holder, AtomicLong::doubleValue)
        .tag(MetricNames.TAG_JOB, job.label())
        .baseUnit(MetricNames.UNIT_SECONDS)
        .description(
            "Epoch seconds of the last successful run of this scheduled job"
                + " (absent until the first success).")
        .register(registry);
    return holder;
  }

  /**
   * A job body that may throw a checked exception, so the wrapper — not the job — owns the
   * catch-record-swallow contract.
   */
  @FunctionalInterface
  public interface ThrowingRunnable {

    /**
     * Executes the job body.
     *
     * @throws Exception if the body fails; the wrapper records it as a {@code failure} and swallows
     *     it
     */
    void run() throws Exception;
  }

  /**
   * A job body that returns the number of items it processed and may throw a checked exception, for
   * the item-counting {@link TaskMetrics#record(ScheduledJob, ThrowingIntSupplier)} overload.
   */
  @FunctionalInterface
  public interface ThrowingIntSupplier {

    /**
     * Executes the job body and reports how many items it processed.
     *
     * @return the number of items processed by this run
     * @throws Exception if the body fails; the wrapper records it as a {@code failure} and swallows
     *     it
     */
    int getAsInt() throws Exception;
  }

  /**
   * Internal adapter unifying both public overloads: a body returning an optional item count
   * ({@code null} when the caller reports none).
   */
  @FunctionalInterface
  private interface ThrowingItemWork {

    /**
     * Executes the job body, optionally reporting an item count.
     *
     * @return the item count, or {@code null} when none is reported
     * @throws Exception if the body fails
     */
    Integer run() throws Exception;
  }
}

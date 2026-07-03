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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Shared instrumentation wrapper for the backend {@code @Scheduled} batch jobs (REQ-OBS-011).
 *
 * <p>Wrapping a job body in {@link #record(ScheduledJob, ThrowingRunnable)} produces, per {@link
 * ScheduledJob}:
 *
 * <ul>
 *   <li>{@code basetool_scheduled_job_executions_total{job,outcome}} — one increment per run,
 *       tagged {@code success} or {@code failure};
 *   <li>{@code basetool_scheduled_job_duration_seconds{job}} — the wall-clock run time;
 *   <li>{@code basetool_scheduled_job_last_success_timestamp_seconds{job}} — epoch seconds of the
 *       last successful run ({@code 0} until the first success), the source of the "sync gone
 *       stale" alert (e.g. {@code user_sync} not succeeding within 15 min).
 * </ul>
 *
 * <p>The wrapper <strong>catches and swallows</strong> any exception the body throws, after
 * recording it as a {@code failure} and logging it at {@code ERROR}: a scheduled sweep must never
 * let a transient failure tear the scheduler thread down, and this centralises that contract (the
 * jobs previously each hand-rolled their own catch-and-log). A run that returns normally counts as
 * {@code success} even if it found problems — a bank integrity sweep that reports violations is a
 * successful run; the violation count is a separate gauge.
 *
 * <p>The last-success gauge is registered lazily on a job's first {@code record(...)} call, so one
 * whose bean is absent or config-gated off (never invoked) simply never publishes the gauge rather
 * than reporting a perpetual, falsely-stale {@code 0}.
 *
 * <p>This class lives in the {@code metrics} leaf package and depends only on the Micrometer
 * registry, so every layer (task, service, filter) can reuse it without forming a package cycle
 * (ADR-0047).
 */
@Component
@Slf4j
public class TaskMetrics {

  private final MeterRegistry registry;
  private final Map<ScheduledJob, AtomicLong> lastSuccessHolders = new ConcurrentHashMap<>();

  /**
   * Binds the wrapper to the auto-configured Micrometer registry.
   *
   * @param registry the meter registry the scrape endpoint exposes
   */
  public TaskMetrics(@NotNull MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Runs {@code work} while recording the executions counter, duration timer and last-success gauge
   * for {@code job}.
   *
   * <p>Any exception thrown by {@code work} is recorded as a {@code failure}, logged at {@code
   * ERROR} (with the job label only — never a payload that could carry PII) and then swallowed so
   * the scheduler thread survives; the last-success gauge advances only on a clean return.
   *
   * @param job the scheduled job being instrumented (its {@link ScheduledJob#label()} is the {@code
   *     job} tag)
   * @param work the job body to execute and measure
   */
  public void record(@NotNull ScheduledJob job, @NotNull ThrowingRunnable work) {
    AtomicLong lastSuccess = lastSuccessHolder(job);
    long startNanos = System.nanoTime();
    String outcome = MetricNames.OUTCOME_SUCCESS;
    try {
      work.run();
      lastSuccess.set(Instant.now().getEpochSecond());
    } catch (Exception e) {
      outcome = MetricNames.OUTCOME_FAILURE;
      log.error("Scheduled job '{}' failed", job.label(), e);
    } finally {
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
    }
  }

  /**
   * Returns the per-job epoch-seconds holder, registering the backing gauge on first use.
   *
   * @param job the job whose last-success holder is requested
   * @return the shared, mutable holder feeding {@code basetool_scheduled_job_last_success_*}
   */
  private @NotNull AtomicLong lastSuccessHolder(@NotNull ScheduledJob job) {
    return lastSuccessHolders.computeIfAbsent(job, this::registerLastSuccessGauge);
  }

  /**
   * Registers the last-success timestamp gauge for {@code job} and returns its backing holder.
   *
   * @param job the job to register the gauge for
   * @return a zero-initialised holder (0 = never succeeded) strongly referenced by the gauge
   */
  private @NotNull AtomicLong registerLastSuccessGauge(@NotNull ScheduledJob job) {
    AtomicLong holder = new AtomicLong(0L);
    Gauge.builder(MetricNames.SCHEDULED_JOB_LAST_SUCCESS, holder, AtomicLong::doubleValue)
        .tag(MetricNames.TAG_JOB, job.label())
        .baseUnit(MetricNames.UNIT_SECONDS)
        .description("Epoch seconds of the last successful run of this scheduled job (0 = never).")
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
}

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
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
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

  /**
   * MDC key the correlation filter and the logback patterns use. Restated as a literal rather than
   * injected from {@code LoggingProperties}: this class sits in the {@code metrics} leaf package on
   * purpose (ADR-0047) and must not grow a dependency on {@code config}. Same precedent as {@code
   * SecurityProblemResponseHandler} and {@code BasetoolErrorController}.
   */
  private static final String MDC_CORRELATION_ID = "correlationId";

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
    recordInternal(
        job,
        () -> {
          work.run();
          return null;
        });
  }

  /**
   * Item-counting variant of {@link #record(ScheduledJob, ThrowingRunnable)}: the same executions
   * counter, duration timer and last-success gauge, plus {@code basetool_scheduled_job_items_total{
   * job}} incremented by the number of items the run reports it processed (users synced,
   * notifications purged, rows granted, …). Use this for jobs that process a countable batch; jobs
   * without a meaningful single count use the {@link ThrowingRunnable} overload.
   *
   * @param job the scheduled job being instrumented
   * @param work the job body; its return value is the item count added to the items counter on a
   *     clean run
   */
  public void recordCounting(@NotNull ScheduledJob job, @NotNull ThrowingIntSupplier work) {
    recordInternal(job, work::getAsInt);
  }

  /**
   * Shared instrumentation core: runs {@code work}, records the executions counter, duration timer
   * and last-success gauge, and — when the body returns a non-null count on a clean run — the items
   * counter. Any exception is recorded as a {@code failure}, logged and swallowed.
   *
   * @param job the scheduled job being instrumented
   * @param work the job body returning an optional item count ({@code null} = no count reported)
   */
  private void recordInternal(@NotNull ScheduledJob job, @NotNull ThrowingItemWork work) {
    AtomicLong lastSuccess = lastSuccessHolder(job);
    long startNanos = System.nanoTime();
    String outcome = MetricNames.OUTCOME_SUCCESS;
    Integer items = null;
    boolean mdcOwned = openRunContext(job);
    try {
      items = work.run();
      lastSuccess.set(Instant.now().getEpochSecond());
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
   * Tags this run with its own {@code correlationId} so every line it emits — the job body's own
   * start/finish lines, anything the services it calls log, and the failure {@code ERROR} above —
   * can be pulled out as one unit.
   *
   * <p>A scheduler thread carries no request, so {@code CorrelationIdFilter} never runs for it and
   * the field was previously empty on every scheduled line. With eight jobs on overlapping
   * schedules that made a nightly window unreadable: the lines interleave with nothing to say which
   * run they belong to. The id is {@code <job-label>-<8 hex>}, so it is greppable by job as well as
   * by run ({@code |= "user_sync"} finds every line of every user-sync run, the full id narrows it
   * to one).
   *
   * <p>An id that is already present is left untouched and not cleared afterwards: {@link
   * #recordCountingRethrow} runs inside an admin request that already owns a real request
   * correlation id, and overwriting it would sever the manual trigger from its HTTP call.
   *
   * @param job the job about to run
   * @return {@code true} when this call installed the id and must therefore remove it again
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
   * Rethrowing counting variant for a SYNCHRONOUS, caller-facing invocation of a job that also runs
   * on a schedule — e.g. an admin-triggered manual user sync ({@code POST /api/v1/users/sync}). It
   * records the identical executions counter, duration timer, last-success gauge and items counter
   * as {@link #recordCounting}, so the manual run is indistinguishable in monitoring and refreshes
   * the same {@code user_sync} staleness gauge; but, UNLIKE the schedule-facing overloads, it
   * RETURNS the item count and RE-THROWS the body's failure (unchecked, wrapping a checked one) so
   * the caller can surface it as an RFC 7807 error rather than the run being silently swallowed.
   *
   * <p>Use only from a request-scoped caller that owns error handling. The scheduler paths must
   * keep using the swallowing overloads so a transient failure never tears the scheduler thread
   * down.
   *
   * @param job the scheduled job being instrumented
   * @param work the job body; its return value is the item count returned to the caller
   * @return the item count the body reported on a clean run
   */
  public int recordCountingRethrow(@NotNull ScheduledJob job, @NotNull ThrowingIntSupplier work) {
    AtomicLong lastSuccess = lastSuccessHolder(job);
    long startNanos = System.nanoTime();
    String outcome = MetricNames.OUTCOME_SUCCESS;
    Integer items = null;
    try {
      items = work.getAsInt();
      lastSuccess.set(Instant.now().getEpochSecond());
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
   * Emits the duration timer, executions counter and (when a count was reported) items counter for
   * a finished job run. Shared by the swallowing {@link #recordInternal} and the rethrowing {@link
   * #recordCountingRethrow} so both paths publish byte-identical meters.
   *
   * @param job the scheduled job being instrumented
   * @param outcome {@code success} or {@code failure}
   * @param startNanos the {@link System#nanoTime()} reading taken before the body ran
   * @param items the item count the body reported, or {@code null} when none
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

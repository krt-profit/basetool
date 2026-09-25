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

package de.greluc.krt.profit.basetool.backend.task;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.JobOrderIntegrityService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job-order integrity sweep (REQ-ORDERS-033) that runs {@link
 * JobOrderIntegrityService#verify()} every {@code app.joborder.integrity.interval} (default {@code
 * PT1H}), detecting ordered-item lines whose blueprint pairing has drifted. Gated by {@code
 * app.joborder.integrity.enabled} (default {@code true}).
 */
@Component
@ConditionalOnProperty(
    prefix = "app.joborder.integrity",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class JobOrderIntegrityTask {

  private final JobOrderIntegrityService jobOrderIntegrityService;
  private final TaskMetrics taskMetrics;
  private final MeterRegistry meterRegistry;

  /**
   * Violation-count holder backing {@code
   * basetool_job_order_integrity_violations{category="item_line_blueprint_drift"}}. Fed by the
   * sweep, so a value {@code > 0} means at least one order is displaying a foreign recipe. Only
   * mutated on the scheduler thread.
   */
  private final AtomicInteger blueprintDriftGauge = new AtomicInteger(0);

  /**
   * Registers the {@code basetool_job_order_integrity_violations} gauge, reporting {@code 0} until
   * the first sweep.
   */
  @PostConstruct
  void registerViolationGauges() {
    Gauge.builder(
            MetricNames.JOB_ORDER_INTEGRITY_VIOLATIONS,
            blueprintDriftGauge,
            AtomicInteger::doubleValue)
        .tag(MetricNames.TAG_CATEGORY, MetricNames.CATEGORY_ITEM_LINE_BLUEPRINT_DRIFT)
        .description(
            "Job-order integrity violations by category; > 0 means an ordered-item line no longer"
                + " matches its blueprint.")
        .register(meterRegistry);
  }

  /**
   * Runs the integrity verification through {@link TaskMetrics} as the {@code job_order_integrity}
   * job. Failures are recorded and swallowed; a run that finds violations still counts as a
   * success.
   */
  @Scheduled(fixedDelayString = "${app.joborder.integrity.interval:PT1H}")
  public void runIntegrityCheck() {
    taskMetrics.record(ScheduledJob.JOB_ORDER_INTEGRITY, this::verifyOrders);
  }

  /**
   * Runs the sweep, pushes the violation count into the gauge and logs the total; any failure
   * propagates to {@link TaskMetrics}.
   */
  private void verifyOrders() {
    log.info("Starting scheduled job order integrity check...");
    JobOrderIntegrityService.IntegrityReport report = jobOrderIntegrityService.verify();
    blueprintDriftGauge.set(report.blueprintDrift().size());
    log.info("Job order integrity check finished — {} violation(s).", report.violationCount());
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="job_order_integrity"} = 1}, so {@code
   * ScheduledJobStale} can tell a disabled sweep (no bean, no gauge) from one that never succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.JOB_ORDER_INTEGRITY);
  }
}

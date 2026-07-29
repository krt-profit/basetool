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
 * Scheduled job-order integrity sweep (REQ-ORDERS-033; pattern: {@link BankLedgerIntegrityTask}).
 * Runs every {@code app.joborder.integrity.interval} (default {@code PT1H}) and delegates to {@link
 * JobOrderIntegrityService#verify()}, which logs each violation at {@code ERROR}. The whole task is
 * gated by {@code app.joborder.integrity.enabled} (default {@code true}); set it to {@code false}
 * to disable the schedule (e.g. in tests that drive the verification directly).
 *
 * <p>The sweep exists because the ordered-item line ↔ blueprint pairing can only be validated when
 * a line is <em>written</em>, while the daily SC-Wiki sync re-resolves each blueprint's output item
 * on every run. Without this the drift is invisible: no error, no audit event, no metric — a line
 * just quietly starts showing a foreign recipe's materials.
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
   * Registers the {@code basetool_job_order_integrity_violations} gauge, backed by a
   * zero-initialised holder the sweep updates. Registration happens once after construction, so the
   * series exists (reporting {@code 0}) before the first sweep and the alert always has something
   * to evaluate. Because the enclosing bean is config-gated ({@code
   * app.joborder.integrity.enabled}), disabling the check also removes the gauge, which is
   * intended.
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
   * Fires the integrity verification on the configured schedule through {@link TaskMetrics},
   * publishing the {@code job_order_integrity} job metrics. A transient DB hiccup is recorded as a
   * failed run and swallowed so the scheduler thread survives; the next tick retries. A run that
   * completes but reports violations is still a {@code success} — the violation count is the
   * separate {@code basetool_job_order_integrity_violations} gauge.
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
}

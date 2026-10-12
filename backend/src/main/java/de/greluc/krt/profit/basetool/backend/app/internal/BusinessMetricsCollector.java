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

package de.greluc.krt.profit.basetool.backend.app.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Samples the approval- and work-queue depths and the age of the oldest waiting item that every
 * module publishes as a {@link BusinessGaugeSource} (REQ-OBS-011).
 *
 * <p>Polled every {@code app.monitoring.business-metrics.interval-ms} (default 60 s) in one
 * read-only transaction, so DB load is independent of scrape frequency. All labels are bounded
 * (REQ-OBS-006).
 */
@Component
@ConditionalOnProperty(
    prefix = "app.monitoring.business-metrics",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class BusinessMetricsCollector {

  private final MeterRegistry meterRegistry;
  private final List<BusinessGaugeSource> sources;
  private final TaskMetrics taskMetrics;

  /**
   * Registers every module's gauges once at startup, so each series reports {@code 0} until the
   * first tick.
   */
  @PostConstruct
  void registerGauges() {
    taskMetrics.markEnabled(ScheduledJob.BUSINESS_METRICS);
    sources.forEach(source -> source.register(meterRegistry));
  }

  /**
   * Re-samples every module's gauges in one read-only transaction.
   *
   * <p>Wrapped in {@link TaskMetrics#record} as {@link ScheduledJob#BUSINESS_METRICS}, so a failing
   * query is recorded as a failure and swallowed.
   */
  @Scheduled(fixedRateString = "${app.monitoring.business-metrics.interval-ms:60000}")
  @Transactional(readOnly = true)
  public void refresh() {
    taskMetrics.record(ScheduledJob.BUSINESS_METRICS, this::sample);
  }

  /** Runs every source's sample inside the transaction opened by {@link #refresh()}. */
  private void sample() {
    sources.forEach(BusinessGaugeSource::sample);
  }
}

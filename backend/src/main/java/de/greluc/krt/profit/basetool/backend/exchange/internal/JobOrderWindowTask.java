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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the job-order window nightly at {@code app.exchange.job-order-window.cron} (default 04:00
 * UTC, REQ-OXCH-003, REQ-OXCH-010), whatever the job-order switches say, so the feed is current
 * when the family is switched on again.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.exchange.job-order-window",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class JobOrderWindowTask {

  private final JobOrderWindowRun run;
  private final TaskMetrics taskMetrics;

  /**
   * Ages closed orders out of the client set as the {@code job_order_exchange_window} job, whose
   * items count the orders aged out; a failure is recorded and swallowed by {@link TaskMetrics}.
   */
  @Scheduled(cron = "${app.exchange.job-order-window.cron:0 0 4 * * *}", zone = "UTC")
  public void ageOut() {
    taskMetrics.recordCounting(ScheduledJob.JOB_ORDER_EXCHANGE_WINDOW, run::ageOut);
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="job_order_exchange_window"} = 1}, so
   * {@code ScheduledJobStale} can tell a disabled run from one that never succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.JOB_ORDER_EXCHANGE_WINDOW);
  }
}

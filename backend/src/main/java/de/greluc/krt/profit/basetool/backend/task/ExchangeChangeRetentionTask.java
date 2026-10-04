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

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeChangeRetentionProperties;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeBulkUndoService;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeChangeRetentionService;
import de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeJournalService;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly purge of the exchange change feed's entries, and so its tombstones, of the exchange write
 * journal and of the ended bulk undo runs past their retention of {@code
 * app.exchange.change-retention.max-age} (default 90 days, REQ-XCH-013, REQ-XCH-022, REQ-XCH-034).
 */
@Component
@ConditionalOnProperty(
    prefix = "app.exchange.change-retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class ExchangeChangeRetentionTask {

  private final ExchangeChangeRetentionService retentionService;
  private final ExchangeJournalService journalService;
  private final ExchangeBulkUndoService bulkUndoService;
  private final ExchangeChangeRetentionProperties properties;
  private final TaskMetrics taskMetrics;
  private final Clock clock = Clock.systemUTC();

  /**
   * Purges the entries older than the retention, publishing the {@code exchange_change_retention}
   * job metrics; a failure is recorded and swallowed by {@link TaskMetrics}.
   */
  @Scheduled(cron = "${app.exchange.change-retention.cron:0 30 3 * * *}", zone = "UTC")
  public void purgeExpiredChanges() {
    taskMetrics.recordCounting(ScheduledJob.EXCHANGE_CHANGE_RETENTION, this::purge);
  }

  /**
   * Runs one purge.
   *
   * @return the number of change entries, journal entries and bulk undo runs deleted
   */
  private int purge() {
    Instant now = clock.instant();
    Instant cutoff = now.minus(properties.maxAge());
    int changes = retentionService.purgeOlderThan(cutoff, now);
    int writes = journalService.purgeRecordedBefore(cutoff);
    int runs = bulkUndoService.purgeFinishedBefore(cutoff);
    log.info(
        "Exchange retention: {} change entries, {} journal entries and {} bulk undo runs purged.",
        changes,
        writes,
        runs);
    return changes + writes + runs;
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="exchange_change_retention"} = 1}, so
   * {@code ScheduledJobStale} can tell a disabled purge from one that never succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.EXCHANGE_CHANGE_RETENTION);
  }
}

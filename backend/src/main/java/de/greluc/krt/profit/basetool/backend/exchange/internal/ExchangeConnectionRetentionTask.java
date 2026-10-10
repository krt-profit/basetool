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

import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly deletion of disconnected exchange installations and members' client revocations older
 * than {@code app.exchange.connection-retention.max-age} (default 90 days, REQ-XCH-035).
 */
@Component
@ConditionalOnProperty(
    prefix = "app.exchange.connection-retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class ExchangeConnectionRetentionTask {

  private final ExchangeConnectionRetentionService retentionService;
  private final ExchangeConnectionRetentionProperties properties;
  private final ExchangeChangeRetentionProperties changeRetention;
  private final TaskMetrics taskMetrics;
  private final Clock clock = Clock.systemUTC();

  /**
   * Deletes the entries past the retention, publishing the {@code exchange_connection_retention}
   * job metrics; a failure is recorded and swallowed by {@link TaskMetrics}.
   */
  @Scheduled(cron = "${app.exchange.connection-retention.cron:0 45 3 * * *}", zone = "UTC")
  public void purgeExpiredConnections() {
    taskMetrics.recordCounting(ScheduledJob.EXCHANGE_CONNECTION_RETENTION, this::purge);
  }

  /**
   * Runs one sweep.
   *
   * @return the number of installations and revocations deleted
   */
  private int purge() {
    ExchangeConnectionRetentionService.Purged purged =
        retentionService.purgeBefore(clock.instant().minus(properties.maxAge()));
    log.info(
        "Exchange connection retention: {} installations and {} client revocations deleted.",
        purged.installations(),
        purged.revocations());
    return purged.total();
  }

  /**
   * Refuses to start when installations would be deleted while the journal still holds their
   * writes, then publishes {@code
   * basetool_scheduled_job_enabled{task="exchange_connection_retention"} = 1}.
   *
   * @throws IllegalStateException when the connection retention is shorter than the change
   *     retention
   */
  @PostConstruct
  void verifyAndPublishEnabledGauge() {
    requireNotShorterThanChangeRetention(properties, changeRetention);
    taskMetrics.markEnabled(ScheduledJob.EXCHANGE_CONNECTION_RETENTION);
  }

  /**
   * Checks that disconnected installations outlive the journal and change feed that name them, so a
   * bulk undo limited to one installation and a tombstone's installation id still resolve.
   *
   * @param connections the connection retention
   * @param changes the change retention
   * @throws IllegalStateException when {@code connections.maxAge()} is shorter than {@code
   *     changes.maxAge()}
   */
  static void requireNotShorterThanChangeRetention(
      @NotNull ExchangeConnectionRetentionProperties connections,
      @NotNull ExchangeChangeRetentionProperties changes) {
    if (connections.maxAge().compareTo(changes.maxAge()) < 0) {
      throw new IllegalStateException(
          "app.exchange.connection-retention.max-age must not be shorter than"
              + " app.exchange.change-retention.max-age");
    }
  }
}

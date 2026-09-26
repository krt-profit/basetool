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

package de.greluc.krt.profit.basetool.backend.service.scwiki;

import de.greluc.krt.profit.basetool.backend.config.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.MasterDataCacheEvictionService;
import de.greluc.krt.profit.basetool.backend.service.SyncCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler that runs the SC Wiki syncs on the dedicated executor, by default every 24 hours after
 * a one-hour initial delay.
 *
 * <p>Checks {@code krt.scwiki.scheduler-enabled}, then runs each sync in dependency order under the
 * {@link SyncCoordinator}; a failing step does not abort the others ({@link #runStep}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScWikiScheduler {

  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final ScWikiCommoditySyncService commoditySyncService;
  private final ScWikiBlueprintSyncService blueprintSyncService;
  private final ScWikiItemSyncService itemSyncService;
  private final ScWikiVehicleSyncService vehicleSyncService;
  private final ScWikiManufacturerSyncService manufacturerSyncService;
  private final SyncCoordinator syncCoordinator;
  private final TaskMetrics taskMetrics;
  private final MasterDataCacheEvictionService masterDataCacheEvictionService;
  private final MeterRegistry meterRegistry;

  /**
   * Periodic SC Wiki sync entry point on the {@link AsyncConfig#SCWIKI_EXECUTOR} pool; returns
   * early when the master switch is off.
   */
  @Async(AsyncConfig.SCWIKI_EXECUTOR)
  @Scheduled(
      fixedDelayString = "${krt.scwiki.scheduler-delay:86400000}",
      initialDelayString = "${krt.scwiki.scheduler-initial-delay:3600000}")
  public void scheduleScWikiSync() {
    if (!Boolean.TRUE.equals(properties.schedulerEnabled())) {
      log.info("ScWikiScheduler invoked but disabled (krt.scwiki.scheduler-enabled=false) — skip.");
      return;
    }
    syncCoordinator.runExclusively(
        "SC Wiki",
        () -> taskMetrics.recordCounting(ScheduledJob.SCWIKI_SYNC, this::runAllSyncSteps));
  }

  /**
   * Runs every SC Wiki sync step in dependency order via {@link
   * SyncCoordinator#runExclusively(String, Runnable)}, then evicts the affected master-data caches
   * through {@link MasterDataCacheEvictionService#evictScWikiSyncedMasterData()}.
   *
   * @return the total number of catalogue rows written across all steps; a failing step counts
   *     {@code 0}
   */
  private int runAllSyncSteps() {
    log.debug("Running scheduled SC Wiki sync against {}", scWikiClient.getClass().getSimpleName());
    int total = 0;
    try {
      total += runStep("commodity", commoditySyncService::syncCommodities);
      total += runStep("vehicle", vehicleSyncService::syncVehicles);
      total += runStep("item", itemSyncService::syncItems);
      total += runStep("blueprint", blueprintSyncService::syncBlueprints);
      total += runStep("manufacturer", manufacturerSyncService::syncManufacturers);
    } finally {
      masterDataCacheEvictionService.evictScWikiSyncedMasterData();
    }
    return total;
  }

  /**
   * Runs one sync step, logging and swallowing any exception.
   *
   * @param label short name of the step for the error log line
   * @param step the sync invocation, returning the number of rows it wrote
   * @return the step's written-row count, or {@code 0} if it threw
   */
  private int runStep(String label, IntSupplier step) {
    try {
      return step.getAsInt();
    } catch (Exception e) {
      log.error("Scheduled SC Wiki {} sync failed", label, e);
      meterRegistry
          .counter(
              MetricNames.SCHEDULED_JOB_STEP_FAILURES,
              MetricNames.TAG_JOB,
              ScheduledJob.SCWIKI_SYNC.label(),
              MetricNames.TAG_STEP,
              label)
          .increment();
      return 0;
    }
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="scwiki_sync"} = 1} only when {@code
   * krt.scwiki.scheduler-enabled} is on.
   */
  @PostConstruct
  void publishEnabledGauge() {
    if (Boolean.TRUE.equals(properties.schedulerEnabled())) {
      taskMetrics.markEnabled(ScheduledJob.SCWIKI_SYNC);
    }
  }
}

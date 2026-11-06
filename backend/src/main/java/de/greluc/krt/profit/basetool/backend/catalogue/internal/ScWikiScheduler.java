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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
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
  private final SyncRunService syncRuns;

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
    if (!hasEnabledSlices()) {
      log.info("ScWikiScheduler has no enabled catalogue slices — skipping source resolution.");
      return;
    }
    syncCoordinator.runExclusively(
        "SC Wiki",
        () -> taskMetrics.recordCounting(ScheduledJob.SCWIKI_SYNC, this::runSourceSweep));
  }

  /**
   * Runs every SC Wiki sync step in dependency order via {@link
   * SyncCoordinator#runExclusively(String, Runnable)}, then evicts the affected master-data caches
   * through {@link MasterDataCacheEvictionService#evictScWikiSyncedMasterData()}.
   *
   * @return the total number of catalogue rows written across all steps; a failing step counts
   *     {@code 0}
   */
  private int runSourceSweep() {
    log.debug("Running scheduled SC Wiki sync against {}", scWikiClient.getClass().getSimpleName());
    return syncRuns
        .run(
            SyncSourceSystem.SCWIKI,
            SyncAggregate.CATALOGUE,
            null,
            () -> {
              try (ScWikiClient.SyncRun scope = scWikiClient.openSyncRun()) {
                syncRuns.recordGameVersion(scope.version());
                return runAllSyncSteps(scope.version());
              }
            })
        .count();
  }

  private int runAllSyncSteps(String version) {
    try {
      SyncRunService.Result[] results = {
        runStep(
            "commodity", SyncAggregate.COMMODITY, version, commoditySyncService::syncCommodities),
        runStep("vehicle", SyncAggregate.SHIP_TYPE, version, vehicleSyncService::syncVehicles),
        runStep("item", SyncAggregate.GAME_ITEM, version, itemSyncService::syncItems),
        runStep(
            "blueprint", SyncAggregate.BLUEPRINT, version, blueprintSyncService::syncBlueprints),
        runStep(
            "manufacturer",
            SyncAggregate.MANUFACTURER,
            version,
            manufacturerSyncService::syncManufacturers)
      };
      if (java.util.Arrays.stream(results).anyMatch(result -> !result.complete())) {
        throw new IllegalStateException(
            "SC Wiki sync did not completely apply every catalogue slice");
      }
      return java.util.Arrays.stream(results).mapToInt(SyncRunService.Result::count).sum();
    } finally {
      masterDataCacheEvictionService.evictScWikiSyncedMasterData();
    }
  }

  /**
   * Runs one sync step, logging and swallowing any exception.
   *
   * @param label short name of the step for the error log line
   * @param aggregate independently applied catalogue slice
   * @param version frozen upstream build
   * @param step the sync invocation, returning the number of rows it wrote
   * @return durable outcome, or a failed outcome when the step threw
   */
  private SyncRunService.Result runStep(
      String label, SyncAggregate aggregate, String version, IntSupplier step) {
    boolean enabled =
        switch (aggregate) {
          case COMMODITY -> Boolean.TRUE.equals(properties.commoditySyncEnabled());
          case SHIP_TYPE -> Boolean.TRUE.equals(properties.vehicleSyncEnabled());
          case GAME_ITEM -> Boolean.TRUE.equals(properties.itemSyncEnabled());
          case BLUEPRINT -> Boolean.TRUE.equals(properties.blueprintSyncEnabled());
          case MANUFACTURER -> Boolean.TRUE.equals(properties.manufacturerSyncEnabled());
          case CATALOGUE,
              STAR_SYSTEM,
              FACTION,
              JURISDICTION,
              PLANET,
              MOON,
              ORBIT,
              CITY,
              OUTPOST,
              POI,
              SPACE_STATION,
              TERMINAL,
              CATEGORY,
              ITEM_PRICE,
              REFINING_METHOD,
              REFINERY_YIELD ->
              throw new IllegalArgumentException("Unsupported SC Wiki catalogue slice");
        };
    if (!enabled) {
      return new SyncRunService.Result(0, SyncRunStatus.UNCHANGED);
    }
    try {
      return syncRuns.run(SyncSourceSystem.SCWIKI, aggregate, version, step);
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
      return new SyncRunService.Result(0, SyncRunStatus.FAILED);
    }
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="scwiki_sync"} = 1} only when {@code
   * krt.scwiki.scheduler-enabled} is on.
   */
  @PostConstruct
  void publishEnabledGauge() {
    if (Boolean.TRUE.equals(properties.schedulerEnabled()) && hasEnabledSlices()) {
      taskMetrics.markEnabled(ScheduledJob.SCWIKI_SYNC);
    }
  }

  private boolean hasEnabledSlices() {
    return Boolean.TRUE.equals(properties.commoditySyncEnabled())
        || Boolean.TRUE.equals(properties.vehicleSyncEnabled())
        || Boolean.TRUE.equals(properties.itemSyncEnabled())
        || Boolean.TRUE.equals(properties.blueprintSyncEnabled())
        || Boolean.TRUE.equals(properties.manufacturerSyncEnabled());
  }
}

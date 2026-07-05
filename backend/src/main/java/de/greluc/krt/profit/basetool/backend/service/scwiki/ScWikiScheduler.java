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
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.MasterDataCacheEvictionService;
import de.greluc.krt.profit.basetool.backend.service.SyncCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler bean that drives the periodic SC Wiki sync.
 *
 * <p>{@code @Scheduled} fixed-delay of {@code krt.scwiki.scheduler-delay} (default 24h); Wiki data
 * changes only on game patches. An {@code initialDelay} of {@code
 * krt.scwiki.scheduler-initial-delay} (default 1h) staggers the first run behind the UEX scheduler
 * (which starts at boot) so the two daily syncs do not fire at the same time.
 * {@code @Async(AsyncConfig.SCWIKI_EXECUTOR)} runs the sweep on the dedicated bounded pool so a
 * slow Wiki response cannot delay the UEX scheduler. Both schedulers additionally share a {@link
 * SyncCoordinator} gate as a safety net: if their cadences ever align, the later sync waits for the
 * earlier one to finish rather than running concurrently.
 *
 * <p>R3 wired the first real sync (commodity merge); R4 adds the vehicle fill, the closure-mode
 * item fill and the blueprint graph. The scheduler checks the {@code krt.scwiki.scheduler-enabled}
 * master switch, then delegates to each sync in dependency order; every sync <b>itself</b>
 * self-guards on its own per-sync feature flag (all default {@code false}) so each stays dark until
 * an operator opts in per the deployment runbook. R6 adds the manufacturer reconciliation, likewise
 * behind its own flag.
 *
 * <p>Per-sync exceptions are swallowed per step ({@link #runStep}) — same fail-one-succeed-others
 * contract as {@code UexScheduler} — so one failing sync never aborts the others or suppresses the
 * next tick.
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

  /**
   * Periodic SC Wiki sync entry point. Runs on the {@link AsyncConfig#SCWIKI_EXECUTOR} pool so a
   * slow Wiki response cannot delay the UEX scheduler (or any other {@code @Scheduled} bean).
   *
   * <p>If the master switch is off, logs and returns. Otherwise runs each sync in dependency order
   * — commodities (R3) and vehicles fill cross-source rows first, then the closure-mode item fill,
   * then blueprints (whose ingredients resolve against {@code game_item} / {@code material}), then
   * the R6 manufacturer reconciliation. Each sync self-guards on its per-sync flag. The {@link
   * ScWikiClient} field is referenced here so the {@code
   * scWikiIntegrationClassesMustWireScWikiClient} ArchUnit rule is satisfied.
   */
  @Async(AsyncConfig.SCWIKI_EXECUTOR)
  @Scheduled(
      fixedDelayString = "${krt.scwiki.scheduler-delay:86400000}",
      initialDelayString = "${krt.scwiki.scheduler-initial-delay:3600000}")
  public void scheduleScWikiSync() {
    if (!Boolean.TRUE.equals(properties.getSchedulerEnabled())) {
      log.info("ScWikiScheduler invoked but disabled (krt.scwiki.scheduler-enabled=false) — skip.");
      return;
    }
    syncCoordinator.runExclusively(
        "SC Wiki", () -> taskMetrics.record(ScheduledJob.SCWIKI_SYNC, this::runAllSyncSteps));
  }

  /**
   * Runs every SC Wiki sync step in dependency order. Invoked by {@link #scheduleScWikiSync()}
   * through {@link SyncCoordinator#runExclusively(String, Runnable)} — and therefore only when no
   * UEX sync is in progress; if one is, this run waits for it to finish first (bounded by the
   * coordinator's wait cap) so the two never execute at the same time.
   *
   * <p>After the steps run, the master-data caches this sweep can make stale are evicted in a
   * {@code finally} ({@link MasterDataCacheEvictionService#evictScWikiSyncedMasterData()}) so
   * synced commodity / vehicle / manufacturer / blueprint-family changes are visible on the next
   * read rather than lagging the 12-hour TTL (CACHE-SYNC-EVICT-001, CACHE-DIST-03).
   */
  private void runAllSyncSteps() {
    log.debug("Running scheduled SC Wiki sync against {}", scWikiClient.getClass().getSimpleName());
    try {
      runStep("commodity", commoditySyncService::syncCommodities);
      runStep("vehicle", vehicleSyncService::syncVehicles);
      runStep("item", itemSyncService::syncItems);
      runStep("blueprint", blueprintSyncService::syncBlueprints);
      runStep("manufacturer", manufacturerSyncService::syncManufacturers);
    } finally {
      masterDataCacheEvictionService.evictScWikiSyncedMasterData();
    }
  }

  /**
   * Runs one sync step, swallowing and logging any exception so a single failing sync never aborts
   * the remaining steps or suppresses the next scheduled tick.
   *
   * @param label short name of the step for the error log line
   * @param step the sync invocation
   */
  private void runStep(String label, Runnable step) {
    try {
      step.run();
    } catch (Exception e) {
      log.error("Scheduled SC Wiki {} sync failed", label, e);
    }
  }
}

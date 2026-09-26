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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.config.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the periodic UEX sync every {@code krt.uex.scheduler-delay} (default 24 h) on the bounded
 * executor of {@link AsyncConfig#uexExecutor()}.
 *
 * <p>Steps run in dependency order, terminals first (REQ-REFINERY-020), and the sweep is serialised
 * against the SC Wiki sync through {@link SyncCoordinator}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "krt.uex",
    name = "scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class UexScheduler {

  private final UexCommodityService uexCommodityService;
  private final UexStarSystemService uexStarSystemService;
  private final UexManufacturerService uexManufacturerService;
  private final UexVehicleService uexVehicleService;
  private final UexUniverseSyncService uexUniverseSyncService;
  private final UexRefinerySyncService uexRefinerySyncService;
  private final UexCategoryRefService uexCategoryRefService;
  private final UexItemSyncService uexItemSyncService;
  private final UexItemPriceSyncService uexItemPriceSyncService;
  private final SyncCoordinator syncCoordinator;
  private final TaskMetrics taskMetrics;
  private final MasterDataCacheEvictionService masterDataCacheEvictionService;

  /**
   * Periodic UEX sync entry point, started at boot and serialised against the SC Wiki sync through
   * {@link SyncCoordinator#runExclusively(String, Runnable)}; a tick waits for a running Wiki sync
   * rather than being dropped.
   */
  @Async(AsyncConfig.UEX_EXECUTOR)
  @Scheduled(
      fixedDelayString = "${krt.uex.scheduler-delay:86400000}",
      initialDelayString = "${krt.uex.scheduler-initial-delay:0}")
  public void scheduleCommodityPriceUpdate() {
    syncCoordinator.runExclusively(
        "UEX", () -> taskMetrics.recordCounting(ScheduledJob.UEX_SYNC, this::runAllSyncSteps));
  }

  /**
   * Runs the full UEX sync sweep in dependency order; the first failing step aborts the rest.
   *
   * <p>A {@code finally} block evicts the master-data caches via {@link
   * MasterDataCacheEvictionService#evictUexSyncedMasterData()} and reconciles the refinery terminal
   * flags, even after an abort.
   *
   * @return the number of {@code game_item} rows the item sync upserted this run ({@code 0} if the
   *     sweep aborted before the item step ran)
   */
  private int runAllSyncSteps() {
    log.info("Running scheduled task to update UEX data...");
    int itemsProcessed = 0;
    try {
      uexUniverseSyncService.syncTerminals();

      uexUniverseSyncService.syncFactions();
      uexUniverseSyncService.syncJurisdictions();
      uexUniverseSyncService.syncPlanets();
      uexUniverseSyncService.syncMoons();
      uexUniverseSyncService.syncOrbits();
      uexUniverseSyncService.syncCities();
      uexUniverseSyncService.syncOutposts();
      uexUniverseSyncService.syncPois();
      uexUniverseSyncService.syncSpaceStations();

      uexStarSystemService.fetchAndProcessStarSystems();
      uexCommodityService.fetchAndProcessCommoditiesPrices();
      uexManufacturerService.syncManufacturers();
      uexVehicleService.syncVehicles();

      uexCategoryRefService.syncCategories();
      itemsProcessed = uexItemSyncService.syncItems();

      uexItemPriceSyncService.syncItemPrices();

      uexRefinerySyncService.syncRefiningMethods();
      uexRefinerySyncService.syncRefineryYields();
    } finally {
      try {
        uexUniverseSyncService.reconcileRefineryTerminalFlags();
      } catch (RuntimeException e) {
        log.error(
            "Refinery-terminal flag reconciliation failed; flags keep their previous values", e);
      }
      masterDataCacheEvictionService.evictUexSyncedMasterData();
    }
    return itemsProcessed;
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="uex_sync"} = 1}.
   *
   * <p>A disabled UEX sync creates no bean and publishes nothing, which lets the {@code
   * ExternalSyncStale} alert tell "switched off" from "never succeeded".
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.UEX_SYNC);
  }
}

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single scheduler bean that drives the periodic UEX-data refresh.
 *
 * <p>{@code @Scheduled} fixed-delay of {@code krt.uex.scheduler-delay} (default 86400000 ms = 24
 * h). The {@code @Async(AsyncConfig.UEX_EXECUTOR)} pulls execution off the scheduler thread onto
 * the dedicated bounded executor declared in {@link AsyncConfig#uexExecutor()}, so a slow UEX
 * response cannot delay other scheduled tasks AND cannot spawn unbounded threads (the previous
 * unqualified {@code @Async} fell back to the unbounded {@code SimpleAsyncTaskExecutor}).
 * {@code @ConditionalOnProperty(matchIfMissing = true)} keeps the bean active by default but lets
 * the {@code test} profile disable it without touching the rest of the wiring.
 *
 * <p>R2 expansion: the original chain (universe topology → commodities → manufacturers → vehicles →
 * refinery) is extended with {@code UexCategoryRefService.syncCategories()} and {@code
 * UexItemSyncService.syncItems()} between manufacturers and refinery. The order matters: categories
 * must land before items (the item walk reads them); manufacturers must land before items (the item
 * upsert resolves the manufacturer FK); vehicles must land before items because vehicle-bound items
 * (paints, components) resolve {@code linked_ship_type_id} via {@code
 * ShipTypeRepository.findByUexVehicleId}.
 *
 * <p>R7 expansion: {@code UexItemPriceSyncService.syncItemPrices()} runs after the item catalogue
 * (it resolves {@code game_item} + {@code terminal} FKs, both synced earlier in the tick), behind
 * its own {@code krt.uex.item-price-sync-enabled} flag (default off) so it stays a no-op until an
 * operator opts in.
 *
 * <p>Cross-scheduler exclusion: the sweep runs through a shared {@link SyncCoordinator} so it never
 * overlaps the SC Wiki sync. UEX starts at boot ({@code initialDelay = 0}) and SC Wiki is staggered
 * an hour later; should their daily cadences ever align, whichever fires second waits for the first
 * to finish instead of running concurrently (which previously caused {@code game_item} write
 * races).
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
   * Periodic UEX sync entry point on a fixed delay, started immediately on boot ({@code
   * initialDelay = 0}) so it leads the staggered SC Wiki tick. Funnels the whole sweep through
   * {@link SyncCoordinator#runExclusively(String, Runnable)} so it never runs at the same time as
   * the SC Wiki sync: if that sync is in progress this tick waits for it to finish and then runs
   * (it is not dropped), bounded by the coordinator's hung-sync wait cap.
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
   * Runs the full UEX sync sweep. Order matters — topology imports first so later imports can
   * resolve parent locations. A failure aborts the remaining steps (best-effort tick); the sweep is
   * wrapped by {@link TaskMetrics} at the call site (see {@link #scheduleCommodityPriceUpdate()}),
   * which records the run as a {@code failure} and swallows the exception so the scheduler thread
   * survives. Only invoked through {@link SyncCoordinator#runExclusively(String, Runnable)}, so it
   * holds the cross-scheduler lock for its full duration — the SC Wiki sync waits behind it.
   *
   * <p>The sweep evicts the master-data caches it can make stale in a {@code finally} ({@link
   * MasterDataCacheEvictionService#evictUexSyncedMasterData()}), so a freshly-synced catalogue is
   * visible on the next read instead of lagging the 12-hour TTL (CACHE-SYNC-EVICT-001) — and
   * committed steps are still reconciled when a later step aborts the sweep.
   *
   * <p>Returns the item-catalogue upsert count from {@link UexItemSyncService#syncItems()} — the
   * representative "rows processed" tally for the whole sweep — which {@link
   * #scheduleCommodityPriceUpdate()} records into {@code
   * basetool_scheduled_job_items_total{job="uex_sync"}} via {@code TaskMetrics.recordCounting}.
   * When a step throws the count is discarded (the run is recorded as a {@code failure}, no items
   * counted); a clean run that upserted zero rows records {@code 0}, which is what the {@code
   * SyncZeroItems} alert watches for (#1041 item 2).
   *
   * @return the number of {@code game_item} rows the item sync upserted this run ({@code 0} if the
   *     sweep aborted before the item step ran)
   */
  private int runAllSyncSteps() {
    log.info("Running scheduled task to update UEX data...");
    int itemsProcessed = 0;
    try {
      uexUniverseSyncService.syncFactions();
      uexUniverseSyncService.syncJurisdictions();
      uexUniverseSyncService.syncPlanets();
      uexUniverseSyncService.syncMoons();
      uexUniverseSyncService.syncOrbits();
      uexUniverseSyncService.syncCities();
      uexUniverseSyncService.syncOutposts();
      uexUniverseSyncService.syncPois();
      uexUniverseSyncService.syncSpaceStations();
      uexUniverseSyncService.syncTerminals();

      uexStarSystemService.fetchAndProcessStarSystems();
      uexCommodityService.fetchAndProcessCommoditiesPrices();
      uexManufacturerService.syncManufacturers();
      uexVehicleService.syncVehicles();

      // R2 — category reference + item catalogue. categoriesRef populates the table that
      // UexItemSyncService iterates; the latter resolves manufacturers (already synced above)
      // and linked ship types (also above), so the topological order is preserved.
      uexCategoryRefService.syncCategories();
      itemsProcessed = uexItemSyncService.syncItems();

      // R7 — item prices. Runs after the item catalogue (resolves game_item) and terminals
      // (synced in the universe phase above). Self-guards on krt.uex.item-price-sync-enabled, so
      // this is a no-op until an operator opts in.
      uexItemPriceSyncService.syncItemPrices();

      uexRefinerySyncService.syncRefiningMethods();
      uexRefinerySyncService.syncRefineryYields();
    } finally {
      masterDataCacheEvictionService.evictUexSyncedMasterData();
    }
    return itemsProcessed;
  }
}

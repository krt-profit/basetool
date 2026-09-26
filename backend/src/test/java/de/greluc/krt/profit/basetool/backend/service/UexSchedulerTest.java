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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link UexScheduler}: every sync service runs exactly once per tick, in the
 * dependency order (universe basics, then star systems / commodities / vehicles, then refineries),
 * and an exception in one service does not escape the scheduled task.
 */
@ExtendWith(MockitoExtension.class)
class UexSchedulerTest {

  @Mock private UexCommodityService uexCommodityService;
  @Mock private UexStarSystemService uexStarSystemService;
  @Mock private UexManufacturerService uexManufacturerService;
  @Mock private UexVehicleService uexVehicleService;
  @Mock private UexUniverseSyncService uexUniverseSyncService;
  @Mock private UexRefinerySyncService uexRefinerySyncService;
  @Mock private UexCategoryRefService uexCategoryRefService;
  @Mock private UexItemSyncService uexItemSyncService;
  @Mock private UexItemPriceSyncService uexItemPriceSyncService;
  @Mock private MasterDataCacheEvictionService masterDataCacheEvictionService;

  @Spy private SyncCoordinator syncCoordinator = new SyncCoordinator(3_600_000);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Spy private TaskMetrics taskMetrics = new TaskMetrics(meterRegistry);

  @InjectMocks private UexScheduler scheduler;

  @Test
  void scheduleTask_invokesEverySyncServiceOnce() {
    scheduler.scheduleCommodityPriceUpdate();

    verify(uexUniverseSyncService).syncFactions();
    verify(uexUniverseSyncService).syncJurisdictions();
    verify(uexUniverseSyncService).syncPlanets();
    verify(uexUniverseSyncService).syncMoons();
    verify(uexUniverseSyncService).syncOrbits();
    verify(uexUniverseSyncService).syncCities();
    verify(uexUniverseSyncService).syncOutposts();
    verify(uexUniverseSyncService).syncPois();
    verify(uexUniverseSyncService).syncSpaceStations();
    verify(uexUniverseSyncService).syncTerminals();

    verify(uexStarSystemService).fetchAndProcessStarSystems();
    verify(uexCommodityService).fetchAndProcessCommoditiesPrices();
    verify(uexManufacturerService).syncManufacturers();
    verify(uexVehicleService).syncVehicles();

    verify(uexCategoryRefService).syncCategories();
    verify(uexItemSyncService).syncItems();
    verify(uexItemPriceSyncService).syncItemPrices();

    verify(uexRefinerySyncService).syncRefiningMethods();
    verify(uexRefinerySyncService).syncRefineryYields();

    verify(uexUniverseSyncService).reconcileRefineryTerminalFlags();

    verifyNoMoreInteractions(
        uexUniverseSyncService,
        uexStarSystemService,
        uexCommodityService,
        uexManufacturerService,
        uexVehicleService,
        uexCategoryRefService,
        uexItemSyncService,
        uexItemPriceSyncService,
        uexRefinerySyncService);
  }

  @Test
  void scheduleTask_invokesUniverseSyncsBeforeStarSystemsAndCommodities() {
    scheduler.scheduleCommodityPriceUpdate();

    InOrder order =
        inOrder(
            uexUniverseSyncService,
            uexStarSystemService,
            uexCommodityService,
            uexManufacturerService,
            uexVehicleService,
            uexCategoryRefService,
            uexItemSyncService,
            uexItemPriceSyncService,
            uexRefinerySyncService);

    order.verify(uexUniverseSyncService).syncTerminals();

    order.verify(uexUniverseSyncService).syncFactions();
    order.verify(uexUniverseSyncService).syncJurisdictions();
    order.verify(uexUniverseSyncService).syncPlanets();
    order.verify(uexUniverseSyncService).syncMoons();
    order.verify(uexUniverseSyncService).syncOrbits();
    order.verify(uexUniverseSyncService).syncCities();
    order.verify(uexUniverseSyncService).syncOutposts();
    order.verify(uexUniverseSyncService).syncPois();
    order.verify(uexUniverseSyncService).syncSpaceStations();

    order.verify(uexStarSystemService).fetchAndProcessStarSystems();
    order.verify(uexCommodityService).fetchAndProcessCommoditiesPrices();
    order.verify(uexManufacturerService).syncManufacturers();
    order.verify(uexVehicleService).syncVehicles();

    order.verify(uexCategoryRefService).syncCategories();
    order.verify(uexItemSyncService).syncItems();
    order.verify(uexItemPriceSyncService).syncItemPrices();

    order.verify(uexRefinerySyncService).syncRefiningMethods();
    order.verify(uexRefinerySyncService).syncRefineryYields();
  }

  @Test
  void scheduleTask_recordsItemCatalogueUpsertCount() {
    when(uexItemSyncService.syncItems()).thenReturn(4242);

    scheduler.scheduleCommodityPriceUpdate();

    assertEquals(
        4242,
        meterRegistry
            .get(MetricNames.SCHEDULED_JOB_ITEMS)
            .tag(MetricNames.TAG_JOB, ScheduledJob.UEX_SYNC.label())
            .counter()
            .count(),
        "basetool_scheduled_job_items_total{job=uex_sync} must equal the item-sync upsert count");
  }

  @Test
  void scheduleTask_recordsNoItemCount_whenTheSweepFails() {
    doThrow(new RuntimeException("UEX 500")).when(uexUniverseSyncService).syncFactions();

    scheduler.scheduleCommodityPriceUpdate();

    assertNull(
        meterRegistry
            .find(MetricNames.SCHEDULED_JOB_ITEMS)
            .tag(MetricNames.TAG_JOB, ScheduledJob.UEX_SYNC.label())
            .counter(),
        "a failed sweep must not register an item count (distinct from a clean zero-item run)");
  }

  @Test
  void scheduleTask_swallowsExceptionFromInnerService() {
    doThrow(new RuntimeException("UEX 500")).when(uexUniverseSyncService).syncFactions();

    scheduler.scheduleCommodityPriceUpdate();

    verify(uexUniverseSyncService).syncFactions();
    verify(uexUniverseSyncService, never()).syncPlanets();
  }

  @Test
  void scheduleTask_continuesAfterPartialFailureInOneServiceMethod() {
    doThrow(new RuntimeException("transient")).when(uexUniverseSyncService).syncFactions();

    scheduler.scheduleCommodityPriceUpdate();

    verify(uexRefinerySyncService, never()).syncRefiningMethods();
  }

  @Test
  void scheduleTask_evictsUexSyncedMasterDataAfterSweep() {
    scheduler.scheduleCommodityPriceUpdate();

    verify(masterDataCacheEvictionService).evictUexSyncedMasterData();
  }

  @Test
  void scheduleTask_evictsMasterDataEvenWhenAStepFails() {
    doThrow(new RuntimeException("UEX 500")).when(uexUniverseSyncService).syncFactions();

    scheduler.scheduleCommodityPriceUpdate();

    verify(masterDataCacheEvictionService).evictUexSyncedMasterData();
  }

  @Test
  void scheduleTask_syncsTerminalsEvenWhenTheRestOfTheTopologyFails() {
    doThrow(new RuntimeException("UEX 500")).when(uexUniverseSyncService).syncFactions();

    scheduler.scheduleCommodityPriceUpdate();

    verify(uexUniverseSyncService).syncTerminals();
  }

  @Test
  void scheduleTask_reconcilesRefineryFlagsEvenWhenALaterStepFails() {
    doThrow(new RuntimeException("UEX 500")).when(uexVehicleService).syncVehicles();

    scheduler.scheduleCommodityPriceUpdate();

    verify(uexUniverseSyncService).reconcileRefineryTerminalFlags();
  }

  @Test
  void scheduleTask_stillEvictsMasterData_whenReconciliationItselfFails() {
    doThrow(new RuntimeException("reconcile boom"))
        .when(uexUniverseSyncService)
        .reconcileRefineryTerminalFlags();

    scheduler.scheduleCommodityPriceUpdate();

    verify(masterDataCacheEvictionService).evictUexSyncedMasterData();
  }

  @Test
  void scheduleTask_skipsEntireSweep_whenAnotherSyncIsAlreadyRunning() {
    doReturn(false).when(syncCoordinator).runExclusively(eq("UEX"), any());

    scheduler.scheduleCommodityPriceUpdate();

    verifyNoInteractions(
        uexUniverseSyncService,
        uexStarSystemService,
        uexCommodityService,
        uexManufacturerService,
        uexVehicleService,
        uexCategoryRefService,
        uexItemSyncService,
        uexItemPriceSyncService,
        uexRefinerySyncService,
        masterDataCacheEvictionService);
  }
}

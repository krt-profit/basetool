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

import static org.mockito.Mockito.*;

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
 * Unit tests for {@link UexScheduler}. The scheduler is pure orchestration: it dispatches the
 * per-resource UEX sync services in a specific order. The tests guard two contracts:
 *
 * <ol>
 *   <li>Every dependency is invoked exactly once per tick (no silent drop-outs after a refactor).
 *   <li>The dispatch order matches the documented sequence — universe basics first (factions,
 *       jurisdictions, planets, ...), then star systems / commodities / vehicles, finally
 *       refineries. Reordering this matters because later syncs depend on the FK targets being
 *       present.
 *   <li>An exception inside one of the services must not propagate out of the scheduled task;
 *       otherwise the {@code @Scheduled} task suppresses subsequent invocations.
 * </ol>
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

  // A real coordinator (spied so it can be told the gate is busy) — its default behaviour runs the
  // sweep synchronously, so the existing ordering/verify tests below exercise the real steps.
  @Spy private SyncCoordinator syncCoordinator = new SyncCoordinator(3_600_000);

  // A real TaskMetrics (spied) so the instrumentation wrapper genuinely runs the sweep body.
  @Spy private TaskMetrics taskMetrics = new TaskMetrics(new SimpleMeterRegistry());

  @InjectMocks private UexScheduler scheduler;

  @Test
  void scheduleTask_invokesEverySyncServiceOnce() {
    // When
    scheduler.scheduleCommodityPriceUpdate();

    // Then — every method called exactly once
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
    // When
    scheduler.scheduleCommodityPriceUpdate();

    // Then — the documented order is preserved. We assert the boundary
    // transitions only (otherwise the test becomes brittle): universe
    // basics first, then catalogue syncs, then refinery syncs.
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

    // Phase 1: universe basics in declared order
    order.verify(uexUniverseSyncService).syncFactions();
    order.verify(uexUniverseSyncService).syncJurisdictions();
    order.verify(uexUniverseSyncService).syncPlanets();
    order.verify(uexUniverseSyncService).syncMoons();
    order.verify(uexUniverseSyncService).syncOrbits();
    order.verify(uexUniverseSyncService).syncCities();
    order.verify(uexUniverseSyncService).syncOutposts();
    order.verify(uexUniverseSyncService).syncPois();
    order.verify(uexUniverseSyncService).syncSpaceStations();
    order.verify(uexUniverseSyncService).syncTerminals();

    // Phase 2: catalogue
    order.verify(uexStarSystemService).fetchAndProcessStarSystems();
    order.verify(uexCommodityService).fetchAndProcessCommoditiesPrices();
    order.verify(uexManufacturerService).syncManufacturers();
    order.verify(uexVehicleService).syncVehicles();

    // Phase 2.5 (R2): category ref + item walk — categories before items, both after
    // manufacturers + vehicles so the item upsert can resolve manufacturer + linked_ship_type FKs.
    order.verify(uexCategoryRefService).syncCategories();
    order.verify(uexItemSyncService).syncItems();
    order.verify(uexItemPriceSyncService).syncItemPrices();

    // Phase 3: refineries last (depend on materials)
    order.verify(uexRefinerySyncService).syncRefiningMethods();
    order.verify(uexRefinerySyncService).syncRefineryYields();
  }

  @Test
  void scheduleTask_swallowsExceptionFromInnerService() {
    // Given
    doThrow(new RuntimeException("UEX 500")).when(uexUniverseSyncService).syncFactions();

    // When / Then — the scheduled task must not propagate; otherwise the
    // @Scheduled wrapper would suppress all subsequent invocations.
    scheduler.scheduleCommodityPriceUpdate();

    // The first failing service is invoked but subsequent ones are NOT —
    // the try/catch wraps the whole block, so a hard failure in step 1
    // aborts the tick (documented behaviour). That is the contract we
    // want: fail loud in the logs, but never propagate.
    verify(uexUniverseSyncService).syncFactions();
    verify(uexUniverseSyncService, never()).syncPlanets();
  }

  @Test
  void scheduleTask_continuesAfterPartialFailureInOneServiceMethod() {
    // Given — only the first call throws
    doThrow(new RuntimeException("transient")).when(uexUniverseSyncService).syncFactions();

    // When / Then — no exception escapes
    scheduler.scheduleCommodityPriceUpdate();

    // The contract: try/catch is around the whole orchestration so the
    // next sync calls are skipped (best-effort). Refactoring this to
    // per-service try/catch would be a behaviour change and break this
    // expectation — the test then makes that change visible.
    verify(uexRefinerySyncService, never()).syncRefiningMethods();
  }

  @Test
  void scheduleTask_skipsEntireSweep_whenAnotherSyncIsAlreadyRunning() {
    // Given the shared gate denies entry (a UEX or SC Wiki sync is already in flight)
    doReturn(false).when(syncCoordinator).runExclusively(eq("UEX"), any());

    // When
    scheduler.scheduleCommodityPriceUpdate();

    // Then no sync step runs — the tick is dropped, never started concurrently
    verifyNoInteractions(
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
}

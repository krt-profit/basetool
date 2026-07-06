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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.MasterDataCacheEvictionService;
import de.greluc.krt.profit.basetool.backend.service.SyncCoordinator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link ScWikiScheduler} — master-switch gating and cross-scheduler exclusion. */
@ExtendWith(MockitoExtension.class)
class ScWikiSchedulerTest {

  @Mock private ScWikiClient scWikiClient;
  @Mock private ScWikiProperties properties;
  @Mock private ScWikiCommoditySyncService commoditySyncService;
  @Mock private ScWikiBlueprintSyncService blueprintSyncService;
  @Mock private ScWikiItemSyncService itemSyncService;
  @Mock private ScWikiVehicleSyncService vehicleSyncService;
  @Mock private ScWikiManufacturerSyncService manufacturerSyncService;
  @Mock private MasterDataCacheEvictionService masterDataCacheEvictionService;

  // A real coordinator (spied so it can be told the gate is busy); its default runs the sweep.
  @Spy private SyncCoordinator syncCoordinator = new SyncCoordinator(3_600_000);

  // A real registry (held so tests can read the item counter) behind a real, spied TaskMetrics so
  // the instrumentation wrapper genuinely runs the sweep body and records into it. Declared before
  // taskMetrics so field initialisation order hands it the same instance.
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  // A real TaskMetrics (spied) so the instrumentation wrapper genuinely runs the sweep body.
  @Spy private TaskMetrics taskMetrics = new TaskMetrics(meterRegistry);

  @InjectMocks private ScWikiScheduler scheduler;

  @Test
  void schedule_isNoOp_whenMasterSwitchOff() {
    when(properties.getSchedulerEnabled()).thenReturn(false);

    scheduler.scheduleScWikiSync();

    verifyNoInteractions(
        commoditySyncService,
        vehicleSyncService,
        itemSyncService,
        blueprintSyncService,
        manufacturerSyncService);
  }

  @Test
  void schedule_runsEverySyncInDependencyOrder_whenEnabledAndGateFree() {
    when(properties.getSchedulerEnabled()).thenReturn(true);

    scheduler.scheduleScWikiSync();

    verify(commoditySyncService).syncCommodities();
    verify(vehicleSyncService).syncVehicles();
    verify(itemSyncService).syncItems();
    verify(blueprintSyncService).syncBlueprints();
    verify(manufacturerSyncService).syncManufacturers();
  }

  @Test
  void schedule_recordsSummedItemCount_acrossAllSteps() {
    // Given — each step reports how many catalogue rows it wrote this run.
    when(properties.getSchedulerEnabled()).thenReturn(true);
    when(commoditySyncService.syncCommodities()).thenReturn(3);
    when(vehicleSyncService.syncVehicles()).thenReturn(5);
    when(itemSyncService.syncItems()).thenReturn(11);
    when(blueprintSyncService.syncBlueprints()).thenReturn(7);
    when(manufacturerSyncService.syncManufacturers()).thenReturn(2);

    // When
    scheduler.scheduleScWikiSync();

    // Then — the scwiki_sync item counter is the sum of the five step counts (#1041 item 2), the
    // signal the SyncZeroItems alert watches (a clean run summing to 0 means an empty-200 outage).
    assertEquals(
        3 + 5 + 11 + 7 + 2,
        meterRegistry
            .get(MetricNames.SCHEDULED_JOB_ITEMS)
            .tag(MetricNames.TAG_JOB, ScheduledJob.SCWIKI_SYNC.label())
            .counter()
            .count(),
        "basetool_scheduled_job_items_total{job=scwiki_sync} must sum every step's written-row"
            + " count");
  }

  @Test
  void schedule_recordsZeroItems_whenAStepFailsAndTheRestWriteNothing() {
    // Given — the item step throws (empty-200 outage or a transient error) and no step writes rows.
    when(properties.getSchedulerEnabled()).thenReturn(true);
    when(itemSyncService.syncItems()).thenThrow(new RuntimeException("Wiki 500"));

    // When — the sweep must not propagate; the failing step contributes 0 to the tally.
    scheduler.scheduleScWikiSync();

    // Then — a successful run that wrote zero rows records 0, which is exactly what SyncZeroItems
    // fires on (as opposed to the series being absent).
    assertEquals(
        0.0,
        meterRegistry
            .get(MetricNames.SCHEDULED_JOB_ITEMS)
            .tag(MetricNames.TAG_JOB, ScheduledJob.SCWIKI_SYNC.label())
            .counter()
            .count(),
        "a zero-write sweep must still register the item counter at 0");
  }

  @Test
  void schedule_evictsScWikiSyncedMasterDataAfterSweep() {
    when(properties.getSchedulerEnabled()).thenReturn(true);

    scheduler.scheduleScWikiSync();

    // The commodity/vehicle/manufacturer/blueprint writes bypass the @CacheEvict read services, so
    // the sweep evicts the caches they can make stale on completion (CACHE-SYNC-EVICT-001,
    // CACHE-DIST-03 for the blueprint-family index).
    verify(masterDataCacheEvictionService).evictScWikiSyncedMasterData();
  }

  @Test
  void schedule_skipsEntireSweep_whenAnotherSyncIsAlreadyRunning() {
    when(properties.getSchedulerEnabled()).thenReturn(true);
    // The shared gate denies entry (a UEX or SC Wiki sync is already in flight) → no step runs.
    doReturn(false).when(syncCoordinator).runExclusively(eq("SC Wiki"), any());

    scheduler.scheduleScWikiSync();

    verifyNoInteractions(
        commoditySyncService,
        vehicleSyncService,
        itemSyncService,
        blueprintSyncService,
        manufacturerSyncService,
        masterDataCacheEvictionService);
  }
}

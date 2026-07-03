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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
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

  // A real coordinator (spied so it can be told the gate is busy); its default runs the sweep.
  @Spy private SyncCoordinator syncCoordinator = new SyncCoordinator(3_600_000);

  // A real TaskMetrics (spied) so the instrumentation wrapper genuinely runs the sweep body.
  @Spy private TaskMetrics taskMetrics = new TaskMetrics(new SimpleMeterRegistry());

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
        manufacturerSyncService);
  }
}

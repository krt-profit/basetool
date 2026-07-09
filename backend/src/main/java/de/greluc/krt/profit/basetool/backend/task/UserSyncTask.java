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

package de.greluc.krt.profit.basetool.backend.task;

import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.UserSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger for the Keycloak-&gt;local user reconciliation.
 *
 * <p>Runs {@link UserSyncService#syncFromKeycloak()} every {@code app.keycloak.sync.interval}
 * (default {@code PT1H} — hourly; the reconciliation is a drift-correction safety net, not a live
 * feed, and the pre-2026-07 1-minute cadence was the accelerant behind the native-thread exhaustion
 * incident). The reconciliation logic lives in {@link UserSyncService} so it can be shared with the
 * admin-triggered manual run ({@code POST /api/v1/users/sync}); this task is only the scheduled
 * entry point plus its instrumentation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserSyncTask {

  private final UserSyncService userSyncService;
  private final TaskMetrics taskMetrics;

  /**
   * Runs the Keycloak reconciliation through {@link TaskMetrics}, publishing the {@code user_sync}
   * execution counter, duration timer and last-success gauge (the source of the {@code
   * UserSyncStale} alert). A whole-batch failure is recorded as {@code failure} and swallowed by
   * the wrapper so the scheduler thread survives; the admin-triggered manual run uses {@link
   * TaskMetrics#recordCountingRethrow} instead so the failure surfaces to the caller.
   */
  @Scheduled(fixedDelayString = "${app.keycloak.sync.interval:PT1H}")
  public void syncUsers() {
    taskMetrics.recordCounting(ScheduledJob.USER_SYNC, userSyncService::syncFromKeycloak);
  }
}

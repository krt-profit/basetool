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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger for the Keycloak-to-local user reconciliation.
 *
 * <p>Runs {@link UserSyncService#syncFromKeycloak()} on {@code app.keycloak.sync.cron} in {@code
 * app.keycloak.sync.zone} (default daily at 05:00 {@code Europe/Berlin}). Admins can also trigger
 * it on demand via {@code POST /api/v1/users/sync}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserSyncTask {

  private final UserSyncService userSyncService;
  private final TaskMetrics taskMetrics;

  /**
   * Runs the Keycloak reconciliation through {@link TaskMetrics}, publishing the {@code user_sync}
   * execution metrics and last-success gauge. A failure is recorded and swallowed so the scheduler
   * thread survives.
   */
  @Scheduled(
      cron = "${app.keycloak.sync.cron:0 0 5 * * *}",
      zone = "${app.keycloak.sync.zone:Europe/Berlin}")
  public void syncUsers() {
    taskMetrics.recordCounting(ScheduledJob.USER_SYNC, userSyncService::syncFromKeycloak);
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="user_sync"} = 1}, so the stale-job alert
   * can tell a disabled task from one that never succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.USER_SYNC);
  }
}

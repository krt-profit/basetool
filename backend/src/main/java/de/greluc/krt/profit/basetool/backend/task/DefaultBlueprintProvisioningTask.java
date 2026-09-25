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
import de.greluc.krt.profit.basetool.backend.service.DefaultBlueprintProvisioningService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic self-heal that re-grants the full default-blueprint set to every active user
 * (REQ-INV-016), every {@code app.default-blueprints.provisioning.interval} (default {@code PT1H}).
 *
 * <p>Gated by {@code app.default-blueprints.provisioning.enabled} (default on); exceptions are
 * logged and swallowed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    prefix = "app.default-blueprints.provisioning",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DefaultBlueprintProvisioningTask {

  private final DefaultBlueprintProvisioningService provisioningService;
  private final TaskMetrics taskMetrics;

  /**
   * Grants any still-missing default blueprints to every active user, publishing the {@code
   * default_blueprint_provisioning} job metrics. A failure is recorded and swallowed by {@link
   * TaskMetrics} so the scheduler thread survives.
   */
  @Scheduled(fixedDelayString = "${app.default-blueprints.provisioning.interval:PT1H}")
  public void ensureDefaultsForAllUsers() {
    taskMetrics.recordCounting(
        ScheduledJob.DEFAULT_BLUEPRINT_PROVISIONING, this::provisionDefaults);
  }

  /**
   * Performs the back-fill; any failure propagates to {@link TaskMetrics}.
   *
   * @return the number of owned-blueprint rows granted this run (the {@code items} metric)
   */
  private int provisionDefaults() {
    int granted = provisioningService.grantDefaultsToAllUsers();
    if (granted > 0) {
      log.info("Default-blueprint provisioning sweep granted {} owned row(s).", granted);
    }
    return granted;
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="default_blueprint_provisioning"} = 1}, so
   * {@code ScheduledJobStale} can tell a disabled sweep (no bean, no gauge) from one that never
   * succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.DEFAULT_BLUEPRINT_PROVISIONING);
  }
}

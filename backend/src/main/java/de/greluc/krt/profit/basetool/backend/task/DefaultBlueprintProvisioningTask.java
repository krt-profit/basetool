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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic self-heal that keeps the "default blueprints are always present" guarantee (REQ-INV-016)
 * true regardless of how a user came to exist.
 *
 * <p>Runs every {@code app.default-blueprints.provisioning.interval} (default {@code PT1H}) and
 * re-grants the full default set to every active user via the idempotent bulk insert. The
 * first-login event and the admin-add grant cover the common cases immediately; this sweep catches
 * anything they miss — users created between sweeps by the Keycloak directory sync, or a
 * transiently failed grant. Exceptions are swallowed and logged so a transient DB hiccup never
 * tears down the scheduler thread.
 *
 * <p>Gated by {@code app.default-blueprints.provisioning.enabled} (default on); the test profile
 * disables it so the sweep never races assertions.
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
}

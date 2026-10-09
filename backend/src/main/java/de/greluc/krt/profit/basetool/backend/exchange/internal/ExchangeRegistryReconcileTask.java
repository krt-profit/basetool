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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rewrites the exchange registry's Redis mirror at startup and reconciles it on a fixed delay,
 * repairing the revocation mirror alongside (REQ-XCH-003, REQ-XCH-008); active only while the
 * mirror is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "app.exchange.mirror", name = "enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class ExchangeRegistryReconcileTask {

  private final ExchangeRegistryMirrorSync mirrorSync;
  private final ExchangeRevocationSync revocationSync;
  private final TaskMetrics taskMetrics;

  /** Writes the mirror once the application is ready; a failure is left to the reconcile. */
  @EventListener(ApplicationReadyEvent.class)
  public void writeOnStartup() {
    mirrorSync.resyncQuietly(ExchangeMirrorPhase.STARTUP);
    try {
      revocationSync.repair();
    } catch (RuntimeException e) {
      log.warn("Exchange revocation mirror repair at startup failed: {}", e.toString());
    }
  }

  /**
   * Compares the mirror with the database and rewrites it on a difference, recorded as the {@code
   * exchange_registry_reconcile} job.
   */
  @Scheduled(
      fixedDelayString = "${app.exchange.mirror.reconcile-interval:PT60S}",
      initialDelayString = "${app.exchange.mirror.reconcile-interval:PT60S}")
  public void reconcile() {
    taskMetrics.record(
        ScheduledJob.EXCHANGE_REGISTRY_RECONCILE,
        () -> {
          if (mirrorSync.resync(ExchangeMirrorPhase.RECONCILE)) {
            log.warn("Exchange registry mirror diverged from the database and was rewritten");
          }
          int repaired = revocationSync.repair();
          if (repaired > 0) {
            log.warn("Exchange revocation mirror lacked {} entries and was repaired", repaired);
          }
        });
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="exchange_registry_reconcile"} = 1}, so a
   * reconcile that never succeeds can be told from a disabled one.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.EXCHANGE_REGISTRY_RECONCILE);
  }
}

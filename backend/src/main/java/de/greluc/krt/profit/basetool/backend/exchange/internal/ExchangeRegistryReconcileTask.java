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

import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
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
 * repairing the revocation mirror first (REQ-XCH-003, REQ-XCH-008); active only while the mirror is
 * enabled.
 *
 * <p>The registry is written only after the revocations were repaired, so an emptied Redis never
 * holds a registry that admits a client without the deny list and revocations it must enforce; a
 * failed repair leaves the gateway on {@code 503 REGISTRY_UNAVAILABLE} until the next run.
 */
@Component
@ConditionalOnProperty(prefix = "app.exchange.mirror", name = "enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class ExchangeRegistryReconcileTask {

  private final ExchangeRegistryMirrorSync mirrorSync;
  private final ExchangeRevocationSync revocationSync;
  private final TaskMetrics taskMetrics;

  /**
   * Repairs the revocations and then writes the registry once the application is ready; a failed
   * repair skips the registry write, and either failure is left to the reconcile.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void writeOnStartup() {
    try {
      revocationSync.repair();
    } catch (RuntimeException e) {
      log.warn(
          "Exchange revocation mirror repair at startup failed; the registry waits for the"
              + " reconcile: {}",
          e.toString());
      return;
    }
    mirrorSync.resyncQuietly(ExchangeMirrorPhase.STARTUP);
  }

  /**
   * Repairs the revocation mirror, then compares the registry mirror with the database and rewrites
   * it on a difference, recorded as the {@code exchange_registry_reconcile} job; a failed repair
   * fails the run before the registry is touched.
   */
  @Scheduled(
      fixedDelayString = "${app.exchange.mirror.reconcile-interval:PT60S}",
      initialDelayString = "${app.exchange.mirror.reconcile-interval:PT60S}")
  public void reconcile() {
    taskMetrics.record(
        ScheduledJob.EXCHANGE_REGISTRY_RECONCILE,
        () -> {
          int repaired = revocationSync.repair();
          if (repaired > 0) {
            log.warn("Exchange revocation mirror lacked {} entries and was repaired", repaired);
          }
          if (mirrorSync.resync(ExchangeMirrorPhase.RECONCILE)) {
            log.warn("Exchange registry mirror diverged from the database and was rewritten");
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

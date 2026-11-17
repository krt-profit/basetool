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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The order of {@link ExchangeRegistryReconcileTask}'s two writes: the revocation mirror is
 * repaired before the registry mirror, and a failed repair leaves the registry untouched, so an
 * emptied Redis never admits a client without the revocations it must enforce (REQ-XCH-003,
 * REQ-XCH-008).
 */
@ExtendWith(MockitoExtension.class)
class ExchangeRegistryReconcileTaskTest {

  @Mock private ExchangeRegistryMirrorSync mirrorSync;
  @Mock private ExchangeRevocationSync revocationSync;

  private SimpleMeterRegistry registry;
  private ExchangeRegistryReconcileTask task;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    task = new ExchangeRegistryReconcileTask(mirrorSync, revocationSync, new TaskMetrics(registry));
  }

  @Test
  void theStartupRepairsTheRevocationsBeforeItWritesTheRegistry() {
    task.writeOnStartup();

    InOrder order = inOrder(revocationSync, mirrorSync);
    order.verify(revocationSync).repair();
    order.verify(mirrorSync).resyncQuietly(ExchangeMirrorPhase.STARTUP);
  }

  @Test
  void aFailedRepairAtStartupLeavesTheRegistryUnwritten() {
    when(revocationSync.repair()).thenThrow(new IllegalStateException("redis unreachable"));

    task.writeOnStartup();

    verifyNoInteractions(mirrorSync);
  }

  @Test
  void theReconcileRepairsTheRevocationsBeforeItRewritesTheRegistry() {
    task.reconcile();

    InOrder order = inOrder(revocationSync, mirrorSync);
    order.verify(revocationSync).repair();
    order.verify(mirrorSync).resync(ExchangeMirrorPhase.RECONCILE);
    assertThat(executions(MetricNames.OUTCOME_SUCCESS)).isEqualTo(1.0);
  }

  @Test
  void aFailedRepairFailsTheReconcileBeforeTheRegistryIsTouched() {
    when(revocationSync.repair()).thenThrow(new IllegalStateException("redis unreachable"));

    task.reconcile();

    verifyNoInteractions(mirrorSync);
    assertThat(executions(MetricNames.OUTCOME_FAILURE)).isEqualTo(1.0);
  }

  /**
   * Reads the reconcile job's executions counter for one outcome.
   *
   * @param outcome the outcome tag value
   * @return the count, {@code 0} when the counter was never registered
   */
  private double executions(String outcome) {
    Counter counter =
        registry
            .find(MetricNames.SCHEDULED_JOB_EXECUTIONS)
            .tag(MetricNames.TAG_JOB, ScheduledJob.EXCHANGE_REGISTRY_RECONCILE.label())
            .tag(MetricNames.TAG_OUTCOME, outcome)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }
}

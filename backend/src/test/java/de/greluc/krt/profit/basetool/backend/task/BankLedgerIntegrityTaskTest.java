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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.BankLedgerIntegrityService;
import de.greluc.krt.profit.basetool.backend.service.BankLedgerIntegrityService.IntegrityReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BankLedgerIntegrityTask}: the hourly sweep publishes each invariant's
 * violation count as a per-category gauge and records a successful job run even when the ledger
 * reports violations (a clean run with findings is not a failure).
 */
@ExtendWith(MockitoExtension.class)
class BankLedgerIntegrityTaskTest {

  @Mock private BankLedgerIntegrityService bankLedgerIntegrityService;

  private SimpleMeterRegistry registry;
  private BankLedgerIntegrityTask task;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    task =
        new BankLedgerIntegrityTask(
            bankLedgerIntegrityService, new TaskMetrics(registry), registry);
    // @PostConstruct is not invoked for a plain unit-constructed bean.
    task.registerViolationGauges();
  }

  @Test
  void runIntegrityCheck_publishesPerCategoryViolationGaugesFromTheReport() {
    // Given a report with two negative-balance accounts and one unbalanced transfer.
    IntegrityReport report =
        new IntegrityReport(
            List.of(UUID.randomUUID(), UUID.randomUUID()),
            List.of(UUID.randomUUID()),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    when(bankLedgerIntegrityService.verify()).thenReturn(report);

    // When
    task.runIntegrityCheck();

    // Then the gauges mirror the per-category counts.
    assertThat(gauge(MetricNames.CATEGORY_NEGATIVE_ACCOUNT_BALANCE)).isEqualTo(2.0d);
    assertThat(gauge(MetricNames.CATEGORY_UNBALANCED_TRANSFER)).isEqualTo(1.0d);
    assertThat(gauge(MetricNames.CATEGORY_TRANSACTION_WITHOUT_AUDIT)).isEqualTo(0.0d);
    // A run that found violations is still a successful job run, not a failure.
    assertThat(
            registry
                .get(MetricNames.SCHEDULED_JOB_EXECUTIONS)
                .tags(
                    MetricNames.TAG_JOB,
                    ScheduledJob.BANK_LEDGER_INTEGRITY.label(),
                    MetricNames.TAG_OUTCOME,
                    MetricNames.OUTCOME_SUCCESS)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void registerViolationGauges_startsEveryCategoryAtZeroBeforeAnySweep() {
    assertThat(gauge(MetricNames.CATEGORY_NEGATIVE_ACCOUNT_BALANCE)).isEqualTo(0.0d);
    assertThat(gauge(MetricNames.CATEGORY_BROKEN_HOLDER_REVERSAL)).isEqualTo(0.0d);
  }

  private double gauge(String category) {
    return registry
        .get(MetricNames.BANK_LEDGER_INTEGRITY_VIOLATIONS)
        .tag(MetricNames.TAG_CATEGORY, category)
        .gauge()
        .value();
  }
}

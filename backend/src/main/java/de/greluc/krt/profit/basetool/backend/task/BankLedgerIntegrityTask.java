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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.BankLedgerIntegrityService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled bank ledger-integrity sweep (REQ-BANK-020) that runs {@link
 * BankLedgerIntegrityService#verify()} every {@code app.bank.integrity.interval} (default {@code
 * PT1H}). Gated by {@code app.bank.integrity.enabled} (default {@code true}).
 */
@Component
@ConditionalOnProperty(
    prefix = "app.bank.integrity",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class BankLedgerIntegrityTask {

  private final BankLedgerIntegrityService bankLedgerIntegrityService;
  private final TaskMetrics taskMetrics;
  private final MeterRegistry meterRegistry;

  /**
   * Per-category violation counts backing {@code basetool_bank_ledger_integrity_violations}; a
   * value above zero means the ledger broke that invariant. Mutated only on the scheduler thread.
   */
  private final Map<String, AtomicInteger> violationGauges = new LinkedHashMap<>();

  /**
   * Registers one {@code basetool_bank_ledger_integrity_violations{category}} gauge per invariant
   * category, reporting {@code 0} until the first sweep.
   */
  @PostConstruct
  void registerViolationGauges() {
    for (String category :
        new String[] {
          MetricNames.CATEGORY_NEGATIVE_ACCOUNT_BALANCE,
          MetricNames.CATEGORY_UNBALANCED_TRANSFER,
          MetricNames.CATEGORY_UNBALANCED_HOLDER_MOVEMENT,
          MetricNames.CATEGORY_BROKEN_REVERSAL,
          MetricNames.CATEGORY_BROKEN_HOLDER_REVERSAL,
          MetricNames.CATEGORY_TRANSACTION_WITHOUT_AUDIT
        }) {
      AtomicInteger holder = new AtomicInteger(0);
      violationGauges.put(category, holder);
      Gauge.builder(
              MetricNames.BANK_LEDGER_INTEGRITY_VIOLATIONS, holder, AtomicInteger::doubleValue)
          .tag(MetricNames.TAG_CATEGORY, category)
          .description("Bank ledger-integrity violations by category; > 0 means the ledger broke.")
          .register(meterRegistry);
    }
  }

  /**
   * Runs the integrity verification through {@link TaskMetrics} as the {@code
   * bank_ledger_integrity} job. Failures are recorded and swallowed; a run that finds violations
   * still counts as a success.
   */
  @Scheduled(fixedDelayString = "${app.bank.integrity.interval:PT1H}")
  public void runIntegrityCheck() {
    taskMetrics.record(ScheduledJob.BANK_LEDGER_INTEGRITY, this::verifyLedger);
  }

  /**
   * Runs the sweep, updates the per-category violation gauges from the report and logs the total;
   * any failure propagates to {@link TaskMetrics}.
   */
  private void verifyLedger() {
    log.info("Starting scheduled bank ledger integrity check...");
    BankLedgerIntegrityService.IntegrityReport report = bankLedgerIntegrityService.verify();
    updateViolationGauges(report);
    log.info("Bank ledger integrity check finished — {} violation(s).", report.violationCount());
  }

  /**
   * Pushes each category's violation count from the report into its gauge holder.
   *
   * @param report the fresh integrity report whose per-invariant list sizes become the gauge values
   */
  private void updateViolationGauges(@NotNull BankLedgerIntegrityService.IntegrityReport report) {
    violationGauges
        .get(MetricNames.CATEGORY_NEGATIVE_ACCOUNT_BALANCE)
        .set(report.negativeAccountBalances().size());
    violationGauges
        .get(MetricNames.CATEGORY_UNBALANCED_TRANSFER)
        .set(report.unbalancedTransfers().size());
    violationGauges
        .get(MetricNames.CATEGORY_UNBALANCED_HOLDER_MOVEMENT)
        .set(report.unbalancedHolderMovements().size());
    violationGauges.get(MetricNames.CATEGORY_BROKEN_REVERSAL).set(report.brokenReversals().size());
    violationGauges
        .get(MetricNames.CATEGORY_BROKEN_HOLDER_REVERSAL)
        .set(report.brokenHolderReversals().size());
    violationGauges
        .get(MetricNames.CATEGORY_TRANSACTION_WITHOUT_AUDIT)
        .set(report.transactionsWithoutAudit().size());
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="bank_ledger_integrity"} = 1}, so {@code
   * ScheduledJobStale} can tell a disabled sweep (no bean, no gauge) from one that never succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.BANK_LEDGER_INTEGRITY);
  }
}

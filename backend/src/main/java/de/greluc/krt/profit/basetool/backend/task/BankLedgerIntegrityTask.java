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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled ledger-integrity sweep for the bank (REQ-BANK-020, epic #556 Phase 5; pattern: {@link
 * UserSyncTask}). Runs every {@code app.bank.integrity.interval} (default {@code PT1H}) and
 * delegates to {@link BankLedgerIntegrityService#verify()}, which logs each violation at {@code
 * ERROR}. The whole task is gated by {@code app.bank.integrity.enabled} (default {@code true}); set
 * it to {@code false} to disable the schedule (e.g. in tests that drive the verification directly).
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
   * Per-category violation-count holders backing {@code basetool_bank_ledger_integrity_violations}.
   * Insertion-ordered for a stable registration order; each holder is fed by the hourly sweep, so a
   * value {@code > 0} in any category means the ledger broke that invariant (CRITICAL alert in
   * Phase 2). Registered once in {@link #registerViolationGauges()} and only mutated on the
   * scheduler thread.
   */
  private final Map<String, AtomicInteger> violationGauges = new LinkedHashMap<>();

  /**
   * Registers one {@code basetool_bank_ledger_integrity_violations{category}} gauge per invariant
   * category, each backed by a zero-initialised holder the hourly sweep updates. Registration
   * happens once after construction; the gauge exists (reporting {@code 0}) even before the first
   * sweep so the CRITICAL alert has a series to evaluate. Because the enclosing bean is
   * config-gated ({@code app.bank.integrity.enabled}), disabling the check also removes these
   * gauges, which is intended.
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
   * Fires the integrity verification on the configured schedule through {@link TaskMetrics},
   * publishing the {@code bank_ledger_integrity} job metrics. A transient DB hiccup is recorded as
   * a failed run and swallowed so the scheduler thread survives; the next tick retries. A run that
   * completes but reports violations is still a {@code success} — the violation count is the
   * separate {@code basetool_bank_ledger_integrity_violations} gauge.
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
  private void updateViolationGauges(BankLedgerIntegrityService.IntegrityReport report) {
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
}

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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import tools.jackson.databind.ObjectMapper;

/** Opt-in synthetic concurrent long-period profile of the bounded snapshot and PDF working sets. */
@SpringBootTest
@EnabledIfSystemProperty(named = "basetool.audit.resourceProfile", matches = "true")
class BankReportResourceProfileTest {
  private static final Instant FROM = Instant.parse("1970-01-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2050-01-01T00:00:00Z");
  @Autowired private BankReportRenderer renderer;
  @Autowired private BankExportProperties properties;
  @Autowired private ObjectMapper mapper;
  @Autowired private BankReportSnapshotReader snapshotReader;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private BankLedgerService ledger;
  @Autowired private TransactionTemplate transactions;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void profileARealDatabaseSnapshotAcrossThreePostingChunks() throws Exception {
    BankAccount account = new BankAccount();
    account.setAccountNo("KB-" + accountRepository.nextAccountNoValue());
    account.setName("Synthetic database profile " + UUID.randomUUID());
    account.setType(BankAccountType.AREA);
    account.setAreaName(account.getName());
    account.setStatus(BankAccountStatus.ACTIVE);
    accountRepository.save(account);
    BankHolder holder = new BankHolder();
    holder.setHandle("Synthetic database profile holder");
    holder.setActive(true);
    holderRepository.save(holder);
    transactions.executeWithoutResult(_ -> {
      for (int i = 0; i < 1001; i++) {
        ledger.bookDeposit(new BankDepositRequest(account.getId(), holder.getId(), BigDecimal.ONE, null));
      }
    });
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean enabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try (BankReportSpool spool = BankReportSpool.create(properties)) {
      statistics.clear();
      long started = System.nanoTime();
      BankReportSpool.Section section = snapshotReader.statement(spool, account.getId(), FROM, TO, false);
      long snapshotNanos = System.nanoTime() - started;
      long statements = statistics.getPrepareStatementCount();
      assertThat(section.rows()).isEqualTo(1001);
      assertThat(section.closing()).isEqualByComparingTo("1001");
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      started = System.nanoTime();
      renderer.statement(spool, section, FROM, TO, null, false);
      long renderNanos = System.nanoTime() - started;
      System.out.printf("BANK_EXPORT_DB_PROFILE rows=1001 snapshot_transaction_ms=%d prepared_statements=%d "
          + "detached_render_ms=%d snapshot_bytes=%d pdf_bytes=%d%n",
          TimeUnit.NANOSECONDS.toMillis(snapshotNanos), statements,
          TimeUnit.NANOSECONDS.toMillis(renderNanos), Files.size(spool.file("rows-0")),
          Files.size(spool.file("report.pdf")));
    } finally {
      statistics.setStatisticsEnabled(enabled);
    }
  }

  @Test
  void profileFourConcurrentLongPeriodStatements() throws Exception {
    render(100);
    int rows = Integer.getInteger("basetool.audit.bank.rows", 20000);
    assertThat(rows).isPositive();
    long baseline = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    AtomicLong peak = new AtomicLong(baseline);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Profile>> profiles = new ArrayList<>();
    long started = System.nanoTime();
    try (var sampler = Executors.newSingleThreadScheduledExecutor();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      sampler.scheduleAtFixedRate(
          () ->
              peak.accumulateAndGet(
                  ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max),
          0,
          10,
          TimeUnit.MILLISECONDS);
      for (int i = 0; i < 4; i++) {
        profiles.add(
            workers.submit(() -> {
              start.await();
              return render(rows);
            }));
      }
      start.countDown();
      long pdfBytes = 0;
      long snapshotBytes = 0;
      long maximumSnapshotNanos = 0;
      long maximumRenderNanos = 0;
      for (Future<Profile> future : profiles) {
        Profile profile = future.get();
        pdfBytes += profile.pdfBytes();
        snapshotBytes += profile.snapshotBytes();
        maximumSnapshotNanos = Math.max(maximumSnapshotNanos, profile.snapshotNanos());
        maximumRenderNanos = Math.max(maximumRenderNanos, profile.renderNanos());
      }
      sampler.shutdown();
      System.out.printf(
          "BANK_EXPORT_PROFILE exports=4 rows_each=%d snapshot_bytes=%d pdf_bytes=%d "
              + "heap_baseline=%d heap_peak=%d snapshot_ms_max=%d render_ms_max=%d total_ms=%d%n",
          rows,
          snapshotBytes,
          pdfBytes,
          baseline,
          peak.get(),
          TimeUnit.NANOSECONDS.toMillis(maximumSnapshotNanos),
          TimeUnit.NANOSECONDS.toMillis(maximumRenderNanos),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
  }

  private Profile render(int count) throws Exception {
    try (BankReportSpool spool = BankReportSpool.create(properties)) {
      long started = System.nanoTime();
      try (var rows = BankReportSpool.writer(spool.file("rows-0"))) {
        for (int first = 0; first < count; first += BankReportSpool.CHUNK_SIZE) {
          List<BankReportSpool.Line> lines = new ArrayList<>();
          for (int row = first; row < Math.min(count, first + BankReportSpool.CHUNK_SIZE); row++) {
            lines.add(
                new BankReportSpool.Line(
                    FROM.plusSeconds(row),
                    BankTransactionType.DEPOSIT,
                    BigDecimal.ONE,
                    "Synthetic profile posting " + row,
                    null,
                    null,
                    "Synthetic holder",
                    "Synthetic counterparty"));
          }
          BankReportSpool.append(rows, mapper, new BankReportSpool.Lines(lines));
        }
      }
      long snapshotNanos = System.nanoTime() - started;
      long snapshotBytes = Files.size(spool.file("rows-0"));
      BankReportSpool.Section section =
          new BankReportSpool.Section(
              new BankReportSpool.Account(
                  UUID.randomUUID(), "SYNTHETIC", "Synthetic profile account"),
              "rows-0",
              BigDecimal.ZERO,
              BigDecimal.valueOf(count),
              BigDecimal.ZERO,
              count);
      started = System.nanoTime();
      renderer.statement(spool, section, FROM, TO, null, false);
      long renderNanos = System.nanoTime() - started;
      long pdfBytes = Files.size(spool.file("report.pdf"));
      assertThat(pdfBytes).isPositive();
      try (var input = Files.newInputStream(spool.file("report.pdf"))) {
        assertThat(input.transferTo(OutputStream.nullOutputStream())).isEqualTo(pdfBytes);
      }
      return new Profile(snapshotBytes, pdfBytes, snapshotNanos, renderNanos);
    }
  }

  /** Measurements of one synthetic export. */
  private record Profile(long snapshotBytes, long pdfBytes, long snapshotNanos, long renderNanos) {}
}

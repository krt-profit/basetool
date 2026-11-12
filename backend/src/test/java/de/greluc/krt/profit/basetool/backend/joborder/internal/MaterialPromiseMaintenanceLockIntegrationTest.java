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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies exclusive multi-order maintenance and the ordinary unlinked-stock prerequisite. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialPromiseMaintenanceLockIntegrationTest extends MaterialContributionTestSupport {
  @Test
  @Timeout(60)
  void multiOrderStartupIsIdempotentAndBlocksAnInitiallyUnlinkedOrdinaryStockWriter()
      throws Exception {
    Fixture firstOrder = seed();
    Fixture secondOrder = seed();
    var first =
        materialClaimService.upsertClaim(
            firstOrder.orderId(), claim(firstOrder.materialId(), firstOrder.squadronAId(), 5.0));
    var second =
        materialClaimService.upsertClaim(
            secondOrder.orderId(), claim(secondOrder.materialId(), secondOrder.squadronAId(), 5.0));
    StockFixture unlinked = stock(firstOrder, 1.0);
    jdbc.update(
        "UPDATE org_unit SET active = false WHERE id IN (?, ?)",
        firstOrder.squadronAId(),
        secondOrder.squadronAId());
    ApplicationRunner startup =
        context.getBean("jobOrderEligibilityStartup", ApplicationRunner.class);
    AtomicInteger maintenancePid = new AtomicInteger();
    AtomicInteger writerPid = new AtomicInteger();
    CountDownLatch maintenanceApplied = new CountDownLatch(1);
    CountDownLatch releaseMaintenance = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var maintenance =
          workers.submit(() ->
              transactionTemplate.executeWithoutResult(_ -> {
                maintenancePid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                try {
                  startup.run(new DefaultApplicationArguments());
                  maintenanceApplied.countDown();
                  if (!releaseMaintenance.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Maintenance release was not observed");
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException("Maintenance was interrupted", interrupted);
                } catch (Exception failure) {
                  throw new IllegalStateException("Startup maintenance failed", failure);
                }
              }));
      assertThat(maintenanceApplied.await(15, TimeUnit.SECONDS)).isTrue();
      var writer =
          workers.submit(() ->
              transactionTemplate.execute(_ -> {
                writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                var row = stockReads.findRow(unlinked.rowId()).orElseThrow();
                assertThat(row.jobOrderReserved()).isZero();
                return stockCommands.bookIntoPool(
                    unlinked.holderId(),
                    row.getOwningOrgUnit(),
                    new StockLot(
                        row.getMaterial(), null, row.getLocation(), row.getQuality(), false),
                    1.0,
                    StockBookingReason.PURCHASE);
              }));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() ->
              jdbc.queryForObject(
                      "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type ="
                          + " 'Lock' AND ? = ANY(pg_blocking_pids(pid))",
                      Integer.class,
                      writerPid.get(),
                      maintenancePid.get())
                  == 1);
      assertThat(writer.isDone()).isFalse();
      releaseMaintenance.countDown();
      maintenance.get(15, TimeUnit.SECONDS);
      var writtenRow = writer.get(15, TimeUnit.SECONDS);
      assertThat(writtenRow).isNotEqualTo(unlinked.rowId());
      assertThat(stockReads.findRow(unlinked.rowId())).isEmpty();
      assertThat(stockReads.findRow(writtenRow).orElseThrow().getAmount()).isEqualTo(2.0);
      var firstEnded = contributionHistory(firstOrder.orderId(), first.id());
      var secondEnded = contributionHistory(secondOrder.orderId(), second.id());
      for (var ended : List.of(firstEnded, secondEnded)) {
        assertThat(ended.openAmount()).isZero();
        assertThat(ended.endedAt()).isNotNull();
        assertThat(ended.changes())
            .anySatisfy(change ->
                assertThat(change.reason())
                    .isEqualTo(
                        de.greluc.krt.profit.basetool.backend.joborder.api
                            .MaterialContributionChange.Reason.UNIT_INELIGIBLE));
      }
      startup.run(new DefaultApplicationArguments());
      assertThat(contributionHistory(firstOrder.orderId(), first.id())).isEqualTo(firstEnded);
      assertThat(contributionHistory(secondOrder.orderId(), second.id())).isEqualTo(secondEnded);
    } finally {
      releaseMaintenance.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
  }
}

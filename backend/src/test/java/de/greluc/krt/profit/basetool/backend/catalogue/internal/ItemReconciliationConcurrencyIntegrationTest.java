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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReconciliationBusyException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Reproduces the catalogue-FK/advisory interleave with two real PostgreSQL transactions. */
@SpringBootTest
class ItemReconciliationConcurrencyIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactions;
  @Autowired private GameItemRepository items;
  @Autowired private LocationRepository locations;
  @Autowired private GameItemRedirectRepository redirects;
  @Autowired private CatalogueItemReassignmentRepository recipes;
  @Autowired private GameItemPriceRepository prices;
  @Autowired private GameItemStatRepository stats;
  @Autowired private List<GameItemReassignmentParticipant> participants;
  @Autowired private SyncReportService reports;
  @Autowired private StockCommands stock;

  @Test
  void busyStockInsertCanCommitBeforeAFreshMergeRescansAllReferences() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch advisoryHeld = new CountDownLatch(1);
    CountDownLatch catalogueLocked = new CountDownLatch(1);
    AtomicInteger stockPid = new AtomicInteger();
    AtomicBoolean firstAttempt = new AtomicBoolean(true);
    List<GameItemReassignmentParticipant> observed = new ArrayList<>();
    observed.add((_, _) -> {
      if (firstAttempt.compareAndSet(true, false)) {
        int mergePid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        catalogueLocked.countDown();
        awaitForeignKeyWait(stockPid.get(), mergePid);
      }
      return 0;
    });
    observed.addAll(participants);
    ItemReconciliationService merger =
        new ItemReconciliationService(items, redirects, recipes, prices, stats, observed, reports);

    try (var pool = Executors.newFixedThreadPool(2)) {
      var booking =
          pool.submit(() ->
              transactions.executeWithoutResult(_ -> {
                stockPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                StockLot lot =
                    new StockLot(
                        null,
                        items.getCanonicalReferenceById(fixture.retired()),
                        locations.getReferenceById(fixture.location()),
                        null,
                        false);
                stock.lockLots(fixture.member(), List.of(lot.key()));
                advisoryHeld.countDown();
                await(catalogueLocked);
                stock.bookIn(fixture.member(), lot, 3);
                entityManager.flush();
              }));
      await(advisoryHeld);
      var merging =
          pool.submit(() -> {
            assertThatThrownBy(() -> merge(merger, fixture))
                .isInstanceOf(GameItemReconciliationBusyException.class);
            booking.get(15, TimeUnit.SECONDS);
            assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM inventory_item WHERE game_item_id = ?",
                        Integer.class,
                        fixture.retired()))
                .isEqualTo(2);
            merge(merger, fixture);
            return true;
          });

      assertThat(merging.get(30, TimeUnit.SECONDS)).isTrue();
      booking.get(15, TimeUnit.SECONDS);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM inventory_item WHERE game_item_id = ?",
                  Integer.class,
                  fixture.retired()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM inventory_item WHERE game_item_id = ?",
                  Integer.class,
                  fixture.canonical()))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT sum(amount) FROM inventory_item WHERE game_item_id = ?",
                  Double.class,
                  fixture.canonical()))
          .isEqualTo(10d);
      assertThat(redirects.findCanonicalId(fixture.retired())).contains(fixture.canonical());
    } finally {
      cleanup(fixture);
    }
  }

  private void merge(ItemReconciliationService merger, Fixture fixture) {
    transactions.executeWithoutResult(_ -> {
      GameItem target = items.findById(fixture.canonical()).orElseThrow();
      assertThat(
              merger.mergeExisting(
                  target,
                  fixture.retired(),
                  fixture.run(),
                  new ItemReconciliationService.WikiIdentity(
                      "Contended Item", GameItemKind.GENERIC, null, null)))
          .isNotNull();
    });
  }

  private void awaitForeignKeyWait(int waitingPid, int blockingPid) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blockingPid, waitingPid))) {
        return;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted before observing the foreign-key wait", e);
      }
    }
    throw new AssertionError("Stock INSERT did not wait on the catalogue row lock");
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while arranging the transaction interleave", e);
    }
  }

  private Fixture fixture() {
    Fixture fixture =
        new Fixture(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    transactions.executeWithoutResult(_ -> {
      jdbc.update(
          "INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)",
          fixture.member(),
          "catalogue-lock-" + fixture.member());
      jdbc.update(
          "INSERT INTO location (id, name, hidden) VALUES (?, ?, false)",
          fixture.location(),
          "Catalogue Lock " + fixture.location());
      jdbc.update(
          "INSERT INTO game_item (id, name, uex_name, uex_item_id, kind, source_systems)"
              + " VALUES (?, 'Contended Item', 'Contended Item', ?, 'GENERIC', 'UEX_ONLY')",
          fixture.retired(),
          Math.floorMod(fixture.retired().hashCode(), 1_000_000_000) + 100_000);
      jdbc.update(
          "INSERT INTO game_item (id, name, external_uuid, kind, source_systems)"
              + " VALUES (?, 'Contended Item', ?, 'GENERIC', 'WIKI_ONLY')",
          fixture.canonical(),
          UUID.randomUUID());
      jdbc.update(
          "INSERT INTO inventory_item (id, user_id, game_item_id, location_id, amount,"
              + " personal, stolen, version) VALUES (?, ?, ?, ?, 7, false, false, 0)",
          UUID.randomUUID(),
          fixture.member(),
          fixture.retired(),
          fixture.location());
    });
    return fixture;
  }

  private void cleanup(Fixture fixture) {
    transactions.executeWithoutResult(_ -> {
      jdbc.update(
          "DELETE FROM audit_event WHERE target_user_id = ? OR subject_id IN"
              + " (SELECT id FROM inventory_item WHERE user_id = ?)",
          fixture.member(),
          fixture.member());
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", fixture.member());
      jdbc.update("DELETE FROM game_item_redirect WHERE canonical_id = ?", fixture.canonical());
      jdbc.update("DELETE FROM external_sync_report WHERE run_id = ?", fixture.run());
      jdbc.update(
          "DELETE FROM game_item WHERE id IN (?, ?)", fixture.retired(), fixture.canonical());
      jdbc.update("DELETE FROM location WHERE id = ?", fixture.location());
      jdbc.update("DELETE FROM app_user WHERE id = ?", fixture.member());
    });
  }

  /** Committed rows shared by the two competing transactions. */
  private record Fixture(UUID retired, UUID canonical, UUID member, UUID location, UUID run) {}
}

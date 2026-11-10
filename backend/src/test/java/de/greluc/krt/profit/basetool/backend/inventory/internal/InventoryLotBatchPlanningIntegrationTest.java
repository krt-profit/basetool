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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityProvenance;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies exact physical lot planning and action-created quantity through the public Inventory
 * API.
 */
@SpringBootTest
class InventoryLotBatchPlanningIntegrationTest {
  @Autowired private TransactionTemplate transactions;
  @Autowired private UserRepository users;
  @Autowired private MaterialRepository materials;
  @Autowired private LocationRepository locations;
  @Autowired private CityRepository cities;
  @Autowired private InventoryItemRepository rows;
  @Autowired private StockReads stock;
  @Autowired private StockCommands commands;
  @Autowired private StockLotWritePlans plans;
  @Autowired private StockQuantityProvenance provenance;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private StockOfferLookup offers;

  /** The holder and ordinary physical lot of one isolated test action. */
  private record Fixture(UUID holder, StockLot lot, UUID row) {}

  @Test
  void planningReadsOfferFactsInBatchesAsThePhysicalLotGrows() {
    Fixture few = seed();
    Fixture many = seed();
    seedOfferedRows(few, 3);
    seedOfferedRows(many, 30);
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean enabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      statistics.clear();
      inspect(few, 3);
      long fewStatements = statistics.getPrepareStatementCount();
      statistics.clear();
      inspect(many, 30);
      assertThat(statistics.getPrepareStatementCount()).isEqualTo(fewStatements);
      assertThat(offers.offerFacts(List.of(few.row())).get(few.row()).activeAmount())
          .isEqualTo(1.0);
      jdbc.update(
          "UPDATE material_exchange_offer SET status = 'DEACTIVATED' WHERE inventory_item_id = ?",
          few.row());
      var historical = offers.offerFacts(List.of(few.row())).get(few.row());
      assertThat(historical.offered()).isTrue();
      assertThat(historical.activeAmount()).isZero();
    } finally {
      statistics.setStatisticsEnabled(enabled);
    }
  }

  /** Runs the public prepare/locked policy/confirm pipeline without physical effects. */
  private void inspect(Fixture fixture, int expectedRows) {
    transactions.executeWithoutResult(_ -> {
      BigDecimal current = BigDecimal.valueOf(expectedRows + 1L);
      List<StockLotWritePlans.Intent> intents =
          List.of(
              new StockLotWritePlans.SetLotAmount(
                  0, fixture.lot(), current, current.add(BigDecimal.ONE)));
      var prepared = plans.prepareLotBatch(fixture.holder(), List.of(fixture.lot()), intents);
      assertThat(prepared.lockedRows().get(fixture.lot().key())).hasSize(expectedRows);
      plans.confirmLotBatch(prepared, intents, true);
    });
  }

  /**
   * Seeds ordinary offer-backed stock for a row-count-independent physical planning observation.
   */
  private void seedOfferedRows(Fixture fixture, int count) {
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, version, created_at, updated_at)
        SELECT gen_random_uuid(), ?, ?, ?, 500, 1, false, false, 0, now(), now()
        FROM generate_series(1, ?)
        """,
        fixture.holder(),
        fixture.lot().material().getId(),
        fixture.lot().location().getId(),
        count - 1);
    jdbc.update(
        """
        INSERT INTO material_exchange_offer (id, inventory_item_id, owner_id, status, released_at,
                                            version, created_at, updated_at, offered_amount, offer_kind)
        SELECT gen_random_uuid(), i.id, ?, 'ACTIVE', now(), 0, now(), now(), 1, 'MATERIAL'
        FROM inventory_item i WHERE i.user_id = ?
        """,
        fixture.holder(),
        fixture.holder());
  }

  @Test
  void anEqualTotalSourceReplacementConflictsAtomicallyAndAnExplicitRetrySucceeds()
      throws Exception {
    Fixture fixture = seed();
    String waiter = "lot-source-plan-" + UUID.randomUUID();
    AtomicReference<Future<Throwable>> contender = new AtomicReference<>();
    AtomicReference<UUID> replacement = new AtomicReference<>();
    try (var worker = Executors.newSingleThreadExecutor()) {
      transactions.executeWithoutResult(_ -> {
        commands.prepareWrites(List.of(fixture.row()), List.of(), List.of());
        contender.set(
            worker.submit(() -> {
              try {
                transactions.executeWithoutResult(_ -> {
                  jdbc.queryForObject(
                      "SELECT set_config('application_name', ?, true)", String.class, waiter);
                  set(fixture, "2", "1", false);
                });
                return null;
              } catch (RuntimeException conflict) {
                return conflict;
              }
            }));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE application_name = ?"
                            + " AND wait_event_type = 'Lock' AND wait_event = 'advisory'",
                        Integer.class,
                        waiter)
                    > 0);
        commands.bookOutRow(fixture.holder(), fixture.row(), 2.0);
        replacement.set(commands.bookIn(fixture.holder(), fixture.lot(), 2.0));
      });
      assertThat(contender.get().get(15, TimeUnit.SECONDS))
          .isInstanceOf(BusinessConflictException.class);
    }
    assertThat(stock.findRow(replacement.get()).orElseThrow().getAmount()).isEqualTo(2.0);
    var original = provenance.quantityOf(replacement.get());
    transactions.executeWithoutResult(_ -> set(fixture, "2", "1", false));
    assertThat(stock.findRow(replacement.get()).orElseThrow().getAmount()).isEqualTo(1.0);
    assertThat(provenance.quantityOf(replacement.get()))
        .containsExactly(new StockQuantityFragment(original.getFirst().origin(), 1000L, 2000L));
  }

  @Test
  void aLaterFallUsesTheNewPersonalPrefixAndPreservesTheOlderSharedQuantity() {
    Fixture fixture = seed();
    transactions.executeWithoutResult(_ -> {
      List<StockLotWritePlans.Intent> intents =
          List.of(
              new StockLotWritePlans.SetLotAmount(
                  0, fixture.lot(), new BigDecimal("2"), new BigDecimal("5")),
              new StockLotWritePlans.SetLotAmount(
                  1, fixture.lot(), new BigDecimal("5"), new BigDecimal("4")));
      var prepared = plans.prepareLotBatch(fixture.holder(), List.of(fixture.lot()), intents);
      var confirmed = plans.confirmLotBatch(prepared, intents, false);
      plans.executePreparedLot(confirmed, 0, false);
      plans.executePreparedLot(confirmed, 1, false);
    });
    assertThat(stock.findRow(fixture.row()).orElseThrow().getAmount()).isEqualTo(2.0);
    var personal =
        transactions.execute(_ ->
            stock
                .lotRows(fixture.holder(), List.of(fixture.lot()))
                .get(fixture.lot().key())
                .stream()
                .filter(row -> Boolean.TRUE.equals(row.getPersonal()))
                .toList());
    assertThat(personal)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getAmount()).isEqualTo(2.0);
          assertThat(provenance.quantityOf(row.getId()))
              .singleElement()
              .satisfies(fragment -> {
                assertThat(fragment.fromQuanta()).isEqualTo(1000L);
                assertThat(fragment.toQuanta()).isEqualTo(3000L);
              });
        });
  }

  @Test
  void aConfirmedDryRunHasNoStockOrProvenanceEffectAndCannotBeReused() {
    Fixture fixture = seed();
    var before = provenance.quantityOf(fixture.row());
    var handle = transactions.execute(_ -> set(fixture, "2", "5", true));
    assertThat(stock.findRow(fixture.row()).orElseThrow().getAmount()).isEqualTo(2.0);
    assertThat(provenance.quantityOf(fixture.row())).isEqualTo(before);
    assertThatThrownBy(
            () -> transactions.executeWithoutResult(_ -> plans.executePreparedLot(handle, 0, true)))
        .isInstanceOf(BusinessConflictException.class);
  }

  @Test
  void aStandaloneRowLockPreparesItsLotBeforeAnOrdinaryBookOut() {
    Fixture fixture = seed();
    transactions.executeWithoutResult(_ -> {
      commands.lockRow(fixture.row()).orElseThrow();
      commands.bookOutRow(fixture.holder(), fixture.row(), 1.0);
    });
    assertThat(stock.findRow(fixture.row()).orElseThrow().getAmount()).isEqualTo(1.0);
  }

  /** Prepares, confirms and executes one public physical lot intention. */
  private StockLotWritePlans.Confirmed set(
      Fixture fixture, String observed, String target, boolean dryRun) {
    List<StockLotWritePlans.Intent> intents =
        List.of(
            new StockLotWritePlans.SetLotAmount(
                0, fixture.lot(), new BigDecimal(observed), new BigDecimal(target)));
    var prepared = plans.prepareLotBatch(fixture.holder(), List.of(fixture.lot()), intents);
    var confirmed = plans.confirmLotBatch(prepared, intents, dryRun);
    plans.executePreparedLot(confirmed, 0, dryRun);
    return confirmed;
  }

  /** Seeds an old unassigned shared row that a new personal rise sorts ahead of. */
  private Fixture seed() {
    return transactions.execute(_ -> {
      User user = new User();
      user.setId(UUID.randomUUID());
      user.setUsername("physical-plan-" + UUID.randomUUID());
      user = users.saveAndFlush(user);
      Material material = new Material();
      material.setName("Physical input " + UUID.randomUUID());
      material.setType(MaterialType.RAW);
      material = materials.saveAndFlush(material);
      Location location = new Location();
      location.setName("Physical target " + UUID.randomUUID());
      location = locations.saveAndFlush(TestPlaces.onOwnCity(location, cities));
      InventoryItem row = new InventoryItem();
      row.setUser(user);
      row.setMaterial(material);
      row.setLocation(location);
      row.setQuality(500);
      row.setAmount(2.0);
      row.setPersonal(false);
      row = rows.saveAndFlush(row);
      return new Fixture(
          user.getId(), new StockLot(material, null, location, 500, false), row.getId());
    });
  }
}

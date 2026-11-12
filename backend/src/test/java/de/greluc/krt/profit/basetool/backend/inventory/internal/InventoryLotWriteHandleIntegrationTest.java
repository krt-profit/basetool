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
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityProvenance;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies originating-transaction plan ownership through public physical stock operations. */
@SpringBootTest
class InventoryLotWriteHandleIntegrationTest {
  @Autowired private StockLotWritePlans plans;
  @Autowired private StockCommands commands;
  @Autowired private StockReads stock;
  @Autowired private StockQuantityProvenance provenance;
  @Autowired private TransactionTemplate transactions;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private UserRepository users;
  @Autowired private MaterialRepository materials;
  @Autowired private LocationRepository locations;
  @Autowired private CityRepository cities;

  @Test
  void aSuspendedPreparedHandleCannotBeConfirmedByAnotherTransactionAndWorksAfterResume() {
    Fixture fixture = seed();
    TransactionTemplate nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    transactions.executeWithoutResult(_ -> {
      List<StockLotWritePlans.Intent> intents =
          List.of(
              new StockLotWritePlans.SetLotAmount(
                  0, fixture.lot(), new BigDecimal("2"), new BigDecimal("3")));
      var prepared =
          plans.prepareLotBatch(
              fixture.holder(), List.of(fixture.lot()), List.of(), List.of(), intents);
      assertThatThrownBy(() ->
              nested.executeWithoutResult(
                  _ -> plans.confirmLotBatch(prepared, List.of(), intents, false)))
          .isInstanceOf(BusinessConflictException.class);
      var confirmed = plans.confirmLotBatch(prepared, List.of(), intents, false);
      var outcome = plans.executePreparedLot(confirmed, 0, false);
      assertThat(outcome.refusal()).isNull();
      assertThat(outcome.quantity()).isNotNull();
      assertThat(outcome.quantity().before().totalQuanta()).isEqualTo(2000L);
      assertThat(outcome.quantity().after().totalQuanta()).isEqualTo(3000L);
    });

    transactions.executeWithoutResult(_ ->
        assertThat(
                stock.lotRows(fixture.holder(), List.of(fixture.lot())).values().stream()
                    .flatMap(List::stream)
                    .mapToDouble(row -> row.getAmount())
                    .sum())
            .isEqualTo(3.0));
  }

  @Test
  void aSuspendedConfirmedHandleCannotRunInAnotherTransactionAndWorksAfterResume() {
    Fixture fixture = seed();
    TransactionTemplate nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    transactions.executeWithoutResult(_ -> {
      List<StockLotWritePlans.Intent> intents =
          List.of(
              new StockLotWritePlans.SetLotAmount(
                  0, fixture.lot(), new BigDecimal("2"), new BigDecimal("3")));
      var prepared =
          plans.prepareLotBatch(
              fixture.holder(), List.of(fixture.lot()), List.of(), List.of(), intents);
      var confirmed = plans.confirmLotBatch(prepared, List.of(), intents, false);
      assertThatThrownBy(
              () -> nested.executeWithoutResult(_ -> plans.executePreparedLot(confirmed, 0, false)))
          .isInstanceOf(BusinessConflictException.class);
      var outcome = plans.executePreparedLot(confirmed, 0, false);
      assertThat(outcome.refusal()).isNull();
      assertThat(outcome.quantity()).isNotNull();
      assertThat(outcome.quantity().before().totalQuanta()).isEqualTo(2000L);
      assertThat(outcome.quantity().after().totalQuanta()).isEqualTo(3000L);
    });

    transactions.executeWithoutResult(_ ->
        assertThat(
                stock.lotRows(fixture.holder(), List.of(fixture.lot())).values().stream()
                    .flatMap(List::stream)
                    .mapToDouble(row -> row.getAmount())
                    .sum())
            .isEqualTo(3.0));
  }

  @Test
  void executingAConfirmedStepTwiceRollsBackItsFirstPhysicalChange() {
    Fixture fixture = seed();
    var originalRow =
        transactions.execute(_ ->
            stock
                .lotRows(fixture.holder(), List.of(fixture.lot()))
                .get(fixture.lot().key())
                .getFirst());
    UUID rowId = originalRow.getId();
    Long version = originalRow.getVersion();
    var originalQuantity = provenance.quantityOf(rowId);
    assertThat(originalRow.getAmount()).isEqualTo(2.0);
    assertThat(originalQuantity).hasSize(1);
    assertThat(originalQuantity.getFirst().quanta()).isEqualTo(2000L);
    AtomicReference<StockLotWritePlans.Outcome> firstExecution = new AtomicReference<>();

    assertThatThrownBy(() ->
            transactions.executeWithoutResult(_ -> {
              List<StockLotWritePlans.Intent> intents =
                  List.of(
                      new StockLotWritePlans.SetLotAmount(
                          0, fixture.lot(), new BigDecimal("2"), new BigDecimal("3")));
              var prepared =
                  plans.prepareLotBatch(
                      fixture.holder(), List.of(fixture.lot()), List.of(), List.of(), intents);
              var confirmed = plans.confirmLotBatch(prepared, List.of(), intents, false);
              var outcome = plans.executePreparedLot(confirmed, 0, false);
              assertThat(
                      stock
                          .lotRows(fixture.holder(), List.of(fixture.lot()))
                          .get(fixture.lot().key())
                          .stream()
                          .mapToDouble(row -> row.getAmount())
                          .sum())
                  .isEqualTo(3.0);
              firstExecution.set(outcome);
              plans.executePreparedLot(confirmed, 0, false);
            }))
        .isInstanceOf(BusinessConflictException.class);

    var outcome = firstExecution.get();
    assertThat(outcome).isNotNull();
    assertThat(outcome.refusal()).isNull();
    assertThat(outcome.quantity()).isNotNull();
    assertThat(outcome.quantity().before())
        .isEqualTo(StockQuantityComposition.known(originalQuantity));
    assertThat(outcome.quantity().before().totalQuanta()).isEqualTo(2000L);
    assertThat(outcome.quantity().after().totalQuanta()).isEqualTo(3000L);

    transactions.executeWithoutResult(_ ->
        assertThat(stock.lotRows(fixture.holder(), List.of(fixture.lot())).get(fixture.lot().key()))
            .singleElement()
            .satisfies(row -> {
              assertThat(row.getId()).isEqualTo(rowId);
              assertThat(row.getAmount()).isEqualTo(2.0);
              assertThat(row.getVersion()).isEqualTo(version);
              assertThat(provenance.quantityOf(row.getId()))
                  .containsExactlyElementsOf(originalQuantity);
            }));
  }

  @Test
  void aConfirmedDryPlanCannotBePromotedToAWriteInItsOriginatingTransaction() {
    Fixture fixture = seed();
    var originalRow =
        transactions.execute(_ ->
            stock
                .lotRows(fixture.holder(), List.of(fixture.lot()))
                .get(fixture.lot().key())
                .getFirst());
    UUID rowId = originalRow.getId();
    Long version = originalRow.getVersion();
    var originalQuantity = provenance.quantityOf(rowId);
    assertThat(originalRow.getAmount()).isEqualTo(2.0);
    assertThat(originalQuantity).hasSize(1);
    assertThat(originalQuantity.getFirst().quanta()).isEqualTo(2000L);
    AtomicReference<StockLotWritePlans.Confirmed> confirmedPlan = new AtomicReference<>();

    assertThatThrownBy(() ->
            transactions.executeWithoutResult(_ -> {
              List<StockLotWritePlans.Intent> intents =
                  List.of(
                      new StockLotWritePlans.SetLotAmount(
                          0, fixture.lot(), new BigDecimal("2"), new BigDecimal("3")));
              var prepared =
                  plans.prepareLotBatch(
                      fixture.holder(), List.of(fixture.lot()), List.of(), List.of(), intents);
              var confirmed = plans.confirmLotBatch(prepared, List.of(), intents, true);
              confirmedPlan.set(confirmed);
              plans.executePreparedLot(confirmed, 0, false);
            }))
        .isInstanceOf(BusinessConflictException.class);

    assertThat(confirmedPlan.get()).isNotNull();
    transactions.executeWithoutResult(_ ->
        assertThat(stock.lotRows(fixture.holder(), List.of(fixture.lot())).get(fixture.lot().key()))
            .singleElement()
            .satisfies(row -> {
              assertThat(row.getId()).isEqualTo(rowId);
              assertThat(row.getAmount()).isEqualTo(2.0);
              assertThat(row.getVersion()).isEqualTo(version);
              assertThat(provenance.quantityOf(row.getId()))
                  .containsExactlyElementsOf(originalQuantity);
            }));
  }

  @Test
  void aSecondSelectorCannotReintroduceAnOperationRemovedByTheFirst() {
    Fixture fixture = seed();
    var originalRow =
        transactions.execute(_ ->
            stock
                .lotRows(fixture.holder(), List.of(fixture.lot()))
                .get(fixture.lot().key())
                .getFirst());
    var originalQuantity = provenance.quantityOf(originalRow.getId());
    AtomicReference<StockLotWritePlans.Selection> beforeReintroduction = new AtomicReference<>();

    assertThatThrownBy(() ->
            transactions.executeWithoutResult(_ -> {
              List<StockLotWritePlans.Intent> intents =
                  List.of(
                      new StockLotWritePlans.SetLotAmount(
                          0, fixture.lot(), new BigDecimal("2"), new BigDecimal("3")));
              plans.prepareLotBatch(
                  fixture.holder(),
                  List.of(fixture.lot()),
                  List.of(),
                  List.of(),
                  intents,
                  selection -> {
                    if (selection.intents().isEmpty()) {
                      beforeReintroduction.set(selection);
                      return new StockLotWritePlans.Selection(selection.entryRestores(), intents);
                    }
                    return new StockLotWritePlans.Selection(selection.entryRestores(), List.of());
                  });
            }))
        .isInstanceOf(BusinessConflictException.class);

    assertThat(beforeReintroduction.get())
        .isEqualTo(new StockLotWritePlans.Selection(List.of(), List.of()));
    transactions.executeWithoutResult(_ ->
        assertThat(stock.lotRows(fixture.holder(), List.of(fixture.lot())).get(fixture.lot().key()))
            .singleElement()
            .satisfies(row -> {
              assertThat(row.getId()).isEqualTo(originalRow.getId());
              assertThat(row.getAmount()).isEqualTo(2.0);
              assertThat(row.getVersion()).isEqualTo(originalRow.getVersion());
              assertThat(provenance.quantityOf(row.getId()))
                  .containsExactlyElementsOf(originalQuantity);
            }));
  }

  /**
   * A real member and persisted material/location lot with two personal stock units.
   *
   * @param holder the actual stock holder
   * @param lot the persisted material and location
   */
  private record Fixture(UUID holder, StockLot lot) {}

  /**
   * Creates stock through the actual owning writer before any plan handle is issued.
   *
   * @return the independent physical fixture
   */
  private Fixture seed() {
    return transactions.execute(_ -> {
      User holder = new User();
      holder.setId(UUID.randomUUID());
      holder.setUsername("handle-owner-" + UUID.randomUUID());
      holder = users.saveAndFlush(holder);
      Material material = new Material();
      material.setName("Handle input " + UUID.randomUUID());
      material.setType(MaterialType.RAW);
      material = materials.saveAndFlush(material);
      Location location = new Location();
      location.setName("Handle target " + UUID.randomUUID());
      location = locations.saveAndFlush(TestPlaces.onOwnCity(location, cities));
      StockLot lot = new StockLot(material, null, location, 500, false);
      commands.bookIn(holder.getId(), lot, 2.0);
      return new Fixture(holder.getId(), lot);
    });
  }
}

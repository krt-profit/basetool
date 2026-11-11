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

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.LockRace;
import de.greluc.krt.profit.basetool.backend.support.LockRace.Caller;
import de.greluc.krt.profit.basetool.backend.support.LockRace.Held;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two transactions on PostgreSQL around one Lager row: every Lager write that locks a single row
 * answers its not-found when another transaction deleted the row while the lock waited, and the
 * lock keeps what a caller that already read the row sees (ADR-0363).
 *
 * <p>Not {@code @Transactional}: every transaction commits, and the fixture is removed afterwards.
 */
@SpringBootTest
class InventoryRowLockRaceIntegrationTest {

  private static final double STOCK = 20.0;

  @Autowired private InventoryCheckoutService checkoutService;
  @Autowired private InventoryStolenMarkService stolenMarkService;
  @Autowired private InventoryOrgUnitChangeService orgUnitChangeService;
  @Autowired private InventoryPoolChangeService poolChangeService;
  @Autowired private StockCommands stockCommands;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private LockRace race;
  private User owner;
  private UUID unitId;
  private UUID materialId;
  private UUID locationId;
  private UUID rowId;

  /** A Lager write on one row, run as the fixture's owner. */
  @FunctionalInterface
  private interface LagerWrite {

    /**
     * Runs the write.
     *
     * @param test the test, for its services and fixture
     * @param row the row the write names
     * @return the write's result
     */
    Object apply(@NotNull InventoryRowLockRaceIntegrationTest test, @NotNull UUID row);
  }

  @BeforeEach
  void setUp() {
    race = new LockRace(transactionTemplate, jdbc);
    String tag = UUID.randomUUID().toString().substring(0, 8);
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("row-lock-" + tag);
    owner = userRepository.save(user);
    Squadron unit = new Squadron();
    unit.setName("Row lock " + tag);
    unit.setShorthand("L" + tag.substring(0, 4));
    unitId = squadronRepository.save(unit).getId();
    Material material = new Material();
    material.setName("Row lock ore " + tag);
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialId = materialRepository.save(material).getId();
    locationId = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, locationId, "Row lock depot " + tag);
    rowId =
        transactionTemplate.execute(_ -> {
          InventoryItem row = new InventoryItem();
          row.setUser(owner);
          row.setMaterial(materialRepository.getReferenceById(materialId));
          row.setLocation(locationRepository.getReferenceById(locationId));
          row.setQuality(500);
          row.setAmount(STOCK);
          row.setPersonal(true);
          return inventoryItemRepository.save(row).getId();
        });
  }

  @AfterEach
  void tearDown() {
    race.close();
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", owner.getId());
    jdbc.update(
        "DELETE FROM audit_event WHERE actor_user_id = ? OR target_user_id = ?",
        owner.getId(),
        owner.getId());
    squadronRepository.deleteById(unitId);
    materialRepository.deleteById(materialId);
    UUID cityId =
        jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, locationId);
    jdbc.update("DELETE FROM location WHERE id = ?", locationId);
    jdbc.update("DELETE FROM city WHERE id = ?", cityId);
    userRepository.deleteById(owner.getId());
  }

  /**
   * The Lager writes that lock their row first, each named for the report.
   *
   * @return the writes
   */
  static Stream<Named<LagerWrite>> lagerWritesThatLockOneRow() {
    return Stream.of(
        Named.of(
            "bulk checkout",
            (test, row) -> {
              test.checkoutService.bulkCheckout(
                  new BulkCheckoutRequest(List.of(row)), test.owner.getId());
              return true;
            }),
        Named.of(
            "bulk rebooking",
            (test, row) ->
                test.checkoutService.bulkRebook(
                    new BulkRebookRequest(
                        List.of(row), BulkRebookMode.LOCATION, null, test.locationId, null, false),
                    test.owner.getId())),
        Named.of(
            "stolen marking",
            (test, row) ->
                test.stolenMarkService.mark(
                    row,
                    new InventoryItemStolenMarkDto(null, true, null),
                    test.owner.getId(),
                    false)),
        Named.of(
            "bulk stolen marking",
            (test, row) ->
                test.stolenMarkService.bulkMark(
                    new BulkStolenMarkRequest(List.of(row), true), test.owner.getId())),
        Named.of(
            "org-unit change",
            (test, row) ->
                test.orgUnitChangeService.changeOrgUnit(
                    row,
                    new InventoryItemOrgUnitChangeDto(null, test.unitId, false),
                    test.owner.getId())),
        Named.of(
            "bulk org-unit change",
            (test, row) ->
                test.orgUnitChangeService.bulkChangeOrgUnit(
                    new BulkOrgUnitChangeRequest(List.of(row), test.unitId, false),
                    test.owner.getId())),
        Named.of(
            "pool change",
            (test, row) ->
                test.poolChangeService.changePool(
                    row,
                    new InventoryItemPoolChangeDto(0L, test.unitId, null, false),
                    test.owner.getId())));
  }

  @ParameterizedTest
  @MethodSource("lagerWritesThatLockOneRow")
  void aWriteWhoseRowIsDeletedWhileItsLockWaitsAnswersNotFound(LagerWrite write) throws Exception {
    Held<Integer> delete = race.hold(null, this::deleteTheRow);

    Future<Object> pending =
        race.startWaiting(
            Caller.of(owner.getId(), Roles.KRT_MEMBER), () -> write.apply(this, rowId));

    assertThat(delete.commit()).isOne();
    assertThatThrownBy(() -> LockRace.finish(pending))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(NotFoundException.class);
  }

  @Test
  void lockRowAnswersEmptyForARowDeletedWhileItsLockWaits() throws Exception {
    Held<Integer> delete = race.hold(null, this::deleteTheRow);

    Future<Boolean> locked =
        race.startWaiting(
            null, () -> inTransaction(() -> stockCommands.lockRow(rowId).isPresent()));

    assertThat(delete.commit()).isOne();
    assertThat(LockRace.finish(locked)).isFalse();
  }

  @Test
  void lockRowWaitsForAConcurrentWriteAndReadsWhatItCommitted() throws Exception {
    Held<Integer> write =
        race.hold(
            null,
            () ->
                jdbc.update(
                    "UPDATE inventory_item SET amount = 7, version = version + 1 WHERE id = ?",
                    rowId));

    Future<String> locked =
        race.startWaiting(
            null,
            () ->
                inTransaction(() ->
                    stockCommands
                        .lockRow(rowId)
                        .map(row -> row.getAmount() + "@" + row.getVersion())
                        .orElseThrow()));

    assertThat(write.commit()).isOne();
    assertThat(LockRace.finish(locked)).isEqualTo("7.0@1");
  }

  @Test
  void lockRowLocksARowTheTransactionAlreadyRead() throws Exception {
    Held<Boolean> lock =
        race.hold(
            null,
            () -> {
              inventoryItemRepository.findById(rowId).orElseThrow();
              return stockCommands.lockRow(rowId).isPresent();
            });

    Future<Integer> write =
        race.startWaiting(
            null,
            () -> jdbc.update("UPDATE inventory_item SET note = 'raced' WHERE id = ?", rowId));

    assertThat(lock.commit()).isTrue();
    assertThat(LockRace.finish(write)).isOne();
  }

  @Test
  void lockRowRefusesARowTheTransactionReadAtAnOlderVersion() {
    transactionTemplate.executeWithoutResult(status -> {
      status.setRollbackOnly();
      inventoryItemRepository.findById(rowId).orElseThrow();
      int bumped =
          CompletableFuture.supplyAsync(() ->
                  jdbc.update(
                      "UPDATE inventory_item SET amount = 7, version = version + 1 WHERE id = ?",
                      rowId))
              .join();

      assertThat(bumped).isOne();
      assertThatThrownBy(() -> stockCommands.lockRow(rowId))
          .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    });
  }

  /**
   * Deletes the fixture row in the caller's transaction.
   *
   * @return the number of deleted rows
   */
  private int deleteTheRow() {
    return jdbc.update("DELETE FROM inventory_item WHERE id = ?", rowId);
  }

  /**
   * Runs work in a transaction of its own.
   *
   * @param work the work
   * @param <T> the work's result
   * @return the work's result
   */
  private <T> T inTransaction(@NotNull Supplier<T> work) {
    return transactionTemplate.execute(_ -> work.get());
  }
}

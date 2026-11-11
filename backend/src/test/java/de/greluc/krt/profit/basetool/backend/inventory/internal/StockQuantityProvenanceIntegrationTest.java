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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJournalAction;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJournalService;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookOut;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolOutcome;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestore;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestoreResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityProvenance;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies durable physical quantity identity through the Inventory module's commands. */
@SpringBootTest
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class StockQuantityProvenanceIntegrationTest {
  /** Applies the ordinary last-membership loss through its owning module. */
  private final OrgUnitMembershipService membershipWrites;

  @Autowired private StockCommands commands;
  @Autowired private StockQuantityProvenance provenance;
  @Autowired private StockReads stock;
  @Autowired private InventoryItemRepository inventoryItems;
  @Autowired private PoolEntryCommands poolCommands;
  @Autowired private StockLotWritePlans lotPlans;
  @Autowired private ExchangeJournalService journal;
  @Autowired private TransactionTemplate transactions;
  @Autowired private UserRepository users;
  @Autowired private MaterialRepository materials;
  @Autowired private LocationRepository locations;
  @Autowired private OrgUnitRepository units;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> locationIds = new ArrayList<>();
  private UUID holder;
  private Material material;
  private Location location;
  private OrgUnit pool;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("quantity-" + UUID.randomUUID());
    user.setDisplayName("Quantity holder");
    holder = users.saveAndFlush(user).getId();
    material = new Material();
    material.setName("Quantity " + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material = materials.saveAndFlush(material);
    location = newLocation();
    UUID poolId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_profit_eligible, version)"
            + " VALUES (?, 'SPECIAL_COMMAND', ?, ?, TRUE, FALSE, 0)",
        poolId,
        "Quantity " + poolId,
        "Q" + poolId.toString().substring(0, 8));
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', false)",
        holder,
        poolId);
    pool = units.findById(poolId).orElseThrow();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                holder, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    jdbc.update("DELETE FROM notification WHERE recipient_user_id = ?", holder);
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", holder);
    jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", holder);
    jdbc.update("DELETE FROM app_user WHERE id = ?", holder);
    jdbc.update("DELETE FROM org_unit WHERE id = ?", pool.getId());
    jdbc.update("DELETE FROM material WHERE id = ?", material.getId());
    locationIds.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
  }

  @Test
  void readingAnUnstampedCompositionDoesNotWriteOrInvalidateTheRowVersion() {
    UUID source = bookIn(3.0);
    jdbc.update("UPDATE inventory_item SET quantity_provenance = '[]'::jsonb WHERE id = ?", source);
    long version = stock.findRow(source).orElseThrow().getVersion();

    transactions.executeWithoutResult(_ -> {
      assertThat(provenance.quantityOf(source))
          .containsExactly(new StockQuantityFragment(source, 0L, 3000L));
      InventoryItem locked = inventoryItems.lockById(source).orElseThrow();
      assertThat(locked.getVersion()).isEqualTo(version);
    });

    assertThat(
            jdbc.queryForObject(
                "SELECT quantity_provenance::text FROM inventory_item WHERE id = ?",
                String.class,
                source))
        .isEqualTo("[]");
    assertThat(stock.findRow(source).orElseThrow().getVersion()).isEqualTo(version);
  }

  @Test
  void aPartialTransferPreservesDisjointPartsOfTheOriginalQuantity() {
    UUID source = bookIn(10.0);
    List<StockQuantityFragment> original = provenance.quantityOf(source);
    assertThat(original).hasSize(1);
    UUID origin = original.getFirst().origin();
    Location targetLocation = newLocation();

    UUID moved =
        transactions.execute(_ ->
            commands.transferToPool(
                source, 3.0, pool, holder, targetLocation, StockBookingReason.DONATION));

    assertThat(provenance.quantityOf(moved))
        .containsExactly(new StockQuantityFragment(origin, 0L, 3000L));
    assertThat(provenance.quantityOf(source))
        .containsExactly(new StockQuantityFragment(origin, 3000L, 10000L));
  }

  @Test
  void aSplitCanMergeBackWithoutInventingNewPhysicalQuantity() {
    UUID source = bookIn(10.0);
    List<StockQuantityFragment> original = provenance.quantityOf(source);
    Location targetLocation = newLocation();
    UUID moved =
        transactions.execute(_ ->
            commands.transferToPool(
                source, 3.0, pool, holder, targetLocation, StockBookingReason.DONATION));

    UUID merged =
        transactions.execute(_ ->
            commands.transferToPool(
                moved, 3.0, pool, holder, location, StockBookingReason.DONATION));

    assertThat(provenance.quantityOf(merged)).isEqualTo(original);
    assertThat(stock.findRow(source)).isEmpty();
    assertThat(stock.findRow(moved)).isEmpty();
  }

  @Test
  void aTrueLossKeepsSurvivingIdentityAndNewProvisionHasAnIndependentOrigin() {
    UUID source = bookIn(10.0);
    UUID origin = provenance.quantityOf(source).getFirst().origin();
    transactions.executeWithoutResult(_ -> commands.bookOutRow(holder, source, 3.0));
    assertThat(provenance.quantityOf(source))
        .containsExactly(new StockQuantityFragment(origin, 3000L, 10000L));

    UUID merged = bookIn(3.0);

    assertThat(provenance.quantityOf(merged))
        .filteredOn(fragment -> origin.equals(fragment.origin()))
        .containsExactly(new StockQuantityFragment(origin, 3000L, 10000L));
    assertThat(provenance.quantityOf(merged))
        .filteredOn(fragment -> !origin.equals(fragment.origin()))
        .singleElement()
        .extracting(StockQuantityFragment::quanta)
        .isEqualTo(3000L);
  }

  @Test
  void journalRestoreRecreatesTheOriginalQuantityIdentity() {
    UUID source = bookIn(3.0);
    List<StockQuantityFragment> original = provenance.quantityOf(source);
    long version = stock.findRow(source).orElseThrow().getVersion();
    StockLotWritePlans.EntryRestore restore = takeAndJournal(source, version, 3.0);
    assertThat(stock.findRow(source)).isEmpty();

    List<PoolRestoreResult> results =
        transactions.execute(_ -> {
          var entries = List.of(restore);
          var prepared = lotPlans.prepareLotBatch(holder, List.of(), entries, entries, List.of());
          var confirmed = lotPlans.confirmLotBatch(prepared, entries, List.of(), false);
          return lotPlans.executePreparedEntryRestores(confirmed);
        });

    assertThat(results).containsExactly(PoolRestoreResult.RESTORED);
    assertThat(provenance.quantityOf(source)).isEqualTo(original);
  }

  @Test
  void aConfirmedEntryRestoreFeedsItsPersonalInverseWithoutASecondResourcePlan() {
    UUID source = bookIn(2.0);
    List<StockQuantityFragment> original = provenance.quantityOf(source);
    long version = stock.findRow(source).orElseThrow().getVersion();
    var restore = takeAndJournal(source, version, 2.0);
    var restoredQuantity =
        Objects.requireNonNull(
            Objects.requireNonNull(restore.request().target()).completeQuantity());
    StockLot lot = new StockLot(material, null, location, 500, false);
    transactions.executeWithoutResult(_ -> {
      var entries = List.of(restore);
      StockLotWritePlans.Intent inverse =
          new StockLotWritePlans.RestoreLot(
              1,
              lot,
              List.of(
                  new RebookCommands.Rebook(
                      holder, lot, pool.getId(), RebookCommands.RebookDirection.TO_PERSONAL, 2.0)),
              BigDecimal.valueOf(2.0),
              new StockLotWritePlans.ExactQuantity(restoredQuantity));
      var intents = List.of(inverse);
      var prepared = lotPlans.prepareLotBatch(holder, List.of(lot), entries, entries, intents);
      assertThat(prepared.lockedEntries()).containsKey(source);
      assertThat(prepared.lockedEntries().get(source)).isEmpty();
      var confirmed = lotPlans.confirmLotBatch(prepared, entries, intents, false);
      assertThat(lotPlans.executePreparedEntryRestores(confirmed))
          .containsExactly(PoolRestoreResult.RESTORED);
      assertThat(lotPlans.executePreparedLot(confirmed, 1, false).refusal()).isNull();
    });
    transactions.executeWithoutResult(_ -> {
      var current = stock.lotRows(holder, List.of(lot)).get(lot.key());
      assertThat(current)
          .singleElement()
          .satisfies(row -> {
            assertThat(row.getAmount()).isEqualTo(2.0);
            assertThat(row.getPersonal()).isTrue();
            assertThat(Objects.requireNonNull(row.getOwningOrgUnit()).getId())
                .isEqualTo(pool.getId());
            assertThat(provenance.quantityOf(row.getId())).containsExactlyElementsOf(original);
          });
    });
  }

  @Test
  void executingAConfirmedEntryPrefixTwiceRollsBackItsFirstRestoration() {
    UUID source = bookIn(2.0);
    List<StockQuantityFragment> original = provenance.quantityOf(source);
    long version = stock.findRow(source).orElseThrow().getVersion();
    var restore = takeAndJournal(source, version, 2.0);
    StockLot lot = new StockLot(material, null, location, 500, false);
    assertThat(original).hasSize(1);
    assertThat(original.getFirst().quanta()).isEqualTo(2000L);
    assertThat(stock.findRow(source)).isEmpty();
    AtomicReference<List<PoolRestoreResult>> firstRestoration = new AtomicReference<>();

    assertThatThrownBy(() ->
            transactions.executeWithoutResult(_ -> {
              var entries = List.of(restore);
              var prepared =
                  lotPlans.prepareLotBatch(holder, List.of(lot), entries, entries, List.of());
              var confirmed = lotPlans.confirmLotBatch(prepared, entries, List.of(), false);
              var restored = lotPlans.executePreparedEntryRestores(confirmed);
              assertThat(stock.lotRows(holder, List.of(lot)).get(lot.key()))
                  .singleElement()
                  .satisfies(row -> {
                    assertThat(row.getId()).isEqualTo(source);
                    assertThat(row.getAmount()).isEqualTo(2.0);
                    assertThat(row.getPersonal()).isFalse();
                    assertThat(Objects.requireNonNull(row.getOwningOrgUnit()).getId())
                        .isEqualTo(pool.getId());
                    assertThat(provenance.quantityOf(row.getId()))
                        .containsExactlyElementsOf(original);
                  });
              firstRestoration.set(restored);
              lotPlans.executePreparedEntryRestores(confirmed);
            }))
        .isInstanceOf(BusinessConflictException.class);

    assertThat(firstRestoration.get()).containsExactly(PoolRestoreResult.RESTORED);
    transactions.executeWithoutResult(_ -> {
      assertThat(stock.findRow(source)).isEmpty();
      assertThat(stock.lotRows(holder, List.of(lot)).get(lot.key())).isEmpty();
    });
  }

  @ParameterizedTest
  @EnumSource(PrefixNarrowingBoundary.class)
  void droppingAnEntryRestoreCannotSubstituteAnotherOriginForItsRetainedInverse(
      PrefixNarrowingBoundary boundary) {
    UUID first = bookIn(2.0);
    StockQuantityFragment firstProvision = provenance.quantityOf(first).getFirst();
    UUID source = bookIn(2.0);
    List<StockQuantityFragment> original = provenance.quantityOf(source);
    assertThat(original).hasSize(2).contains(firstProvision);
    assertThat(original)
        .allSatisfy(fragment -> {
          assertThat(fragment.fromQuanta()).isZero();
          assertThat(fragment.toQuanta()).isEqualTo(2000L);
        });
    assertThat(original.getFirst().origin()).isNotEqualTo(original.getLast().origin());
    assertThat(stock.findRow(first)).isEmpty();
    assertThat(stock.findRow(source).orElseThrow().getAmount()).isEqualTo(4.0);
    long version = stock.findRow(source).orElseThrow().getVersion();
    var restore = takeAndJournal(source, version, 2.0);
    var expected = Objects.requireNonNull(restore.request().expected());
    var restoredQuantity =
        Objects.requireNonNull(
            Objects.requireNonNull(restore.request().target()).completeQuantity());
    assertThat(restoredQuantity.knownPhysical()).containsExactlyElementsOf(original);
    StockQuantityFragment prefixQuantity = restoredQuantity.knownPhysical().getFirst();
    StockQuantityFragment survivingQuantity = restoredQuantity.knownPhysical().getLast();
    assertThat(prefixQuantity.origin()).isNotEqualTo(survivingQuantity.origin());
    assertThat(expected.amount()).isEqualTo(2.0);
    assertThat(expected.quantityProvenance()).containsExactly(survivingQuantity);
    assertThat(provenance.quantityOf(source)).containsExactly(survivingQuantity);
    StockLot lot = new StockLot(material, null, location, 500, false);
    var entries = List.of(restore);
    StockLotWritePlans.Intent inverse =
        new StockLotWritePlans.RestoreLot(
            1,
            lot,
            List.of(
                new RebookCommands.Rebook(
                    holder, lot, pool.getId(), RebookCommands.RebookDirection.TO_PERSONAL, 2.0)),
            BigDecimal.valueOf(4.0),
            new StockLotWritePlans.ExactQuantity(restoredQuantity));
    var intents = List.of(inverse);
    var dryOutcome =
        transactions.execute(_ -> {
          var prepared =
              lotPlans.prepareLotBatch(holder, List.of(lot), entries, List.of(), intents);
          var confirmed = lotPlans.confirmLotBatch(prepared, List.of(), intents, true);
          return lotPlans.executePreparedLot(confirmed, 1, true);
        });
    assertThat(dryOutcome).isNotNull();
    assertThat(Objects.requireNonNull(dryOutcome).refusal()).isNull();
    assertThat(dryOutcome.quantity()).isNull();
    AtomicReference<StockLotWritePlans.EntryState> narrowedEntry = new AtomicReference<>();
    AtomicReference<StockLotWritePlans.Selection> narrowedSelection = new AtomicReference<>();

    assertThatThrownBy(() ->
            transactions.executeWithoutResult(_ -> {
              var prepared =
                  switch (boundary) {
                    case PREPARE ->
                        lotPlans.prepareLotBatch(
                            holder,
                            List.of(lot),
                            entries,
                            entries,
                            intents,
                            selection -> {
                              narrowedSelection.set(selection);
                              return new StockLotWritePlans.Selection(
                                  List.of(), selection.intents());
                            });
                    case CONFIRM ->
                        lotPlans.prepareLotBatch(holder, List.of(lot), entries, entries, intents);
                  };
              narrowedEntry.set(prepared.lockedEntries().get(source).orElseThrow());
              lotPlans.confirmLotBatch(prepared, List.of(), intents, false);
            }))
        .isInstanceOf(BusinessConflictException.class);

    if (boundary == PrefixNarrowingBoundary.PREPARE) {
      assertThat(narrowedSelection.get())
          .isEqualTo(new StockLotWritePlans.Selection(List.of(), intents));
    }
    assertThat(narrowedEntry.get()).isEqualTo(new StockLotWritePlans.EntryState(expected));
    transactions.executeWithoutResult(_ -> {
      assertThat(stock.lotRows(holder, List.of(lot)).get(lot.key()))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.getId()).isEqualTo(source);
            assertThat(row.getVersion()).isEqualTo(expected.version());
            assertThat(row.getAmount()).isEqualTo(2.0);
            assertThat(row.getPersonal()).isFalse();
            assertThat(row.getUser().getId()).isEqualTo(holder);
            assertThat(Objects.requireNonNull(row.getOwningOrgUnit()).getId())
                .isEqualTo(pool.getId());
            assertThat(Objects.requireNonNull(row.getMaterial()).getId())
                .isEqualTo(material.getId());
            assertThat(row.getGameItem()).isNull();
            assertThat(row.getLocation().getId()).isEqualTo(location.getId());
            assertThat(row.getQuality()).isEqualTo(500);
            assertThat(row.getStolen()).isFalse();
            assertThat(row.jobOrderReserved()).isZero();
            assertThat(row.missionReserved()).isZero();
            assertThat(row.donationReserved()).isZero();
            assertThat(provenance.quantityOf(source)).containsExactly(survivingQuantity);
          });
    });
  }

  @Test
  void anExpectedAbsentJournalCannotReplaceAPhysicallyPresentUnassignedRow() {
    UUID source = bookIn(2.0);
    List<StockQuantityFragment> original = provenance.quantityOf(source);
    long version = stock.findRow(source).orElseThrow().getVersion();
    var restore = takeAndJournal(source, version, 2.0);
    assertThat(restore.request().expected()).isNull();
    assertThat(Objects.requireNonNull(restore.request().target()).amount()).isEqualTo(2.0);
    assertThat(stock.findRow(source)).isEmpty();
    StockLot lot = new StockLot(material, null, location, 500, false);
    var entries = List.of(restore);

    var firstRestoration =
        transactions.execute(_ -> {
          var prepared =
              lotPlans.prepareLotBatch(holder, List.of(lot), entries, entries, List.of());
          assertThat(prepared.lockedEntries().get(source)).isEmpty();
          var confirmed = lotPlans.confirmLotBatch(prepared, entries, List.of(), false);
          return lotPlans.executePreparedEntryRestores(confirmed);
        });
    assertThat(firstRestoration).containsExactly(PoolRestoreResult.RESTORED);
    long restoredVersion = stock.findRow(source).orElseThrow().getVersion();

    membershipWrites.removeMember(pool.getId(), holder);

    var before =
        transactions.execute(_ -> stock.lotRows(holder, List.of(lot)).get(lot.key()).getFirst());
    assertThat(before).isNotNull();
    assertThat(Objects.requireNonNull(before).getId()).isEqualTo(source);
    assertThat(before.getAmount()).isEqualTo(2.0);
    assertThat(before.getPersonal()).isFalse();
    assertThat(before.getOwningOrgUnit()).isNull();
    assertThat(before.getVersion()).isGreaterThan(restoredVersion);
    assertThat(provenance.quantityOf(source)).isEqualTo(original);

    var refusedRestoration =
        transactions.execute(_ -> {
          var prepared =
              lotPlans.prepareLotBatch(holder, List.of(lot), entries, entries, List.of());
          assertThat(prepared.lockedEntries().get(source))
              .contains(new StockLotWritePlans.EntryState(null));
          var confirmed = lotPlans.confirmLotBatch(prepared, entries, List.of(), false);
          return lotPlans.executePreparedEntryRestores(confirmed);
        });

    assertThat(refusedRestoration).containsExactly(PoolRestoreResult.CHANGED_AFTERWARDS);
    transactions.executeWithoutResult(_ -> {
      assertThat(stock.lotRows(holder, List.of(lot)).get(lot.key()))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.getId()).isEqualTo(source);
            assertThat(row.getVersion()).isEqualTo(before.getVersion());
            assertThat(row.getAmount()).isEqualTo(2.0);
            assertThat(row.getPersonal()).isFalse();
            assertThat(row.getOwningOrgUnit()).isNull();
            assertThat(row.getUser().getId()).isEqualTo(holder);
            assertThat(Objects.requireNonNull(row.getMaterial()).getId())
                .isEqualTo(material.getId());
            assertThat(row.getGameItem()).isNull();
            assertThat(row.getLocation().getId()).isEqualTo(location.getId());
            assertThat(row.getQuality()).isEqualTo(500);
            assertThat(row.getStolen()).isFalse();
            assertThat(row.jobOrderReserved()).isZero();
            assertThat(row.missionReserved()).isZero();
            assertThat(row.donationReserved()).isZero();
            assertThat(provenance.quantityOf(source)).isEqualTo(original);
          });
    });
  }

  /** The public planning boundary that removes an initially accepted historical prefix. */
  private enum PrefixNarrowingBoundary {
    PREPARE,
    CONFIRM
  }

  private StockLotWritePlans.EntryRestore takeAndJournal(UUID source, long version, double amount) {
    return transactions.execute(_ -> {
      PoolOutcome removed =
          poolCommands.bookOut(
              new PoolBookOut(
                  holder, source, pool.getId(), version, amount, "SCU", List.of(), List.of()),
              false);
      assertThat(removed.refusal()).isNull();
      var change = removed.changes().getFirst();
      var recorded =
          journal.record(
              new ExchangeCaller(holder, "quantity-provenance", "quantity-installation"),
              UUID.randomUUID(),
              ExchangeJournalAction.ORG_STOCK_BOOK_OUT,
              source.toString(),
              true,
              change.before(),
              change.after());
      return new StockLotWritePlans.EntryRestore(
          0, recorded.getId(), new PoolRestore(source, change.after(), change.before()));
    });
  }

  private UUID bookIn(double amount) {
    return transactions.execute(_ ->
        commands.bookIntoPool(
            holder,
            pool,
            new StockLot(material, null, location, 500, false),
            amount,
            StockBookingReason.PURCHASE));
  }

  private Location newLocation() {
    UUID id = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, id, "Quantity " + id);
    jdbc.update("UPDATE location SET version = 0 WHERE id = ?", id);
    locationIds.add(id);
    return locations.findById(id).orElseThrow();
  }
}

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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolBookOut;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolOutcome;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestore;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestoreResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityProvenance;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies durable physical quantity identity through the Inventory module's commands. */
@SpringBootTest
class StockQuantityProvenanceIntegrationTest {

  @Autowired private StockCommands commands;
  @Autowired private StockQuantityProvenance provenance;
  @Autowired private StockReads stock;
  @Autowired private PoolEntryCommands poolCommands;
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
    PoolOutcome removed =
        transactions.execute(_ ->
            poolCommands.bookOut(
                new PoolBookOut(
                    holder, source, pool.getId(), version, 3.0, "SCU", List.of(), List.of()),
                false));
    assertThat(removed.refusal()).isNull();
    assertThat(stock.findRow(source)).isEmpty();

    List<PoolRestoreResult> results =
        transactions.execute(_ ->
            poolCommands.restore(
                Map.of(),
                List.of(
                    new PoolRestore(
                        source,
                        removed.changes().getFirst().after(),
                        removed.changes().getFirst().before()))));

    assertThat(results).containsExactly(PoolRestoreResult.RESTORED);
    assertThat(provenance.quantityOf(source)).isEqualTo(original);
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

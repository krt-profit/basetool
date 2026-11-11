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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import de.greluc.krt.profit.basetool.backend.support.LockRace;
import de.greluc.krt.profit.basetool.backend.support.LockRace.Caller;
import de.greluc.krt.profit.basetool.backend.support.LockRace.Held;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two transactions on PostgreSQL: a pledge from a Lager row or the receipt of a pledge from that
 * row, and a delete of the row. The pledge that waited for the row's lock answers its not-found;
 * the receipt books the received amount into the pool, as for a row gone before it started
 * (REQ-SUP-008, REQ-SUP-009, ADR-0363).
 *
 * <p>Not {@code @Transactional}: every transaction commits, and the fixture is removed afterwards.
 */
@SpringBootTest
class SupplyDonationStockLockRaceIntegrationTest {

  private static final String SUPPLY_ENABLED = "supply.enabled";
  private static final double STOCK = 20.0;
  private static final double PLEDGED = 5.0;

  @Autowired private SupplyDonationService donationService;
  @Autowired private SupplyUnitSettingRepository unitSettingRepository;
  @Autowired private SystemSettings systemSettings;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private LockRace race;
  private Optional<String> supplyWasEnabled;
  private User donor;
  private User admin;
  private UUID unitId;
  private UUID materialId;
  private UUID locationId;
  private UUID rowId;

  @BeforeEach
  void setUp() {
    race = new LockRace(transactionTemplate, jdbc);
    supplyWasEnabled = systemSettings.getSettingValue(SUPPLY_ENABLED);
    systemSettings.putSettingValue(SUPPLY_ENABLED, "true");
    String tag = UUID.randomUUID().toString().substring(0, 8);
    donor = user("donation-lock-donor-" + tag);
    admin = user("donation-lock-admin-" + tag);
    Squadron unit = new Squadron();
    unit.setName("Donation lock " + tag);
    unit.setShorthand("D" + tag.substring(0, 4));
    unitId = squadronRepository.save(unit).getId();
    unitSettingRepository.save(new SupplyUnitSetting(unitId, false, true));
    Material material = new Material();
    material.setName("Donation lock ore " + tag);
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialId = materialRepository.save(material).getId();
    locationId = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, locationId, "Donation lock depot " + tag);
    rowId =
        transactionTemplate.execute(_ -> {
          InventoryItem row = new InventoryItem();
          row.setUser(donor);
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
    jdbc.update("DELETE FROM inventory_item WHERE user_id IN (?, ?)", donor.getId(), admin.getId());
    jdbc.update("DELETE FROM supply_donation WHERE receiving_org_unit_id = ?", unitId);
    jdbc.update(
        "DELETE FROM audit_event WHERE actor_user_id IN (?, ?) OR target_user_id IN (?, ?)",
        donor.getId(),
        admin.getId(),
        donor.getId(),
        admin.getId());
    unitSettingRepository.deleteById(unitId);
    squadronRepository.deleteById(unitId);
    materialRepository.deleteById(materialId);
    UUID cityId =
        jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, locationId);
    jdbc.update("DELETE FROM location WHERE id = ?", locationId);
    jdbc.update("DELETE FROM city WHERE id = ?", cityId);
    userRepository.deleteById(donor.getId());
    userRepository.deleteById(admin.getId());
    systemSettings.putSettingValue(SUPPLY_ENABLED, supplyWasEnabled.orElse("false"));
  }

  @Test
  void aPledgeWhoseRowIsDeletedWhileItsLockWaitsAnswersNotFound() throws Exception {
    Held<Integer> delete = race.hold(null, this::deleteTheRow);

    Future<DonationDto> pledge = race.startWaiting(asDonor(), this::pledgeFromTheRow);

    assertThat(delete.commit()).isOne();
    assertThatThrownBy(() -> LockRace.finish(pledge))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(NotFoundException.class);
    assertThat(donations()).isZero();
  }

  @Test
  void aReceiptWhoseSourceRowIsDeletedWhileItsLockWaitsBooksIntoThePool() throws Exception {
    DonationDto pledged = LockRace.finish(race.start(asDonor(), this::pledgeFromTheRow));
    Held<Integer> delete = race.hold(null, this::deleteTheRow);

    Future<DonationDto> receipt =
        race.startWaiting(
            Caller.of(admin.getId(), Roles.ADMIN),
            () ->
                donationService.receive(
                    pledged.id(),
                    new ReceiveDonationRequest(PLEDGED, locationId, pledged.version())));

    assertThat(delete.commit()).isOne();
    assertThat(LockRace.finish(receipt).status()).isEqualTo(DonationStatus.RECEIVED);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(amount) FROM inventory_item WHERE user_id = ?"
                    + " AND owning_org_unit_id = ? AND personal = false",
                Double.class,
                admin.getId(),
                unitId))
        .isEqualTo(PLEDGED);
  }

  /**
   * Pledges part of the fixture row to the fixture unit as the signed-in donor.
   *
   * @return the pledge
   */
  private @NotNull DonationDto pledgeFromTheRow() {
    return donationService.pledge(
        new PledgeRequest(null, unitId, null, null, null, PLEDGED, rowId));
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
   * Counts the donations made to the fixture unit.
   *
   * @return the donations
   */
  private int donations() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM supply_donation WHERE receiving_org_unit_id = ?",
            Integer.class,
            unitId);
    return count == null ? 0 : count;
  }

  /**
   * The fixture's donor, signed in as a member.
   *
   * @return the caller
   */
  private @NotNull Caller asDonor() {
    return Caller.of(donor.getId(), Roles.KRT_MEMBER);
  }

  /**
   * Commits a member.
   *
   * @param username the username
   * @return the member
   */
  private @NotNull User user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }
}

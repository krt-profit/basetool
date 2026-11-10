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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryCheckoutService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.LockRace;
import de.greluc.krt.profit.basetool.backend.support.LockRace.Caller;
import de.greluc.krt.profit.basetool.backend.support.LockRace.Held;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.time.Instant;
import java.util.List;
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
 * Two transactions on PostgreSQL: a Materialbörse release or deactivation on a Lager row, and a
 * Lager delete of the same row. Whichever runs first, the other waits for its row lock, so an offer
 * a delete cascades away carries its {@code MARKET_OFFER_REMOVED}, a release on a deleted row is
 * refused, and an offer deactivated before the delete is not reported as removed (REQ-MARKET-013,
 * ADR-0362).
 *
 * <p>Not {@code @Transactional}: every transaction commits, and the fixture is removed afterwards.
 */
@SpringBootTest
class MaterialExchangeOfferDeleteRaceIntegrationTest {

  private static final double STOCK = 20.0;
  private static final double OFFERED = 5.0;

  @Autowired private MaterialExchangeService exchangeService;
  @Autowired private InventoryCheckoutService checkoutService;
  @Autowired private MaterialExchangeOfferRepository offerRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private LockRace race;
  private User owner;
  private User admin;
  private UUID unitId;
  private UUID materialId;
  private UUID locationId;
  private UUID rowId;

  @BeforeEach
  void setUp() {
    race = new LockRace(transactionTemplate, jdbc);
    String tag = UUID.randomUUID().toString().substring(0, 8);
    owner = user("race-owner-" + tag);
    admin = user("race-admin-" + tag);
    Squadron unit = new Squadron();
    unit.setName("Race " + tag);
    unit.setShorthand("R" + tag.substring(0, 4));
    unit.setProfitEligible(true);
    unitId = squadronRepository.save(unit).getId();
    Material material = new Material();
    material.setName("Race ore " + tag);
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialId = materialRepository.save(material).getId();
    locationId = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, locationId, "Race depot " + tag);
    rowId =
        transactionTemplate.execute(_ -> {
          InventoryItem row = new InventoryItem();
          row.setUser(owner);
          row.setMaterial(materialRepository.getReferenceById(materialId));
          row.setLocation(locationRepository.getReferenceById(locationId));
          row.setOwningOrgUnit(squadronRepository.getReferenceById(unitId));
          row.setQuality(500);
          row.setAmount(STOCK);
          row.setPersonal(false);
          return inventoryItemRepository.save(row).getId();
        });
  }

  @AfterEach
  void tearDown() {
    race.close();
    jdbc.update("DELETE FROM material_exchange_offer WHERE owner_id = ?", owner.getId());
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", owner.getId());
    jdbc.update(
        "DELETE FROM audit_event WHERE actor_user_id IN (?, ?) OR target_user_id IN (?, ?)",
        owner.getId(),
        admin.getId(),
        owner.getId(),
        admin.getId());
    squadronRepository.deleteById(unitId);
    materialRepository.deleteById(materialId);
    UUID cityId =
        jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, locationId);
    jdbc.update("DELETE FROM location WHERE id = ?", locationId);
    jdbc.update("DELETE FROM city WHERE id = ?", cityId);
    userRepository.deleteById(owner.getId());
    userRepository.deleteById(admin.getId());
  }

  @Test
  void anOfferReleasedWhileTheWipeWaitsForTheRowIsAuditedAsRemoved() throws Exception {
    Held<MaterialExchangeOfferDto> release = race.hold(asOwner(), this::release);

    Future<Integer> wipe = race.startWaiting(asAdmin(), checkoutService::deleteAllGlobalInventory);

    UUID offerId = release.commit().id();
    assertThat(LockRace.finish(wipe)).isEqualTo(1);
    assertThat(details("MARKET_OFFER_RELEASED", offerId)).hasSize(1);
    assertThat(details("MARKET_OFFER_REMOVED", offerId))
        .containsExactly("kind=MATERIAL reason=wipe");
    assertThat(offerRepository.existsById(offerId)).isFalse();
  }

  @Test
  void aReleaseOnARowTheWipeHoldsIsRefusedOnceTheWipeCommits() throws Exception {
    Held<Integer> wipe = race.hold(asAdmin(), checkoutService::deleteAllGlobalInventory);

    Future<MaterialExchangeOfferDto> release = race.startWaiting(asOwner(), this::release);

    assertThat(wipe.commit()).isEqualTo(1);
    assertThatThrownBy(() -> LockRace.finish(release))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(NotFoundException.class);
    assertThat(offersOnTheRow()).isZero();
    assertThat(eventsFor(owner, "MARKET_OFFER_RELEASED")).isZero();
  }

  @Test
  void anOfferReleasedWhileTheBulkCheckoutWaitsForTheRowIsAuditedAsRemoved() throws Exception {
    Held<MaterialExchangeOfferDto> release = race.hold(asOwner(), this::release);

    Future<Boolean> checkout = race.startWaiting(asOwner(), this::bulkCheckout);

    UUID offerId = release.commit().id();
    assertThat(LockRace.finish(checkout)).isTrue();
    assertThat(details("MARKET_OFFER_REMOVED", offerId))
        .containsExactly("kind=MATERIAL reason=bulk-checkout");
    assertThat(offerRepository.existsById(offerId)).isFalse();
  }

  @Test
  void anOfferDeactivatedWhileTheBulkCheckoutWaitsForTheRowIsNotReportedAsRemoved()
      throws Exception {
    UUID offerId = activeOffer();
    Held<MaterialExchangeOfferDto> deactivate =
        race.hold(asOwner(), () -> exchangeService.deactivate(offerId));

    Future<Boolean> checkout = race.startWaiting(asOwner(), this::bulkCheckout);

    deactivate.commit();
    assertThat(LockRace.finish(checkout)).isTrue();
    assertThat(details("MARKET_OFFER_DEACTIVATED", offerId)).hasSize(1);
    assertThat(details("MARKET_OFFER_REMOVED", offerId)).isEmpty();
    assertThat(offerRepository.existsById(offerId)).isFalse();
  }

  /**
   * Releases part of the fixture row as the signed-in owner.
   *
   * @return the released offer
   */
  private MaterialExchangeOfferDto release() {
    return exchangeService.release(new MaterialExchangeReleaseRequest(rowId, OFFERED, null));
  }

  /**
   * Books the fixture row out in full through the bulk checkout.
   *
   * @return {@code true} once the checkout committed
   */
  private boolean bulkCheckout() {
    checkoutService.bulkCheckout(new BulkCheckoutRequest(List.of(rowId)), owner.getId());
    return true;
  }

  /**
   * Commits an active material offer on the fixture row.
   *
   * @return the offer's id
   */
  private UUID activeOffer() {
    return transactionTemplate.execute(_ ->
        offerRepository
            .save(
                MaterialExchangeOffer.builder()
                    .kind(MaterialExchangeOfferKind.MATERIAL)
                    .inventoryItemId(rowId)
                    .owner(owner)
                    .owningOrgUnit(squadronRepository.getReferenceById(unitId))
                    .offeredAmount(OFFERED)
                    .status(MaterialExchangeOfferStatus.ACTIVE)
                    .releasedAt(Instant.now())
                    .build())
            .getId());
  }

  /**
   * The fixture's owner, signed in without an active org unit.
   *
   * @return the caller
   */
  private @NotNull Caller asOwner() {
    return Caller.of(owner.getId(), Roles.KRT_MEMBER);
  }

  /**
   * The fixture's admin, with the fixture's org unit active.
   *
   * @return the caller
   */
  private @NotNull Caller asAdmin() {
    return new Caller(admin.getId(), Roles.ADMIN, unitId);
  }

  /**
   * Reads the details of the audit rows of one type for one offer.
   *
   * @param type the event type
   * @param offerId the offer
   * @return the details, one per row
   */
  private List<String> details(String type, UUID offerId) {
    return jdbc.queryForList(
        "SELECT details FROM audit_event WHERE event_type = ? AND subject_id = ?",
        String.class,
        type,
        offerId);
  }

  /**
   * Counts the audit rows of one type recorded by a member.
   *
   * @param actor the member
   * @param type the event type
   * @return the rows
   */
  private int eventsFor(@NotNull User actor, String type) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE event_type = ? AND actor_user_id = ?",
            Integer.class,
            type,
            actor.getId());
    return count == null ? 0 : count;
  }

  /**
   * Counts the offers that reference the fixture row.
   *
   * @return the offers
   */
  private int offersOnTheRow() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM material_exchange_offer WHERE inventory_item_id = ?",
            Integer.class,
            rowId);
    return count == null ? 0 : count;
  }

  /**
   * Commits a member.
   *
   * @param username the username
   * @return the member
   */
  private User user(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }
}

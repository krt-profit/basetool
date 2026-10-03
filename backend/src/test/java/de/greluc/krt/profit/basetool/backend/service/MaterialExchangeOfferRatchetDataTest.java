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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies against PostgreSQL that the Lager's own book-outs audit the Materialbörse offers they
 * lower or remove, and that the wipe and account-erasure reads find the offers their bulk deletes
 * cascade away (REQ-MARKET-013, REQ-AUDIT-001).
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class MaterialExchangeOfferRatchetDataTest {

  @Autowired private InventoryCheckoutService checkoutService;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialExchangeOfferRepository offerRepository;
  @Autowired private JdbcTemplate jdbc;

  @PersistenceContext private EntityManager entityManager;

  @Test
  void aPartialDiscardLowersTheOfferAndAuditsItAsReduced() {
    MaterialExchangeOffer offer = offer(100.0, 80.0, false);
    InventoryItem row = detach(offer);

    checkoutService.bookOutInventoryItem(
        row.getId(), discard(70.0, row.getVersion()), row.getUser().getId(), false);
    entityManager.flush();

    assertThat(
            jdbc.queryForObject(
                "SELECT offered_amount FROM material_exchange_offer WHERE id = ?",
                Double.class,
                offer.getId()))
        .isEqualTo(30.0);
    assertThat(details("MARKET_OFFER_REDUCED", offer.getId()))
        .containsExactly("kind=MATERIAL from=80.0 to=30.0 reason=checkout");
  }

  @Test
  void aDiscardThatEmptiesTheRowAuditsTheCascadedOfferAsRemoved() {
    MaterialExchangeOffer offer = offer(40.0, 40.0, false);
    InventoryItem row = detach(offer);

    checkoutService.bookOutInventoryItem(
        row.getId(), discard(40.0, row.getVersion()), row.getUser().getId(), false);
    entityManager.flush();

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM material_exchange_offer WHERE id = ?",
                Integer.class,
                offer.getId()))
        .isZero();
    assertThat(details("MARKET_OFFER_REMOVED", offer.getId()))
        .containsExactly("kind=MATERIAL reason=checkout");
  }

  @Test
  void aBulkCheckoutAuditsTheOfferOfEveryDeletedRow() {
    MaterialExchangeOffer offer = offer(20.0, 5.0, false);
    InventoryItem row = detach(offer);

    checkoutService.bulkCheckout(
        new BulkCheckoutRequest(List.of(row.getId())), row.getUser().getId());
    entityManager.flush();

    assertThat(details("MARKET_OFFER_REMOVED", offer.getId()))
        .containsExactly("kind=MATERIAL reason=bulk-checkout");
  }

  @Test
  void theWipeReadFindsOffersOnSharedRowsOnlyAndTheErasureReadFindsTheMembersOffers() {
    MaterialExchangeOffer shared = offer(20.0, 5.0, false);
    MaterialExchangeOffer personal = offer(20.0, 5.0, true);
    entityManager.flush();

    List<UUID> wiped =
        offerRepository.findActiveStockOnNonPersonalRows(true, null, Set.of()).stream()
            .map(MaterialExchangeOfferRepository.OfferStock::getId)
            .toList();
    List<UUID> erased =
        offerRepository.findActiveStockByRowOwner(personal.getOwner().getId()).stream()
            .map(MaterialExchangeOfferRepository.OfferStock::getId)
            .toList();

    assertThat(wiped).contains(shared.getId()).doesNotContain(personal.getId());
    assertThat(erased).containsExactly(personal.getId());
  }

  /**
   * Writes the fixture and empties the persistence context, so the service under test loads the row
   * itself and knows nothing of the offer, as a request would.
   *
   * @param offer the offer
   * @return the offer's row as it was written
   */
  private InventoryItem detach(MaterialExchangeOffer offer) {
    InventoryItem row = offer.getInventoryItem();
    entityManager.flush();
    entityManager.clear();
    return row;
  }

  /**
   * Reads the details of the audit rows of one type for one offer.
   *
   * @param type the event type
   * @param offer the offer
   * @return the details, one per row
   */
  private List<String> details(String type, UUID offer) {
    return jdbc.queryForList(
        "SELECT details FROM audit_event WHERE event_type = ? AND subject_id = ?",
        String.class,
        type,
        offer);
  }

  /**
   * A discard of part of a row.
   *
   * @param amount the amount
   * @param version the row's version
   * @return the book-out
   */
  private static InventoryItemBookOutDto discard(double amount, Long version) {
    return new InventoryItemBookOutDto(
        amount, null, null, CheckoutType.DISCARD, null, null, version, null, null, null, null);
  }

  /**
   * Persists a member, an SCU material, a location and a Lager row with an active material offer on
   * it.
   *
   * @param stock the row's amount
   * @param offered the offered amount
   * @param personal whether the row is personal
   * @return the offer
   */
  private MaterialExchangeOffer offer(double stock, double offered, boolean personal) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    OrgUnit orgUnit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Ore-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialRepository.save(material);

    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setMaterial(material);
    item.setLocation(location);
    item.setOwningOrgUnit(orgUnit);
    item.setQuality(500);
    item.setAmount(stock);
    item.setPersonal(personal);
    inventoryItemRepository.save(item);

    return offerRepository.save(
        MaterialExchangeOffer.builder()
            .kind(MaterialExchangeOfferKind.MATERIAL)
            .inventoryItem(item)
            .owner(user)
            .owningOrgUnit(orgUnit)
            .offeredAmount(offered)
            .status(MaterialExchangeOfferStatus.ACTIVE)
            .releasedAt(Instant.parse("2026-09-27T00:00:00Z"))
            .build());
  }
}

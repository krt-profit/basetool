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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeInterest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level coverage for the Materialbörse repositories ({@link MaterialExchangeOfferRepository} /
 * {@link MaterialExchangeInterestRepository}) against the real Postgres test schema (Testcontainers
 * + Flyway V210/V212 via the {@code test} profile). Validates the board JPQL (including the
 * nested-path sort, the live-item filters and the offered-amount filter/sort of ADR-0086), the
 * anonymity-safe grouped interest counts, and the two DB invariants the migration enforces: one
 * {@code ACTIVE} offer per Lager row (partial-unique) and one interest registration per {@code
 * (offer, user)}.
 *
 * <p>{@link Transactional} so each method rolls back — the seeded rows must never commit to the
 * shared Testcontainers database. Reads still see the rows because they are flushed within the test
 * transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaterialExchangeRepositoryDataTest {

  @Autowired private MaterialExchangeOfferRepository offerRepository;
  @Autowired private MaterialExchangeInterestRepository interestRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * The board query returns an active offer, applies the min-quality / text filters live off the
   * linked item, honours the nested-path quality sort, and hides a deactivated offer.
   */
  @Test
  void findBoard_returnsActiveOffers_filteredAndSortedLiveFromItem() {
    User owner = persistUser("boersen-anbieter");
    InventoryItem highItem = persistItem(owner, "Quantanium", 920, 512.0);
    InventoryItem lowItem = persistItem(owner, "Tungsten", 480, 60.0);
    MaterialExchangeOffer highOffer =
        persistOffer(highItem, owner, MaterialExchangeOfferStatus.ACTIVE);
    MaterialExchangeOffer lowOffer =
        persistOffer(lowItem, owner, MaterialExchangeOfferStatus.ACTIVE);
    MaterialExchangeOffer deactivated =
        persistOffer(
            persistItem(owner, "Corundum", 700, 40.0),
            owner,
            MaterialExchangeOfferStatus.DEACTIVATED);
    entityManager.flush();

    var all =
        offerRepository.findBoard(
            owner.getId(),
            false,
            null,
            0,
            null,
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "inventoryItem.quality")));

    assertThat(all.getContent())
        .as("only the two ACTIVE offers, quality-sorted desc, deactivated excluded")
        .containsExactly(highOffer, lowOffer)
        .doesNotContain(deactivated);

    var minQual =
        offerRepository.findBoard(owner.getId(), false, null, 900, null, PageRequest.of(0, 20));
    assertThat(minQual.getContent())
        .as("min-quality 900 keeps only the Q920 item (read live from the item)")
        .containsExactly(highOffer);

    var byText =
        offerRepository.findBoard(
            owner.getId(), false, "%tungsten%", 0, null, PageRequest.of(0, 20));
    assertThat(byText.getContent())
        .as("text filter matches the material name live off the item")
        .containsExactly(lowOffer);
  }

  /**
   * The amount filter and the "menge" sort use the effective offered quantity {@code
   * LEAST(offeredAmount, stock)} (ADR-0086) — with stock above the offer here, that is the
   * owner-chosen partial amount, not the underlying item's stock: a small offer off a large row
   * sorts and filters by the 30 SCU it offers, not the 500 SCU in stock.
   */
  @Test
  void findBoard_amountFilterAndSortUseOfferedAmount_notItemStock() {
    User owner = persistUser("partial-anbieter");
    InventoryItem bigStock = persistItem(owner, "Quantanium", 900, 500.0);
    MaterialExchangeOffer smallOffer =
        persistOffer(bigStock, owner, MaterialExchangeOfferStatus.ACTIVE, 30.0);
    InventoryItem smallStock = persistItem(owner, "Tungsten", 400, 200.0);
    MaterialExchangeOffer bigOffer =
        persistOffer(smallStock, owner, MaterialExchangeOfferStatus.ACTIVE, 200.0);
    entityManager.flush();

    var byAmount =
        offerRepository.findBoard(
            owner.getId(),
            false,
            null,
            0,
            null,
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "offeredAmount")));
    assertThat(byAmount.getContent())
        .as("sorted by offered amount desc — the 200-SCU offer outranks the 30-SCU one")
        .containsExactly(bigOffer, smallOffer);

    var minAmount =
        offerRepository.findBoard(owner.getId(), false, null, 0, 100.0, PageRequest.of(0, 20));
    assertThat(minAmount.getContent())
        .as("min-amount 100 filters on offered amount, so the 30-SCU-off-500-stock offer is out")
        .containsExactly(bigOffer);
  }

  /** The Lager-status lookups and the tab counts see only active offers and the right owner. */
  @Test
  void lagerStatusAndTabCounts() {
    User owner = persistUser("counts-anbieter");
    InventoryItem item = persistItem(owner, "Agricium", 796, 340.0);
    MaterialExchangeOffer offer = persistOffer(item, owner, MaterialExchangeOfferStatus.ACTIVE);
    entityManager.flush();

    assertThat(
            offerRepository.findByInventoryItemIdAndStatus(
                item.getId(), MaterialExchangeOfferStatus.ACTIVE))
        .contains(offer);
    assertThat(
            offerRepository.findInventoryItemIdsWithStatus(
                MaterialExchangeOfferStatus.ACTIVE, List.of(item.getId())))
        .containsExactly(item.getId());
    assertThat(
            offerRepository.countByStatusAndOwnerId(
                MaterialExchangeOfferStatus.ACTIVE, owner.getId()))
        .isEqualTo(1);
  }

  /**
   * The board clamps to live stock (ADR-0086): after part of a row is booked out, the min-amount
   * filter uses the effective {@code LEAST(offeredAmount, stock)} — so a partially-booked-out offer
   * is filtered by what actually remains, not what was originally stated. (A <em>fully</em>
   * booked-out row is deleted and its offer cascade-deleted, so it never reaches the board — no
   * separate hide is needed.)
   */
  @Test
  void findBoard_clampsAmountFilterToRemainingStock() {
    User owner = persistUser("clamp-anbieter");
    // Stated 200 SCU, but the row has been booked out to 80 SCU since release.
    InventoryItem partly = persistItem(owner, "Titanium", 700, 80.0);
    MaterialExchangeOffer partialOffer =
        persistOffer(partly, owner, MaterialExchangeOfferStatus.ACTIVE, 200.0);
    entityManager.flush();

    var all = offerRepository.findBoard(owner.getId(), false, null, 0, null, PageRequest.of(0, 20));
    assertThat(all.getContent()).containsExactly(partialOffer);

    var minAbove =
        offerRepository.findBoard(owner.getId(), false, null, 0, 100.0, PageRequest.of(0, 20));
    assertThat(minAbove.getContent())
        .as("min-amount 100 filters on the remaining 80 SCU (LEAST), not the stated 200")
        .isEmpty();

    var minBelow =
        offerRepository.findBoard(owner.getId(), false, null, 0, 50.0, PageRequest.of(0, 20));
    assertThat(minBelow.getContent())
        .as("min-amount 50 keeps the offer since 80 SCU remain")
        .containsExactly(partialOffer);
  }

  /** Interest registration is a grouped, name-free count; withdrawal removes exactly one row. */
  @Test
  void interestCountsAndWithdraw() {
    User owner = persistUser("int-anbieter");
    User a = persistUser("int-a");
    User b = persistUser("int-b");
    InventoryItem item = persistItem(owner, "Beryl", 835, 210.0);
    MaterialExchangeOffer offer = persistOffer(item, owner, MaterialExchangeOfferStatus.ACTIVE);
    persistInterest(offer, a);
    persistInterest(offer, b);
    entityManager.flush();

    assertThat(interestRepository.countByOfferId(offer.getId())).isEqualTo(2);
    assertThat(interestRepository.countByOfferIdIn(List.of(offer.getId())))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.offerId()).isEqualTo(offer.getId());
              assertThat(c.count()).isEqualTo(2L);
            });
    assertThat(interestRepository.findOfferIdsInterestedByViewer(a.getId(), List.of(offer.getId())))
        .containsExactly(offer.getId());

    assertThat(interestRepository.deleteByOfferIdAndInterestedUserId(offer.getId(), a.getId()))
        .isEqualTo(1);
    entityManager.flush();
    assertThat(interestRepository.countByOfferId(offer.getId())).isEqualTo(1);
  }

  /**
   * The partial-unique index (V210) rejects a second ACTIVE offer for the same Lager row. Exercised
   * through {@code saveAndFlush} so the constraint violation surfaces as Spring's translated {@link
   * DataIntegrityViolationException} — exactly what the service's re-release path observes.
   */
  @Test
  void partialUnique_rejectsSecondActiveOfferForSameItem() {
    User owner = persistUser("uq-anbieter");
    InventoryItem item = persistItem(owner, "Laranite", 645, 1240.0);
    persistOffer(item, owner, MaterialExchangeOfferStatus.ACTIVE);

    MaterialExchangeOffer duplicate = new MaterialExchangeOffer();
    duplicate.setInventoryItem(item);
    duplicate.setOwner(owner);
    duplicate.setOfferedAmount(item.getAmount());
    duplicate.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    duplicate.setReleasedAt(Instant.now());

    assertThatThrownBy(() -> offerRepository.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** Persists a minimal user with a unique username. */
  private User persistUser(String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + "-" + UUID.randomUUID());
    return userRepository.save(user);
  }

  /** Persists a minimal Lager row for the given owner, material name, quality and amount. */
  private InventoryItem persistItem(User owner, String materialName, int quality, double amount) {
    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName(materialName + "-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    InventoryItem item = new InventoryItem();
    item.setUser(owner);
    item.setLocation(location);
    item.setMaterial(material);
    item.setQuality(quality);
    item.setAmount(amount);
    item.setPersonal(true);
    return inventoryItemRepository.save(item);
  }

  /**
   * Persists an offer in the given status for the item (owningOrgUnit left null), offering the
   * whole row's stock.
   */
  private MaterialExchangeOffer persistOffer(
      InventoryItem item, User owner, MaterialExchangeOfferStatus status) {
    return persistOffer(item, owner, status, item.getAmount());
  }

  /** Persists an ACTIVE-or-other offer that releases a specific offered amount (partial offers). */
  private MaterialExchangeOffer persistOffer(
      InventoryItem item, User owner, MaterialExchangeOfferStatus status, double offeredAmount) {
    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setInventoryItem(item);
    offer.setOwner(owner);
    offer.setOfferedAmount(offeredAmount);
    offer.setRemark("Tausche gegen **Titanium**.");
    offer.setStatus(status);
    offer.setReleasedAt(Instant.now());
    return offerRepository.save(offer);
  }

  /** Persists an interest registration by the given user on the offer. */
  private MaterialExchangeInterest persistInterest(MaterialExchangeOffer offer, User user) {
    MaterialExchangeInterest interest = new MaterialExchangeInterest();
    interest.setOffer(offer);
    interest.setInterestedUser(user);
    return interestRepository.save(interest);
  }
}

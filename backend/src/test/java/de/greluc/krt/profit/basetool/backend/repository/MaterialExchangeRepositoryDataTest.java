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

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeInterest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level coverage for the Materialbörse repositories ({@link MaterialExchangeOfferRepository} /
 * {@link MaterialExchangeInterestRepository}) against the real Postgres test schema (Testcontainers
 * + Flyway V210…V213 via the {@code test} profile). Validates the board JPQL (the COALESCE-based
 * cross-kind filters/sort spanning both material and item offers, REQ-MARKET-012, and the effective
 * {@code LEAST(offeredAmount, stock)} amount filter/sort of ADR-0086), the anonymity-safe grouped
 * interest counts, and the DB invariants the migrations enforce: one {@code ACTIVE} offer per Lager
 * row (partial-unique), one interest registration per {@code (offer, user)}, and the V213
 * exactly-one-branch {@code CHECK} on an offer's kind.
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
  @Autowired private GameItemRepository gameItemRepository;
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
            owner.getId(), false, null, 0, null, "qual", PageRequest.of(0, 20));

    assertThat(all.getContent())
        .as("only the two ACTIVE offers, quality-sorted desc, deactivated excluded")
        .containsExactly(highOffer, lowOffer)
        .doesNotContain(deactivated);

    var minQual =
        offerRepository.findBoard(
            owner.getId(), false, null, 900, null, "qual", PageRequest.of(0, 20));
    assertThat(minQual.getContent())
        .as("min-quality 900 keeps only the Q920 item (read live from the item)")
        .containsExactly(highOffer);

    var byText =
        offerRepository.findBoard(
            owner.getId(), false, "%tungsten%", 0, null, "qual", PageRequest.of(0, 20));
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
            owner.getId(), false, null, 0, null, "menge", PageRequest.of(0, 20));
    assertThat(byAmount.getContent())
        .as("sorted by effective offered amount desc — the 200-SCU offer outranks the 30-SCU one")
        .containsExactly(bigOffer, smallOffer);

    var minAmount =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, 100.0, "qual", PageRequest.of(0, 20));
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

    var all =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, null, "qual", PageRequest.of(0, 20));
    assertThat(all.getContent()).containsExactly(partialOffer);

    var minAbove =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, 100.0, "qual", PageRequest.of(0, 20));
    assertThat(minAbove.getContent())
        .as("min-amount 100 filters on the remaining 80 SCU (LEAST), not the stated 200")
        .isEmpty();

    var minBelow =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, 50.0, "qual", PageRequest.of(0, 20));
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
    duplicate.setKind(MaterialExchangeOfferKind.MATERIAL);
    duplicate.setInventoryItem(item);
    duplicate.setOwner(owner);
    duplicate.setOfferedAmount(item.getAmount());
    duplicate.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    duplicate.setReleasedAt(Instant.now());

    assertThatThrownBy(() -> offerRepository.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /**
   * The board carries item offers (REQ-MARKET-012): the JPQL's {@code LEFT JOIN}s keep the
   * null-inventory-item rows, the name filter matches the item's stored display name, a min-quality
   * filter excludes item offers (they have no quality), and a min-amount filter compares against
   * the stated item quantity.
   */
  @Test
  void findBoard_includesItemOffers_withCrossKindFiltersAndSort() {
    User owner = persistUser("item-anbieter");
    InventoryItem materialItem = persistItem(owner, "Quantanium", 950, 500.0);
    MaterialExchangeOffer materialOffer =
        persistOffer(materialItem, owner, MaterialExchangeOfferStatus.ACTIVE);
    MaterialExchangeOffer itemOffer =
        persistItemOffer(owner, "venture helmet", "Venture Helmet", 7);
    entityManager.flush();

    var all =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, null, "neu", PageRequest.of(0, 20));
    assertThat(all.getContent())
        .as("both a material and an item offer are on the board")
        .contains(materialOffer, itemOffer);

    var byItemName =
        offerRepository.findBoard(
            owner.getId(), false, "%venture%", 0, null, "mat", PageRequest.of(0, 20));
    assertThat(byItemName.getContent())
        .as("the name filter matches the item's stored display name")
        .containsExactly(itemOffer);

    var minQual =
        offerRepository.findBoard(
            owner.getId(), false, null, 500, null, "qual", PageRequest.of(0, 20));
    assertThat(minQual.getContent())
        .as("a non-zero min-quality excludes item offers (no quality)")
        .containsExactly(materialOffer)
        .doesNotContain(itemOffer);

    var minAmount =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, 8.0, "menge", PageRequest.of(0, 20));
    assertThat(minAmount.getContent())
        .as("min-amount 8 drops the 7-piece item offer but keeps the 500-SCU material")
        .contains(materialOffer)
        .doesNotContain(itemOffer);
  }

  /**
   * Item offers are deliberately not de-duplicated (REQ-MARKET-012): unlike a material offer (one
   * ACTIVE per Lager row), a member may list the same product several times, so two active item
   * offers for the same (owner, product) coexist.
   */
  @Test
  void multipleActiveItemOffersForSameOwnerAndProductAllowed() {
    User owner = persistUser("multi-item");
    MaterialExchangeOffer first = persistItemOffer(owner, "venture helmet", "Venture Helmet", 3);
    MaterialExchangeOffer second = persistItemOffer(owner, "venture helmet", "Venture Helmet", 9);
    entityManager.flush();

    var mine =
        offerRepository.findBoard(owner.getId(), true, null, 0, null, "neu", PageRequest.of(0, 20));
    assertThat(mine.getContent())
        .as("both active item offers for the same product survive (no unique constraint)")
        .contains(first, second);
  }

  /**
   * A <b>stock-backed</b> item offer (REQ-MARKET-014, ADR-0108) persists with a non-null {@code
   * inventory_item_id} (V221 relaxed the V213 CHECK) and the board clamps its quantity to the
   * backing row's current stock: an offer that stated 6 on a game-item row since booked down to 2
   * is kept by a min-amount of 2 but dropped by a min-amount of 3 — mirroring the material offer's
   * {@code LEAST} clamp, and proving the board never breaks on the new inventoryItem on an ITEM
   * offer.
   */
  @Test
  void findBoard_stockBackedItemOffer_quantityClampedToStock() {
    // covers REQ-MARKET-014
    User owner = persistUser("stock-item");
    InventoryItem row = persistItemStockRow(owner, "Quantum Drive", 2.0); // booked down to 2
    MaterialExchangeOffer stockBacked = persistStockBackedItemOffer(row, owner, 6); // stated 6
    entityManager.flush();

    var all =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, null, "neu", PageRequest.of(0, 20));
    assertThat(all.getContent())
        .as("a stock-backed item offer is on the board (LEFT JOIN keeps it)")
        .contains(stockBacked);

    var keptByStock =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, 2.0, "menge", PageRequest.of(0, 20));
    assertThat(keptByStock.getContent())
        .as("min-amount 2 keeps it — effective quantity clamped to the row's 2 in stock")
        .contains(stockBacked);

    var droppedByStock =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, 3.0, "menge", PageRequest.of(0, 20));
    assertThat(droppedByStock.getContent())
        .as("min-amount 3 drops it — the stated 6 is clamped to the 2 in stock")
        .doesNotContain(stockBacked);
  }

  /**
   * A <b>stock-backed</b> item offer whose stated quantity is <em>below</em> its backing stock must
   * be filtered and sorted by the <b>stated quantity</b>, not the full stock (REQ-MARKET-014). This
   * is the regression guard for the PostgreSQL {@code LEAST}-ignores-NULL trap: because {@code
   * LEAST(offeredAmount [NULL], ii.amount)} returns the stock (Postgres drops the NULL argument), a
   * {@code COALESCE(LEAST(...), ...)} effective amount would advertise/sort the full stock; the
   * query uses an explicit {@code CASE} instead. Here a row with 10 in stock offered at 3 is kept
   * by a min-amount of 3 but dropped by 4, and sorts below a material offer of 4 (whereas the buggy
   * expression would rank it as 10, i.e. first).
   */
  @Test
  void findBoard_stockBackedItemOffer_offeredBelowStock_usesStatedQuantity() {
    // covers REQ-MARKET-014
    User owner = persistUser("under-offer");
    InventoryItem itemRow = persistItemStockRow(owner, "Power Plant", 10.0); // 10 in stock
    MaterialExchangeOffer understated = persistStockBackedItemOffer(itemRow, owner, 3); // offered 3
    InventoryItem matRow = persistItem(owner, "Agricium", 500, 4.0);
    MaterialExchangeOffer material =
        persistOffer(matRow, owner, MaterialExchangeOfferStatus.ACTIVE, 4.0);
    entityManager.flush();

    var keptByStatedQty =
        offerRepository.findBoard(owner.getId(), false, null, 0, 3.0, "neu", PageRequest.of(0, 20));
    assertThat(keptByStatedQty.getContent())
        .as("min-amount 3 keeps it — the effective quantity is the stated 3, not the 10 in stock")
        .contains(understated);

    var droppedAboveStatedQty =
        offerRepository.findBoard(owner.getId(), false, null, 0, 4.0, "neu", PageRequest.of(0, 20));
    assertThat(droppedAboveStatedQty.getContent())
        .as("min-amount 4 drops it — the stated 3 (NOT the 10 in stock) is below the threshold")
        .doesNotContain(understated);

    var byMenge =
        offerRepository.findBoard(
            owner.getId(), false, null, 0, null, "menge", PageRequest.of(0, 20));
    assertThat(byMenge.getContent())
        .as("menge sort ranks the material offer (4) above the item offer (stated 3, not stock 10)")
        .containsSubsequence(material, understated);
  }

  /**
   * The V213 exactly-one-branch {@code CHECK} rejects a malformed item offer — here one missing its
   * quantity — surfacing as a translated {@link DataIntegrityViolationException}.
   */
  @Test
  void itemOfferCheckConstraint_rejectsMissingQuantity() {
    User owner = persistUser("ck-item");
    MaterialExchangeOffer bad = new MaterialExchangeOffer();
    bad.setKind(MaterialExchangeOfferKind.ITEM);
    bad.setOwner(owner);
    bad.setItemProductKey("venture helmet");
    bad.setItemName("Venture Helmet");
    bad.setItemQuantity(null);
    bad.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    bad.setReleasedAt(Instant.now());

    assertThatThrownBy(() -> offerRepository.saveAndFlush(bad))
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
   * Persists a material offer in the given status for the item (owningOrgUnit left null), offering
   * the whole row's stock.
   */
  private MaterialExchangeOffer persistOffer(
      InventoryItem item, User owner, MaterialExchangeOfferStatus status) {
    return persistOffer(item, owner, status, item.getAmount());
  }

  /** Persists an ACTIVE-or-other offer that releases a specific offered amount (partial offers). */
  private MaterialExchangeOffer persistOffer(
      InventoryItem item, User owner, MaterialExchangeOfferStatus status, double offeredAmount) {
    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setKind(MaterialExchangeOfferKind.MATERIAL);
    offer.setInventoryItem(item);
    offer.setOwner(owner);
    offer.setOfferedAmount(offeredAmount);
    offer.setRemark("Tausche gegen **Titanium**.");
    offer.setStatus(status);
    offer.setReleasedAt(Instant.now());
    return offerRepository.save(offer);
  }

  /**
   * Persists a minimal game-item Lager row (no quality) for the given owner, item name and amount.
   */
  private InventoryItem persistItemStockRow(User owner, String itemName, double amount) {
    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    GameItem gameItem = new GameItem();
    gameItem.setName(itemName + "-" + UUID.randomUUID());
    gameItemRepository.save(gameItem);

    InventoryItem item = new InventoryItem();
    item.setUser(owner);
    item.setLocation(location);
    item.setGameItem(gameItem);
    item.setAmount(amount);
    item.setPersonal(true);
    return inventoryItemRepository.save(item);
  }

  /**
   * Persists an active stock-backed item offer (REQ-MARKET-014) bound to a game-item Lager row,
   * with a stated whole-unit quantity and its product key/name derived from the row's item name.
   */
  private MaterialExchangeOffer persistStockBackedItemOffer(
      InventoryItem row, User owner, int quantity) {
    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setKind(MaterialExchangeOfferKind.ITEM);
    offer.setInventoryItem(row);
    offer.setOwner(owner);
    offer.setItemProductKey("prod-key-" + UUID.randomUUID());
    offer.setItemName(row.getGameItem().getName());
    offer.setItemQuantity(quantity);
    offer.setRemark("Tausche gegen **aUEC**.");
    offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    offer.setReleasedAt(Instant.now());
    return offerRepository.save(offer);
  }

  /** Persists an active item (blueprint-product) offer with a stated quantity and no Lager row. */
  private MaterialExchangeOffer persistItemOffer(
      User owner, String productKey, String itemName, int quantity) {
    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setKind(MaterialExchangeOfferKind.ITEM);
    offer.setOwner(owner);
    offer.setItemProductKey(productKey);
    offer.setItemName(itemName);
    offer.setItemQuantity(quantity);
    offer.setRemark("Tausche gegen **aUEC**.");
    offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
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

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

import de.greluc.krt.profit.basetool.backend.model.GameItem;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level coverage for the Materialbörse ratchet-down of an offer when its backing Lager row is
 * reduced, against the real Postgres test schema (Testcontainers + Flyway via the {@code test}
 * profile) — the atomic, persisting counterpart of the display-time clamp-on-read (ADR-0086).
 * Covers both {@link MaterialExchangeOfferRepository#clampOfferedAmountToStock} for a material
 * offer (REQ-MARKET-013) and {@link MaterialExchangeOfferRepository#clampItemQuantityToStock} for a
 * stock-backed item offer (REQ-MARKET-014, ADR-0108).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaterialExchangeOfferClampDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialExchangeOfferRepository offerRepository;

  @PersistenceContext private EntityManager entityManager;

  @Test
  void clamp_reducesActiveOfferWhenStockDropsBelowOfferedAmount() {
    MaterialExchangeOffer offer = activeOffer(80.0);
    UUID itemId = offer.getInventoryItem().getId();
    entityManager.flush();

    int changed = offerRepository.clampOfferedAmountToStock(itemId, 30.0);

    assertThat(changed).isEqualTo(1);
    assertThat(reloadOfferedAmount(offer.getId())).isEqualTo(30.0);
  }

  @Test
  void clamp_isNoOpWhenStockDidNotDropBelowOfferedAmount() {
    MaterialExchangeOffer offer = activeOffer(80.0);
    UUID itemId = offer.getInventoryItem().getId();
    entityManager.flush();

    // Stock is still >= the offered amount (e.g. after an increase): the offer must not change.
    int changed = offerRepository.clampOfferedAmountToStock(itemId, 90.0);

    assertThat(changed).isZero();
    assertThat(reloadOfferedAmount(offer.getId())).isEqualTo(80.0);
  }

  @Test
  void clamp_leavesDeactivatedOffersUntouched() {
    MaterialExchangeOffer offer = activeOffer(80.0);
    offer.setStatus(MaterialExchangeOfferStatus.DEACTIVATED);
    UUID itemId = offer.getInventoryItem().getId();
    entityManager.flush();

    int changed = offerRepository.clampOfferedAmountToStock(itemId, 30.0);

    assertThat(changed).isZero();
    assertThat(reloadOfferedAmount(offer.getId())).isEqualTo(80.0);
  }

  // ---- stock-backed item offers (REQ-MARKET-014, ADR-0108) ----

  @Test
  void clampItem_reducesActiveStockBackedItemOfferWhenStockDropsBelowQuantity() {
    // covers REQ-MARKET-014 — the item sibling of the material ratchet; this also proves V221
    // lets an ITEM offer carry an inventory_item_id (a stock-backed item offer persists).
    MaterialExchangeOffer offer = activeItemStockOffer(8);
    UUID itemId = offer.getInventoryItem().getId();
    entityManager.flush();

    int changed = offerRepository.clampItemQuantityToStock(itemId, 3);

    assertThat(changed).isEqualTo(1);
    assertThat(reloadItemQuantity(offer.getId())).isEqualTo(3);
  }

  @Test
  void clampItem_isNoOpWhenStockDidNotDropBelowQuantity() {
    // covers REQ-MARKET-014
    MaterialExchangeOffer offer = activeItemStockOffer(8);
    UUID itemId = offer.getInventoryItem().getId();
    entityManager.flush();

    int changed = offerRepository.clampItemQuantityToStock(itemId, 12);

    assertThat(changed).isZero();
    assertThat(reloadItemQuantity(offer.getId())).isEqualTo(8);
  }

  @Test
  void clampItem_leavesMaterialOffersUntouched() {
    // covers REQ-MARKET-014 — the item clamp keys kind=ITEM/item_quantity, so a material offer
    // (offered_amount, item_quantity NULL) never matches even under the same item id.
    MaterialExchangeOffer material = activeOffer(80.0);
    UUID itemId = material.getInventoryItem().getId();
    entityManager.flush();

    int changed = offerRepository.clampItemQuantityToStock(itemId, 1);

    assertThat(changed).isZero();
    assertThat(reloadOfferedAmount(material.getId())).isEqualTo(80.0);
  }

  /**
   * Reloads an offer's persisted {@code offeredAmount} after a {@code @Modifying} clamp, clearing
   * the context first so the read reflects the direct SQL update rather than a stale cached entity.
   *
   * @param offerId the offer id.
   * @return the current {@code offeredAmount}.
   */
  private Double reloadOfferedAmount(UUID offerId) {
    entityManager.flush();
    entityManager.clear();
    return offerRepository.findById(offerId).orElseThrow().getOfferedAmount();
  }

  /**
   * Reloads an offer's persisted {@code itemQuantity} after a {@code @Modifying} clamp, clearing
   * the context first so the read reflects the direct SQL update rather than a stale cached entity.
   *
   * @param offerId the offer id.
   * @return the current {@code itemQuantity}.
   */
  private Integer reloadItemQuantity(UUID offerId) {
    entityManager.flush();
    entityManager.clear();
    return offerRepository.findById(offerId).orElseThrow().getItemQuantity();
  }

  /**
   * Persists a fresh owner, game item, location and game-item stock row (amount 20) plus an {@code
   * ACTIVE} stock-backed {@link MaterialExchangeOfferKind#ITEM} offer on that row with the given
   * whole-unit quantity — the physical fixture the item ratchet acts on.
   *
   * @param quantity the stored offered whole-unit quantity.
   * @return the saved item offer.
   */
  private MaterialExchangeOffer activeItemStockOffer(int quantity) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    OrgUnit orgUnit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    GameItem gameItem = new GameItem();
    gameItem.setName("Item-" + UUID.randomUUID());
    gameItemRepository.save(gameItem);

    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setGameItem(gameItem);
    item.setLocation(location);
    item.setOwningOrgUnit(orgUnit);
    item.setAmount(20.0);
    item.setPersonal(false);
    inventoryItemRepository.save(item);

    return offerRepository.save(
        MaterialExchangeOffer.builder()
            .kind(MaterialExchangeOfferKind.ITEM)
            .inventoryItem(item)
            .owner(user)
            .owningOrgUnit(orgUnit)
            .itemProductKey("prod-key")
            .itemName(gameItem.getName())
            .itemQuantity(quantity)
            .status(MaterialExchangeOfferStatus.ACTIVE)
            .releasedAt(Instant.parse("2026-07-17T00:00:00Z"))
            .build());
  }

  /**
   * Persists a fresh owner, PIECE material, location and inventory row (amount 100) plus an {@code
   * ACTIVE} material offer on that row with the given offered amount.
   *
   * @param offeredAmount the stored offered quantity.
   * @return the saved offer.
   */
  private MaterialExchangeOffer activeOffer(double offeredAmount) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    OrgUnit orgUnit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Component-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.PIECE);
    materialRepository.save(material);

    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setMaterial(material);
    item.setLocation(location);
    item.setOwningOrgUnit(orgUnit);
    item.setQuality(800);
    item.setAmount(100.0);
    item.setPersonal(false);
    inventoryItemRepository.save(item);

    return offerRepository.save(
        MaterialExchangeOffer.builder()
            .kind(MaterialExchangeOfferKind.MATERIAL)
            .inventoryItem(item)
            .owner(user)
            .owningOrgUnit(orgUnit)
            .offeredAmount(offeredAmount)
            .status(MaterialExchangeOfferStatus.ACTIVE)
            .releasedAt(Instant.parse("2026-07-13T00:00:00Z"))
            .build());
  }
}

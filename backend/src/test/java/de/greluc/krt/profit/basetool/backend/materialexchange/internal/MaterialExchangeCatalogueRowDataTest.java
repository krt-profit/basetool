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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the catalogue-row identity of Materialbörse item entries against PostgreSQL (ADR-0371): the
 * kind checks take exactly one of a product key and a catalogue row, and a catalogue merge moves
 * the entries named by the retired row. Each test rolls back.
 */
@SpringBootTest
@Transactional
class MaterialExchangeCatalogueRowDataTest {

  @Autowired private MaterialExchangeOfferRepository offerRepository;
  @Autowired private MaterialExchangeRequestRepository requestRepository;
  @Autowired private MaterialExchangeItemReassignmentRepository reassignmentRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private UserRepository userRepository;

  @PersistenceContext private EntityManager entityManager;

  @Test
  void anItemOfferAndRequestNamedByTheirCatalogueRowPersist() {
    User owner = persistUser("row-owner");
    GameItem railgun = persistGameItem("Scourge Railgun");

    MaterialExchangeOffer offer =
        offerRepository.saveAndFlush(itemOffer(owner, null, railgun.getId()));
    MaterialExchangeRequest request =
        requestRepository.saveAndFlush(itemRequest(owner, null, railgun.getId()));

    assertThat(offer.getItemGameItemId()).isEqualTo(railgun.getId());
    assertThat(request.getItemGameItemId()).isEqualTo(railgun.getId());
  }

  @Test
  void anItemOfferNamingBothIdentitiesIsRejected() {
    User owner = persistUser("row-offer-both");
    GameItem railgun = persistGameItem("Scourge Railgun");

    assertThatThrownBy(() ->
            offerRepository.saveAndFlush(itemOffer(owner, "scourge railgun", railgun.getId())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anItemOfferNamingNeitherIdentityIsRejected() {
    User owner = persistUser("row-offer-neither");

    assertThatThrownBy(() -> offerRepository.saveAndFlush(itemOffer(owner, null, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anItemRequestNamingBothIdentitiesIsRejected() {
    User owner = persistUser("row-request-both");
    GameItem railgun = persistGameItem("Scourge Railgun");

    assertThatThrownBy(() ->
            requestRepository.saveAndFlush(itemRequest(owner, "scourge railgun", railgun.getId())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anItemRequestNamingNeitherIdentityIsRejected() {
    User owner = persistUser("row-request-neither");

    assertThatThrownBy(() -> requestRepository.saveAndFlush(itemRequest(owner, null, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aMaterialRequestCarryingACatalogueRowIsRejected() {
    User owner = persistUser("row-material");
    Material material = new Material();
    material.setName("Laranite-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialRepository.save(material);
    MaterialExchangeRequest bad = new MaterialExchangeRequest();
    bad.setKind(MaterialExchangeRequestKind.MATERIAL);
    bad.setRequestedMaterial(material);
    bad.setRequestedAmount(10.0);
    bad.setItemGameItemId(persistGameItem("Scourge Railgun").getId());
    bad.setOwner(owner);
    bad.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    bad.setPostedAt(Instant.now());

    assertThatThrownBy(() -> requestRepository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aMergeMovesTheEntriesOfTheRetiredRowAndBumpsTheirVersions() {
    User owner = persistUser("row-merge");
    GameItem retired = persistGameItem("Retired Railgun");
    GameItem survivor = persistGameItem("Scourge Railgun");
    GameItem other = persistGameItem("Other Railgun");
    MaterialExchangeOffer moved = offerRepository.save(itemOffer(owner, null, retired.getId()));
    MaterialExchangeOffer kept = offerRepository.save(itemOffer(owner, null, other.getId()));
    MaterialExchangeRequest request =
        requestRepository.save(itemRequest(owner, null, retired.getId()));
    entityManager.flush();
    long offerVersion = moved.getVersion();
    long requestVersion = request.getVersion();
    entityManager.clear();

    assertThat(reassignmentRepository.offers(retired.getId(), survivor.getId()))
        .containsExactly(moved.getId());
    assertThat(reassignmentRepository.requests(retired.getId(), survivor.getId()))
        .containsExactly(request.getId());
    entityManager.clear();

    MaterialExchangeOffer movedNow = offerRepository.findById(moved.getId()).orElseThrow();
    assertThat(movedNow.getItemGameItemId()).isEqualTo(survivor.getId());
    assertThat(movedNow.getVersion()).isEqualTo(offerVersion + 1);
    assertThat(offerRepository.findById(kept.getId()).orElseThrow().getItemGameItemId())
        .isEqualTo(other.getId());
    MaterialExchangeRequest requestNow = requestRepository.findById(request.getId()).orElseThrow();
    assertThat(requestNow.getItemGameItemId()).isEqualTo(survivor.getId());
    assertThat(requestNow.getVersion()).isEqualTo(requestVersion + 1);
  }

  /**
   * Builds an active free-stated item offer.
   *
   * @param owner the offering member
   * @param productKey the product key, or {@code null}
   * @param gameItemId the catalogue row, or {@code null}
   * @return the unsaved offer
   */
  private static MaterialExchangeOffer itemOffer(
      User owner, @Nullable String productKey, @Nullable UUID gameItemId) {
    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setKind(MaterialExchangeOfferKind.ITEM);
    offer.setOwner(owner);
    offer.setItemProductKey(productKey);
    offer.setItemGameItemId(gameItemId);
    offer.setItemName("Scourge Railgun");
    offer.setItemQuantity(1);
    offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    offer.setReleasedAt(Instant.now());
    return offer;
  }

  /**
   * Builds an active item request.
   *
   * @param owner the requesting member
   * @param productKey the product key, or {@code null}
   * @param gameItemId the catalogue row, or {@code null}
   * @return the unsaved request
   */
  private static MaterialExchangeRequest itemRequest(
      User owner, @Nullable String productKey, @Nullable UUID gameItemId) {
    MaterialExchangeRequest request = new MaterialExchangeRequest();
    request.setKind(MaterialExchangeRequestKind.ITEM);
    request.setOwner(owner);
    request.setItemProductKey(productKey);
    request.setItemGameItemId(gameItemId);
    request.setItemName("Scourge Railgun");
    request.setItemQuantity(1);
    request.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    request.setPostedAt(Instant.now());
    return request;
  }

  /**
   * Persists a game item with a unique name.
   *
   * @param name the name before the unique suffix
   * @return the saved item
   */
  private GameItem persistGameItem(String name) {
    GameItem item = new GameItem();
    item.setName(name + "-" + UUID.randomUUID());
    return gameItemRepository.save(item);
  }

  /**
   * Persists a minimal user with a unique username.
   *
   * @param prefix the username before the unique suffix
   * @return the saved user
   */
  private User persistUser(String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + "-" + UUID.randomUUID());
    return userRepository.save(user);
  }
}

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

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestInterest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeRequestInterestCount;
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
 * Data-level coverage for the Materialbörse Gesuche repositories ({@link
 * MaterialExchangeRequestRepository} / {@link MaterialExchangeRequestInterestRepository}) against
 * the real Postgres test schema (Testcontainers + Flyway V224 via the {@code test} profile).
 * Validates the board JPQL (the CASE-based cross-kind name/quantity filters/sort spanning both
 * material and item requests, REQ-MARKET-015), the anonymity-safe grouped supplier counts, and the
 * DB invariants the migration enforces: the exactly-one-branch {@code CHECK} on a request's kind,
 * the 0–1000 min-quality range {@code CHECK}, and one fulfilment signal per {@code (request,
 * user)}.
 *
 * <p>{@link Transactional} so each method rolls back — the seeded rows must never commit to the
 * shared Testcontainers database. Reads still see the rows because they are flushed within the test
 * transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaterialExchangeRequestRepositoryDataTest {

  @Autowired private MaterialExchangeRequestRepository requestRepository;
  @Autowired private MaterialExchangeRequestInterestRepository interestRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * The board query returns an active request, applies the min-quality (on the request's stated
   * floor) and text (material name) filters, honours the quality sort, and hides a deactivated
   * request.
   */
  @Test
  void findBoard_returnsActiveRequests_filteredAndSorted() {
    User owner = persistUser("gesuch-suchende");
    Material quantanium = persistMaterial("Quantanium");
    Material tungsten = persistMaterial("Tungsten");
    MaterialExchangeRequest high =
        persistMaterialRequest(owner, quantanium, 920, 512.0, MaterialExchangeRequestStatus.ACTIVE);
    MaterialExchangeRequest low =
        persistMaterialRequest(owner, tungsten, 480, 60.0, MaterialExchangeRequestStatus.ACTIVE);
    MaterialExchangeRequest deactivated =
        persistMaterialRequest(
            owner,
            persistMaterial("Corundum"),
            700,
            40.0,
            MaterialExchangeRequestStatus.DEACTIVATED);
    entityManager.flush();

    var all =
        requestRepository.findBoard(
            owner.getId(), false, null, 0, null, "qual", PageRequest.of(0, 20));
    assertThat(all.getContent())
        .as("only the two ACTIVE requests, quality-sorted desc, deactivated excluded")
        .containsExactly(high, low)
        .doesNotContain(deactivated);

    var minQual =
        requestRepository.findBoard(
            owner.getId(), false, null, 900, null, "qual", PageRequest.of(0, 20));
    assertThat(minQual.getContent())
        .as("min-quality 900 keeps only the request whose stated floor is >= 900")
        .containsExactly(high);

    var byText =
        requestRepository.findBoard(
            owner.getId(), false, "%tungsten%", 0, null, "qual", PageRequest.of(0, 20));
    assertThat(byText.getContent())
        .as("text filter matches the requested material name")
        .containsExactly(low);
  }

  /**
   * The board carries item requests (REQ-MARKET-015): the JPQL's {@code LEFT JOIN}s keep the
   * null-material rows, the name filter matches the item's stored display name, a min-quality
   * filter excludes a request that states no quality floor, and a min-amount filter compares
   * against the stated item quantity.
   */
  @Test
  void findBoard_includesItemRequests_crossKindFiltersAndSort() {
    User owner = persistUser("item-suchende");
    Material quantanium = persistMaterial("Quantanium");
    MaterialExchangeRequest materialRequest =
        persistMaterialRequest(owner, quantanium, 950, 500.0, MaterialExchangeRequestStatus.ACTIVE);
    MaterialExchangeRequest itemRequest =
        persistItemRequest(owner, "venture_helmet", "Venture Helmet", 7, null);
    entityManager.flush();

    var all =
        requestRepository.findBoard(
            owner.getId(), false, null, 0, null, "neu", PageRequest.of(0, 20));
    assertThat(all.getContent())
        .as("both requests listed (the item request's null material is kept by the LEFT JOIN)")
        .containsExactlyInAnyOrder(materialRequest, itemRequest);

    var byItemName =
        requestRepository.findBoard(
            owner.getId(), false, "%venture%", 0, null, "neu", PageRequest.of(0, 20));
    assertThat(byItemName.getContent())
        .as("name filter matches the item request's stored display name")
        .containsExactly(itemRequest);

    var minQual =
        requestRepository.findBoard(
            owner.getId(), false, null, 900, null, "neu", PageRequest.of(0, 20));
    assertThat(minQual.getContent())
        .as("a non-zero min-quality excludes the item request that states no quality floor")
        .containsExactly(materialRequest);

    var minAmount =
        requestRepository.findBoard(
            owner.getId(), false, null, 0, 100.0, "menge", PageRequest.of(0, 20));
    assertThat(minAmount.getContent())
        .as("min-amount 100 keeps only the material request's 500-SCU desired quantity")
        .containsExactly(materialRequest);
  }

  /** The exactly-one-branch CHECK (V224) rejects a MATERIAL request carrying item-branch fields. */
  @Test
  void kindCheck_rejectsMaterialRequestWithItemFields() {
    User owner = persistUser("bad-material");
    Material material = persistMaterial("Laranite");
    MaterialExchangeRequest bad = new MaterialExchangeRequest();
    bad.setKind(MaterialExchangeRequestKind.MATERIAL);
    bad.setRequestedMaterial(material);
    bad.setRequestedAmount(120.0);
    bad.setItemProductKey("venture_helmet"); // forbidden on the MATERIAL branch
    bad.setOwner(owner);
    bad.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    bad.setPostedAt(Instant.now());

    assertThatThrownBy(() -> requestRepository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** The exactly-one-branch CHECK (V224) rejects an ITEM request missing its quantity. */
  @Test
  void kindCheck_rejectsItemRequestMissingQuantity() {
    User owner = persistUser("bad-item");
    MaterialExchangeRequest bad = new MaterialExchangeRequest();
    bad.setKind(MaterialExchangeRequestKind.ITEM);
    bad.setItemProductKey("venture_helmet");
    bad.setItemName("Venture Helmet");
    bad.setItemQuantity(null); // required on the ITEM branch
    bad.setOwner(owner);
    bad.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    bad.setPostedAt(Instant.now());

    assertThatThrownBy(() -> requestRepository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** The min-quality range CHECK (V224) rejects a floor above 1000. */
  @Test
  void minQualityCheck_rejectsOutOfRange() {
    User owner = persistUser("bad-quality");
    Material material = persistMaterial("Agricium");
    MaterialExchangeRequest bad = new MaterialExchangeRequest();
    bad.setKind(MaterialExchangeRequestKind.MATERIAL);
    bad.setRequestedMaterial(material);
    bad.setRequestedAmount(50.0);
    bad.setMinQuality(1500); // out of the 0-1000 range
    bad.setOwner(owner);
    bad.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    bad.setPostedAt(Instant.now());

    assertThatThrownBy(() -> requestRepository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** The unique index (V224) rejects a second fulfilment signal for the same (request, user). */
  @Test
  void interestUnique_rejectsSecondSignalForSamePair() {
    User owner = persistUser("req-owner");
    User supplier = persistUser("supplier");
    MaterialExchangeRequest request =
        persistMaterialRequest(
            owner, persistMaterial("Titanium"), 500, 100.0, MaterialExchangeRequestStatus.ACTIVE);
    persistInterest(request, supplier);

    MaterialExchangeRequestInterest duplicate = new MaterialExchangeRequestInterest();
    duplicate.setRequest(request);
    duplicate.setInterestedUser(supplier);

    assertThatThrownBy(() -> interestRepository.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** The grouped supplier counts return one row per request, and withdrawing removes one signal. */
  @Test
  void interestCounts_groupedAndWithdrawRemovesOne() {
    User owner = persistUser("req-owner-2");
    User supplierA = persistUser("supplier-a");
    User supplierB = persistUser("supplier-b");
    MaterialExchangeRequest request =
        persistMaterialRequest(
            owner, persistMaterial("Beryl"), null, 100.0, MaterialExchangeRequestStatus.ACTIVE);
    persistInterest(request, supplierA);
    persistInterest(request, supplierB);
    entityManager.flush();

    List<MaterialExchangeRequestInterestCount> counts =
        interestRepository.countByRequestIdIn(List.of(request.getId()));
    assertThat(counts).hasSize(1);
    assertThat(counts.get(0).requestId()).isEqualTo(request.getId());
    assertThat(counts.get(0).count()).isEqualTo(2L);

    long removed =
        interestRepository.deleteByRequestIdAndInterestedUserId(request.getId(), supplierA.getId());
    assertThat(removed).isEqualTo(1L);
    assertThat(interestRepository.countByRequestId(request.getId())).isEqualTo(1L);
  }

  /** Persists a minimal user with a unique username. */
  private User persistUser(String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + "-" + UUID.randomUUID());
    return userRepository.save(user);
  }

  /** Persists a minimal catalogue material with a unique name. */
  private Material persistMaterial(String name) {
    Material material = new Material();
    material.setName(name + "-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    return materialRepository.save(material);
  }

  /** Persists a material request in the given status for the material. */
  private MaterialExchangeRequest persistMaterialRequest(
      User owner,
      Material material,
      Integer minQuality,
      double amount,
      MaterialExchangeRequestStatus status) {
    MaterialExchangeRequest request = new MaterialExchangeRequest();
    request.setKind(MaterialExchangeRequestKind.MATERIAL);
    request.setRequestedMaterial(material);
    request.setRequestedAmount(amount);
    request.setMinQuality(minQuality);
    request.setOwner(owner);
    request.setRemark("Suche gegen **Titanium**.");
    request.setStatus(status);
    request.setPostedAt(Instant.now());
    return requestRepository.save(request);
  }

  /** Persists an active item (blueprint-product) request with a stated quantity and no material. */
  private MaterialExchangeRequest persistItemRequest(
      User owner, String productKey, String itemName, int quantity, Integer minQuality) {
    MaterialExchangeRequest request = new MaterialExchangeRequest();
    request.setKind(MaterialExchangeRequestKind.ITEM);
    request.setItemProductKey(productKey);
    request.setItemName(itemName);
    request.setItemQuantity(quantity);
    request.setMinQuality(minQuality);
    request.setOwner(owner);
    request.setRemark("Suche gegen **aUEC**.");
    request.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    request.setPostedAt(Instant.now());
    return requestRepository.save(request);
  }

  /** Persists a fulfilment signal by the given user on the request. */
  private MaterialExchangeRequestInterest persistInterest(
      MaterialExchangeRequest request, User user) {
    MaterialExchangeRequestInterest interest = new MaterialExchangeRequestInterest();
    interest.setRequest(request);
    interest.setInterestedUser(user);
    return interestRepository.save(interest);
  }
}

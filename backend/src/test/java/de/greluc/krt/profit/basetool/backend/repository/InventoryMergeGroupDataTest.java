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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level coverage for {@link InventoryItemRepository#findMergeGroupForUpdate} against the real
 * Postgres test schema (Testcontainers + Flyway via the {@code test} profile) — the runtime
 * stock-merge grouping query (REQ-INV-026, ADR-0097). The query's risky shape (the NULL-as-equal
 * predicate on the nullable {@code owningOrgUnit} dimension, the correlated {@code NOT EXISTS}
 * offer exclusion, and {@code @Lock(PESSIMISTIC_WRITE)} + {@code ORDER BY}) can only be validated
 * against a real database — a mock cannot catch a mis-generated join that silently drops NULL rows.
 * The {@link InventoryStockMergeTest} sibling only mocks this call, so its correctness is pinned
 * here.
 *
 * <p>Since Variante C (REQ-INV-027) the merge-group key is the row's <em>physical</em> identity
 * only — user · material · location · quality · personal · owningOrgUnit; the former {@code
 * jobOrder} / {@code mission} earmark dimensions are no longer part of it (they moved onto the
 * allocation tables). {@code owningOrgUnit} is the sole nullable dimension, matched with the {@code
 * ((:x IS NULL AND i.y IS NULL) OR i.y.id = :x)} JPQL predicate, so exercising the NULL-vs-set
 * matching on it covers that predicate's shape.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryMergeGroupDataTest {

  private static final int QUALITY = 800;

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialExchangeOfferRepository offerRepository;

  @PersistenceContext private EntityManager entityManager;

  private User user;
  private Material material;
  private Location location;
  private OrgUnit orgUnit;

  /**
   * Persists a fresh owner, PIECE material, location and the IRIDIUM org unit shared by the rows.
   */
  @BeforeEach
  void seedFixtures() {
    user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    orgUnit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();

    location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    material = new Material();
    material.setName("Component-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.PIECE);
    materialRepository.save(material);
  }

  @Test
  void returnsEveryRowSharingTheStackIdentity_withNullNullableDimensions() {
    InventoryItem a = persistRow(5.0, QUALITY, false, null);
    InventoryItem b = persistRow(3.0, QUALITY, false, null);
    entityManager.flush();

    List<InventoryItem> group =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), material.getId(), location.getId(), QUALITY, false, null);

    assertThat(group)
        .extracting(InventoryItem::getId)
        .containsExactlyInAnyOrder(a.getId(), b.getId());
  }

  @Test
  void matchesNullOwningOrgUnitAsEqual_andExcludesOrgStampedSiblings() {
    InventoryItem nullOrgRow = persistRow(5.0, QUALITY, false, null);
    InventoryItem orgStampedRow = persistRow(4.0, QUALITY, false, orgUnit);
    entityManager.flush();

    // Query with owningOrgUnitId = null must return ONLY the null-org row (NULL-as-equal branch),
    // never the org-stamped sibling.
    List<InventoryItem> nullGroup =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), material.getId(), location.getId(), QUALITY, false, null);
    assertThat(nullGroup).extracting(InventoryItem::getId).containsExactly(nullOrgRow.getId());

    // Query with the org id must return ONLY the org-stamped row.
    List<InventoryItem> orgGroup =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), material.getId(), location.getId(), QUALITY, false, orgUnit.getId());
    assertThat(orgGroup).extracting(InventoryItem::getId).containsExactly(orgStampedRow.getId());
  }

  @Test
  void excludesRowsDifferingInAScalarDimension() {
    InventoryItem match = persistRow(5.0, QUALITY, false, null);
    persistRow(2.0, QUALITY - 100, false, null); // different quality
    persistRow(1.0, QUALITY, true, null); // personal = true
    entityManager.flush();

    List<InventoryItem> group =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), material.getId(), location.getId(), QUALITY, false, null);

    assertThat(group).extracting(InventoryItem::getId).containsExactly(match.getId());
  }

  @Test
  void excludesOfferBackedRows() {
    InventoryItem plain = persistRow(5.0, QUALITY, false, null);
    InventoryItem offerBacked = persistRow(3.0, QUALITY, false, null);
    offerRepository.save(
        MaterialExchangeOffer.builder()
            .kind(MaterialExchangeOfferKind.MATERIAL)
            .inventoryItem(offerBacked)
            .owner(user)
            .owningOrgUnit(orgUnit)
            .offeredAmount(3.0)
            .status(MaterialExchangeOfferStatus.ACTIVE)
            .releasedAt(Instant.parse("2026-07-13T00:00:00Z"))
            .build());
    entityManager.flush();

    List<InventoryItem> group =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), material.getId(), location.getId(), QUALITY, false, null);

    // The offer-backed sibling is excluded by the NOT EXISTS, so a merge never folds (and deletes)
    // a row the Materialbörse still references (ON DELETE CASCADE, V210).
    assertThat(group).extracting(InventoryItem::getId).containsExactly(plain.getId());
  }

  /**
   * Persists one inventory row sharing the fixture user / material / location, with the given
   * amount, quality, personal flag and (nullable) owning org unit. The row carries no job-order or
   * mission allocations, so its earmark dimensions play no part in the merge-group key, which since
   * Variante C is the row's physical identity only.
   *
   * @param amount the row's quantity.
   * @param quality the quality grade.
   * @param personal whether the row is a private entry.
   * @param owningOrgUnit the owning org-unit pool, or {@code null} for an unstamped row.
   * @return the saved row.
   */
  private InventoryItem persistRow(
      double amount, int quality, boolean personal, OrgUnit owningOrgUnit) {
    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setMaterial(material);
    item.setLocation(location);
    item.setOwningOrgUnit(owningOrgUnit);
    item.setQuality(quality);
    item.setAmount(amount);
    item.setPersonal(personal);
    return inventoryItemRepository.save(item);
  }
}

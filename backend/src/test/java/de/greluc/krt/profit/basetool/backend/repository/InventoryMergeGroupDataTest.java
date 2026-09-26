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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies {@link InventoryItemRepository#findMergeGroupForUpdate} against PostgreSQL
 * (REQ-INV-026): the physical merge key, NULL-vs-set matching on {@code owningOrgUnit}, offer
 * exclusion and locking.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryMergeGroupDataTest {

  private static final int QUALITY = 800;

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private GameItemRepository gameItemRepository;
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

    List<InventoryItem> nullGroup =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), material.getId(), location.getId(), QUALITY, false, null);
    assertThat(nullGroup).extracting(InventoryItem::getId).containsExactly(nullOrgRow.getId());

    List<InventoryItem> orgGroup =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), material.getId(), location.getId(), QUALITY, false, orgUnit.getId());
    assertThat(orgGroup).extracting(InventoryItem::getId).containsExactly(orgStampedRow.getId());
  }

  @Test
  void excludesRowsDifferingInAScalarDimension() {
    InventoryItem match = persistRow(5.0, QUALITY, false, null);
    persistRow(2.0, QUALITY - 100, false, null);
    persistRow(1.0, QUALITY, true, null);
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

    assertThat(group).extracting(InventoryItem::getId).containsExactly(plain.getId());
  }

  @Test
  void gameItemGroup_matchesNullMaterialAndNullQualityRows_only() {
    GameItem drive = persistGameItem("Quantum Drive");
    GameItem cooler = persistGameItem("Cooler");
    InventoryItem a = persistItemRow(drive, 3.0, false);
    InventoryItem b = persistItemRow(drive, 2.0, false);
    persistItemRow(cooler, 1.0, false);
    persistRow(5.0, QUALITY, false, null);
    entityManager.flush();

    List<InventoryItem> group =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), null, drive.getId(), location.getId(), null, false, null);

    assertThat(group)
        .extracting(InventoryItem::getId)
        .containsExactlyInAnyOrder(a.getId(), b.getId());
  }

  @Test
  void materialGroup_excludesGameItemRows() {
    InventoryItem materialRow = persistRow(5.0, QUALITY, false, null);
    persistItemRow(persistGameItem("Quantum Drive"), 3.0, false);
    entityManager.flush();

    List<InventoryItem> group =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), material.getId(), location.getId(), QUALITY, false, null);

    assertThat(group).extracting(InventoryItem::getId).containsExactly(materialRow.getId());
  }

  @Test
  void gameItemGroup_excludesRowsDifferingInPersonalFlag() {
    GameItem drive = persistGameItem("Quantum Drive");
    InventoryItem shared = persistItemRow(drive, 3.0, false);
    persistItemRow(drive, 2.0, true);
    entityManager.flush();

    List<InventoryItem> group =
        inventoryItemRepository.findMergeGroupForUpdate(
            user.getId(), null, drive.getId(), location.getId(), null, false, null);

    assertThat(group).extracting(InventoryItem::getId).containsExactly(shared.getId());
  }

  /**
   * Persists a bookable game item with the given display name (kind/source defaults apply).
   *
   * @param name the item's display name.
   * @return the saved game item.
   */
  private GameItem persistGameItem(String name) {
    GameItem gameItem = new GameItem();
    gameItem.setName(name + "-" + UUID.randomUUID());
    return gameItemRepository.save(gameItem);
  }

  /**
   * Persists a game-item stock row for the fixture user and location, without material, quality or
   * owning org unit.
   *
   * @param gameItem the stocked game item.
   * @param amount the row's quantity.
   * @param personal whether the row is a private entry.
   * @return the saved row.
   */
  private InventoryItem persistItemRow(GameItem gameItem, double amount, boolean personal) {
    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setGameItem(gameItem);
    item.setLocation(location);
    item.setAmount(amount);
    item.setPersonal(personal);
    return inventoryItemRepository.save(item);
  }

  /**
   * Persists an unallocated inventory row for the fixture user, material and location.
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
